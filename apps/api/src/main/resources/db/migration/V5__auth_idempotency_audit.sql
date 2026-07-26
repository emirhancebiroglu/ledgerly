-- M3: auth (password hash on app_user), idempotency records, and the audit trail.

ALTER TABLE app_user
    ADD COLUMN password_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE app_user
    ALTER COLUMN password_hash DROP DEFAULT;

CREATE TABLE idempotency_record (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    key             TEXT NOT NULL,
    endpoint        TEXT NOT NULL,
    request_hash    TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response        TEXT,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, key, endpoint)
);

CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    actor           UUID NOT NULL REFERENCES app_user (id),
    action          TEXT NOT NULL,
    entity_type     TEXT NOT NULL,
    entity_id       UUID NOT NULL,
    before          JSONB,
    after           JSONB,
    correlation_id  UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_record_expires_at ON idempotency_record (expires_at);
CREATE INDEX idx_audit_log_organization_id ON audit_log (organization_id);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
