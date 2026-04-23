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

	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/controller"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/pusher"
)

const version = "1.0.0-automation"

func main() {
	log.Printf("Starting XamOps Automation Agent %s", version)

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Config error: %v", err)
	}

	if cfg.DryRun {
		log.Println("WARNING: DRY-RUN MODE — no cluster changes will be made. Set DRY_RUN=false to enable actions.")
	}

	log.Printf("Cluster: %s | Account: %s | Server: %s", cfg.ClusterName, cfg.CloudAccountID, cfg.ServerURL)
	log.Printf("Controllers — Evictor=%v Rightsizer=%v Rebalancer=%v HPAManager=%v VPAManager=%v NodeOptimizer=%v DryRun=%v",
		cfg.EnableEvictor, cfg.EnableRightsizer, cfg.EnableRebalancer,
		cfg.EnableHPAManager, cfg.EnableVPAManager, cfg.EnableNodeOptimizer,
		cfg.DryRun)

	// ── Kubernetes clients ───────────────────────────────────────────────
	k8sCfg, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("Cannot create in-cluster config: %v", err)
	}

	k8s, err := kubernetes.NewForConfig(k8sCfg)
	if err != nil {
		log.Fatalf("Cannot create k8s client: %v", err)
	}

	// Dynamic client — required for VPA CRD management
	dc, err := dynamic.NewForConfig(k8sCfg)
	if err != nil {
		log.Fatalf("Cannot create dynamic k8s client: %v", err)
	}

	// Metrics client — optional; used by NodeOptimizer for node utilization
	mc, mcErr := metricsv.NewForConfig(k8sCfg)
	if mcErr != nil {
		log.Printf("WARN: metrics-server client unavailable (%v) — NodeOptimizer will use Prometheus fallback", mcErr)
		mc = nil
	}

	// ── Shared components ────────────────────────────────────────────────
	policyClient := policy.NewClient(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID)
	push := pusher.New(cfg.ServerURL, cfg.APIKey, cfg.ClusterName, cfg.CloudAccountID, cfg.MaxRetries, cfg.RetryDelay)

	// ── Controller instances (all respect DryRun flag) ───────────────────
	evictor := controller.NewEvictor(k8s, cfg.DryRun)
	rightsizer := controller.NewRightsizer(k8s, cfg.PrometheusURL, cfg.DryRun)
	rebalancer := controller.NewRebalancer(k8s, cfg.DryRun)
	hpaMgr := controller.NewHPAManager(k8s, cfg.PrometheusURL, cfg.DryRun)
	vpaMgr := controller.NewVPAManager(k8s, dc, cfg.DryRun)
	nodeOpt := controller.NewNodeOptimizer(k8s, mc, dc, cfg.PrometheusURL, cfg.DryRun)

	// ── Context ──────────────────────────────────────────────────────────
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── Health server ────────────────────────────────────────────────────
	go startHealthServer()

	// ── Main control loop ────────────────────────────────────────────────
	go runControlLoop(ctx, cfg, policyClient, push, evictor, rightsizer, rebalancer, hpaMgr, vpaMgr, nodeOpt)

	// ── Heartbeat loop ────────────────────────────────────────────────────
	go runHeartbeatLoop(ctx, cfg, push)

	// ── Graceful shutdown ─────────────────────────────────────────────────
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutdown signal received — stopping automation agent...")
	cancel()
	time.Sleep(3 * time.Second)
	log.Println("Automation agent stopped")
}

