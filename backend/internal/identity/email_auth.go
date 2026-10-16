package identity

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"github.com/jackc/pgx/v5"
	"io"
	"strings"
	"time"
)

// EmailAuthKey protects queued message bodies. Challenges store only a token digest.
func emailCipher(key []byte) (cipher.AEAD, error) {
	if len(key) != 32 {
		return nil, errors.New("invalid email encryption key")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}
func EncryptEmail(key []byte, id, body string) ([]byte, error) {
	a, err := emailCipher(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, a.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	return a.Seal(nonce, nonce, []byte(body), []byte(id)), nil
}
func DecryptEmail(key []byte, id string, body []byte) (string, error) {
	a, err := emailCipher(key)
	if err != nil || len(body) < a.NonceSize() {
		return "", errors.New("email unavailable")
	}
	v, err := a.Open(nil, body[:a.NonceSize()], body[a.NonceSize():], []byte(id))
	if err != nil {
		return "", errors.New("email unavailable")
	}
	return string(v), nil
}

// StartEmailAuth gives the same public outcome for known and unknown addresses.
// The password is hashed for every registration request, including existing users.
func (s *Service) StartEmailAuth(ctx context.Context, key []byte, email, purpose, password, zone string) error {
	if purpose != "registration" && purpose != "recovery" {
		return failure(422, "validation_error")
	}
	var passwordHash any
	var timezone any
	if purpose == "registration" {
		h, err := s.Hasher.Hash(ctx, password)
		if err != nil {
			return failure(503, "service_unavailable")
		}
		passwordHash = h
		timezone = zone
	}
	id, code := uuid(), token()
	body := "Accounting\r\n\r\nКод подтверждения регистрации: " + code + "\r\nКод действует 30 минут. Если вы не отправляли запрос, проигнорируйте письмо.\r\n"
	if purpose == "recovery" {
		body = "Accounting\r\n\r\nКод восстановления пароля: " + code + "\r\nКод действует 30 минут. Если вы не отправляли запрос, проигнорируйте письмо.\r\n"
	}
	encrypted, err := EncryptEmail(key, id, body)
	if err != nil {
		return failure(503, "service_unavailable")
	}
	tx, err := s.begin(ctx)
	if err != nil {
		return dbError(err)
	}
	defer rollback(tx)
	// Global lock bounds outstanding messages and makes per-address cooldown atomic across processes.
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(741902107)`); err != nil {
		return dbError(err)
	}
	var recent bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM auth_challenges WHERE email=$1 AND purpose=$2 AND created_at>clock_timestamp()-interval '60 seconds')`, email, purpose).Scan(&recent); err != nil {
		return dbError(err)
	}
	if recent {
		return tx.Commit(ctx)
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE expires_at<=clock_timestamp()`); err != nil {
		return dbError(err)
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM auth_challenges`).Scan(&count); err != nil {
		return dbError(err)
	}
	if count >= 10000 {
		return failure(503, "service_unavailable")
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE email=$1 AND purpose=$2`, email, purpose); err != nil {
		return dbError(err)
	}
	if _, err = tx.Exec(ctx, `INSERT INTO auth_challenges(id,email,purpose,token_hash,password_hash,timezone,expires_at) VALUES($1,$2,$3,$4,$5,$6,clock_timestamp()+interval '30 minutes')`, id, email, purpose, digest(code), passwordHash, timezone); err != nil {
		return dbError(err)
	}
	if _, err = tx.Exec(ctx, `INSERT INTO auth_mail(challenge_id,encrypted_body) VALUES($1,$2)`, id, encrypted); err != nil {
		return dbError(err)
	}
	if err = tx.Commit(ctx); err != nil {
		return dbError(err)
	}
	return nil
}

func (s *Service) ConfirmRegistration(ctx context.Context, email, code, password, device string) (Auth, error) {
	tx, err := s.begin(ctx)
	if err != nil {
		return Auth{}, dbError(err)
	}
	defer rollback(tx)
	// Same user -> challenge lock order as deletion and reset.
	var exists bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE email_normalized=$1)`, email).Scan(&exists); err != nil {
		return Auth{}, dbError(err)
	}
	var id, hash, zone string
	var expected []byte
	var expires time.Time
	err = tx.QueryRow(ctx, `SELECT id::text,token_hash,password_hash,timezone,expires_at FROM auth_challenges WHERE email=$1 AND purpose='registration' FOR UPDATE`, email).Scan(&id, &expected, &hash, &zone, &expires)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return Auth{}, dbError(err)
	}
	if err != nil || len(code) != 43 || subtle.ConstantTimeCompare(expected, digest(code)) != 1 || !time.Now().Before(expires) || exists {
		return Auth{}, failure(422, "confirmation_invalid")
	}
	matches, err := s.Hasher.Verify(ctx, hash, password)
	if err != nil {
		return Auth{}, failure(503, "service_unavailable")
	}
	if !matches {
		return Auth{}, failure(422, "confirmation_invalid")
	}
	p := Profile{uuid(), email, zone}
	workspace, generation := uuid(), uuid()
	if _, err = tx.Exec(ctx, `INSERT INTO users(id,email_normalized,password_hash,timezone) VALUES($1,$2,$3,$4)`, p.ID, email, hash, zone); err != nil {
		mapped := dbError(err)
		if failure, ok := mapped.(*Error); ok && failure.Status == 422 {
			return Auth{}, &Error{Status: 422, Code: "confirmation_invalid"}
		}
		return Auth{}, mapped
	}
	if _, err = tx.Exec(ctx, `INSERT INTO workspaces(id,name,owner_user_id,sync_generation_id) VALUES($1,'Личное пространство',$2,$3)`, workspace, p.ID, generation); err != nil {
		return Auth{}, dbError(err)
	}
	if _, err = tx.Exec(ctx, `INSERT INTO memberships(workspace_id,user_id,role) VALUES($1,$2,'owner')`, workspace, p.ID); err != nil {
		return Auth{}, dbError(err)
	}
	if _, err = tx.Exec(ctx, `INSERT INTO sync_heads(workspace_id,generation_id) VALUES($1,$2)`, workspace, generation); err != nil {
		return Auth{}, dbError(err)
	}
	a, err := s.createSession(ctx, tx, p, device)
	if err != nil {
		return Auth{}, err
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE email=$1`, email); err != nil {
		return Auth{}, dbError(err)
	}
	if err = tx.Commit(ctx); err != nil {
		return Auth{}, dbError(err)
	}
	return a, nil
}
func (s *Service) ConfirmRecovery(ctx context.Context, email, code, password string) error {
	hash, err := s.Hasher.Hash(ctx, password)
	if err != nil {
		return failure(503, "service_unavailable")
	}
	tx, err := s.begin(ctx)
	if err != nil {
		return dbError(err)
	}
	defer rollback(tx)
	var user string
	err = tx.QueryRow(ctx, `SELECT id::text FROM users WHERE email_normalized=$1 AND disabled_at IS NULL FOR UPDATE`, email).Scan(&user)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return dbError(err)
	}
	var id string
	var expected []byte
	var expires time.Time
	challengeErr := tx.QueryRow(ctx, `SELECT id::text,token_hash,expires_at FROM auth_challenges WHERE email=$1 AND purpose='recovery' FOR UPDATE`, email).Scan(&id, &expected, &expires)
	if challengeErr != nil && !errors.Is(challengeErr, pgx.ErrNoRows) {
		return dbError(challengeErr)
	}
	if err != nil || challengeErr != nil || len(code) != 43 || subtle.ConstantTimeCompare(expected, digest(code)) != 1 || !time.Now().Before(expires) {
		return failure(422, "confirmation_invalid")
	}
	if _, err = tx.Exec(ctx, `UPDATE users SET password_hash=$2 WHERE id=$1`, user, hash); err != nil {
		return dbError(err)
	}
	if _, err = tx.Exec(ctx, `UPDATE sessions SET revoked_at=coalesce(revoked_at,clock_timestamp()) WHERE user_id=$1`, user); err != nil {
		return dbError(err)
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE email=$1`, email); err != nil {
		return dbError(err)
	}
	if err = tx.Commit(ctx); err != nil {
		return dbError(err)
	}
	return nil
}

