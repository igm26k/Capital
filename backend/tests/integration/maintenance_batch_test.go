package integration

import (
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"encoding/json"
	"testing"
)

func TestMaintenanceBatchAtomic(t *testing.T) {
	f := newAccessFixture(t)
	account := f.account(f.a)
	makeStep := func(amount, date string) commands.MaintenanceStep {
		v := ledger.HistoricalAdjustmentInput{ID: f.id(), Kind: "adjustment", AccountID: account, Amount: amount, OccurredAt: date, Timezone: "UTC", Reason: "Synthetic imported movement", Note: "", Payee: ""}
		body, _ := json.Marshal(v)
		action := f.id()
		return commands.MaintenanceStep{Request: commands.Request{WorkspaceID: f.a.Workspace.ID, GenerationID: f.a.Workspace.Generation, ActionID: action, RequestID: action, Method: "POST", Path: accessRoot(f.a) + "/maintenance/import-ledger/adjustment", Body: body}, Command: ledger.CreateHistoricalAdjustment(f.a.Workspace.ID, v)}
	}
	steps := []commands.MaintenanceStep{makeStep("100", "2026-10-01T00:00:00Z"), makeStep("-20", "2026-10-02T00:00:00Z")}
	check := func(balance string, receipts int) {
		t.Helper()
		var actual string
		if err := f.db.QueryRow(f.ctx, `SELECT sum(amount_minor)::text FROM entries WHERE account_id=$1`, account).Scan(&actual); err != nil || actual != balance {
			t.Fatal("balance", actual, err)
		}
		var count int
		if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM actions WHERE workspace_id=$1 AND action_id IN ($2,$3)`, f.a.Workspace.ID, steps[0].Request.ActionID, steps[1].Request.ActionID).Scan(&count); err != nil || count != receipts {
			t.Fatal("receipts", count, err)
		}
	}
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, steps, false); err != nil {
		t.Fatal(err)
	}
	check("10000", 0)
	bad := []commands.MaintenanceStep{steps[0], makeStep("1", "9998-01-01T00:00:00Z")}
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, bad, true); err == nil {
		t.Fatal("future step accepted")
	}
	check("10000", 0)
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.b.Profile.ID, steps, true); err == nil {
		t.Fatal("foreign actor accepted")
	}
	check("10000", 0)
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, steps, true); err != nil {
		t.Fatal(err)
	}
	check("10080", 2)
	results, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, steps, true)
	if err != nil || len(results) != 2 || !results[0].Replayed || !results[1].Replayed {
		t.Fatal("replay", results, err)
	}
	check("10080", 2)
	// A later rejection must also undo a preceding soft deletion and its journal.
	var created ledger.HistoricalAdjustmentInput
	if err := json.Unmarshal(steps[0].Request.Body, &created); err != nil {
		t.Fatal(err)
	}
	remove := ledger.TransactionDeleteInput{ExpectedVersion: "1", Related: []ledger.RelatedVersion{}}
	body, _ := json.Marshal(remove)
	action := f.id()
	deleteStep := commands.MaintenanceStep{Request: commands.Request{WorkspaceID: f.a.Workspace.ID, GenerationID: f.a.Workspace.Generation, ActionID: action, RequestID: action, Method: "POST", Path: accessRoot(f.a) + "/maintenance/import-ledger/delete", Body: body}, Command: ledger.DeleteTransaction(f.a.Workspace.ID, created.ID, remove)}
	assertDeleted := func(want bool) {
		t.Helper()
		var deleted bool
		if err := f.db.QueryRow(f.ctx, `SELECT deleted_at IS NOT NULL FROM transactions WHERE id=$1`, created.ID).Scan(&deleted); err != nil || deleted != want {
			t.Fatal("soft deletion", deleted, err)
		}
	}
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, []commands.MaintenanceStep{deleteStep, makeStep("1", "9998-01-01T00:00:00Z")}, true); err == nil {
		t.Fatal("invalid replacement committed a deletion")
	}
	assertDeleted(false)
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, []commands.MaintenanceStep{deleteStep}, false); err != nil {
		t.Fatal(err)
	}
	assertDeleted(false)
	if _, err := commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, []commands.MaintenanceStep{deleteStep}, true); err != nil {
		t.Fatal(err)
	}
	assertDeleted(true)
	results, err = commands.ExecuteMaintenanceBatch(f.ctx, f.db, f.a.Profile.ID, []commands.MaintenanceStep{deleteStep}, true)
	if err != nil || len(results) != 1 || !results[0].Replayed {
		t.Fatal("delete replay", results, err)
	}
	assertDeleted(true)
}
