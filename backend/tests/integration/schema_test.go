package integration

import (
	"accounting/backend/migrate"
	"accounting/backend/migrations"
	"context"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"os"
	"testing"
	"time"
)

func TestSchema(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatal("invalid test configuration")
	}
	if cfg.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test")
	}
	admin, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal("test database unavailable")
	}
	defer admin.Close()
	schema := fmt.Sprintf("schema_test_%d", time.Now().UnixNano())
	ident := pgx.Identifier{schema}.Sanitize()
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+ident); err != nil {
		t.Fatal(err)
	}
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		if _, err := admin.Exec(cleanup, "DROP SCHEMA "+ident+" CASCADE"); err != nil {
			t.Error("schema cleanup failed")
		}
	}()
	scoped := cfg.Copy()
	scoped.ConnConfig.RuntimeParams["search_path"] = schema
	db, err := pgxpool.NewWithConfig(ctx, scoped)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if n, e := migrate.Apply(ctx, db, migrations.Files); e != nil || n != 4 {
		t.Fatalf("schema migrations: count=%d error=%v", n, e)
	}
	if n, e := migrate.Apply(ctx, db, migrations.Files); e != nil || n != 0 {
		t.Fatal("schema repeat failed", e)
	}
	exec := func(sql string) {
		t.Helper()
		if _, e := db.Exec(ctx, sql, pgx.QueryExecModeSimpleProtocol); e != nil {
			t.Fatal(e)
		}
	}
	exec(`INSERT INTO users(id,email_normalized,password_hash,timezone) VALUES ('10000000-0000-4000-8000-000000000001','one@example.test','test_hash','UTC');
 INSERT INTO workspaces(id,name,owner_user_id,sync_generation_id) VALUES
 ('20000000-0000-4000-8000-000000000001','one','10000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000001'),
 ('20000000-0000-4000-8000-000000000002','two','10000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000002');
 INSERT INTO accounts(id,workspace_id,name,type,currency_code,opened_at) VALUES
 ('30000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','EUR','cash','EUR',now()),
 ('30000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000002','other','bank','EUR',now());
 INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,opening_account_id) VALUES
 ('40000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','opening','posted',now(),'UTC','30000000-0000-4000-8000-000000000001');
 INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone) VALUES
 ('40000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000001','transfer','posted',now(),'UTC');
 INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,parent_transaction_id) VALUES
 ('40000000-0000-4000-8000-000000000003','20000000-0000-4000-8000-000000000001','expense','posted',now(),'UTC','40000000-0000-4000-8000-000000000002');`)
	checks := []struct{ name, sql, code string }{
		{"cross-workspace entry", `INSERT INTO entries VALUES ('50000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000002',1)`, "23503"},
		{"zero money", `INSERT INTO entries VALUES ('50000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',0)`, "23514"},
		{"money over limit", `INSERT INTO entries VALUES ('50000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',9000000000000001)`, "23514"},
		{"immutable currency", `UPDATE accounts SET currency_code='USD'`, "23514"},
		{"immutable used scale", `UPDATE currencies SET scale=3 WHERE code='EUR'`, "23514"},
		{"duplicate opening", `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,opening_account_id) SELECT gen_random_uuid(),workspace_id,kind,status,occurred_at,occurred_timezone,opening_account_id FROM transactions WHERE kind='opening'`, "23505"},
		{"delete opening", `UPDATE transactions SET deleted_at=now() WHERE kind='opening'`, "23514"},
		{"duplicate active fee", `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,parent_transaction_id) SELECT gen_random_uuid(),workspace_id,kind,status,occurred_at,occurred_timezone,parent_transaction_id FROM transactions WHERE kind='expense'`, "23505"},
		{"zero version", `UPDATE accounts SET version=0`, "23514"},
		{"fractional FX", `UPDATE transactions SET rate_numerator=1.5,rate_denominator=1 WHERE kind='transfer'`, "23514"},
		{"invalid head boundary", `INSERT INTO sync_heads VALUES ('20000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000001',0,2)`, "23514"},
		{"wrong generation", `INSERT INTO sync_heads VALUES ('20000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000002',0,1)`, "23503"},
	}
	for _, c := range checks {
		t.Run(c.name, func(t *testing.T) {
			_, e := db.Exec(ctx, c.sql)
			var pe *pgconn.PgError
			if !errors.As(e, &pe) || pe.Code != c.code {
				t.Fatalf("expected SQLSTATE %s, got %v", c.code, e)
			}
		})
	}
	exec(`INSERT INTO entries VALUES ('50000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',-9000000000000000);
 UPDATE transactions SET deleted_at=now() WHERE kind='expense';
 INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,parent_transaction_id) VALUES ('40000000-0000-4000-8000-000000000004','20000000-0000-4000-8000-000000000001','expense','posted',now(),'UTC','40000000-0000-4000-8000-000000000002');
 INSERT INTO sync_heads(workspace_id,generation_id) SELECT id,sync_generation_id FROM workspaces;
 INSERT INTO actions(workspace_id,actor_user_id,action_id,canonical_request_hash,state,original_http_status,completed_at,response_expires_at,group_sequence,generation_id,entity_refs) VALUES ('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','60000000-0000-4000-8000-000000000001',decode(repeat('00',32),'hex'),'applied',201,now(),now()+interval '720 hours',1,'90000000-0000-4000-8000-000000000001','[]');
 INSERT INTO sync_groups VALUES ('20000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000001',1,'60000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001',now(),1,100);
 DELETE FROM sync_groups;`)
	exec(`INSERT INTO categories(id,workspace_id,name) VALUES ('70000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002','other');
 INSERT INTO tags(id,workspace_id,name,name_normalized) VALUES ('80000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002','Other','other');
 INSERT INTO sync_snapshots(workspace_id,actor_user_id,id,generation_id,base_sequence,expires_at,item_count,payload_bytes) VALUES ('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','a0000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000001',0,now()+interval '15 minutes',0,0);`)
	more := []struct{ name, sql, code string }{
		{"cross-workspace category", `INSERT INTO allocations(id,workspace_id,transaction_id,category_id,amount_minor) VALUES ('b0000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000004','70000000-0000-4000-8000-000000000001',1)`, "23503"},
		{"cross-workspace tag", `INSERT INTO transaction_tags VALUES ('20000000-0000-4000-8000-000000000001','40000000-0000-4000-8000-000000000004','80000000-0000-4000-8000-000000000001')`, "23503"},
		{"category self cycle", `UPDATE categories SET parent_id=id`, "23514"},
		{"group size limit", `INSERT INTO sync_groups VALUES ('20000000-0000-4000-8000-000000000001','90000000-0000-4000-8000-000000000001',1,'60000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001',now(),201,100)`, "23514"},
		{"snapshot id reuse", `INSERT INTO sync_snapshots SELECT * FROM sync_snapshots`, "23505"},
		{"snapshot TTL", `UPDATE sync_snapshots SET expires_at=created_at+interval '16 minutes'`, "23514"},
		{"rejected receipt generation", `UPDATE actions SET state='rejected',original_http_status=409`, "23514"},
	}
	for _, c := range more {
		t.Run(c.name, func(t *testing.T) {
			_, e := db.Exec(ctx, c.sql)
			var pe *pgconn.PgError
			if !errors.As(e, &pe) || pe.Code != c.code {
				t.Fatalf("expected SQLSTATE %s, got %v", c.code, e)
			}
		})
	}
	exec(`BEGIN;
 UPDATE workspaces SET sync_generation_id='90000000-0000-4000-8000-000000000003' WHERE id='20000000-0000-4000-8000-000000000001';
 UPDATE sync_heads SET generation_id='90000000-0000-4000-8000-000000000003',last_sequence=0,min_available_sequence=1 WHERE workspace_id='20000000-0000-4000-8000-000000000001';
 COMMIT;`)
	var oldGeneration string
	if err = db.QueryRow(ctx, "SELECT generation_id::text FROM actions").Scan(&oldGeneration); err != nil || oldGeneration != "90000000-0000-4000-8000-000000000001" {
		t.Fatal("rotation changed historical receipt")
	}

	var receipts int
	if err = db.QueryRow(ctx, "SELECT count(*) FROM actions").Scan(&receipts); err != nil || receipts != 1 {
		t.Fatal("journal retention deleted receipt")
	}
	var scales int
	if err = db.QueryRow(ctx, "SELECT count(*) FROM currencies").Scan(&scales); err != nil || scales != 6 {
		t.Fatal("currency seed mismatch")
	}
}
