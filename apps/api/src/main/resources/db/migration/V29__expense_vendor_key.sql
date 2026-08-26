-- DuplicateMatcher's SUSPECTED/CONFIRMED queries compared LOWER(TRIM(e.vendor)) in SQL against a
-- vendorKey the Java side built with String.toLowerCase(Locale.ROOT). The two disagree on Turkish
-- "İ" (U+0130): Postgres's LOWER folds it to a bare "i" under this database's collation, while
-- Java's locale-independent case folding produces "i" followed by a combining dot above (U+0307).
-- Any vendor name containing "İ" therefore never matched its own earlier postings, and duplicate
-- detection silently found nothing for it — this shipped as 111 unflagged repeat postings for one
-- real vendor before it was caught.
--
-- The fix stores the Java-computed key alongside the row instead of re-deriving it in SQL with a
-- different algorithm. New rows get it from ExpensePostingTransactions/ExpenseReviewTransactions'
-- write path (Expense.java); this backfill computes the same value for existing rows so history
-- keeps matching. REPLACE(vendor, 'İ', 'i' || CHR(775)) reproduces Java's expansion of "İ" before
-- folding the rest with lower() — every other letter LOWER() already handles the same way Java
-- does, so this one substitution is sufficient to make the two sides agree.
ALTER TABLE expense ADD COLUMN vendor_key TEXT;

UPDATE expense
SET vendor_key = LOWER(REPLACE(TRIM(vendor), 'İ', 'i' || CHR(775)))
WHERE vendor IS NOT NULL;

DROP INDEX idx_expense_org_vendor_invoice_number;
DROP INDEX idx_expense_org_vendor_issue_date;

CREATE INDEX idx_expense_org_vendor_invoice_number
    ON expense (organization_id, vendor_key, invoice_number)
    WHERE invoice_number IS NOT NULL;
CREATE INDEX idx_expense_org_vendor_issue_date
    ON expense (organization_id, vendor_key, issue_date)
    WHERE issue_date IS NOT NULL;
