-- M9.5 T3: a NEEDS_REVIEW expense routed there for low categorization confidence gets its own
-- immutable alert snapshot, carrying the confidence value that triggered the review. One alert
-- per expense: re-running the same posting pipeline for an already-reviewed expense must not
-- pile up duplicate alerts.

ALTER TABLE alert DROP CONSTRAINT ck_alert_type_payload;
ALTER TABLE alert
    ADD CONSTRAINT ck_alert_type_payload CHECK (
        (alert_type = 'BUDGET_THRESHOLD' AND threshold_percent IN (80, 100))
        OR (alert_type = 'ANOMALY_HIGH' AND threshold_percent IS NULL)
        OR (alert_type = 'LOW_CONFIDENCE' AND threshold_percent IS NULL)
    );
ALTER TABLE alert ADD COLUMN categorization_confidence NUMERIC(4, 3);
CREATE UNIQUE INDEX uq_alert_low_confidence_expense ON alert (expense_id)
    WHERE alert_type = 'LOW_CONFIDENCE';
