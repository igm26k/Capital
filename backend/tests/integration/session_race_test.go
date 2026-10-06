package integration

import (
	"accounting/backend/internal/identity"
	"context"
	"fmt"
	"github.com/jackc/pgx/v5"
	"os"
	"strings"
	"testing"
	"time"
)

// pg_blocking_pids proves lock acquisition order on separate PostgreSQL
// connections; races do not depend on the scheduler or arbitrary long sleeps.
func (f *accessFixture) blockedBy(blocker int) int {
	f.t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		var pid int
		e := f.db.QueryRow(f.ctx, `SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND $1::integer=ANY(pg_blocking_pids(pid)) ORDER BY pid LIMIT 1`, blocker).Scan(&pid)
		if e == nil {
			return pid
		}
		if e != pgx.ErrNoRows {
			f.t.Fatal(e)
		}
		time.Sleep(10 * time.Millisecond)
	}
	f.t.Fatal("expected PostgreSQL lock wait absent")
	return 0
}
func (f *accessFixture) hold(query string, args ...any) (pgx.Tx, int) {
	f.t.Helper()
	tx, e := f.db.Begin(f.ctx)
	if e != nil {
		f.t.Fatal(e)
	}
	f.t.Cleanup(func() {
		ctx, stop := context.WithTimeout(context.Background(), time.Second)
		defer stop()
		_ = tx.Rollback(ctx)
	})
	var pid int
	if e = tx.QueryRow(f.ctx, `SELECT pg_backend_pid()`).Scan(&pid); e != nil {
		f.t.Fatal(e)
	}
	if _, e = tx.Exec(f.ctx, query, args...); e != nil {
		f.t.Fatal(e)
	}
	return tx, pid
}
func (f *accessFixture) async(q accessRequest) <-chan accessResponse {
	done := make(chan accessResponse, 1)
	go func() { done <- f.send(q) }()
	return done
}
func (f *accessFixture) await(done <-chan accessResponse) accessResponse {
	f.t.Helper()
	select {
	case result := <-done:
		return result
	case <-time.After(5 * time.Second):
		f.t.Fatal("protected HTTP request did not finish")
		return accessResponse{}
	}
}
func (f *accessFixture) noReceipt(action, transaction string) {
	f.t.Helper()
	var count int
	e := f.db.QueryRow(f.ctx, `SELECT (SELECT count(*) FROM actions WHERE action_id=$1)+(SELECT count(*) FROM sync_groups WHERE action_id=$1)+(SELECT count(*) FROM transactions WHERE id=$2)`, action, transaction).Scan(&count)
	if e != nil || count != 0 {
		f.t.Fatal("denied command left receipt, group, or transaction", e)
	}
}
func TestSessionRace(t *testing.T) {
	f := newAccessFixture(t)
	controller := f.a
	account := f.account(controller)
	root := accessRoot(controller)
	victim := func() identity.Auth {
		a, e := f.auth.Login(f.ctx, controller.Profile.Email, "synthetic transfer password", "Race device")
		if e != nil {
			t.Fatal(e)
		}
		return a
	}
	command := func(actor identity.Auth) (accessRequest, string, string) {
		body := f.basic(account, "expense")
		action := f.id()
		return accessRequest{method: "POST", path: root + "/transactions", body: body, actor: actor, key: action}, action, body["id"].(string)
	}
	for _, mode := range []string{"delete", "logout"} {
		t.Run(mode+"_command_first", func(t *testing.T) {
			previousT := f.t
			f.t = t
			defer func() { f.t = previousT }()
			target := victim()
			q, action, transaction := command(target)
			accountHold, accountPID := f.hold(`SELECT id FROM accounts WHERE id=$1 FOR UPDATE`, account)
			executing := f.async(q)
			commandPID := f.blockedBy(accountPID)
			revoke := accessRequest{method: "DELETE", path: "/api/v1/sessions/" + target.Session.ID, actor: controller}
			if mode == "logout" {
				revoke = accessRequest{method: "POST", path: "/api/v1/auth/logout", actor: target}
			}
			revoking := f.async(revoke)
			f.blockedBy(commandPID)
			if e := accountHold.Rollback(f.ctx); e != nil {
				t.Fatal(e)
			}
			f.expect(f.await(executing), 201, "")
			f.expect(f.await(revoking), 200, "")
			var applied int
			if e := f.db.QueryRow(f.ctx, `SELECT count(*) FROM actions WHERE action_id=$1 AND state='applied'`, action).Scan(&applied); e != nil || applied != 1 {
				t.Fatal("command was not committed before revoke", e)
			}
			f.call(q, 401, "unauthenticated") // Even applied replay cannot bypass revoke.
			f.call(accessRequest{method: "GET", path: root + "/transactions/" + transaction, actor: target}, 401, "unauthenticated")
			f.call(accessRequest{method: "GET", path: root + "/actions/" + action, actor: target}, 401, "unauthenticated")
			denied, key, tid := command(target)
			f.call(denied, 401, "unauthenticated")
			f.noReceipt(key, tid)
		})
		t.Run(mode+"_revoke_first", func(t *testing.T) {
			previousT := f.t
			f.t = t
			defer func() { f.t = previousT }()
			target := victim()
			q, action, transaction := command(target)
			sessionHold, sessionPID := f.hold(`SELECT id FROM sessions WHERE id=$1 FOR UPDATE`, target.Session.ID)
			revoke := accessRequest{method: "DELETE", path: "/api/v1/sessions/" + target.Session.ID, actor: controller}
			if mode == "logout" {
				revoke = accessRequest{method: "POST", path: "/api/v1/auth/logout", actor: target}
			}
			revoking := f.async(revoke)
			revokePID := f.blockedBy(sessionPID)
			executing := f.async(q)
			f.blockedBy(revokePID)
			if e := sessionHold.Rollback(f.ctx); e != nil {
				t.Fatal(e)
			}
			f.expect(f.await(revoking), 200, "")
			f.expect(f.await(executing), 401, "unauthenticated")
			f.noReceipt(action, transaction)
			f.call(accessRequest{method: "GET", path: root + "/accounts/" + account, actor: target}, 401, "unauthenticated")
		})
	}
	// There is no public membership-revoke endpoint. These administrative SQL
	// fixtures acquire the membership's exclusive lock as specified by protocol.
	t.Run("membership_command_first", func(t *testing.T) {
		previousT := f.t
		f.t = t
		defer func() { f.t = previousT }()
		member := f.b
		if _, e := f.db.Exec(f.ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'member')`, controller.Workspace.ID, member.Profile.ID); e != nil {
			t.Fatal(e)
		}
		member.Workspace = controller.Workspace
		q, action, transaction := command(member)
		accountHold, accountPID := f.hold(`SELECT id FROM accounts WHERE id=$1 FOR UPDATE`, account)
		executing := f.async(q)
		commandPID := f.blockedBy(accountPID)
		revoked := make(chan error, 1)
		revoker, e := f.db.Begin(f.ctx)
		if e != nil {
			t.Fatal(e)
		}
		defer revoker.Rollback(f.ctx)
		go func() {
			_, err := revoker.Exec(f.ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE workspace_id=$1 AND user_id=$2`, controller.Workspace.ID, member.Profile.ID)
			if err == nil {
				err = revoker.Commit(f.ctx)
			}
			revoked <- err
		}()
		f.blockedBy(commandPID)
		if e := accountHold.Rollback(f.ctx); e != nil {
			t.Fatal(e)
		}
		f.expect(f.await(executing), 201, "")
		if e := <-revoked; e != nil {
			t.Fatal(e)
		}
		f.call(q, 404, "not_found")
		f.call(accessRequest{method: "GET", path: root + "/actions/" + action, actor: member}, 404, "not_found")
		f.call(accessRequest{method: "GET", path: root + "/transactions/" + transaction, actor: member}, 404, "not_found")
		denied, key, tid := command(member)
		f.call(denied, 404, "not_found")
		f.noReceipt(key, tid)
	})
	t.Run("membership_revoke_first", func(t *testing.T) {
		previousT := f.t
		f.t = t
		defer func() { f.t = previousT }()
		member := f.b
		member.Workspace = controller.Workspace
		if _, e := f.db.Exec(f.ctx, `UPDATE memberships SET revoked_at=NULL WHERE workspace_id=$1 AND user_id=$2`, controller.Workspace.ID, member.Profile.ID); e != nil {
			t.Fatal(e)
		}
		membershipHold, membershipPID := f.hold(`SELECT user_id FROM memberships WHERE workspace_id=$1 AND user_id=$2 FOR UPDATE`, controller.Workspace.ID, member.Profile.ID)
		q, action, transaction := command(member)
		executing := f.async(q)
		f.blockedBy(membershipPID)
		if _, e := membershipHold.Exec(f.ctx, `UPDATE memberships SET revoked_at=clock_timestamp() WHERE workspace_id=$1 AND user_id=$2`, controller.Workspace.ID, member.Profile.ID); e != nil {
			t.Fatal(e)
		}
		if e := membershipHold.Commit(f.ctx); e != nil {
			t.Fatal(e)
		}
		f.expect(f.await(executing), 404, "not_found")
		f.noReceipt(action, transaction)
	})
	// Renewal that began after a committed revoke must not resurrect the session.
	target := victim()
	f.call(accessRequest{method: "DELETE", path: "/api/v1/sessions/" + target.Session.ID, actor: controller}, 200, "")
	f.call(accessRequest{method: "POST", path: "/api/v1/auth/renew", actor: target}, 401, "unauthenticated")
	// All activity locks are released; another authorized command still proceeds.
	q, _, _ := command(controller)
	f.call(q, 201, "")
	for _, secret := range []string{controller.Credential, target.Credential, controller.Profile.Email, "Access note <script>"} {
		if strings.Contains(f.logs.text(), secret) {
			t.Fatal("race log disclosed secret")
		}
	}
	t.Log(fmt.Sprintf("PASS protected command/revoke orders with PostgreSQL blocking evidence; %d HTTP responses", len(f.observed)))
	// Separate artifact prevents concurrent overwrite of TestAccess responses.
	if file := os.Getenv("SESSION_RACE_RESPONSE_ARTIFACT"); file != "" {
		f.saveTo(file)
	}
}
