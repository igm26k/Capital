package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"accounting/backend/internal/platform"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := platform.LoadConfig()
	if err != nil {
		logger.Error("configuration_invalid", "reason", err.Error())
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := platform.RunWorker(ctx, cfg, logger); err != nil {
		logger.Error("worker_failed", "reason", err.Error())
		os.Exit(1)
	}
}
