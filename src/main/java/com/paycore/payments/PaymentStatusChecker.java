package com.paycore.payments;

import com.paycore.api.ApiException;
import com.paycore.payments.ProcessorClient.ChargeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resolves payments we don't know the outcome of, by asking the processor what happened to
 * their charge (using the processor_ref we sent with it).
 *
 * This is the answer to "the processor charged the card but the response timed out". We don't
 * assume it failed (we'd keep money we took) and we don't retry the charge (we'd take it twice).
 * We ask, and record what really happened. It never sends a new charge.
 */
@Component
public class PaymentStatusChecker {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusChecker.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final ProcessorClient processor;
    private final Duration minAge;

    public PaymentStatusChecker(PaymentRepository payments, PaymentService paymentService, ProcessorClient processor,
                                @Value("${paycore.status-check.min-age}") Duration minAge) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.processor = processor;
        this.minAge = minAge;
    }

    @Scheduled(fixedDelayString = "${paycore.status-check.interval-ms}",
            initialDelayString = "${paycore.status-check.interval-ms}")
    void runOnSchedule() {
        resolveUnknownPayments();
    }

    /** Returns how many payments it resolved. Payments the processor still can't tell us about wait for the next run. */
    public int resolveUnknownPayments() {
        int resolved = 0;
        for (Payment payment : payments.findUnresolved(minAge, BATCH_SIZE)) {
            ChargeResult result = processor.chargeStatus(payment.processorRef());
            if (result == ChargeResult.UNKNOWN) {
                continue;
            }
            try {
                PaymentResponse response = paymentService.recordCharge(new ChargeAttempt(payment, result));
                log.info("Payment {} resolved: {} → {}", payment.id(), payment.status().apiName(), response.status());
                resolved++;
            } catch (IllegalTransitionException | ApiException e) {
                // Someone else (a request, or another instance of this job) resolved it first;
                // the version check stopped us from recording it twice.
                log.info("Payment {} was already resolved elsewhere", payment.id());
            }
        }
        return resolved;
    }
}
