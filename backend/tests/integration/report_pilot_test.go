package integration

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	"encoding/json"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func TestReportPilot(t *testing.T) {
	f := newAccessFixture(t)
	account := f.account(f.a)
	if _, err := f.db.Exec(f.ctx, `INSERT INTO transactions(id,workspace_id,kind,status,occurred_at,occurred_timezone,note)
 SELECT md5('pilot-t-'||n)::uuid,$1,'expense','posted','2026-09-15T12:00:00Z','UTC','Synthetic pilot' FROM generate_series(1,10000) n`, f.a.Workspace.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec(f.ctx, `INSERT INTO entries(id,workspace_id,transaction_id,account_id,amount_minor)
 SELECT md5('pilot-e-'||n)::uuid,$1,md5('pilot-t-'||n)::uuid,$2,-1 FROM generate_series(1,10000) n`, f.a.Workspace.ID, account); err != nil {
		t.Fatal(err)
	}
	if _, err := f.db.Exec(f.ctx, `INSERT INTO allocations(id,workspace_id,transaction_id,amount_minor)
 SELECT md5('pilot-p-'||n)::uuid,$1,md5('pilot-t-'||n)::uuid,1 FROM generate_series(1,10000) n`, f.a.Workspace.ID); err != nil {
		t.Fatal(err)
	}
	from, _ := ledger.ParseInstant("2026-09-01T00:00:00Z")
	to, _ := ledger.ParseInstant("2026-10-01T00:00:00Z")
	start := time.Now()
	var report ledger.Report
	err := f.auth.WithSessionIsolation(f.ctx, f.a.Credential, pgx.RepeatableRead, func(tx pgx.Tx, p identity.Principal) error {
		var err error
		report, err = ledger.ReadReport(f.ctx, tx, f.a.Workspace.ID, ledger.ReportQuery{From: from, To: to, Status: "all"})
		return err
	})
	elapsed := time.Since(start)
	if err != nil {
		t.Fatal(err)
	}
	var totals struct {
		Expense string `json:"expense_minor"`
	}
	if len(report.Currencies) != 1 {
		t.Fatal("pilot currency count")
	}
	json.Unmarshal(report.Currencies[0], &totals)
	if totals.Expense != "10000" {
		t.Fatal("pilot aggregation lost records")
	}
	if elapsed > 30*time.Second {
		t.Fatalf("pilot report exceeded30s: %s", elapsed)
	}
	t.Logf("PASS actual PostgreSQL report over10000 transactions in %s", elapsed)
}
