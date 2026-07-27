-- M4: uploaded documents awaiting extraction.
--
-- storage_key is the opaque handle minted by StorageClient — never a filesystem path, and never
-- exposed to a client. The status lifecycle is modelled here even though M4 processes
-- synchronously, so moving extraction off-thread later is a service change, not a schema change.

CREATE TABLE document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    uploaded_by     UUID NOT NULL REFERENCES app_user (id),
    filename        TEXT NOT NULL,
    content_type    TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL CHECK (size_bytes > 0),
    storage_key     TEXT NOT NULL UNIQUE,
    content_hash    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'EXTRACTED', 'NEEDS_REVIEW', 'FAILED')),
    proposal        JSONB,
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every read is org-scoped; the org column leads so (organization_id, id) lookups use the index.
CREATE INDEX idx_document_organization_id ON document (organization_id, created_at DESC);
CREATE INDEX idx_document_status ON document (organization_id, status);