// runControlLoop polls for policy and runs all enabled controllers on each cycle.
func runControlLoop(
	ctx context.Context,
	cfg *config.Config,
	policyClient *policy.Client,
	push *pusher.Pusher,
	evictor *controller.Evictor,
	rightsizer *controller.Rightsizer,
	rebalancer *controller.Rebalancer,
	hpaMgr *controller.HPAManager,
	vpaMgr *controller.VPAManager,
	nodeOpt *controller.NodeOptimizer,
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
			runCycle(ctx, cfg, policyClient, push, evictor, rightsizer, rebalancer, hpaMgr, vpaMgr, nodeOpt)
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
	hpaMgr *controller.HPAManager,
	vpaMgr *controller.VPAManager,
	nodeOpt *controller.NodeOptimizer,
) {
	log.Println("── Automation cycle start ──────────────────────────────────────")

	// 1. Fetch current policy from XamOps backend
	bundle, err := policyClient.FetchPolicy(ctx)
	if err != nil {
		log.Printf("ERROR: fetch policy: %v", err)
		return
	}

	if bundle.AutoscalerMode == "disabled" {
		log.Println("Automation mode=disabled — idle this cycle")
		return
	}

	var batches []pusher.Batch
	events := make([]policy.AutoscalingEvent, 0)

	// ── 1. Rebalancer (highest priority — changes node topology) ─────────────
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

	// ── 2. Evictor (bin-packing scale-in) ────────────────────────────────────
	evictorEnabled := cfg.EnableEvictor && bundle.EvictorPolicy.Enabled
	if bundle.AutoscalerMode == "scoped" {
		evictorEnabled = false // scoped mode: workload controllers only
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
			batches = append(batches, pusher.Batch{
				Collector:   "autoscaling-evictor-candidates",
				Items:       result.CandidateNodes,
				Count:       len(result.CandidateNodes),
				CollectedAt: time.Now(),
			})
		}
	}

	// ── 3. Rightsizer (workload CPU/memory request patching) ─────────────────
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
			if applied := countApplied(results); applied > 0 {
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

	// ── 4. HPA Manager (analyse + optionally create/update HPAs) ─────────────
	if cfg.EnableHPAManager && bundle.HPAManagerPolicy.Enabled {
		recs, err := hpaMgr.Analyse(ctx, bundle.SafetyGuards)
		if err != nil {
			log.Printf("ERROR: hpa-manager analyse: %v", err)
		}
		if len(recs) > 0 {
			batches = append(batches, pusher.Batch{
				Collector:   "hpa-recommendations",
				Items:       recs,
				Count:       len(recs),
				CollectedAt: time.Now(),
			})
		}
	}

	// ── 5. VPA Manager (collect existing VPA recommendations) ────────────────
	if cfg.EnableVPAManager && bundle.VPAManagerPolicy.Enabled {
		recs, err := vpaMgr.CollectRecommendations(ctx)
		if err != nil {
			log.Printf("WARN: vpa-manager collect: %v", err)
		} else if len(recs) > 0 {
			batches = append(batches, pusher.Batch{
				Collector:   "vpa-recommendations",
				Items:       recs,
				Count:       len(recs),
				CollectedAt: time.Now(),
			})
		}
	}

	// ── 6. Node Optimizer (idle detection, spot analysis, interruption handling)
	if cfg.EnableNodeOptimizer && bundle.NodeOptimizerPolicy.Enabled {
		report, err := nodeOpt.Analyse(ctx, bundle.SafetyGuards)
		if err != nil {
			log.Printf("ERROR: node-optimizer analyse: %v", err)
		}
		if report != nil {
			batches = append(batches, pusher.Batch{
				Collector:   "node-optimizer-report",
				Items:       []interface{}{report},
				Count:       1,
				CollectedAt: time.Now(),
			})

			// Auto-cordon idle nodes if policy says so
			if bundle.NodeOptimizerPolicy.AutoCordonIdleNodes {
				for _, idle := range report.IdleNodes {
					if idle.RecommendDrain {
						if err := nodeOpt.CordonIdleNode(ctx, idle.NodeName); err != nil {
							log.Printf("WARN: cordon idle node %s: %v", idle.NodeName, err)
						} else {
							events = append(events, policy.AutoscalingEvent{
								ID:        fmt.Sprintf("node-opt-cordon-%d", time.Now().Unix()),
								Timestamp: time.Now().UTC().Format(time.RFC3339),
								Type:      "scale-in",
								Severity:  "info",
								Message:   fmt.Sprintf("Cordoned idle node %s (CPU=%.1f%% Mem=%.1f%%)", idle.NodeName, idle.CPUUsagePct, idle.MemUsagePct),
								NodeCount: 1,
							})
						}
					}
				}
			}

			// Auto-drain idle nodes (PDB-aware safe drain, one at a time)
			if bundle.NodeOptimizerPolicy.AutoDrainIdleNodes {
				drainReports := make([]controller.DrainProgressReport, 0)
				for _, idle := range report.IdleNodes {
					if !idle.RecommendDrain {
						continue
					}
					drainCtx, cancel := context.WithTimeout(ctx, time.Duration(cfg.DrainTimeoutSeconds)*time.Second)
					drainRpt := nodeOpt.DrainForSpotConversion(drainCtx, idle.NodeName, bundle.SafetyGuards, cfg.DrainTimeoutSeconds)
					cancel()
					drainReports = append(drainReports, *drainRpt)
					if drainRpt.Status == "drained" {
						events = append(events, policy.AutoscalingEvent{
							ID:        fmt.Sprintf("idle-drain-%d", time.Now().Unix()),
							Timestamp: time.Now().UTC().Format(time.RFC3339),
							Type:      "scale-in",
							Severity:  "success",
							Message:   fmt.Sprintf("Drained idle node %s (%d pods evicted)", idle.NodeName, drainRpt.PodsEvicted),
							NodeCount: 1,
						})
					} else if drainRpt.Status == "failed" {
						events = append(events, policy.AutoscalingEvent{
							ID:        fmt.Sprintf("idle-drain-fail-%d", time.Now().Unix()),
							Timestamp: time.Now().UTC().Format(time.RFC3339),
							Type:      "scale-in",
							Severity:  "warning",
							Message:   fmt.Sprintf("Drain failed for idle node %s: %s", idle.NodeName, drainRpt.Error),
							NodeCount: 0,
						})
					}
				}
				if len(drainReports) > 0 {
					batches = append(batches, pusher.Batch{
						Collector:   "node-drain-progress",
						Items:       drainReports,
						Count:       len(drainReports),
						CollectedAt: time.Now(),
					})
				}
			}

			// ── Spot ratio management (drain on-demand → let CA/Karpenter provision spot)
			if bundle.NodeOptimizerPolicy.SpotRatioTargetPct > 0 {
				maxDrains := bundle.NodeOptimizerPolicy.MaxParallelDrains
				if maxDrains <= 0 {
					maxDrains = 1
				}
				candidates := nodeOpt.SelectOnDemandNodesForDrain(report, bundle.NodeOptimizerPolicy.SpotRatioTargetPct, maxDrains)
				if len(candidates) > 0 {
					log.Printf("NodeOptimizer: spot ratio target=%d%% current=%d/%d — draining %d on-demand node(s)",
						bundle.NodeOptimizerPolicy.SpotRatioTargetPct,
						report.SpotNodes, report.TotalNodes, len(candidates))

					// Annotate Karpenter NodePools if enabled
					if bundle.NodeOptimizerPolicy.KarpenterEnabled {
						if err := nodeOpt.PatchKarpenterSpotRatio(ctx, bundle.NodeOptimizerPolicy.SpotRatioTargetPct); err != nil {
							log.Printf("WARN: Karpenter NodePool patch: %v", err)
						}
					}

					spotDrainReports := make([]controller.DrainProgressReport, 0, len(candidates))
					for _, nodeName := range candidates {
						drainCtx, cancel := context.WithTimeout(ctx, time.Duration(cfg.DrainTimeoutSeconds)*time.Second)
						rpt := nodeOpt.DrainForSpotConversion(drainCtx, nodeName, bundle.SafetyGuards, cfg.DrainTimeoutSeconds)
						cancel()
						spotDrainReports = append(spotDrainReports, *rpt)
						if rpt.Status == "drained" {
							events = append(events, policy.AutoscalingEvent{
								ID:        fmt.Sprintf("spot-convert-%d", time.Now().Unix()),
								Timestamp: time.Now().UTC().Format(time.RFC3339),
								Type:      "node-replace",
								Severity:  "info",
								Message:   fmt.Sprintf("Drained on-demand node %s for spot conversion (%d pods evicted)", nodeName, rpt.PodsEvicted),
								NodeCount: 1,
							})
						} else if rpt.Status == "failed" {
							events = append(events, policy.AutoscalingEvent{
								ID:        fmt.Sprintf("spot-convert-fail-%d", time.Now().Unix()),
								Timestamp: time.Now().UTC().Format(time.RFC3339),
								Type:      "node-replace",
								Severity:  "warning",
								Message:   fmt.Sprintf("Spot conversion drain failed for %s: %s", nodeName, rpt.Error),
							})
						}
					}
					if len(spotDrainReports) > 0 {
						batches = append(batches, pusher.Batch{
							Collector:   "node-drain-progress",
							Items:       spotDrainReports,
							Count:       len(spotDrainReports),
							CollectedAt: time.Now(),
						})
					}
				}
			}

			// Handle spot interruption notices
			if bundle.NodeOptimizerPolicy.HandleSpotInterruptions {
				for _, nodeName := range report.InterruptedNodes {
					action, err := nodeOpt.HandleSpotInterruption(ctx, nodeName, bundle.SafetyGuards)
					if err != nil {
						log.Printf("ERROR: spot interruption handler %s: %v", nodeName, err)
					} else {
						events = append(events, policy.AutoscalingEvent{
							ID:        fmt.Sprintf("spot-interrupt-%d", time.Now().Unix()),
							Timestamp: time.Now().UTC().Format(time.RFC3339),
							Type:      "spot-fallback",
							Severity:  "warning",
							Message:   fmt.Sprintf("Spot interruption: cordoned+drained node %s (status=%s)", action.NodeName, action.Status),
							NodeCount: 1,
						})
					}
				}
			}
		}
	}

	// ── Push events ───────────────────────────────────────────────────────────
	if len(events) > 0 {
		batches = append(batches, pusher.Batch{
			Collector:   "autoscaling-events",
			Items:       events,
			Count:       len(events),
			CollectedAt: time.Now(),
		})
	}

	// ── Send all batches to XamOps ────────────────────────────────────────────
	if len(batches) > 0 {
		if err := push.Push(batches); err != nil {
			log.Printf("ERROR: push results: %v", err)
		} else {
			log.Printf("Pushed %d batch(es) to XamOps", len(batches))
		}
	}

	log.Println("── Automation cycle end ────────────────────────────────────────")
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
