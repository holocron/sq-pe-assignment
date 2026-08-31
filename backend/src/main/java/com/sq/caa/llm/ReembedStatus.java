package com.sq.caa.llm;

/**
 * A point-in-time snapshot of the background re-embed job, returned by
 * {@code GET /api/admin/llm-settings/reembed-status}.
 *
 * @param running            whether the job is currently working through the corpus
 * @param totalDocuments     documents the current/last run set out to re-embed
 * @param completedDocuments documents re-embedded successfully so far
 * @param failedDocuments    documents that could not be re-embedded (e.g. no stored source bytes)
 * @param lastError          the most recent per-document failure; null when none
 */
public record ReembedStatus(boolean running, int totalDocuments, int completedDocuments,
        int failedDocuments, String lastError) {

    /** Nothing has run yet. */
    public static ReembedStatus idle() {
        return new ReembedStatus(false, 0, 0, 0, null);
    }
}
