package com.paycore.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void addsAndSubtractsExactlyInMinorUnits() {
        Money tenFifty = Money.of(1050, "AED");

        assertThat(tenFifty.plus(Money.of(25, "AED"))).isEqualTo(Money.of(1075, "AED"));
        assertThat(tenFifty.minus(Money.of(1050, "AED")).isZero()).isTrue();
        assertThat(tenFifty.negate()).isEqualTo(Money.of(-1050, "AED"));
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of(100, "AED").plus(Money.of(100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Currency mismatch: AED vs USD");
    }

    @Test
    void throwsOnOverflowInsteadOfWrappingAround() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "AED").plus(Money.of(1, "AED")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsUnknownCurrencyCodes() {
        assertThatThrownBy(() -> Money.of(100, "XYZ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displaysWithTheCurrencysDecimals() {
        assertThat(Money.of(1050, "AED")).hasToString("10.50 AED");
        assertThat(Money.of(-5, "USD")).hasToString("-0.05 USD");
        assertThat(Money.of(500, "JPY")).hasToString("500 JPY");
    }
}
