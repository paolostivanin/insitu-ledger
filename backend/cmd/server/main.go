package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/pstivanin/insitu-ledger/backend/internal"
	"github.com/pstivanin/insitu-ledger/backend/internal/api"
	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
	"github.com/pstivanin/insitu-ledger/backend/internal/db"
	"github.com/pstivanin/insitu-ledger/backend/internal/scheduler"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dataDir := flag.String("data", "./data", "data directory for SQLite database")
	flag.Parse()

	log.Printf("InSitu Ledger v%s", internal.Version)

	// Allow env override
	if v := os.Getenv("INSITU_ADDR"); v != "" {
		*addr = v
	}
	if v := os.Getenv("INSITU_DATA_DIR"); v != "" {
		*dataDir = v
	}

	conn, err := db.Open(*dataDir)
	if err != nil {
		log.Fatalf("failed to open database: %v", err)
	}
	defer conn.Close()

	server := &api.Server{
		DB:         conn,
		AuthStore:  auth.NewStore(conn),
		TrustProxy: os.Getenv("INSITU_TRUST_PROXY") == "true",
	}

	router := api.NewRouter(server)

	// Check for due scheduled transactions every minute
	schedulerCtx, schedulerCancel := context.WithCancel(context.Background())
	defer schedulerCancel()
	scheduler.Start(schedulerCtx, conn, 1*time.Minute)

	// Check for due backups every hour
	scheduler.StartBackup(schedulerCtx, conn, *dataDir, 1*time.Hour)

	srv := &http.Server{
		Addr:    *addr,
		Handler: router,
	}

	// Graceful shutdown on SIGINT/SIGTERM
	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Printf("InSitu Ledger listening on %s", *addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	<-done
	log.Println("Shutting down...")

	// Stop background goroutines first so they don't use a closing DB connection.
	schedulerCancel()
	server.LoginRateLimiter.Stop()
	server.TOTPRateLimiter.Stop()
	server.APIRateLimiter.Stop()

	// Then gracefully drain in-flight HTTP requests.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Fatalf("forced shutdown: %v", err)
	}

	log.Println("Server stopped")
}
