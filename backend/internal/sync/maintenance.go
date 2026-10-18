package sync

import (
	"accounting/backend/internal/identity"
	"context"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

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
