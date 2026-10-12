package com.paycore.payments;

/**
 * When the money is taken. Most merchants capture straight away; holding an authorization and
 * capturing later is for when the final amount or the delivery comes later (a hotel bill, an
 * order that ships, a tip).
 */
public enum CaptureMethod {
    /** Captured in the same transaction as the authorization. The default. */
    AUTOMATIC,
    /** Stays authorized (money in holds) until the merchant calls capture, or cancels. */
    MANUAL;

    public String apiName() {
        return name().toLowerCase();
    }
}
