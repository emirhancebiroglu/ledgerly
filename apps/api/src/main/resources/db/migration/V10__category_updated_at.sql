-- M6: category CRUD needs to record rename timestamps; V1 only ever inserted seed data and never
-- updated a row, so no updated_at column existed until now.

ALTER TABLE category ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
