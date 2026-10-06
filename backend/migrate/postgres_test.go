package migrate

import (
	"context"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"testing/fstest"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestPostgresMigrationTransactions(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	config, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatal("invalid test database configuration")
	}
	if config.ConnConfig.Database != "accounting_test" {
		t.Fatal("requires isolated accounting_test database")
	}
	admin, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal("test database unavailable")
	}
	defer admin.Close()
	schema := fmt.Sprintf("migration_test_%d", time.Now().UnixNano())
	identifier := pgx.Identifier{schema}.Sanitize()
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+identifier); err != nil {
		t.Fatal("cannot create isolated test schema")
	}
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		if _, err := admin.Exec(cleanup, "DROP SCHEMA "+identifier+" CASCADE"); err != nil {
			t.Error("test schema cleanup failed")
		}
	}()
	scoped := config.Copy()
	scoped.ConnConfig.RuntimeParams["search_path"] = schema
	db, err := pgxpool.NewWithConfig(ctx, scoped)
	if err != nil {
		t.Fatal("cannot open isolated schema")
	}
	defer db.Close()
	files := fstest.MapFS{"0001_probe.sql": {Data: []byte("CREATE TABLE probe(id integer PRIMARY KEY); SELECT pg_sleep(0.1);")}}
	var wg sync.WaitGroup
	counts := make(chan int, 2)
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); n, e := Apply(ctx, db, files); counts <- n; errs <- e }()
	}
	wg.Wait()
	close(counts)
	close(errs)
	total := 0
	for n := range counts {
		total += n
	}
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	if total != 1 {
		t.Fatal("concurrent migration applied more than once")
	}
	if n, e := Apply(ctx, db, files); e != nil || n != 0 {
		t.Fatal("repeat migration failed")
	}
	changed := fstest.MapFS{"0001_probe.sql": {Data: []byte("CREATE TABLE probe(id bigint PRIMARY KEY);")}}
	if _, e := Apply(ctx, db, changed); e == nil || !strings.Contains(e.Error(), "differs") {
		t.Fatal("changed migration accepted")
	}
	failing := fstest.MapFS{"0001_probe.sql": files["0001_probe.sql"], "0002_rollback.sql": {Data: []byte("INSERT INTO probe VALUES (1); SELECT missing_private_function();")}}
	if _, e := Apply(ctx, db, failing); e == nil {
		t.Fatal("invalid migration succeeded")
	}
	var rows, history int
	if err = db.QueryRow(ctx, "SELECT count(*) FROM probe").Scan(&rows); err != nil {
		t.Fatal(err)
	}
	if err = db.QueryRow(ctx, "SELECT count(*) FROM accounting_schema_migrations").Scan(&history); err != nil {
		t.Fatal(err)
	}
	if rows != 0 || history != 1 {
		t.Fatal("failed migration left partial changes")
	}
}
