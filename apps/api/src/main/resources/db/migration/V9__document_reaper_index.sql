-- M5 T7: the reaper scans across all organizations for stuck PROCESSING documents, unlike every
-- other query on this table (which lead with organization_id). idx_document_status is useless for
-- that scan since it leads with organization_id; this index leads with status instead.

CREATE INDEX idx_document_status_updated_at ON document (status, updated_at);
