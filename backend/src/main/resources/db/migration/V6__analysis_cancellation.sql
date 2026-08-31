-- V6: analysis cancellation.
-- A user may abort a RUNNING analysis (POST /api/analyses/{id}/cancel); the run then settles
-- with the verdicts it had already obtained and is recorded as CANCELLED, a new terminal status.

ALTER TABLE analysis_runs DROP CONSTRAINT ck_analysis_runs_status;
ALTER TABLE analysis_runs
    ADD CONSTRAINT ck_analysis_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));
