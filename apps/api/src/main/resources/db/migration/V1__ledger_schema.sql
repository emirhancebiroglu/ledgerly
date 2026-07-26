-- Ledgerly core schema. Every monetary column is BIGINT minor units + CHAR(3) ISO currency —
-- no float/real/double precision anywhere (enforced by CI grep and a schema-assertion test).

CREATE TABLE organization (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL,
    base_currency CHAR(3) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    email           TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, email)
);

CREATE TABLE account (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    name            TEXT NOT NULL,
    account_type    TEXT NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EXPENSE', 'REVENUE', 'EQUITY')),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE category (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    name            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE fx_rate (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_currency   CHAR(3) NOT NULL,
    to_currency     CHAR(3) NOT NULL,
    rate            NUMERIC(20, 8) NOT NULL,
    as_of           DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_currency, to_currency, as_of)
);

CREATE TABLE ledger_transaction (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    base_currency   CHAR(3) NOT NULL,
    posted_at       TIMESTAMPTZ NOT NULL,
    description     TEXT,
    reversal_of_id  UUID REFERENCES ledger_transaction (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL REFERENCES ledger_transaction (id),
    account_id          UUID NOT NULL REFERENCES account (id),
    direction           TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    native_amount_minor BIGINT NOT NULL CHECK (native_amount_minor > 0),
    native_currency     CHAR(3) NOT NULL,
    base_amount_minor   BIGINT NOT NULL CHECK (base_amount_minor > 0),
    base_currency       CHAR(3) NOT NULL,
    fx_rate             NUMERIC(20, 8) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_user_organization_id ON app_user (organization_id);
CREATE INDEX idx_account_organization_id ON account (organization_id);
CREATE INDEX idx_category_organization_id ON category (organization_id);
CREATE INDEX idx_ledger_transaction_organization_id ON ledger_transaction (organization_id);
CREATE INDEX idx_ledger_entry_transaction_id ON ledger_entry (transaction_id);
CREATE INDEX idx_ledger_entry_account_id ON ledger_entry (account_id);
