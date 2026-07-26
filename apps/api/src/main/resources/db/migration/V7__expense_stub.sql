-- M3 T4: minimal mutating write to exercise the idempotency filter. Superseded by the real
-- expense/ledger-transaction pipeline at M4/M6.

ALTER TABLE idempotency_record
    ADD COLUMN response_status SMALLINT;

CREATE TABLE expense_stub (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_expense_stub_organization_id ON expense_stub (organization_id);
