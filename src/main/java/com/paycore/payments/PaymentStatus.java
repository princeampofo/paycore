package com.paycore.payments;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    CANCELED,
    /** The processor didn't answer, so we don't know whether the card was charged. */
    UNKNOWN;

    /** The whole state machine in one place. Any move not listed here is illegal. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(AUTHORIZED, FAILED, UNKNOWN),
            UNKNOWN, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, CANCELED),
            CAPTURED, EnumSet.of(REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canMoveTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public void requireCanMoveTo(PaymentStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException(this, next);
        }
    }

    /** As shown in the API, e.g. "authorized". */
    public String apiName() {
        return name().toLowerCase();
    }
}
