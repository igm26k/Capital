-- Display preferences stay separate from ledger balances and the v1 Account wire shape.
CREATE TABLE account_orders (
 workspace_id uuid PRIMARY KEY REFERENCES workspaces(id),
 version bigint NOT NULL CHECK(version > 0),
 account_ids uuid[] NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
