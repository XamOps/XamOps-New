package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/xamops/agent/internal/collector"
	"github.com/xamops/agent/internal/config"
	"github.com/xamops/agent/internal/pusher"
	"github.com/xamops/agent/internal/version"
)

func main() {
	log.Printf("Starting XamOps Agent %s", version.Version)

	// Load configuration from environment
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	log.Printf("Cluster: %s, Account: %s", cfg.ClusterName, cfg.CloudAccountID)
	log.Printf("Server: %s", cfg.ServerURL)
	log.Printf("Collect interval: %ds, Push interval: %ds", cfg.CollectInterval, cfg.PushInterval)

	// Create pusher and fetch remote config FIRST so OpenObserve credentials are
	// available before NewCollectorManager decides whether to create the log watcher.
	dataPusher := pusher.NewPusher(cfg)
	dataPusher.FetchConfig()

	// Create collectors (log watcher is only created when OpenObserve password is set)
	collectors := collector.NewCollectorManager(cfg)

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
		fmt.Fprintf(w, `{"status":"healthy","version":%q}`, version.Version)
	})

	mux.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ready"}`))
	})

	addr := ":" + cfg.HealthPort
	log.Printf("Health server listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Printf("Health server error: %v", err)
	}
}
