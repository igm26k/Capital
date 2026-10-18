// reconcile-wallet applies an operator-reviewed Wallet balance plan from stdin.
// Default is a complete transactional dry run; -apply commits with audit/sync.
package main

import (
	"accounting/backend/internal/maintenance"
	commands "accounting/backend/internal/sync"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/jackc/pgx/v5/pgxpool"
	"os"
	"time"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	apply := flag.Bool("apply", false, "commit the reviewed plan")
	flag.Parse()
	var plan maintenance.WalletPlan
	decoder := json.NewDecoder(os.Stdin)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&plan); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		return err
	}
	defer db.Close()
	request, err := maintenance.Request(plan)
	if err != nil {
		return err
	}
	result, err := commands.ExecuteMaintenance(ctx, db, plan.Actor, request, maintenance.ReconcileWallet(plan), *apply)
	if err != nil {
		return err
	}
	if result.Status != 200 {
		return fmt.Errorf("reconciliation rejected: status %d", result.Status)
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"applied": *apply, "replayed": result.Replayed, "status": result.Status})
}
