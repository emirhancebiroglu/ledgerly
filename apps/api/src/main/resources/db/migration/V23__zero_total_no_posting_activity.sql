-- A valid zero-total document is complete, but must never fabricate an expense or ledger movement.
ALTER TABLE document_activity DROP CONSTRAINT document_activity_stage_check;

ALTER TABLE document_activity
    ADD CONSTRAINT document_activity_stage_check CHECK (stage IN (
        'UPLOADED', 'EXTRACTING', 'CATEGORIZING', 'DRAFTING_LEDGER',
        'POSTED', 'NO_POSTING_REQUIRED', 'NEEDS_REVIEW', 'EXTRACTION_NEEDS_REVIEW', 'FAILED',
        'CATEGORIZATION_FAILED'));
