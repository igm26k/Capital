CREATE TABLE accounts (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id),
 name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100), type text NOT NULL CHECK(type IN ('cash','bank','card')),
 currency_code text NOT NULL REFERENCES currencies(code), opened_at timestamptz NOT NULL, archived_at timestamptz,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), balance_version bigint NOT NULL DEFAULT 1 CHECK(balance_version>0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(workspace_id,id)
);
CREATE FUNCTION protect_account_currency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.currency_code IS DISTINCT FROM OLD.currency_code THEN RAISE EXCEPTION 'account currency is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER accounts_currency_immutable BEFORE UPDATE OF currency_code ON accounts FOR EACH ROW EXECUTE FUNCTION protect_account_currency();
CREATE FUNCTION protect_currency_scale() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.scale IS DISTINCT FROM OLD.scale AND EXISTS(SELECT 1 FROM accounts WHERE currency_code=OLD.code) THEN RAISE EXCEPTION 'used currency scale is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER currencies_scale_immutable BEFORE UPDATE OF scale ON currencies FOR EACH ROW EXECUTE FUNCTION protect_currency_scale();
CREATE TABLE categories (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), name text NOT NULL CHECK(length(name) BETWEEN 1 AND 100), parent_id uuid,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), archived_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(workspace_id,id), CHECK(parent_id IS NULL OR parent_id<>id),
 FOREIGN KEY(workspace_id,parent_id) REFERENCES categories(workspace_id,id)
);
CREATE TABLE tags (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), name text NOT NULL CHECK(length(name) BETWEEN 1 AND 50),
 name_normalized text NOT NULL CHECK(length(name_normalized) BETWEEN 1 AND 50),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), archived_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(workspace_id,id)
);
CREATE UNIQUE INDEX tags_active_name ON tags(workspace_id,name_normalized) WHERE archived_at IS NULL AND deleted_at IS NULL;
CREATE TABLE transactions (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id),
 kind text NOT NULL CHECK(kind IN ('opening','expense','income','transfer','refund','adjustment')),
 status text NOT NULL CHECK(status IN ('pending','posted')),
 occurred_at timestamptz NOT NULL, occurred_timezone text NOT NULL CHECK(length(occurred_timezone) BETWEEN 1 AND 100),
 note text NOT NULL DEFAULT '' CHECK(length(note)<=2000), payee text NOT NULL DEFAULT '' CHECK(length(payee)<=200),
 parent_transaction_id uuid, opening_account_id uuid,
 rate_numerator numeric, rate_denominator numeric, reason text,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(workspace_id,id),
 FOREIGN KEY(workspace_id,parent_transaction_id) REFERENCES transactions(workspace_id,id),
 FOREIGN KEY(workspace_id,opening_account_id) REFERENCES accounts(workspace_id,id),
 CHECK(parent_transaction_id IS NULL OR (parent_transaction_id<>id AND kind IN ('refund','expense'))),
 CHECK(kind<>'refund' OR parent_transaction_id IS NOT NULL),
 CHECK((kind='opening') = (opening_account_id IS NOT NULL)),
 CHECK(kind<>'opening' OR (deleted_at IS NULL AND status='posted')),
 CHECK((rate_numerator IS NULL AND rate_denominator IS NULL) OR (kind='transfer' AND rate_numerator>0 AND rate_denominator>0 AND rate_numerator=trunc(rate_numerator) AND rate_denominator=trunc(rate_denominator) AND rate_numerator<1e40 AND rate_denominator<1e40 AND rate_numerator IS NOT NULL AND rate_denominator IS NOT NULL)),
 CHECK((kind='adjustment' AND reason IS NOT NULL AND length(reason) BETWEEN 1 AND 500) OR (kind<>'adjustment' AND reason IS NULL))
);
CREATE UNIQUE INDEX transactions_one_opening ON transactions(workspace_id,opening_account_id) WHERE kind='opening';
CREATE UNIQUE INDEX transactions_one_active_fee ON transactions(workspace_id,parent_transaction_id) WHERE kind='expense' AND parent_transaction_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX transactions_workspace_date ON transactions(workspace_id,occurred_at,id) WHERE deleted_at IS NULL;
CREATE INDEX transactions_parent ON transactions(workspace_id,parent_transaction_id) WHERE parent_transaction_id IS NOT NULL;
CREATE TABLE entries (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), transaction_id uuid NOT NULL, account_id uuid NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor<>0 AND amount_minor BETWEEN -9000000000000000 AND 9000000000000000),
 UNIQUE(workspace_id,id), UNIQUE(workspace_id,transaction_id,account_id),
 FOREIGN KEY(workspace_id,transaction_id) REFERENCES transactions(workspace_id,id),
 FOREIGN KEY(workspace_id,account_id) REFERENCES accounts(workspace_id,id)
);
CREATE INDEX entries_account ON entries(workspace_id,account_id,transaction_id);
CREATE TABLE allocations (
 id uuid PRIMARY KEY, workspace_id uuid NOT NULL REFERENCES workspaces(id), transaction_id uuid NOT NULL, category_id uuid,
 amount_minor bigint NOT NULL CHECK(amount_minor BETWEEN 1 AND 9000000000000000), original_allocation_id uuid,
 UNIQUE(workspace_id,id), UNIQUE(workspace_id,transaction_id,original_allocation_id),
 FOREIGN KEY(workspace_id,transaction_id) REFERENCES transactions(workspace_id,id),
 FOREIGN KEY(workspace_id,category_id) REFERENCES categories(workspace_id,id),
 FOREIGN KEY(workspace_id,original_allocation_id) REFERENCES allocations(workspace_id,id),
 CHECK(original_allocation_id IS NULL OR original_allocation_id<>id)
);
CREATE INDEX allocations_transaction ON allocations(workspace_id,transaction_id);
CREATE INDEX allocations_original ON allocations(workspace_id,original_allocation_id) WHERE original_allocation_id IS NOT NULL;
CREATE TABLE transaction_tags (
 workspace_id uuid NOT NULL REFERENCES workspaces(id), transaction_id uuid NOT NULL, tag_id uuid NOT NULL,
 PRIMARY KEY(workspace_id,transaction_id,tag_id),
 FOREIGN KEY(workspace_id,transaction_id) REFERENCES transactions(workspace_id,id), FOREIGN KEY(workspace_id,tag_id) REFERENCES tags(workspace_id,id)
);
