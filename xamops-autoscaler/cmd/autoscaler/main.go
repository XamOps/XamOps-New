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

	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"

	"github.com/xamops/autoscaler/internal/config"
	"github.com/xamops/autoscaler/internal/controller"
	"github.com/xamops/autoscaler/internal/policy"
	"github.com/xamops/autoscaler/internal/pusher"
)

const version = "1.0.0-autoscaler"

func main() {
	log.Printf("Starting XamOps Autoscaling Controller %s", version)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Config error: %v", err)
	}

	if cfg.DryRun {
		log.Println("⚠️  DRY-RUN MODE: no cluster changes will be made. Set DRY_RUN=false to enable actions.")
	}

	log.Printf("Cluster: %s | Account: %s | Server: %s", cfg.ClusterName, cfg.CloudAccountID, cfg.ServerURL)
	log.Printf("Evictor=%v Rightsizer=%v Rebalancer=%v DryRun=%v",
		cfg.EnableEvictor, cfg.EnableRightsizer, cfg.EnableRebalancer, cfg.DryRun)

	// ── Kubernetes client ────────────────────────────────────────────────
	k8sCfg, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("Cannot create in-cluster config: %v", err)
	}
	k8s, err := kubernetes.NewForConfig(k8sCfg)
	if err != nil {
		log.Fatalf("Cannot create k8s client: %v", err)
	}

	// ── Shared components ────────────────────────────────────────────────
	policyClient := policy.NewClient(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID)
	push := pusher.New(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID, cfg.MaxRetries, cfg.RetryDelay)

	evictor := controller.NewEvictor(k8s, cfg.DryRun)
	rightsizer := controller.NewRightsizer(k8s, cfg.PrometheusURL, cfg.DryRun)
	rebalancer := controller.NewRebalancer(k8s, cfg.DryRun)

	// ── Context ──────────────────────────────────────────────────────────
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── Health server ────────────────────────────────────────────────────
	go startHealthServer()

	// ── Main control loop ─────────────────────────────────────────────────
	go runControlLoop(ctx, cfg, policyClient, push, evictor, rightsizer, rebalancer)

	// ── Heartbeat loop ────────────────────────────────────────────────────
	go runHeartbeatLoop(ctx, cfg, push)

	// ── Graceful shutdown ─────────────────────────────────────────────────
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutdown signal received — stopping autoscaler controller...")
	cancel()
	time.Sleep(3 * time.Second)
	log.Println("Autoscaler controller stopped")
}

// runControlLoop polls for policy and runs enabled controllers on each cycle.
func runControlLoop(
	ctx context.Context,
	cfg *config.Config,
	policyClient *policy.Client,
	push *pusher.Pusher,
	evictor *controller.Evictor,
	rightsizer *controller.Rightsizer,
	rebalancer *controller.Rebalancer,
) {
	// Short initial delay so the k8s API server is ready
	time.Sleep(15 * time.Second)

	ticker := time.NewTicker(time.Duration(cfg.PolicyPollInterval) * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runCycle(ctx, cfg, policyClient, push, evictor, rightsizer, rebalancer)
		}
	}
}

