-- M7a T1: the expense list needs to search and display vendor, but vendor only ever existed
-- inside document.proposal (JSONB) -- never a queryable column. Denormalized onto expense at
-- posting time, same reasoning as amount_minor/currency at M6: fast org-scoped reads with no
-- join back to document.
--
-- Nullable: existing rows predate this column and have no vendor to backfill from without
-- re-parsing their document.proposal JSONB, which is out of scope for this task.

ALTER TABLE expense ADD COLUMN vendor TEXT;

CREATE INDEX idx_expense_vendor_search ON expense (organization_id, lower(vendor) text_pattern_ops);
