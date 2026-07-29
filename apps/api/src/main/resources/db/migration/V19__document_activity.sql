-- M9.1: durable, ordered agent activity. Redis remains a live-delivery optimization only.
CREATE TABLE document_activity (
    id              BIGSERIAL PRIMARY KEY,
    document_id     UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization (id),
    stage           TEXT NOT NULL CHECK (stage IN (
        'UPLOADED', 'EXTRACTING', 'CATEGORIZING', 'DRAFTING_LEDGER',
        'POSTED', 'NEEDS_REVIEW', 'FAILED', 'CATEGORIZATION_FAILED')),
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_activity_document_id
    ON document_activity (document_id, id);
