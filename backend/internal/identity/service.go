package identity

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"time"
	_ "time/tzdata"
)

type Error struct {
	Status int
	Code   string
}

func (e *Error) Error() string              { return e.Code }
func failure(status int, code string) error { return &Error{status, code} }
func dbError(err error) error {
	var e *pgconn.PgError
	if errors.As(err, &e) && e.Code == "23505" {
		return failure(422, "validation_error")
	}
	return failure(503, "service_unavailable")
}
func uuid() string {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		panic("identifier unavailable")
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	s := hex.EncodeToString(b)
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}
func token() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic("credential unavailable")
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func digest(s string) []byte { h := sha256.Sum256([]byte(s)); return h[:] }

// CSRF is recoverable from the presented credential; only its hash is stored.
func CSRF(credential string) string {
	h := hmac.New(sha256.New, []byte(credential))
	h.Write([]byte("accounting/csrf/v1"))
	return base64.RawURLEncoding.EncodeToString(h.Sum(nil))
}

type Profile struct {
	ID       string `json:"id"`
	Email    string `json:"email"`
	Timezone string `json:"timezone"`
}
type Workspace struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Version    string `json:"version"`
	Role       string `json:"role"`
	Generation string `json:"sync_generation_id"`
}
type Session struct {
	ID       string    `json:"id"`
	Device   string    `json:"device_name"`
	Current  bool      `json:"is_current"`
	Created  time.Time `json:"created_at"`
	LastSeen time.Time `json:"last_seen_at"`
	Expires  time.Time `json:"expires_at"`
	Absolute time.Time `json:"absolute_expires_at"`
}
type Principal struct {
	Profile  Profile
	Session  Session
	CSRFHash []byte
}
type Auth struct {
	Profile    Profile
	Workspace  Workspace
	Session    Session
	Credential string
}

func (a Auth) Response(transport string) any {
	body := map[string]any{"profile": a.Profile, "workspace": a.Workspace, "session": a.Session, "transport": transport}
	if transport == "cookie" {
		body["csrf_token"] = CSRF(a.Credential)
	} else {
		body["access_token"] = a.Credential
		body["token_type"] = "Bearer"
	}
	return body
}

type Service struct {
	DB     *pgxpool.Pool
	Hasher *Hasher
}

