-- Expected categorization uncertainty is reviewable without inventing a fallback category.
ALTER TABLE expense ALTER COLUMN category_id DROP NOT NULL;
