-- M6 T6/T7: the real business-level expense record, linking a document to the category it was
-- classified into and the ledger transaction it produced — architecture.md §3's `expense` node.
--
-- Distinct from `expense_stub` (V7), which stays as-is: it exercises the idempotency filter from
-- M3 and nothing here supersedes that purpose.
--
-- ledger_transaction_id is nullable: a NEEDS_REVIEW expense has no ledger entry until a human
-- approves it (T7) — the row exists to hold the categorization result, but posting is withheld.

CREATE TABLE expense (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID NOT NULL REFERENCES organization (id),
    document_id           UUID NOT NULL REFERENCES document (id),
    category_id           UUID NOT NULL REFERENCES category (id),
    ledger_transaction_id UUID REFERENCES ledger_transaction (id),
    amount_minor          BIGINT NOT NULL CHECK (amount_minor > 0),
    currency              CHAR(3) NOT NULL,
    categorization_confidence NUMERIC(4, 3) NOT NULL CHECK (categorization_confidence BETWEEN 0 AND 1),
    citation              TEXT,
    status                TEXT NOT NULL
        CHECK (status IN ('POSTED', 'NEEDS_REVIEW')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id)
);

CREATE INDEX idx_expense_organization_id ON expense (organization_id, created_at DESC);
CREATE INDEX idx_expense_status ON expense (organization_id, status);
