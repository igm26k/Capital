CREATE TABLE csv_imports (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), id uuid NOT NULL, actor_user_id uuid NOT NULL REFERENCES users(id),
 session_id uuid NOT NULL REFERENCES sessions(id), generation_id uuid NOT NULL, account_id uuid NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), state text NOT NULL CHECK(state IN ('ready','queued','committed','failed','expired')),
 preview jsonb NOT NULL, expires_at timestamptz NOT NULL, error_code text,
 PRIMARY KEY(workspace_id,id), FOREIGN KEY(workspace_id,account_id) REFERENCES accounts(workspace_id,id)
);
CREATE TABLE csv_rows (
 workspace_id uuid NOT NULL, import_id uuid NOT NULL, row_number integer NOT NULL CHECK(row_number BETWEEN 2 AND 1001),
 action_id uuid NOT NULL, transaction_id uuid NOT NULL, fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 payload jsonb, state text NOT NULL CHECK(state IN ('prepared','invalid','queued','applied','linked','skipped')),
 resolved_id uuid, PRIMARY KEY(workspace_id,import_id,row_number),
 FOREIGN KEY(workspace_id,import_id) REFERENCES csv_imports(workspace_id,id),
 FOREIGN KEY(workspace_id,resolved_id) REFERENCES transactions(workspace_id,id)
);
CREATE TABLE csv_sources (
 workspace_id uuid NOT NULL, account_id uuid NOT NULL, fingerprint bytea NOT NULL,
 import_id uuid NOT NULL, row_number integer NOT NULL, transaction_id uuid,
 PRIMARY KEY(workspace_id,account_id,fingerprint),
 FOREIGN KEY(workspace_id,import_id,row_number) REFERENCES csv_rows(workspace_id,import_id,row_number),
 FOREIGN KEY(workspace_id,transaction_id) REFERENCES transactions(workspace_id,id)
);
CREATE INDEX csv_imports_queue ON csv_imports(workspace_id,id) WHERE state='queued';