type EmailSender interface {
	Send(context.Context, string, string) error
}

// DispatchEmail holds only a mail-row lock while sending. Failures never expose message text.
func (s *Service) DispatchEmail(ctx context.Context, key []byte, sender EmailSender) (bool, error) {
	tx, err := s.begin(ctx)
	if err != nil {
		return false, dbError(err)
	}
	defer rollback(tx)
	var id, email string
	var encrypted []byte
	var attempts int
	err = tx.QueryRow(ctx, `SELECT m.challenge_id::text,c.email,m.encrypted_body,m.attempts FROM auth_mail m JOIN auth_challenges c ON c.id=m.challenge_id WHERE m.sent_at IS NULL AND m.attempts<5 AND m.available_at<=clock_timestamp() AND c.expires_at>clock_timestamp() ORDER BY m.available_at,m.challenge_id FOR UPDATE OF m SKIP LOCKED LIMIT 1`).Scan(&id, &email, &encrypted, &attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		_, err = tx.Exec(ctx, `DELETE FROM auth_challenges WHERE expires_at<=clock_timestamp()`)
		if err != nil {
			return false, dbError(err)
		}
		return false, tx.Commit(ctx)
	}
	if err != nil {
		return false, dbError(err)
	}
	body, err := DecryptEmail(key, id, encrypted)
	if err == nil {
		err = sender.Send(ctx, email, body)
	}
	sent := err == nil
	if sent {
		_, err = tx.Exec(ctx, `UPDATE auth_mail SET sent_at=clock_timestamp(),encrypted_body=NULL WHERE challenge_id=$1`, id)
	} else {
		_, err = tx.Exec(ctx, `UPDATE auth_mail SET attempts=attempts+1,available_at=clock_timestamp()+($2::int*interval '30 seconds') WHERE challenge_id=$1`, id, 1<<attempts)
	}
	if err != nil {
		return false, dbError(err)
	}
	if err = tx.Commit(ctx); err != nil {
		return false, dbError(err)
	}
	if !sent {
		return true, errors.New("email delivery unavailable")
	}
	return true, nil
}

// NormalizeEmail remains deliberately ASCII, matching the existing registration contract.
func NormalizeEmail(email string) string { return strings.ToLower(strings.TrimSpace(email)) }
