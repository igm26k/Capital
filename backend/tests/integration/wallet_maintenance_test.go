package integration

import (
	"accounting/backend/internal/maintenance"
	commands "accounting/backend/internal/sync"
	"testing"
)

func TestWalletBalanceMaintenance(t *testing.T) {
	f := newAccessFixture(t)
	account := f.account(f.a)
	expected := "10000"
	plan := maintenance.WalletPlan{Workspace: f.a.Workspace.ID, Actor: f.a.Profile.ID, Generation: f.a.Workspace.Generation, ActionID: f.id(), AsOf: "2026-10-01T00:00:00Z", OpenedAt: "2026-01-01T00:00:00Z", ExpectedTransactions: 1, Accounts: []maintenance.WalletAccount{
		{ID: account, Name: "Synthetic access account", RenameTo: "Renamed", Currency: "EUR", Type: "bank", Expected: &expected, Target: "12500", AdjustmentID: f.id()},
		{ID: f.id(), Name: "Missing", Currency: "USD", Type: "bank", Target: "294"},
	}}
	execute := func(p maintenance.WalletPlan, apply bool) (commands.Result, error) {
		r, err := maintenance.Request(p)
		if err != nil {
			return commands.Result{}, err
		}
		return commands.ExecuteMaintenance(f.ctx, f.db, p.Actor, r, maintenance.ReconcileWallet(p), apply)
	}
	result, err := execute(plan, false)
	if err != nil || result.Status != 200 {
		t.Fatal("dry run", result, err)
	}
	var count int
	if err = f.db.QueryRow(f.ctx, `SELECT count(*) FROM accounts WHERE workspace_id=$1`, plan.Workspace).Scan(&count); err != nil || count != 1 {
		t.Fatal("dry run wrote accounts", count, err)
	}
	if err = f.db.QueryRow(f.ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id=$2`, plan.Workspace, plan.ActionID).Scan(&count); err != nil || count != 0 {
		t.Fatal("dry run wrote receipt", count, err)
	}
	// A failure after the first account changed must roll everything back.
	bad := plan
	bad.ActionID = f.id()
	bad.Accounts = append([]maintenance.WalletAccount(nil), plan.Accounts...)
	bad.Accounts[1].Currency = "BAD"
	if rejected, failure := execute(bad, true); failure == nil && rejected.Status == 200 {
		t.Fatal("invalid currency accepted")
	}
	var name string
	if err = f.db.QueryRow(f.ctx, `SELECT name FROM accounts WHERE id=$1`, account).Scan(&name); err != nil || name != "Synthetic access account" {
		t.Fatal("partial rename survived", name, err)
	}
	foreign := plan
	foreign.Actor = f.b.Profile.ID
	if _, err = execute(foreign, true); err == nil {
		t.Fatal("foreign operator actor accepted")
	}
	stale := plan
	stale.Accounts = append([]maintenance.WalletAccount(nil), plan.Accounts...)
	wrong := "9999"
	stale.Accounts[0].Expected = &wrong
	if _, err = execute(stale, true); err == nil {
		t.Fatal("stale balance accepted")
	}
	result, err = execute(plan, true)
	if err != nil || result.Status != 200 || result.Replayed {
		t.Fatal("apply", result, err)
	}
	result, err = execute(plan, true)
	if err != nil || !result.Replayed {
		t.Fatal("replay", result, err)
	}
	var total string
	if err = f.db.QueryRow(f.ctx, `SELECT sum(e.amount_minor)::text FROM entries e JOIN transactions t ON (t.workspace_id,t.id)=(e.workspace_id,e.transaction_id) WHERE e.account_id=$1 AND t.deleted_at IS NULL`, account).Scan(&total); err != nil || total != "12500" {
		t.Fatal("wrong balance", total, err)
	}
	if err = f.db.QueryRow(f.ctx, `SELECT count(*) FROM transactions WHERE workspace_id=$1`, plan.Workspace).Scan(&count); err != nil || count != 3 {
		t.Fatal("duplicate movements", count, err)
	}
	if err = f.db.QueryRow(f.ctx, `SELECT count(*) FROM sync_groups WHERE workspace_id=$1 AND action_id=$2`, plan.Workspace, plan.ActionID).Scan(&count); err != nil || count != 1 {
		t.Fatal("missing sync group", count, err)
	}
	if err = f.db.QueryRow(f.ctx, `SELECT count(*) FROM audit_events WHERE workspace_id=$1 AND action_id=$2`, plan.Workspace, plan.ActionID).Scan(&count); err != nil || count != 4 {
		t.Fatal("missing audit", count, err)
	}
}
