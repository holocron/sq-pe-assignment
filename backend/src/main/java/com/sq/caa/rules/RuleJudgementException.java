package com.sq.caa.rules;

/**
 * Thrown when a single-rule judgement could not be obtained.
 *
 * <p>Every failure mode of "ask the model one question" is one of four things, and the caller needs
 * to tell them apart because they map to different HTTP statuses and to different advice: waiting
 * longer helps a {@link Reason#TIMEOUT}, retrying helps a {@link Reason#BUSY}, and neither helps a
 * model that answered with prose where a verdict was asked for.
 */
public class RuleJudgementException extends RuntimeException {

    /** What went wrong, in the terms the operator needs. */
    public enum Reason {

        /** The model did not answer within the configured budget. */
        TIMEOUT,

        /** Every judgement slot is occupied; this is a bounded, deliberately small pool. */
        BUSY,

        /** The model server refused the request or failed it. */
        MODEL_ERROR,

        /** The model answered, but not with a verdict this code could read. */
        UNREADABLE_ANSWER,

        /** No {@link RuleJudge} is registered, so nothing can judge the rule. */
        UNAVAILABLE
    }

    private final Reason reason;

    public RuleJudgementException(Reason reason, String message) {
        this(reason, message, null);
    }

    public RuleJudgementException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.MODEL_ERROR : reason;
    }

    public Reason reason() {
        return reason;
    }
}
