package com.paycore.payments;

import com.paycore.ledger.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRepository {

    private static final RowMapper<Payment> PAYMENT = (rs, row) -> new Payment(
            rs.getLong("id"),
            rs.getLong("merchant_wallet_id"),
            Money.of(rs.getLong("amount"), rs.getString("currency")),
            rs.getString("card_token"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("processor_ref"),
            Money.of(rs.getLong("fee"), rs.getString("currency")),
            rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Payment insert(long merchantWalletId, Money amount, String cardToken, String processorRef) {
        return jdbc.queryForObject("""
                INSERT INTO payments (merchant_wallet_id, amount, currency, card_token, status, processor_ref)
                VALUES (?, ?, ?, ?, 'CREATED', ?)
                RETURNING *""",
                PAYMENT, merchantWalletId, amount.amount(), amount.currency(), cardToken, processorRef);
    }

    public Optional<Payment> find(long id) {
        return jdbc.query("SELECT * FROM payments WHERE id = ?", PAYMENT, id).stream().findFirst();
    }

    /**
     * Payments whose outcome we don't know: 'unknown' ones (the processor didn't answer) and
     * 'created' ones (we crashed or failed before recording the answer). Only those untouched for
     * at least {@code minAge}, so a charge still on its way to the processor isn't asked about yet.
     */
    public List<Payment> findUnresolved(Duration minAge, int limit) {
        return jdbc.query("""
                SELECT * FROM payments
                WHERE status IN ('UNKNOWN', 'CREATED')
                  AND updated_at <= now() - make_interval(secs => ?)
                ORDER BY id
                LIMIT ?""",
                PAYMENT, minAge.toMillis() / 1000.0, limit);
    }

    /**
     * Optimistic locking: the update only applies if the row still has the version we read.
     * Returns false if another request changed the payment in the meantime.
     */
    public boolean updateStatus(Payment payment, PaymentStatus next, Money fee) {
        int updated = jdbc.update("""
                UPDATE payments SET status = ?, fee = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ?""",
                next.name(), fee.amount(), payment.id(), payment.version());
        return updated == 1;
    }
}
