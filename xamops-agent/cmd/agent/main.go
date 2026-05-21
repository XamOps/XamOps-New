package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/xamops/agent/internal/collector"
	"github.com/xamops/agent/internal/config"
	"github.com/xamops/agent/internal/pusher"
)

func main() {
	log.Println("Starting XamOps Agent v1.0.0")

	// Load configuration from environment
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	log.Printf("Cluster: %s, Account: %s", cfg.ClusterName, cfg.CloudAccountID)
	log.Printf("Server: %s", cfg.ServerURL)
	log.Printf("Collect interval: %ds, Push interval: %ds", cfg.CollectInterval, cfg.PushInterval)

	// Create collectors
	collectors := collector.NewCollectorManager(cfg)

	// Create pusher
	dataPusher := pusher.NewPusher(cfg)

	// Auto-fetch OpenObserve credentials from the backend so operators don't
	// need to set OPENOBSERVE_* env vars manually — the server already has them.
	dataPusher.FetchConfig()

	// Create context for graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	collectors.StartLogWatcher(ctx)

	// Start health server
	go startHealthServer(cfg)

	// Start collection loop
	go runCollectionLoop(ctx, cfg, collectors, dataPusher)

	// Start heartbeat loop
	go runHeartbeatLoop(ctx, cfg, dataPusher)

	// Wait for shutdown signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutdown signal received, stopping agent...")
	cancel()
	time.Sleep(2 * time.Second)
	log.Println("Agent stopped")
}

func runCollectionLoop(ctx context.Context, cfg *config.Config, collectors *collector.Manager, dataPusher *pusher.Pusher) {
	// Collect immediately on startup — IMDS retry logic inside each collector handles boot delays.
	collectAndPush(cfg, collectors, dataPusher)

	ticker := time.NewTicker(time.Duration(cfg.CollectInterval) * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			collectAndPush(cfg, collectors, dataPusher)
		}
	}
}

func collectAndPush(cfg *config.Config, collectors *collector.Manager, dataPusher *pusher.Pusher) {
	log.Println("Starting data collection...")

	// Collect from all sources
	data, err := collectors.CollectAll()
	if err != nil {
		log.Printf("Collection error (partial data may be available): %v", err)
	}

	if data == nil || len(data.Batches) == 0 {
		log.Println("No data collected, skipping push")
		return
	}

	// Push to server
	log.Printf("Pushing %d batches to server...", len(data.Batches))
	err = dataPusher.Push(data)
	if err != nil {
		log.Printf("Push failed: %v", err)
	} else {
		log.Println("Data pushed successfully")
	}
}

func runHeartbeatLoop(ctx context.Context, cfg *config.Config, dataPusher *pusher.Pusher) {
	ticker := time.NewTicker(time.Duration(cfg.HeartbeatInterval) * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// Poll for pending EXPLAIN commands before heartbeat
			dataPusher.PollAndExecCommands()

			if err := dataPusher.Heartbeat(); err != nil {
				log.Printf("Heartbeat failed: %v", err)
			}
		}
	}
}

func startHealthServer(cfg *config.Config) {
	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"healthy","version":"1.0.0"}`))
	})

	mux.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ready"}`))
	})

	log.Println("Health server listening on :8080")
	if err := http.ListenAndServe(":8080", mux); err != nil {
		log.Printf("Health server error: %v", err)
	}
}
