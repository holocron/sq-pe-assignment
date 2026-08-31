package com.sq.caa.domain;

/** Lifecycle of an asynchronous AI analysis run. */
public enum AnalysisStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    /** Aborted at the user's request while still running; verdicts obtained so far are kept. */
    CANCELLED
}
