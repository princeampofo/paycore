package com.paycore.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    @Test
    void acceptsPostingsThatSumToZero() {
        assertThatCode(() -> JournalEntry.of("transfer",
                new Posting(1, Money.of(-5000, "AED")),
                new Posting(2, Money.of(5000, "AED"))))
                .doesNotThrowAnyException();
    }

    @Test
    void cannotBeCreatedUnbalanced() {
        assertThatThrownBy(() -> JournalEntry.of("bad",
                new Posting(1, Money.of(-500, "AED")),
                new Posting(2, Money.of(400, "AED"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Postings must sum to zero but are off by -1.00 AED");
    }

    @Test
    void cannotMixCurrencies() {
        // +100 and -100 sum to zero as plain numbers, but dollars and dirhams aren't the same money.
        assertThatThrownBy(() -> JournalEntry.of("fake fx",
                new Posting(1, Money.of(100, "USD")),
                new Posting(2, Money.of(-100, "AED"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsAtLeastTwoPostings() {
        assertThatThrownBy(() -> JournalEntry.of("one leg", new Posting(1, Money.of(100, "AED"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postingsCannotBeZero() {
        assertThatThrownBy(() -> new Posting(1, Money.of(0, "AED")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