func runCycle(
	ctx context.Context,
	cfg *config.Config,
	policyClient *policy.Client,
	push *pusher.Pusher,
	evictor *controller.Evictor,
	rightsizer *controller.Rightsizer,
	rebalancer *controller.Rebalancer,
) {
	log.Println("── Autoscaler cycle start ──────────────────────────────────────")

	// 1. Fetch current policy from XamOps backend
	bundle, err := policyClient.FetchPolicy(ctx)
	if err != nil {
		log.Printf("ERROR: fetch policy: %v", err)
		return
	}

	// 2. If mode is disabled, skip everything
	if bundle.AutoscalerMode == "disabled" {
		log.Println("Autoscaler mode=disabled — idle this cycle")
		return
	}

	var batches []pusher.Batch
	events := make([]policy.AutoscalingEvent, 0)

	// 3. Rebalancer — check first (highest priority, changes node topology)
	if cfg.EnableRebalancer || bundle.Rebalancer.Status == "running" {
		result, err := rebalancer.Run(ctx, bundle.Rebalancer, bundle.SafetyGuards)
		if err != nil {
			log.Printf("ERROR: rebalancer: %v", err)
		}
		if result != nil && result.Status != "idle" {
			status := policy.RebalanceStatus{
				Status:          result.Status,
				StartedAt:       result.StartedAt.Format(time.RFC3339),
				NodesProcessed:  len(result.NodesDrained),
				NodesDrained:    len(result.NodesDrained),
				DurationMinutes: result.DurationMinutes,
				Error:           result.Error,
			}
			if !result.CompletedAt.IsZero() {
				status.CompletedAt = result.CompletedAt.Format(time.RFC3339)
			}
			batches = append(batches, pusher.Batch{
				Collector:   "autoscaling-rebalance-status",
				Items:       []policy.RebalanceStatus{status},
				Count:       1,
				CollectedAt: time.Now(),
			})
			if result.Status == "completed" {
				events = append(events, policy.AutoscalingEvent{
					ID:        fmt.Sprintf("rebalance-%d", time.Now().Unix()),
					Timestamp: time.Now().UTC().Format(time.RFC3339),
					Type:      "rebalance",
					Severity:  "success",
					Message:   fmt.Sprintf("Rebalance completed: %d nodes drained in %d min", len(result.NodesDrained), result.DurationMinutes),
					NodeCount: len(result.NodesDrained),
				})
			}
		}
	}

	// 4. Evictor — bin-packing scale-in (only in full or scoped mode, if enabled)
	evictorEnabled := cfg.EnableEvictor && bundle.EvictorPolicy.Enabled
	if bundle.AutoscalerMode == "scoped" {
		evictorEnabled = false // scoped mode: rightsizer only
	}
	if evictorEnabled {
		result, err := evictor.Run(ctx, bundle.EvictorPolicy, bundle.SafetyGuards)
		if err != nil {
			log.Printf("ERROR: evictor: %v", err)
		}
		if result != nil && result.NodesSaved > 0 {
			events = append(events, policy.AutoscalingEvent{
				ID:        fmt.Sprintf("evictor-%d", time.Now().Unix()),
				Timestamp: time.Now().UTC().Format(time.RFC3339),
				Type:      "scale-in",
				Severity:  "success",
				Message:   fmt.Sprintf("Evictor consolidated %d node(s) (%d pods evicted)", result.NodesSaved, len(result.EvictedPods)),
				NodeCount: result.NodesSaved,
			})
		}
		if result != nil && len(result.CandidateNodes) > 0 {
			// Push evictor candidates so the UI can show them
			batches = append(batches, pusher.Batch{
				Collector:   "autoscaling-evictor-candidates",
				Items:       result.CandidateNodes,
				Count:       len(result.CandidateNodes),
				CollectedAt: time.Now(),
			})
		}
	}

	// 5. Rightsizer — workload resource recommendation/application
	if cfg.EnableRightsizer && bundle.WorkloadRightsizerPolicy.Enabled {
		results, err := rightsizer.Run(ctx, bundle.WorkloadRightsizerPolicy, bundle.SafetyGuards)
		if err != nil {
			log.Printf("ERROR: rightsizer: %v", err)
		}
		if len(results) > 0 {
			batches = append(batches, pusher.Batch{
				Collector:   "autoscaling-rightsizing",
				Items:       results,
				Count:       len(results),
				CollectedAt: time.Now(),
			})
			applied := countApplied(results)
			if applied > 0 {
				events = append(events, policy.AutoscalingEvent{
					ID:        fmt.Sprintf("rightsizer-%d", time.Now().Unix()),
					Timestamp: time.Now().UTC().Format(time.RFC3339),
					Type:      "workload-resize",
					Severity:  "success",
					Message:   fmt.Sprintf("Rightsizer applied %d resource adjustments", applied),
				})
			}
		}
	}

	// 6. Push events
	if len(events) > 0 {
		batches = append(batches, pusher.Batch{
			Collector:   "autoscaling-events",
			Items:       events,
			Count:       len(events),
			CollectedAt: time.Now(),
		})
	}

	// 7. Send all batches to XamOps
	if len(batches) > 0 {
		if err := push.Push(batches); err != nil {
			log.Printf("ERROR: push results: %v", err)
		} else {
			log.Printf("Pushed %d batch(es) to XamOps", len(batches))
		}
	}

	log.Println("── Autoscaler cycle end ────────────────────────────────────────")
}

func runHeartbeatLoop(ctx context.Context, cfg *config.Config, push *pusher.Pusher) {
	ticker := time.NewTicker(time.Duration(cfg.HeartbeatInterval) * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := push.Heartbeat(); err != nil {
				log.Printf("WARN: heartbeat failed: %v", err)
			}
		}
	}
}

func startHealthServer() {
	mux := http.NewServeMux()
	// /healthz and /readyz match the Kubernetes probe paths in the manifest
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"status":"healthy","version":"%s"}`, version)
	})
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ready"}`))
	})
	log.Println("Health server listening on :8081")
	if err := http.ListenAndServe(":8081", mux); err != nil {
		log.Printf("Health server error: %v", err)
	}
}

func countApplied(results []policy.WorkloadRightsizingResult) int {
	n := 0
	for _, r := range results {
		if r.Applied {
			n++
		}
	}
	return n
}
