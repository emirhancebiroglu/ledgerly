-- M9.6 T1: promotes invoice_number/document_date out of document.proposal JSONB into indexed
-- expense columns so duplicate detection can query them directly instead of round-tripping
-- through JSONB on every posting. Both are nullable — many real invoices lack a printed number,
-- and a legacy row can predate a schema-valid persisted proposal (see M9.3's null-proposal-field
-- defect); backfill leaves those rows null rather than guessing.

ALTER TABLE expense ADD COLUMN invoice_number TEXT;
ALTER TABLE expense ADD COLUMN issue_date DATE;

-- A malformed or non-ISO document_date in a legacy proposal (see M9.3's schema-drift findings)
-- must leave issue_date null rather than failing the whole migration — a bare `::date` cast on
-- untrusted JSONB text would abort the transaction on the first bad row. The helper is dropped at
-- the end of this migration; it exists only to backfill this one column.
CREATE FUNCTION expense_invoice_identity_safe_to_date(value TEXT) RETURNS DATE AS $$
BEGIN
    RETURN value::date;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

UPDATE expense e
SET invoice_number = NULLIF(TRIM(d.proposal ->> 'invoice_number'), ''),
    issue_date = expense_invoice_identity_safe_to_date(d.proposal ->> 'document_date')
FROM document d
WHERE e.document_id = d.id
  AND d.proposal IS NOT NULL
  AND jsonb_typeof(d.proposal) = 'object';

DROP FUNCTION expense_invoice_identity_safe_to_date(TEXT);

-- Supports the exact match (organization + vendor + invoice_number) and the windowed heuristic
-- (organization + vendor + issue_date) in one index; a partial index on invoice_number alone would
-- miss the heuristic's issue_date-only lookups when invoice_number is null.
CREATE INDEX idx_expense_org_vendor_invoice_number
    ON expense (organization_id, lower(vendor), invoice_number)
    WHERE invoice_number IS NOT NULL;
CREATE INDEX idx_expense_org_vendor_issue_date
    ON expense (organization_id, lower(vendor), issue_date)
    WHERE issue_date IS NOT NULL;
