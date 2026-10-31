package sync

import (
	"accounting/backend/internal/identity"
	"context"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// MaintenanceStep is one normal journaled command in an atomic operator plan.
type MaintenanceStep struct {
	Request Request
	Command Command
}

// ExecuteMaintenanceBatch rehearses or commits the entire plan in one database
// transaction. Dry runs retain earlier steps for validation, then roll back.
// Each step keeps its own normal receipt, audit event and bounded sync group.
func ExecuteMaintenanceBatch(ctx context.Context, db *pgxpool.Pool, actor string, steps []MaintenanceStep, apply bool) ([]Result, error) {
	if len(steps) == 0 || len(steps) > 1000 {
		return nil, fmt.Errorf("maintenance requires 1..1000 steps")
	}
	workspace := steps[0].Request.WorkspaceID
	for _, step := range steps {
		if step.Request.WorkspaceID != workspace {
			return nil, fmt.Errorf("maintenance must use one workspace")
		}
	}
	tx, err := db.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, "SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'"); err != nil {
		return nil, err
	}
	var owner string
	err = tx.QueryRow(ctx, `SELECT u.id::text FROM users u JOIN memberships m ON m.user_id=u.id JOIN workspaces w ON w.id=m.workspace_id WHERE u.id=$1 AND w.id=$2 AND w.owner_user_id=u.id AND m.role='owner' AND m.revoked_at IS NULL AND u.disabled_at IS NULL FOR SHARE OF u,m,w`, actor, workspace).Scan(&owner)
	if err != nil {
		return nil, err
	}
	p := identity.Principal{}
	p.Profile.ID = owner
	r := &Runner{}
	results := make([]Result, 0, len(steps))
	for i, step := range steps {
		result, err := r.execute(ctx, step.Request, step.Command, func(callback func(pgx.Tx, identity.Principal) error) error { return callback(tx, p) })
		if err != nil {
			return nil, fmt.Errorf("step %d: %w", i+1, err)
		}
		if result.Status < 200 || result.Status >= 300 {
			return nil, fmt.Errorf("step %d rejected: status %d", i+1, result.Status)
		}
		results = append(results, result)
	}
	if apply {
		if err = tx.Commit(ctx); err != nil {
			return nil, err
		}
	}
	return results, nil
}

// ExecuteMaintenance is for an explicitly authorized database operator, never an
// HTTP handler. It retains the normal receipt, audit and sync journal, without
// borrowing or creating a user's session. Dry runs roll back the whole command.
func ExecuteMaintenance(ctx context.Context, db *pgxpool.Pool, actor string, request Request, command Command, apply bool) (Result, error) {
	r := &Runner{}
	return r.execute(ctx, request, command, func(callback func(pgx.Tx, identity.Principal) error) error {
		tx, err := db.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable})
		if err != nil {
			return err
		}
		defer tx.Rollback(ctx)
		if _, err = tx.Exec(ctx, "SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'"); err != nil {
			return err
		}
		var owner string
		err = tx.QueryRow(ctx, `SELECT u.id::text FROM users u JOIN memberships m ON m.user_id=u.id JOIN workspaces w ON w.id=m.workspace_id WHERE u.id=$1 AND w.id=$2 AND w.owner_user_id=u.id AND m.role='owner' AND m.revoked_at IS NULL AND u.disabled_at IS NULL FOR SHARE OF u,m,w`, actor, request.WorkspaceID).Scan(&owner)
		if err != nil {
			return err
		}
		p := identity.Principal{}
		p.Profile.ID = owner
		if err = callback(tx, p); err != nil {
			return err
		}
		if !apply {
			return nil
		}
		return tx.Commit(ctx)
	})
}
