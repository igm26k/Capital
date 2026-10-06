-- Persist the signing key ID so live snapshot metadata/page tokens remain stable
-- during key rotation. Legacy markers without a key require a new bootstrap.
ALTER TABLE sync_snapshots ADD COLUMN cursor_key_id text CHECK (cursor_key_id IS NULL OR length(cursor_key_id) BETWEEN 1 AND 64);
