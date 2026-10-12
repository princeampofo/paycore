package com.paycore.payments;

import com.paycore.api.ApiException;
import com.paycore.ledger.Account;
import com.paycore.ledger.AccountType;
import com.paycore.ledger.JournalEntry;
import com.paycore.ledger.Ledger;
import com.paycore.ledger.Money;
import com.paycore.ledger.Posting;
import com.paycore.metrics.PaycoreMetrics;
import com.paycore.outbox.Outbox;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Card payments to merchants. Money flows through the ledger like this:
 *
 *   authorize:  external_funding → holds                       (the card is charged, we hold the money)
 *   capture:    holds → merchant wallet, minus a fee → fee_revenue
 *   refund:     merchant wallet + fee_revenue → external_funding (back to the card)
 *   cancel:     holds → external_funding                        (the hold is released)
 *
 * Every state change, its ledger entry and its webhook event are written in one transaction.
 */
@Service
public class PaymentService {

    /** The platform keeps 2.90% of every captured payment. */
    public static final long FEE_BASIS_POINTS = 290;

    private static final Pattern CARD_TOKEN = Pattern.compile("tok_[A-Za-z0-9_]{1,64}");
    private static final Pattern LOOKS_LIKE_CARD_NUMBER = Pattern.compile("\\d{12,}");

    private final PaymentRepository payments;
    private final Ledger ledger;
    private final ProcessorClient processor;
    private final Outbox outbox;
    private final PaycoreMetrics metrics;
    private final TransactionTemplate transaction;

    public PaymentService(PaymentRepository payments, Ledger ledger, ProcessorClient processor, Outbox outbox,
                          PaycoreMetrics metrics, PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.ledger = ledger;
        this.processor = processor;
        this.outbox = outbox;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Creates a payment and authorizes it with the processor. Must not be called inside a transaction. */
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        return recordCharge(chargeCard(request));
    }

    /**
     * Step 1 of creating a payment, outside any transaction: save the payment, then call the processor.
     *
     * The payment row (with the processor_ref we chose) is committed BEFORE the call, so if we crash
     * while waiting, we still have a record of a charge that may have happened, and the ref to ask about.
     * No transaction is open during the call: holding one would tie up a connection and any row locks
     * for as long as the processor takes.
     */
    public ChargeAttempt chargeCard(CreatePaymentRequest request) {
        Money amount = validAmount(request.amount(), request.currency());
        String token = validCardToken(request.cardToken());
        Account merchant = ledger.findWallet(request.merchantWalletId())
                .orElseThrow(() -> ApiException.notFound("wallet_not_found",
                        "No merchant wallet with id " + request.merchantWalletId()));
        if (!merchant.currency().equals(amount.currency())) {
            throw ApiException.badRequest("currency_mismatch", "The merchant wallet holds " + merchant.currency());
        }

        Payment payment = payments.insert(merchant.id(), amount, token, "pay_" + UUID.randomUUID());
        return new ChargeAttempt(payment, processor.charge(payment.processorRef(), amount, token));
    }

    /**
     * Step 2 of creating a payment: record the processor's answer and its ledger entry, in one
     * transaction. Also used by {@link PaymentStatusChecker} once it finds out what happened.
     */
    public PaymentResponse recordCharge(ChargeAttempt attempt) {
        return transaction.execute(tx -> {
            Payment payment = attempt.payment();
            Payment updated = switch (attempt.result()) {
                case CHARGED -> authorize(payment);
                case NOT_CHARGED -> moveTo(payment, PaymentStatus.FAILED, payment.fee());
                case UNKNOWN -> moveTo(payment, PaymentStatus.UNKNOWN, payment.fee());
            };
            return PaymentResponse.of(updated);
        });
    }

