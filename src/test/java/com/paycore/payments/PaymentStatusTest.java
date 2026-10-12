package com.paycore.payments;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.paycore.payments.PaymentStatus.AUTHORIZED;
import static com.paycore.payments.PaymentStatus.CANCELED;
import static com.paycore.payments.PaymentStatus.CAPTURED;
import static com.paycore.payments.PaymentStatus.CREATED;
import static com.paycore.payments.PaymentStatus.FAILED;
import static com.paycore.payments.PaymentStatus.REFUNDED;
import static com.paycore.payments.PaymentStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStatusTest {

    record Move(PaymentStatus from, PaymentStatus to) {
    }

    /** Every legal move, written out independently of the table in PaymentStatus. */
    private static final Set<Move> LEGAL = Set.of(
            new Move(CREATED, AUTHORIZED),
            new Move(CREATED, FAILED),
            new Move(CREATED, UNKNOWN),
            new Move(UNKNOWN, AUTHORIZED),
            new Move(UNKNOWN, FAILED),
            new Move(AUTHORIZED, CAPTURED),
            new Move(AUTHORIZED, CANCELED),
            new Move(CAPTURED, REFUNDED));

    @Test
    void exactlyTheListedMovesAreAllowed() {
        // All 49 pairs, including staying in the same state.
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canMoveTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(LEGAL.contains(new Move(from, to)));
            }
        }
    }

    @Test
    void finalStatesAreFinal() {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(REFUNDED.canMoveTo(to)).isFalse();
            assertThat(FAILED.canMoveTo(to)).isFalse();
            assertThat(CANCELED.canMoveTo(to)).isFalse();
        }
    }

    @Test
    void illegalMovesThrowWithAReadableMessage() {
        assertThatThrownBy(() -> FAILED.requireCanMoveTo(CAPTURED))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessage("A payment that is failed cannot become captured");
    }
}
