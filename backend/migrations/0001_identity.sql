CREATE TABLE users (
 id uuid PRIMARY KEY,
 email_normalized text NOT NULL UNIQUE CHECK (length(email_normalized) BETWEEN 3 AND 254 AND email_normalized = translate(email_normalized, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')),
 password_hash text NOT NULL CHECK (length(password_hash) > 0),
 timezone text NOT NULL CHECK (length(timezone) BETWEEN 1 AND 100),
 created_at timestamptz NOT NULL DEFAULT now(), disabled_at timestamptz
);
CREATE TABLE workspaces (
 id uuid PRIMARY KEY, name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
 owner_user_id uuid NOT NULL REFERENCES users(id), version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
 sync_generation_id uuid NOT NULL DEFAULT gen_random_uuid(),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (id, sync_generation_id)
);
CREATE TABLE memberships (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), user_id uuid NOT NULL REFERENCES users(id),
 role text NOT NULL CHECK (role IN ('owner','member')), created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz,
 PRIMARY KEY (workspace_id,user_id)
);
CREATE UNIQUE INDEX memberships_one_active_owner ON memberships(workspace_id) WHERE role='owner' AND revoked_at IS NULL;
CREATE TABLE sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), device_name text NOT NULL CHECK (length(device_name) BETWEEN 1 AND 100),
 token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash)=32),
 csrf_token_hash bytea NOT NULL CHECK (octet_length(csrf_token_hash)=32),
 created_at timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz,
 CHECK (expires_at > created_at)
);
CREATE INDEX sessions_user ON sessions(user_id,created_at);
CREATE TABLE currencies (code text PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'), scale smallint NOT NULL CHECK (scale BETWEEN 0 AND 9));
INSERT INTO currencies(code,scale) VALUES ('EUR',2),('USD',2),('RUB',2),('GBP',2),('JPY',0),('KWD',3);
