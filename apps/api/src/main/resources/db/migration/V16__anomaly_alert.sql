-- M8 T5: HIGH anomaly advisories become immutable alert snapshots after a posting commits.

ALTER TABLE alert DROP CONSTRAINT IF EXISTS alert_alert_type_check;
ALTER TABLE alert DROP CONSTRAINT IF EXISTS alert_threshold_percent_check;
ALTER TABLE alert ALTER COLUMN threshold_percent DROP NOT NULL;
ALTER TABLE alert ALTER COLUMN spent_minor DROP NOT NULL;
ALTER TABLE alert ALTER COLUMN limit_minor DROP NOT NULL;
ALTER TABLE alert DROP CONSTRAINT IF EXISTS alert_spent_minor_check;
ALTER TABLE alert DROP CONSTRAINT IF EXISTS alert_limit_minor_check;
ALTER TABLE alert
    ADD CONSTRAINT ck_alert_type_payload CHECK (
        (alert_type = 'BUDGET_THRESHOLD' AND threshold_percent IN (80, 100))
        OR (alert_type = 'ANOMALY_HIGH' AND threshold_percent IS NULL)
    );
ALTER TABLE alert ADD COLUMN history_count INTEGER;
ALTER TABLE alert ADD COLUMN z_score NUMERIC;
ALTER TABLE alert ADD COLUMN budget_burn_rate NUMERIC;
ALTER TABLE alert ADD COLUMN explanation TEXT;
ALTER TABLE alert ADD COLUMN model TEXT;
CREATE UNIQUE INDEX uq_alert_anomaly_expense ON alert (expense_id) WHERE alert_type = 'ANOMALY_HIGH';
