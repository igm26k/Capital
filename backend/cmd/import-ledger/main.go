// import-ledger executes a reviewed, private bank import plan through the ledger
// domain commands. By default all writes (including audit/sync) are rolled back.
package main

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"io"
	"os"
	"time"
)

type operation struct {
	Action  string          `json:"action_id"`
	Type    string          `json:"type"`
	Target  string          `json:"target_id,omitempty"`
	Payload json.RawMessage `json:"payload"`
}
type plan struct {
	Workspace    string      `json:"workspace"`
	Actor        string      `json:"actor"`
	Generation   string      `json:"generation"`
	ExpectedHead int64       `json:"expected_head"`
	Operations   []operation `json:"operations"`
}

func prepare(p plan) ([]commands.MaintenanceStep, error) {
	if p.ExpectedHead < 0 || len(p.Operations) == 0 || len(p.Operations) > 1000 {
		return nil, fmt.Errorf("invalid plan size/head")
	}
	steps := make([]commands.MaintenanceStep, 0, len(p.Operations))
	seen := map[string]bool{}
	for i, op := range p.Operations {
		if (op.Type == "delete") != (op.Target != "") {
			return nil, fmt.Errorf("step %d: target_id is required only for delete", i+1)
		}
		if seen[op.Action] {
			return nil, fmt.Errorf("duplicate action")
		}
		seen[op.Action] = true
		var command commands.Command
		var err error
		switch op.Type {
		case "delete":
			var v ledger.TransactionDeleteInput
			v, err = ledger.DecodeTransactionDelete(op.Payload)
			command = ledger.DeleteTransaction(p.Workspace, op.Target, v)
		case "basic":
			var v ledger.BasicInput
			v, err = ledger.DecodeBasic(op.Payload, false)
			command = ledger.CreateBasic(p.Workspace, v)
		case "transfer":
			var v ledger.TransferInput
			v, err = ledger.DecodeTransferCreate(op.Payload)
			command = ledger.CreateTransfer(p.Workspace, v)
		case "adjustment":
			var v ledger.HistoricalAdjustmentInput
			v, err = ledger.DecodeHistoricalAdjustment(op.Payload)
			command = ledger.CreateHistoricalAdjustment(p.Workspace, v)
		case "refund":
			var v ledger.RefundInput
			v, err = ledger.DecodeRefundCreate(op.Payload)
			command = ledger.CreateRefund(p.Workspace, v)
		case "account":
			var v ledger.AccountCreate
			v, err = ledger.DecodeAccountCreate(op.Payload)
			command = ledger.CreateAccount(p.Workspace, v)
		default:
			return nil, fmt.Errorf("step %d: unknown operation", i+1)
		}
		if err != nil {
			return nil, fmt.Errorf("step %d (%s): %w", i+1, op.Type, err)
		}
		expected := p.ExpectedHead + int64(i)
		domain := command
		guarded := func(ctx context.Context, tx pgx.Tx, principal identity.Principal) (commands.Mutation, error) {
			var head int64
			if err := tx.QueryRow(ctx, "SELECT last_sequence FROM sync_heads WHERE workspace_id=$1", p.Workspace).Scan(&head); err != nil {
				return commands.Mutation{}, err
			}
			if head != expected {
				return commands.Mutation{}, fmt.Errorf("workspace changed since review; expected head %d, got %d", expected, head)
			}
			return domain(ctx, tx, principal)
		}
		body, _ := json.Marshal(op)
		steps = append(steps, commands.MaintenanceStep{Request: commands.Request{WorkspaceID: p.Workspace, ActionID: op.Action, GenerationID: p.Generation, RequestID: op.Action, Method: "POST", Path: "/api/v1/workspaces/" + p.Workspace + "/maintenance/import-ledger/" + op.Type, Body: body}, Command: guarded})
	}
	return steps, nil
}

func run() error {
	apply := flag.Bool("apply", false, "commit the entire plan atomically")
	flag.Parse()
	var p plan
	d := json.NewDecoder(io.LimitReader(os.Stdin, 14*1024*1024))
	d.DisallowUnknownFields()
	if err := d.Decode(&p); err != nil {
		return err
	}
	if err := d.Decode(new(any)); err != io.EOF {
		return fmt.Errorf("expected exactly one plan")
	}
	steps, err := prepare(p)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		return err
	}
	defer db.Close()
	results, err := commands.ExecuteMaintenanceBatch(ctx, db, p.Actor, steps, *apply)
	if err != nil {
		return err
	}
	replayed := 0
	for _, r := range results {
		if r.Replayed {
			replayed++
		}
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"applied": *apply, "steps": len(results), "replayed": replayed})
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
