package platform

import (
	"accounting/backend/internal/identity"
	"accounting/backend/internal/ledger"
	"accounting/backend/internal/maildelivery"
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
	auth, err := identity.New(database, cfg.AuthHashConcurrency)
	if err != nil {
		return err
	}
	logger.Info("worker_started", "jobs", []string{"receipt_compaction", "sync_retention", "csv_import"})
	ticker := time.NewTicker(2 * time.Second)
	lastMaintenance := time.Time{}
	defer ticker.Stop()
	for {
		if cfg.EmailVerification {
			for i := 0; i < 2; i++ {
				processed, mailErr := auth.DispatchEmail(ctx, cfg.EmailKey, maildelivery.Sender{Config: cfg.SMTP})
				if mailErr != nil && ctx.Err() == nil {
					logger.Error("auth_email_delivery_failed")
				}
				if !processed {
					break
				}
			}
		}
		sourceCount, sourceErr := ledger.ProcessSourceEvents(ctx, auth, 20)
		if sourceErr != nil && ctx.Err() == nil {
			logger.Error("source_events_failed")
		} else if sourceCount > 0 {
			logger.Info("source_events_processed", "count", sourceCount)
		}
		processed, importErr := ledger.ProcessImports(ctx, auth, 20)
		if importErr != nil && ctx.Err() == nil {
			logger.Error("csv_import_failed")
		} else if processed > 0 {
			logger.Info("csv_import_processed", "count", processed)
		}
		if time.Since(lastMaintenance) >= time.Minute {
			lastMaintenance = time.Now()
			if _, emailCleanupErr := database.Exec(ctx, `DELETE FROM auth_challenges WHERE expires_at<=clock_timestamp()`); emailCleanupErr != nil && ctx.Err() == nil {
				logger.Error("auth_email_cleanup_failed")
			}
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

		}
		select {
		case <-ctx.Done():
			logger.Info("worker_stopped")
			return nil
		case <-ticker.C:
		}
	}
}
