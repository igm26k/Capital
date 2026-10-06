CREATE TABLE sync_heads (
 workspace_id uuid PRIMARY KEY REFERENCES workspaces(id), generation_id uuid NOT NULL,
 last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence>=0), min_available_sequence bigint NOT NULL DEFAULT 1 CHECK(min_available_sequence>=1),
 CHECK(min_available_sequence::numeric<=last_sequence::numeric+1),
 FOREIGN KEY(workspace_id,generation_id) REFERENCES workspaces(id,sync_generation_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE actions (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), actor_user_id uuid NOT NULL REFERENCES users(id), action_id uuid NOT NULL,
 canonical_request_hash bytea NOT NULL CHECK(octet_length(canonical_request_hash)=32),
 state text NOT NULL CHECK(state IN ('applied','rejected')), original_http_status smallint NOT NULL,
 response_body jsonb CHECK(response_body IS NULL OR jsonb_typeof(response_body)='object'),
 completed_at timestamptz NOT NULL DEFAULT now(), response_expires_at timestamptz NOT NULL,
 group_sequence bigint, generation_id uuid,
 entity_refs jsonb NOT NULL CHECK(jsonb_typeof(entity_refs)='array'),
 PRIMARY KEY(workspace_id,actor_user_id,action_id),
 CHECK(response_expires_at=completed_at+interval '720 hours'),
 CHECK((state='applied' AND original_http_status BETWEEN 200 AND 299 AND group_sequence>0 AND group_sequence IS NOT NULL AND generation_id IS NOT NULL) OR (state='rejected' AND original_http_status IN (409,422) AND group_sequence IS NULL AND generation_id IS NULL))
);
-- Receipts deliberately do not reference retained groups: receipt lifetime exceeds journal retention.
CREATE TABLE sync_groups (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), generation_id uuid NOT NULL, sequence bigint NOT NULL CHECK(sequence>0),
 action_id uuid NOT NULL, actor_user_id uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now(),
 change_count integer NOT NULL CHECK(change_count BETWEEN 1 AND 200), payload_bytes integer NOT NULL CHECK(payload_bytes BETWEEN 1 AND 1048576),
 PRIMARY KEY(workspace_id,generation_id,sequence), UNIQUE(workspace_id,actor_user_id,action_id),
 FOREIGN KEY(workspace_id,actor_user_id,action_id) REFERENCES actions(workspace_id,actor_user_id,action_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX sync_groups_retention ON sync_groups(workspace_id,created_at);
CREATE TABLE sync_changes (
 workspace_id uuid NOT NULL, generation_id uuid NOT NULL, sequence bigint NOT NULL, ordinal integer NOT NULL CHECK(ordinal BETWEEN 1 AND 200),
 change_id uuid NOT NULL UNIQUE, entity_type text NOT NULL CHECK(entity_type IN ('account','transaction','category','tag')), entity_id uuid NOT NULL,
 entity_version bigint NOT NULL CHECK(entity_version>0), operation text NOT NULL CHECK(operation IN ('upsert','delete')),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(workspace_id,generation_id,sequence,ordinal), UNIQUE(workspace_id,generation_id,sequence,entity_type,entity_id),
 FOREIGN KEY(workspace_id,generation_id,sequence) REFERENCES sync_groups(workspace_id,generation_id,sequence) ON DELETE CASCADE
);
CREATE TABLE sync_snapshots (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), actor_user_id uuid NOT NULL REFERENCES users(id), id uuid NOT NULL,
 generation_id uuid NOT NULL, base_sequence bigint NOT NULL CHECK(base_sequence>=0), created_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
 item_count integer NOT NULL CHECK(item_count BETWEEN 0 AND 50000), payload_bytes bigint NOT NULL CHECK(payload_bytes BETWEEN 0 AND 67108864), payload_cleared_at timestamptz,
 PRIMARY KEY(workspace_id,actor_user_id,id), CHECK(expires_at=created_at+interval '15 minutes')
);
CREATE INDEX sync_snapshots_expiry ON sync_snapshots(expires_at) WHERE payload_cleared_at IS NULL;
CREATE TABLE sync_snapshot_items (
 workspace_id uuid NOT NULL, actor_user_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 position integer NOT NULL CHECK(position BETWEEN 1 AND 50000),
 entity_type text NOT NULL CHECK(entity_type IN ('account','transaction','category','tag')), entity_id uuid NOT NULL,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 PRIMARY KEY(workspace_id,actor_user_id,snapshot_id,position), UNIQUE(workspace_id,actor_user_id,snapshot_id,entity_type,entity_id),
 FOREIGN KEY(workspace_id,actor_user_id,snapshot_id) REFERENCES sync_snapshots(workspace_id,actor_user_id,id) ON DELETE CASCADE
);
-- Old snapshots retain markers across generation rotation; no FK to the current head generation.
CREATE TABLE audit_events (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), actor_user_id uuid NOT NULL REFERENCES users(id), action_id uuid NOT NULL,
 entity_type text NOT NULL CHECK(entity_type IN ('account','transaction','category','tag','workspace')), entity_id uuid NOT NULL,
 event_type text NOT NULL CHECK(length(event_type) BETWEEN 1 AND 100), timestamp timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(workspace_id,actor_user_id,action_id) REFERENCES actions(workspace_id,actor_user_id,action_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX audit_events_workspace_time ON audit_events(workspace_id,timestamp);
