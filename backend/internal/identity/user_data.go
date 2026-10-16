package identity

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"
)

type UserDataExport struct {
	Format     string           `json:"format"`
	Created    time.Time        `json:"created_at"`
	Profile    Profile          `json:"profile"`
	Workspaces []map[string]any `json:"workspaces"`
}

func ExportUserData(ctx context.Context, tx pgx.Tx, p Principal) (UserDataExport, error) {
	result := UserDataExport{Format: "accounting-user-data-v1", Created: time.Now().UTC(), Profile: p.Profile, Workspaces: []map[string]any{}}
	rows, err := tx.Query(ctx, `SELECT w.id::text,w.name FROM workspaces w JOIN memberships m ON m.workspace_id=w.id WHERE m.user_id=$1 AND m.revoked_at IS NULL ORDER BY w.id FOR SHARE OF m`, p.Profile.ID)
	if err != nil {
		return result, err
	}
	type workspace struct{ id, name string }
	spaces := []workspace{}
	for rows.Next() {
		var w workspace
		if err := rows.Scan(&w.id, &w.name); err != nil {
			rows.Close()
			return result, err
		}
		spaces = append(spaces, w)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	for _, w := range spaces {
		value := map[string]any{"id": w.id, "name": w.name}
		for _, table := range []string{"accounts", "transactions", "entries", "allocations", "categories", "tags", "transaction_tags", "planning_resources", "csv_imports", "csv_rows", "csv_sources"} {
			expression := "to_jsonb(t)"
			switch table {
			case "accounts":
				expression += "||jsonb_build_object('version',t.version::text,'balance_version',t.balance_version::text)"
			case "categories", "tags", "planning_resources":
				expression += "||jsonb_build_object('version',t.version::text)"
			case "transactions":
				expression += "||jsonb_build_object('version',t.version::text,'rate_numerator',t.rate_numerator::text,'rate_denominator',t.rate_denominator::text)"
			case "entries", "allocations":
				expression += "||jsonb_build_object('amount_minor',t.amount_minor::text)"
			}
			filter := "workspace_id=$1"
			arguments := []any{w.id}
			if table == "csv_imports" {
				filter += " AND actor_user_id=$2"
				arguments = append(arguments, p.Profile.ID)
			}
			if table == "csv_rows" || table == "csv_sources" {
				filter += " AND EXISTS(SELECT 1 FROM csv_imports i WHERE i.workspace_id=t.workspace_id AND i.id=t.import_id AND i.actor_user_id=$2)"
				arguments = append(arguments, p.Profile.ID)
			}
			rows, err := tx.Query(ctx, `SELECT `+expression+` FROM `+table+` t WHERE `+filter+` LIMIT 50001`, arguments...)
			if err != nil {
				return result, err
			}
			items := []json.RawMessage{}
			for rows.Next() {
				var item json.RawMessage
				if err := rows.Scan(&item); err != nil {
					rows.Close()
					return result, err
				}
				items = append(items, item)
				if len(items) > 50000 {
					rows.Close()
					return result, failure(422, "export_too_large")
				}
			}
			err = rows.Err()
			rows.Close()
			if err != nil {
				return result, err
			}
			value[table] = items
		}
		result.Workspaces = append(result.Workspaces, value)
	}
	raw, err := json.Marshal(result)
	if err != nil {
		return result, err
	}
	if len(raw) > 10*1024*1024 {
		return result, failure(422, "export_too_large")
	}
	return result, nil
}

// DeleteUserData removes personal workspaces and scrubs the login identity. Shared
// workspaces with active other members require an ownership decision before deletion.
func (s *Service) DeleteUserData(ctx context.Context, tx pgx.Tx, p Principal, password string) error {
	var hash string
	if err := tx.QueryRow(ctx, `SELECT password_hash FROM users WHERE id=$1`, p.Profile.ID).Scan(&hash); err != nil {
		return err
	}
	valid, err := s.Hasher.Verify(ctx, hash, password)
	if err != nil {
		return failure(503, "service_unavailable")
	}
	if !valid {
		return failure(403, "password_invalid")
	}
	var shared bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM workspaces w JOIN memberships m ON m.workspace_id=w.id WHERE w.owner_user_id=$1 AND m.user_id<>$1 AND m.revoked_at IS NULL)`, p.Profile.ID).Scan(&shared); err != nil {
		return err
	}
	if shared {
		return failure(409, "shared_workspace")
	}
	rows, err := tx.Query(ctx, `SELECT id::text FROM workspaces WHERE owner_user_id=$1 ORDER BY id FOR UPDATE`, p.Profile.ID)
	if err != nil {
		return err
	}
	workspaces := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		workspaces = append(workspaces, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, id := range workspaces {
		if _, err := tx.Exec(ctx, `SELECT workspace_id FROM sync_heads WHERE workspace_id=$1 FOR UPDATE`, id); err != nil {
			return err
		}
		for _, table := range []string{"csv_sources", "csv_rows", "csv_imports", "planning_audit", "planning_actions", "planning_resources", "sync_snapshot_items", "sync_snapshots", "audit_events", "sync_changes", "sync_groups", "actions", "transaction_tags", "allocations", "entries", "transactions", "accounts", "categories", "tags", "sync_heads", "memberships"} {
			if _, err := tx.Exec(ctx, `DELETE FROM `+table+` WHERE workspace_id=$1`, id); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `DELETE FROM workspaces WHERE id=$1`, id); err != nil {
			return err
		}
	}
	// Financial records of other workspaces remain owned by those workspaces.
	// Discard only uncommitted source reservations and private drafts of this user.
	if _, err := tx.Exec(ctx, `DELETE FROM csv_sources s USING csv_imports i WHERE s.workspace_id=i.workspace_id AND s.import_id=i.id AND i.actor_user_id=$1 AND s.transaction_id IS NULL`, p.Profile.ID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE csv_rows r SET payload=NULL,state=CASE WHEN r.state IN ('prepared','invalid','queued') THEN 'skipped' ELSE r.state END FROM csv_imports i WHERE r.workspace_id=i.workspace_id AND r.import_id=i.id AND i.actor_user_id=$1`, p.Profile.ID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE csv_imports SET state='committed',preview='{"erased":true}',error_code=NULL WHERE actor_user_id=$1`, p.Profile.ID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM planning_actions WHERE actor_user_id=$1`, p.Profile.ID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE memberships SET revoked_at=coalesce(revoked_at,clock_timestamp()) WHERE user_id=$1`, p.Profile.ID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `DELETE FROM sessions s WHERE user_id=$1 AND NOT EXISTS(SELECT 1 FROM csv_imports i WHERE i.session_id=s.id)`, p.Profile.ID); err != nil {
		return err
	}
	remaining, err := tx.Query(ctx, `SELECT id::text FROM sessions WHERE user_id=$1`, p.Profile.ID)
	if err != nil {
		return err
	}
	ids := []string{}
	for remaining.Next() {
		var id string
		if err := remaining.Scan(&id); err != nil {
			remaining.Close()
			return err
		}
		ids = append(ids, id)
	}
	err = remaining.Err()
	remaining.Close()
	if err != nil {
		return err
	}
	for _, id := range ids {
		if _, err := tx.Exec(ctx, `UPDATE sessions SET revoked_at=coalesce(revoked_at,clock_timestamp()),device_name='Deleted',token_hash=$2 WHERE id=$1`, id, digest(uuid())); err != nil {
			return err
		}
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE email=$1`, p.Profile.Email); err != nil {
		return dbError(err)
	}
	_, err = tx.Exec(ctx, `UPDATE users SET disabled_at=clock_timestamp(),email_normalized=$2,password_hash='deleted',timezone='UTC' WHERE id=$1`, p.Profile.ID, "deleted-"+p.Profile.ID+"@deleted.invalid")
	return err
}
