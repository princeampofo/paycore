package com.paycore.ledger;

import java.util.List;
import java.util.Objects;

/**
 * One business event (a transfer, a capture, a refund...) as a list of postings, with an
 * optional note from the customer (null if none).
 *
 * The postings must sum to zero, and an entry that doesn't can't be constructed at all,
 * so an unbalanced entry never gets near the database.
 */
public record JournalEntry(String description, String note, List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(description, "description");
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two postings");
        }

        Money total = postings.getFirst().amount();
        for (Posting posting : postings.subList(1, postings.size())) {
            total = total.plus(posting.amount()); // also refuses to mix currencies
        }
        if (!total.isZero()) {
            throw new IllegalArgumentException("Postings must sum to zero but are off by " + total);
        }
    }

    public JournalEntry(String description, List<Posting> postings) {
        this(description, null, postings);
    }

    public static JournalEntry of(String description, Posting... postings) {
        return new JournalEntry(description, List.of(postings));
    }

    public JournalEntry withNote(String note) {
        return new JournalEntry(description, note, postings);
    }
}
