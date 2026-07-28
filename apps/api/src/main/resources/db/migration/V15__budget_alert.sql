-- M8 T2: immutable snapshots of deterministic budget-threshold crossings. The snapshot keeps an
-- alert meaningful even if its budget is later edited or deleted.

CREATE TABLE alert (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organization (id),
    expense_id        UUID NOT NULL REFERENCES expense (id),
    budget_id         UUID REFERENCES budget (id) ON DELETE SET NULL,
    category_id       UUID NOT NULL REFERENCES category (id),
    period            VARCHAR(7) NOT NULL,
    currency          CHAR(3) NOT NULL,
    alert_type        TEXT NOT NULL CHECK (alert_type = 'BUDGET_THRESHOLD'),
    threshold_percent SMALLINT NOT NULL CHECK (threshold_percent IN (80, 100)),
    spent_minor       BIGINT NOT NULL CHECK (spent_minor >= 0),
    limit_minor       BIGINT NOT NULL CHECK (limit_minor > 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_alert_budget_threshold UNIQUE (budget_id, threshold_percent)
);

CREATE INDEX idx_alert_organization_created_at ON alert (organization_id, created_at DESC);
CREATE INDEX idx_expense_budget_spend
    ON expense (organization_id, category_id, currency)
    WHERE status = 'POSTED';
