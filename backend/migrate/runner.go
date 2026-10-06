package migrate

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"regexp"
	"sort"
	"strconv"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Migration struct {
	Version  int
	Name     string
	SQL      string
	Checksum string
}

func Load(files fs.FS) ([]Migration, error) {
	entries, err := fs.ReadDir(files, ".")
	if err != nil {
		return nil, errors.New("migration directory unavailable")
	}
	pattern := regexp.MustCompile(`^([0-9]{4})_[a-z0-9_]+\.sql$`)
	migrations := []Migration{}
	for _, entry := range entries {
		if entry.IsDir() || fs.ValidPath(entry.Name()) == false {
			continue
		}
		if !regexp.MustCompile(`\.sql$`).MatchString(entry.Name()) {
			continue
		}
		match := pattern.FindStringSubmatch(entry.Name())
		if match == nil {
			return nil, errors.New("invalid migration filename")
		}
		number, err := strconv.Atoi(match[1])
		if err != nil || number < 1 {
			return nil, errors.New("invalid migration version")
		}
		data, err := fs.ReadFile(files, entry.Name())
		if err != nil {
			return nil, errors.New("migration file unavailable")
		}
		checksum := sha256.Sum256(data)
		migrations = append(migrations, Migration{Version: number, Name: entry.Name(), SQL: string(data), Checksum: hex.EncodeToString(checksum[:])})
	}
	sort.Slice(migrations, func(i, j int) bool { return migrations[i].Version < migrations[j].Version })
	for i, migration := range migrations {
		if migration.Version != i+1 {
			return nil, errors.New("migration versions must be unique and contiguous")
		}
	}
	return migrations, nil
}

func Apply(ctx context.Context, database *pgxpool.Pool, files fs.FS) (int, error) {
	migrations, err := Load(files)
	if err != nil {
		return 0, err
	}
	tx, err := database.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return 0, errors.New("migration transaction unavailable")
	}
	defer func() { _ = tx.Rollback(context.Background()) }()
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(809001)`); err != nil {
		return 0, errors.New("migration lock unavailable")
	}
	if _, err := tx.Exec(ctx, `CREATE TABLE IF NOT EXISTS accounting_schema_migrations (version integer PRIMARY KEY CHECK (version > 0), name text NOT NULL, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return 0, errors.New("migration metadata unavailable")
	}
	rows, err := tx.Query(ctx, `SELECT version, name, checksum FROM accounting_schema_migrations ORDER BY version`)
	if err != nil {
		return 0, errors.New("migration history unavailable")
	}
	applied := map[int]Migration{}
	for rows.Next() {
		var migration Migration
		if err := rows.Scan(&migration.Version, &migration.Name, &migration.Checksum); err != nil {
			rows.Close()
			return 0, errors.New("invalid migration history")
		}
		applied[migration.Version] = migration
	}
	if rows.Err() != nil {
		rows.Close()
		return 0, errors.New("migration history read failed")
	}
	rows.Close()
	for version, previous := range applied {
		if version < 1 || version > len(migrations) || migrations[version-1].Name != previous.Name || migrations[version-1].Checksum != previous.Checksum {
			return 0, errors.New("applied migration differs from bundled SQL")
		}
	}
	for version := 1; version <= len(applied); version++ {
		if _, exists := applied[version]; !exists {
			return 0, errors.New("migration history is not contiguous")
		}
	}
	count := 0
	for _, migration := range migrations {
		if _, exists := applied[migration.Version]; exists {
			continue
		}
		if _, err := tx.Exec(ctx, migration.SQL, pgx.QueryExecModeSimpleProtocol); err != nil {
			return 0, fmt.Errorf("migration %04d failed", migration.Version)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO accounting_schema_migrations(version,name,checksum) VALUES ($1,$2,$3)`, migration.Version, migration.Name, migration.Checksum); err != nil {
			return 0, errors.New("migration history write failed")
		}
		count++
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, errors.New("migration commit failed")
	}
	return count, nil
}
