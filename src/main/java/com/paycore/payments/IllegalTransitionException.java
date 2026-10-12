package com.paycore.payments;

/** A payment was asked to make a move its state machine doesn't allow, e.g. refunding a failed payment. */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(PaymentStatus from, PaymentStatus to) {
        super("A payment that is " + from.apiName() + " cannot become " + to.apiName());
    }
}
