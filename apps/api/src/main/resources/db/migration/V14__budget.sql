-- M8 T1: an organization can define one exact-money budget per category, calendar month and
-- currency. Budget spend is derived from posted ledger transactions; it is never cached here.

CREATE TABLE budget (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    category_id     UUID NOT NULL REFERENCES category (id),
    period          VARCHAR(7) NOT NULL
        CHECK (period ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])$'),
    limit_minor     BIGINT NOT NULL CHECK (limit_minor > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_budget_organization_category_period_currency
        UNIQUE (organization_id, category_id, period, currency)
);

CREATE INDEX idx_budget_organization_period ON budget (organization_id, period);
