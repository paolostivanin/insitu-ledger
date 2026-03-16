package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/pstivanin/insitu-ledger/backend/internal/api"
	"github.com/pstivanin/insitu-ledger/backend/internal/auth"
	"github.com/pstivanin/insitu-ledger/backend/internal/db"
	"github.com/pstivanin/insitu-ledger/backend/internal/scheduler"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dataDir := flag.String("data", "./data", "data directory for SQLite database")
	flag.Parse()

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
		DB:        conn,
		AuthStore: auth.NewStore(conn),
	}

	router := api.NewRouter(server)

	// Check for due scheduled transactions every hour
	scheduler.Start(conn, 1*time.Hour)

	log.Printf("InSitu Ledger listening on %s", *addr)
	if err := http.ListenAndServe(*addr, router); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