    @Transactional
    public PaymentResponse capture(long paymentId) {
        Payment payment = find(paymentId);
        Money fee = feeFor(payment.amount());
        Payment captured = moveTo(payment, PaymentStatus.CAPTURED, fee);

        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(system(AccountType.HOLDS, payment), payment.amount().negate()));
        postings.add(new Posting(payment.merchantWalletId(), payment.amount().minus(fee)));
        if (!fee.isZero()) {
            postings.add(new Posting(system(AccountType.FEE_REVENUE, payment), fee));
        }
        ledger.post(new JournalEntry("capture payment " + paymentId, postings));
        return PaymentResponse.of(captured);
    }

    /** Reverses the capture and sends the money back to the card, fee included. */
    @Transactional
    public PaymentResponse refund(long paymentId) {
        Payment payment = find(paymentId);
        Payment refunded = moveTo(payment, PaymentStatus.REFUNDED, payment.fee());

        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(payment.merchantWalletId(), payment.amount().minus(payment.fee()).negate()));
        if (!payment.fee().isZero()) {
            postings.add(new Posting(system(AccountType.FEE_REVENUE, payment), payment.fee().negate()));
        }
        postings.add(new Posting(system(AccountType.EXTERNAL_FUNDING, payment), payment.amount()));
        // Throws InsufficientFundsException if the merchant has already spent the money.
        ledger.post(new JournalEntry("refund payment " + paymentId, postings));
        return PaymentResponse.of(refunded);
    }

    /** Releases the hold without ever paying the merchant. */
    @Transactional
    public PaymentResponse cancel(long paymentId) {
        Payment payment = find(paymentId);
        Payment canceled = moveTo(payment, PaymentStatus.CANCELED, payment.fee());
        ledger.post(JournalEntry.of("cancel payment " + paymentId,
                new Posting(system(AccountType.HOLDS, payment), payment.amount().negate()),
                new Posting(system(AccountType.EXTERNAL_FUNDING, payment), payment.amount())));
        return PaymentResponse.of(canceled);
    }

    public PaymentResponse get(long paymentId) {
        return PaymentResponse.of(find(paymentId));
    }

    /** Rounds down, so a fee is never more than 2.90%. */
    public static Money feeFor(Money amount) {
        return Money.of(Math.multiplyExact(amount.amount(), FEE_BASIS_POINTS) / 10_000, amount.currency());
    }

    private Payment authorize(Payment payment) {
        Payment authorized = moveTo(payment, PaymentStatus.AUTHORIZED, payment.fee());
        ledger.post(JournalEntry.of("authorize payment " + payment.id(),
                new Posting(system(AccountType.EXTERNAL_FUNDING, payment), payment.amount().negate()),
                new Posting(system(AccountType.HOLDS, payment), payment.amount())));
        return authorized;
    }

    /**
     * Checks the move against the state machine, then writes it with the version we read. If another
     * request changed the payment first (e.g. a second capture), the update matches no row and we refuse.
     *
     * Also queues the merchant's webhook, e.g. "payment.captured", in the same transaction.
     * 'unknown' is our own problem to resolve, so merchants aren't told about it.
     */
    private Payment moveTo(Payment payment, PaymentStatus next, Money fee) {
        payment.status().requireCanMoveTo(next);
        if (!payments.updateStatus(payment, next, fee)) {
            throw new ApiException(HttpStatus.CONFLICT, "concurrent_update",
                    "Payment " + payment.id() + " was changed by another request; fetch it and retry");
        }
        Payment moved = payment.moved(next, fee);
        metrics.paymentMovedTo(next.apiName());
        if (next != PaymentStatus.UNKNOWN) {
            outbox.enqueue(payment.merchantWalletId(), "payment." + next.apiName(), PaymentResponse.of(moved));
        }
        return moved;
    }

    private Payment find(long paymentId) {
        return payments.find(paymentId)
                .orElseThrow(() -> ApiException.notFound("payment_not_found", "No payment with id " + paymentId));
    }

    private long system(AccountType type, Payment payment) {
        return ledger.systemAccount(type, payment.amount().currency()).id();
    }

    private static Money validAmount(long amount, String currency) {
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "amount must be a positive number of minor units");
        }
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("invalid_currency", "currency must be an ISO 4217 code such as AED");
        }
    }

    // We only ever accept fake tokens, and refuse anything that could be a real card number,
    // so nothing card-like is ever stored.
    private static String validCardToken(String token) {
        if (token == null || !CARD_TOKEN.matcher(token).matches() || LOOKS_LIKE_CARD_NUMBER.matcher(token).find()) {
            throw ApiException.badRequest("invalid_card_token", "card_token must be a test token such as tok_visa");
        }
        return token;
    }
}
