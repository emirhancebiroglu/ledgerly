-- M9.6 T3: a DUPLICATE_SUSPECTED alert names both the newly posted/review-routed expense
-- (expense_id, as every alert type already does) and the earlier expense DuplicateMatcher
-- believes it duplicates (matched_expense_id) plus the tier the match was made at.
-- ON DELETE SET NULL: if the earlier expense is later deleted, the alert stays a readable
-- historical record rather than becoming unloadable or being cascaded away.

ALTER TABLE alert DROP CONSTRAINT ck_alert_type_payload;
ALTER TABLE alert
    ADD CONSTRAINT ck_alert_type_payload CHECK (
        (alert_type = 'BUDGET_THRESHOLD' AND threshold_percent IN (80, 100))
        OR (alert_type = 'ANOMALY_HIGH' AND threshold_percent IS NULL)
        OR (alert_type = 'LOW_CONFIDENCE' AND threshold_percent IS NULL)
        OR (alert_type = 'DUPLICATE_SUSPECTED' AND threshold_percent IS NULL)
    );
ALTER TABLE alert ADD COLUMN matched_expense_id UUID REFERENCES expense (id) ON DELETE SET NULL;
ALTER TABLE alert ADD COLUMN duplicate_tier TEXT CHECK (duplicate_tier IN ('CONFIRMED', 'SUSPECTED'));

-- One duplicate alert per expense: a repeated evaluation of the same posting (idempotency-key
-- retry, worker redelivery) must not pile up a second alert for the same candidate.
CREATE UNIQUE INDEX uq_alert_duplicate_suspected_expense ON alert (expense_id)
    WHERE alert_type = 'DUPLICATE_SUSPECTED';
