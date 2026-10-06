package platform

import (
	commands "accounting/backend/internal/sync"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"
)

func RunAPI(ctx context.Context, cfg Config, logger *slog.Logger) error {
	database, err := OpenDatabase(ctx, cfg)
	if err != nil {
		return err
	}
	defer database.Close()
	httpHandler, err := NewAPIHandler(database, cfg, logger)
	if err != nil {
		return errors.New("identity initialization failed")
	}
	server := &http.Server{Addr: cfg.HTTPAddress, Handler: httpHandler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024, ErrorLog: slog.NewLogLogger(logger.Handler(), slog.LevelError)}
	stopped := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(stopped)
		select {
		case <-ctx.Done():
			shutdownContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := server.Shutdown(shutdownContext); err != nil {
				_ = server.Close()
			}
		case <-done:
		}
	}()
	logger.Info("api_started", "address", cfg.HTTPAddress)
	err = server.ListenAndServe()
	close(done)
	<-stopped
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	if err != nil {
		return errors.New("HTTP server failed")
	}
	return nil
}

func RunWorker(ctx context.Context, cfg Config, logger *slog.Logger) error {
	database, err := OpenDatabase(ctx, cfg)
	if err != nil {
		return err
	}
	defer database.Close()
	startContext, cancel := context.WithTimeout(ctx, 3*time.Second)
	err = database.Ping(startContext)
	cancel()
	if err != nil {
		return errors.New("database unavailable")
	}
	logger.Info("worker_started", "jobs", []string{"receipt_compaction", "sync_retention"})
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		count, err := commands.CompactReceipts(ctx, database, 1000)
		if err != nil && ctx.Err() == nil {
			logger.Error("receipt_compaction_failed")
		} else if count > 0 {
			logger.Info("receipts_compacted", "count", count)
		}
		cleaned, cleanupErr := commands.CleanupSync(ctx, database, 100)
		if cleanupErr != nil && ctx.Err() == nil {
			logger.Error("sync_cleanup_failed")
		} else if cleaned.Snapshots > 0 || cleaned.Groups > 0 {
			logger.Info("sync_cleaned", "snapshots", cleaned.Snapshots, "groups", cleaned.Groups)
		}

		select {
		case <-ctx.Done():
			logger.Info("worker_stopped")
			return nil
		case <-ticker.C:
		}
	}
}
