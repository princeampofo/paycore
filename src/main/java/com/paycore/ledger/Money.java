package com.paycore.ledger;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in minor units (1050 = 10.50 AED) of one currency.
 *
 * Never a float or double: 0.1 + 0.2 is not 0.3 in floating point, and a ledger must add up
 * exactly. Arithmetic across currencies is refused, and overflow throws instead of wrapping.
 */
public record Money(long amount, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        Currency.getInstance(currency); // rejects anything that isn't an ISO 4217 code
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amount), currency);
    }

    public boolean isZero() {
        return amount == 0;
    }

    public boolean isNegative() {
        return amount < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    /** For display only, e.g. "10.50 AED". */
    @Override
    public String toString() {
        int decimals = Currency.getInstance(currency).getDefaultFractionDigits();
        return BigDecimal.valueOf(amount, decimals).toPlainString() + " " + currency;
    }
}
