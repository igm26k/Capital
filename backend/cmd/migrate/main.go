package main

import (
	"context"
	"log/slog"
	"os"
	"time"

	"accounting/backend/internal/platform"
	"accounting/backend/migrate"
	"accounting/backend/migrations"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := platform.LoadConfig()
	if err != nil {
		logger.Error("configuration_invalid", "reason", err.Error())
		os.Exit(1)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	database, err := platform.OpenDatabase(ctx, cfg)
	if err != nil {
		logger.Error("database_failed", "reason", err.Error())
		os.Exit(1)
	}
	count, err := migrate.Apply(ctx, database, migrations.Files)
	database.Close()
	if err != nil {
		logger.Error("migration_failed", "reason", err.Error())
		os.Exit(1)
	}
	logger.Info("migrations_applied", "count", count)
}
