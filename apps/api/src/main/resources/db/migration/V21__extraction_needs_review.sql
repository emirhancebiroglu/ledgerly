-- Separate a rejected extraction proposal from a valid expense that awaits human review.
-- Existing activity rows can be classified from the document's old terminal status; valid expense
-- reviews leave their document EXTRACTED and therefore retain the NEEDS_REVIEW activity stage.
ALTER TABLE document_activity DROP CONSTRAINT document_activity_stage_check;
ALTER TABLE document DROP CONSTRAINT document_status_check;

ALTER TABLE document_activity
    ADD CONSTRAINT document_activity_stage_check CHECK (stage IN (
        'UPLOADED', 'EXTRACTING', 'CATEGORIZING', 'DRAFTING_LEDGER',
        'POSTED', 'NEEDS_REVIEW', 'EXTRACTION_NEEDS_REVIEW', 'FAILED',
        'CATEGORIZATION_FAILED'));

-- Keep the legacy status legal only until persisted rows have been rewritten below.
ALTER TABLE document
    ADD CONSTRAINT document_status_check CHECK (status IN (
        'PENDING', 'PROCESSING', 'EXTRACTED', 'NEEDS_REVIEW',
        'EXTRACTION_NEEDS_REVIEW', 'FAILED'));

UPDATE document_activity AS activity
SET stage = 'EXTRACTION_NEEDS_REVIEW'
FROM document
WHERE activity.document_id = document.id
  AND activity.stage = 'NEEDS_REVIEW'
  AND document.status = 'NEEDS_REVIEW';

UPDATE document
SET status = 'EXTRACTION_NEEDS_REVIEW'
WHERE status = 'NEEDS_REVIEW';

ALTER TABLE document DROP CONSTRAINT document_status_check;
ALTER TABLE document
    ADD CONSTRAINT document_status_check CHECK (status IN (
        'PENDING', 'PROCESSING', 'EXTRACTED', 'EXTRACTION_NEEDS_REVIEW', 'FAILED'));
