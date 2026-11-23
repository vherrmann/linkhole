package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/vherrmann/the-next-thing/server/internal/api"
	"github.com/vherrmann/the-next-thing/server/internal/auth"
	"github.com/vherrmann/the-next-thing/server/internal/store"
	"github.com/vherrmann/the-next-thing/server/internal/titles"
)

func main() {
	defaultDB := "items.db"
	if d := os.Getenv("STATE_DIRECTORY"); d != "" {
		defaultDB = filepath.Join(d, "items.db")
	}
	listen := flag.String("listen", "127.0.0.1:8787", "listen address")
	dbPath := flag.String("db", defaultDB, "SQLite database path")
	tokenFile := flag.String("token-file", "", "file containing the bearer token")
	insecure := flag.Bool("insecure-no-auth", false, "disable authentication (development only)")
	flag.Parse()

	var token string
	switch {
	case *insecure:
		log.Print("WARNING: authentication disabled")
	case *tokenFile == "":
		log.Fatal("-token-file is required (or pass -insecure-no-auth)")
	default:
		t, err := auth.ReadTokenFile(*tokenFile)
		if err != nil {
			log.Fatalf("reading token: %v", err)
		}
		if t == "" {
			log.Fatal("token file is empty")
		}
		token = t
	}

	st, err := store.Open(*dbPath)
	if err != nil {
		log.Fatalf("opening database: %v", err)
	}
	defer st.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	worker := titles.NewWorker(titles.NewFetcher(), st)
	go worker.Run(ctx)
	pending, err := st.PendingTitles()
	if err != nil {
		log.Fatalf("listing pending titles: %v", err)
	}
	for _, it := range pending {
		worker.Enqueue(titles.Job{ID: it.ID, URL: it.URL})
	}

	srv := &http.Server{
		Addr:              *listen,
		Handler:           api.New(st, worker, token),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(sctx)
	}()
	log.Printf("listening on %s", *listen)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
