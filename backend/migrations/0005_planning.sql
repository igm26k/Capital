-- Online planning settings have independent receipts; the v1 financial mirror remains compatible.
CREATE TABLE planning_resources (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('budget','rule')), version bigint NOT NULL CHECK(version>0),
 data jsonb NOT NULL CHECK(jsonb_typeof(data)='object'), deleted_at timestamptz,
 PRIMARY KEY(workspace_id,kind,id)
);
CREATE TABLE planning_actions (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), actor_user_id uuid NOT NULL REFERENCES users(id),
 action_id uuid NOT NULL, request_hash bytea NOT NULL CHECK(octet_length(request_hash)=32),
 response jsonb NOT NULL CHECK(jsonb_typeof(response)='object'), status smallint NOT NULL CHECK(status IN (200,201,409,422)),
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(workspace_id,actor_user_id,action_id)
);
CREATE TABLE planning_audit (
 id bigserial PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), actor_user_id uuid NOT NULL REFERENCES users(id),
 action_id uuid NOT NULL, resource_kind text NOT NULL CHECK(resource_kind IN ('budget','rule')), resource_id uuid NOT NULL,
 resource_version bigint NOT NULL CHECK(resource_version>0), operation text NOT NULL CHECK(operation IN ('create','update','delete')),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
