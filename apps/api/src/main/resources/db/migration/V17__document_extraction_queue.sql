-- M9: durable extraction queue. A transient agent outage must leave uploaded work retryable.
ALTER TABLE document
    ADD COLUMN extraction_attempts INTEGER NOT NULL DEFAULT 0 CHECK (extraction_attempts >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_document_pending_attempt
    ON document (next_attempt_at ASC)
    WHERE status = 'PENDING';
