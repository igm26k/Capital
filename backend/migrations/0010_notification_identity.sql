-- Keep legacy rows and hashes intact. New Android identities span parser profiles/account assignments.
ALTER TABLE source_events ADD COLUMN notification_identity bytea CHECK(notification_identity IS NULL OR octet_length(notification_identity)=32);
ALTER TABLE source_events ADD COLUMN payload_hash_format smallint NOT NULL DEFAULT 1 CHECK(payload_hash_format IN (1,2));
CREATE UNIQUE INDEX source_notification_identity ON source_events(workspace_id,actor_user_id,notification_identity) WHERE notification_identity IS NOT NULL;