func New(db *pgxpool.Pool, concurrency int) (*Service, error) {
	h, e := NewHasher(concurrency)
	if e != nil {
		return nil, e
	}
	return &Service{db, h}, nil
}
func rollback(tx pgx.Tx) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = tx.Rollback(ctx)
}
func (s *Service) createSession(ctx context.Context, tx pgx.Tx, p Profile, device string) (Auth, error) {
	a := Auth{Profile: p, Credential: token()}
	a.Session.ID = uuid()
	a.Session.Current = true
	err := tx.QueryRow(ctx, `INSERT INTO sessions(id,user_id,device_name,token_hash,csrf_token_hash,expires_at) VALUES ($1,$2,$3,$4,$5,now()+interval '168 hours') RETURNING device_name,created_at,last_seen_at,expires_at`, a.Session.ID, p.ID, device, digest(a.Credential), digest(CSRF(a.Credential))).Scan(&a.Session.Device, &a.Session.Created, &a.Session.LastSeen, &a.Session.Expires)
	if err != nil {
		return Auth{}, dbError(err)
	}
	a.Session.Created = a.Session.Created.UTC()
	a.Session.LastSeen = a.Session.LastSeen.UTC()
	a.Session.Expires = a.Session.Expires.UTC()
	a.Session.Absolute = a.Session.Created.Add(30 * 24 * time.Hour)
	a.Workspace, err = personalWorkspace(ctx, tx, p.ID)
	return a, err
}
func personalWorkspace(ctx context.Context, tx pgx.Tx, user string) (Workspace, error) {
	var w Workspace
	e := tx.QueryRow(ctx, `SELECT w.id::text,w.name,w.version::text,m.role,w.sync_generation_id::text FROM workspaces w JOIN memberships m ON m.workspace_id=w.id WHERE m.user_id=$1 AND m.revoked_at IS NULL ORDER BY (w.owner_user_id=$1) DESC,w.id LIMIT 1 FOR SHARE OF m`, user).Scan(&w.ID, &w.Name, &w.Version, &w.Role, &w.Generation)
	if errors.Is(e, pgx.ErrNoRows) {
		return w, failure(403, "forbidden")
	}
	if e != nil {
		return w, dbError(e)
	}
	return w, nil
}
func (s *Service) Register(ctx context.Context, email, password, device, zone string) (Auth, error) {
	hash, err := s.Hasher.Hash(ctx, password)
	if err != nil {
		return Auth{}, failure(503, "service_unavailable")
	}
	tx, err := s.begin(ctx)
	if err != nil {
		return Auth{}, dbError(err)
	}
	defer rollback(tx)
	p := Profile{uuid(), email, zone}
	workspace, generation := uuid(), uuid()
	_, err = tx.Exec(ctx, `INSERT INTO users(id,email_normalized,password_hash,timezone) VALUES ($1,$2,$3,$4)`, p.ID, email, hash, zone)
	if err != nil {
		return Auth{}, dbError(err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO workspaces(id,name,owner_user_id,sync_generation_id) VALUES ($1,'Личное пространство',$2,$3)`, workspace, p.ID, generation)
	if err != nil {
		return Auth{}, dbError(err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES ($1,$2,'owner')`, workspace, p.ID)
	if err != nil {
		return Auth{}, dbError(err)
	}
	_, err = tx.Exec(ctx, `INSERT INTO sync_heads(workspace_id,generation_id) VALUES ($1,$2)`, workspace, generation)
	if err != nil {
		return Auth{}, dbError(err)
	}
	a, err := s.createSession(ctx, tx, p, device)
	if err != nil {
		return Auth{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Auth{}, dbError(err)
	}
	return a, nil
}
func (s *Service) Login(ctx context.Context, email, password, device string) (Auth, error) {
	var hash string
	err := s.DB.QueryRow(ctx, `SELECT password_hash FROM users WHERE email_normalized=$1`, email).Scan(&hash)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Auth{}, dbError(err)
	}
	matches, e := s.Hasher.Verify(ctx, hash, password)
	if e != nil {
		return Auth{}, failure(503, "service_unavailable")
	}
	if !matches {
		return Auth{}, failure(401, "unauthenticated")
	}
	tx, e := s.begin(ctx)
	if e != nil {
		return Auth{}, dbError(e)
	}
	defer rollback(tx)
	var p Profile
	var currentHash string
	var disabled *time.Time
	e = tx.QueryRow(ctx, `SELECT id::text,email_normalized,timezone,password_hash,disabled_at FROM users WHERE email_normalized=$1 FOR UPDATE`, email).Scan(&p.ID, &p.Email, &p.Timezone, &currentHash, &disabled)
	if errors.Is(e, pgx.ErrNoRows) {
		return Auth{}, failure(401, "unauthenticated")
	}
	if e != nil {
		return Auth{}, dbError(e)
	}
	if disabled != nil || currentHash != hash {
		return Auth{}, failure(401, "unauthenticated")
	}
	a, e := s.createSession(ctx, tx, p, device)
	if e != nil {
		return Auth{}, e
	}
	if e = tx.Commit(ctx); e != nil {
		return Auth{}, dbError(e)
	}
	return a, nil
}

// WithSession keeps authorization locks through the caller's commit, including future domain commands.
func (s *Service) WithSession(ctx context.Context, credential string, exclusive bool, callback func(pgx.Tx, Principal) error) error {
	return s.withSession(ctx, credential, exclusive, pgx.ReadCommitted, callback)
}

// WithSessionIsolation holds the same access locks in a single consistent snapshot.
func (s *Service) WithSessionIsolation(ctx context.Context, credential string, isolation pgx.TxIsoLevel, callback func(pgx.Tx, Principal) error) error {
	return s.withSession(ctx, credential, false, isolation, callback)
}
func (s *Service) withSession(ctx context.Context, credential string, exclusive bool, isolation pgx.TxIsoLevel, callback func(pgx.Tx, Principal) error) error {
	hash := digest(credential)
	var candidate string
	err := s.DB.QueryRow(ctx, `SELECT user_id::text FROM sessions WHERE token_hash=$1`, hash).Scan(&candidate)
	if errors.Is(err, pgx.ErrNoRows) {
		return failure(401, "unauthenticated")
	}
	if err != nil {
		return dbError(err)
	}
	tx, err := s.beginIsolation(ctx, isolation)
	if err != nil {
		return dbError(err)
	}
	defer rollback(tx)
	mode := "SHARE"
	if exclusive {
		mode = "UPDATE"
	}
	var p Principal
	var disabled *time.Time
	err = tx.QueryRow(ctx, `SELECT id::text,email_normalized,timezone,disabled_at FROM users WHERE id=$1 FOR `+mode, candidate).Scan(&p.Profile.ID, &p.Profile.Email, &p.Profile.Timezone, &disabled)
	if errors.Is(err, pgx.ErrNoRows) || disabled != nil {
		return failure(401, "unauthenticated")
	}
	if err != nil {
		return dbError(err)
	}
	err = tx.QueryRow(ctx, `SELECT id::text,device_name,created_at,last_seen_at,expires_at,created_at+interval '720 hours',csrf_token_hash FROM sessions WHERE user_id=$1 AND token_hash=$2 AND revoked_at IS NULL AND expires_at>now() AND created_at+interval '720 hours'>now() FOR `+mode, candidate, hash).Scan(&p.Session.ID, &p.Session.Device, &p.Session.Created, &p.Session.LastSeen, &p.Session.Expires, &p.Session.Absolute, &p.CSRFHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return failure(401, "unauthenticated")
	}
	if err != nil {
		return dbError(err)
	}
	var checkedAt time.Time
	if err = tx.QueryRow(ctx, "SELECT clock_timestamp()").Scan(&checkedAt); err != nil {
		return dbError(err)
	}
	if !p.Session.Expires.After(checkedAt) || !p.Session.Absolute.After(checkedAt) {
		return failure(401, "unauthenticated")
	}
	p.Session.Created = p.Session.Created.UTC()
	p.Session.LastSeen = p.Session.LastSeen.UTC()
	p.Session.Expires = p.Session.Expires.UTC()
	p.Session.Absolute = p.Session.Absolute.UTC()
	p.Session.Current = true
	if err = callback(tx, p); err != nil {
		return err
	}
	if err = tx.Commit(ctx); err != nil {
		return dbError(err)
	}
	// Separate best-effort activity update; no lock promotion in the protected transaction.
	activity, stop := context.WithTimeout(ctx, time.Second)
	defer stop()
	_, _ = s.DB.Exec(activity, `UPDATE sessions SET last_seen_at=now() WHERE id=$1 AND revoked_at IS NULL`, p.Session.ID)
	return nil
}
func RequireMembership(ctx context.Context, tx pgx.Tx, user, workspace string) error {
	var role string
	e := tx.QueryRow(ctx, `SELECT role FROM memberships WHERE user_id=$1 AND workspace_id=$2 AND revoked_at IS NULL FOR SHARE`, user, workspace).Scan(&role)
	if errors.Is(e, pgx.ErrNoRows) {
		return failure(404, "not_found")
	}
	if e != nil {
		return dbError(e)
	}
	return nil
}
func Current(ctx context.Context, tx pgx.Tx, p Principal, credential string) (Auth, error) {
	w, e := personalWorkspace(ctx, tx, p.Profile.ID)
	return Auth{p.Profile, w, p.Session, credential}, e
}
func Renew(ctx context.Context, tx pgx.Tx, p Principal, credential string) (Auth, error) {
	a, e := Current(ctx, tx, p, credential)
	if e != nil {
		return Auth{}, e
	}
	e = tx.QueryRow(ctx, `UPDATE sessions SET expires_at=least(now()+interval '168 hours',created_at+interval '720 hours') WHERE id=$1 RETURNING expires_at`, p.Session.ID).Scan(&a.Session.Expires)
	if e != nil {
		return Auth{}, dbError(e)
	}
	a.Session.Expires = a.Session.Expires.UTC()
	return a, nil
}
func Revoke(ctx context.Context, tx pgx.Tx, user, id string) error {
	r, e := tx.Exec(ctx, `UPDATE sessions SET revoked_at=coalesce(revoked_at,now()) WHERE user_id=$1 AND id=$2`, user, id)
	if e != nil {
		return dbError(e)
	}
	if r.RowsAffected() != 1 {
		return failure(404, "not_found")
	}
	return nil
}
func UpdateProfile(ctx context.Context, tx pgx.Tx, p Profile, zone string) (Profile, error) {
	_, e := tx.Exec(ctx, `UPDATE users SET timezone=$1 WHERE id=$2`, zone, p.ID)
	p.Timezone = zone
	if e != nil {
		return Profile{}, dbError(e)
	}
	return p, nil
}

func (s *Service) begin(ctx context.Context) (pgx.Tx, error) {
	return s.beginIsolation(ctx, pgx.ReadCommitted)
}
func (s *Service) beginIsolation(ctx context.Context, isolation pgx.TxIsoLevel) (pgx.Tx, error) {
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{IsoLevel: isolation})
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, "SET LOCAL lock_timeout='3s'"); err != nil {
		rollback(tx)
		return nil, err
	}
	if _, err = tx.Exec(ctx, "SET LOCAL statement_timeout='10s'"); err != nil {
		rollback(tx)
		return nil, err
	}
	return tx, nil
}
