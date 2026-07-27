-- M6: org-scoped expense-policy documents and their embedded chunks for retrieval-augmented
-- categorization. pgvector is enabled here — no earlier migration needed it.
--
-- embedding has no fixed dimension in the column type itself: `ai`'s embedding model determines
-- it, and a real embedding model may replace the fake one used in tests without a schema change.
-- HNSW/ivfflat indexing is deferred until real query-volume evidence exists (M9/M10 concern).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE policy_document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization (id),
    uploaded_by     UUID NOT NULL REFERENCES app_user (id),
    filename        TEXT NOT NULL,
    storage_key     TEXT NOT NULL UNIQUE,
    content_hash    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'EMBEDDED', 'FAILED')),
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy_chunk (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL REFERENCES organization (id),
    policy_document_id UUID NOT NULL REFERENCES policy_document (id),
    chunk_index        INT NOT NULL CHECK (chunk_index >= 0),
    chunk_text         TEXT NOT NULL,
    embedding          vector NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_document_id, chunk_index)
);

CREATE INDEX idx_policy_document_organization_id ON policy_document (organization_id, created_at DESC);
CREATE INDEX idx_policy_chunk_organization_id ON policy_chunk (organization_id);
CREATE INDEX idx_policy_chunk_policy_document_id ON policy_chunk (policy_document_id);
