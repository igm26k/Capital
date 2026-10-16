-- Structured observations are private auxiliary resources, not financial mirror entities.
CREATE TABLE source_events (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), id uuid NOT NULL,
 actor_user_id uuid NOT NULL REFERENCES users(id), session_id uuid NOT NULL REFERENCES sessions(id), generation_id uuid NOT NULL,
 connection_id uuid NOT NULL, external_id text NOT NULL CHECK(external_id ~ '^[0-9a-f]{64}$'),
 account_id uuid NOT NULL, payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'), version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 state text NOT NULL DEFAULT 'review' CHECK(state IN ('review','dismissed','applied')),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(workspace_id,id), UNIQUE(workspace_id,actor_user_id,connection_id,external_id),
 FOREIGN KEY(workspace_id,account_id) REFERENCES accounts(workspace_id,id)
);
CREATE INDEX source_events_owner ON source_events(workspace_id,actor_user_id,received_at DESC,id);
