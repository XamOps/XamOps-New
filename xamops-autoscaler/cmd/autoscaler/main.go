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

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	metricsv "k8s.io/metrics/pkg/client/clientset/versioned"

	"github.com/xamops/automation/internal/config"
	"github.com/xamops/automation/internal/controller"
	"github.com/xamops/automation/internal/drain"
	"github.com/xamops/automation/internal/fitsim"
	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/policy"
	"github.com/xamops/automation/internal/pusher"
	"github.com/xamops/automation/internal/state"
	"github.com/xamops/automation/internal/telemetry"
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
	log.Printf("Controllers — Evictor=%v Rightsizer=%v RightsizerApply=%v Rebalancer=%v HPAManager=%v VPAManager=%v NodeOptimizer=%v DryRun=%v PodNamespace=%s StateConfigMap=%s",
		cfg.EnableEvictor, cfg.EnableRightsizer, cfg.EnableRightsizerApply, cfg.EnableRebalancer,
		cfg.EnableHPAManager, cfg.EnableVPAManager, cfg.EnableNodeOptimizer,
		cfg.DryRun, cfg.PodNamespace, cfg.StateConfigMap)

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
	go runControlLoop(ctx, cfg, k8s, policyClient, push, evictor, rightsizer, rebalancer, hpaMgr, vpaMgr, nodeOpt)

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
	k8s kubernetes.Interface,
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
			runCycle(ctx, cfg, k8s, policyClient, push, evictor, rightsizer, rebalancer, hpaMgr, vpaMgr, nodeOpt)
		}
	}
}

// cycleActions collects the action records of one cycle and their legacy events.
type cycleActions struct {
	records []policy.ActionRecord
	events  []policy.AutoscalingEvent
}

// add records each action and its paired legacy event (same ID). nodeCount is the
// node count of a verified outcome; LegacyEvent zeroes it for anything else.
func (c *cycleActions) add(eventType string, nodeCount int, recs ...policy.ActionRecord) {
	for _, r := range recs {
		c.records = append(c.records, r)
		c.events = append(c.events, telemetry.LegacyEvent(r, eventType, nodeCount))
	}
}

func (c *cycleActions) addOutcome(eventType string, o drain.Outcome) {
	c.add(eventType, len(o.Reports), o.Actions...)
}

// addRightsizer records the rightsizer's per-container actions. Simulated, refused,
// failed and unverified records get their own honest legacy event. Verified live
// patches are recorded individually and summarised in ONE event, "Rightsizer patched
// N Deployment template(s) — rollout pending", backed by a summary record (same ID).
func (c *cycleActions) addRightsizer(recs []policy.ActionRecord) {
	var verified []policy.ActionRecord
	for _, r := range recs {
		if telemetry.VerifiedLive(r) {
			verified = append(verified, r)
			continue
		}
		c.add("workload-resize", 0, r)
	}
	if len(verified) == 0 {
		return
	}
	deployments := map[string]bool{}
	ids := make([]string, 0, len(verified))
	for _, r := range verified {
		c.records = append(c.records, r)
		ids = append(ids, r.ID)
		if w, ok := r.Details["workload"].(string); ok {
			deployments[w] = true
		}
	}
	n := len(deployments)
	sum := telemetry.NewAction(policy.ControllerRightsizer, "patch-requests-summary",
		fmt.Sprintf("%d Deployment template(s)", n), false)
	telemetry.SetDetail(&sum, "actionIds", ids)
	sum.Verification = "Deployment templates carry the new requests; rollout pending"
	sum = telemetry.Finish(sum, policy.ActionSucceeded, true, "")
	ev := telemetry.LegacyEvent(sum, "workload-resize", 0)
	ev.Message = fmt.Sprintf("Rightsizer patched %d Deployment template(s) — rollout pending", n)
	c.records = append(c.records, sum)
	c.events = append(c.events, ev)
}

// cycleDecisions are all gate decisions of one cycle, computed once.
type cycleDecisions struct {
	Rebalancer, Evictor, NodeOptimizer, Cordon, IdleDrain, SpotRatio, Karpenter, Interruption,
	Rightsizer, RightsizerApply, HPAManager gate.Decision
}

func decide(cfg *config.Config, b policy.PolicyBundle) cycleDecisions {
	nop := b.NodeOptimizerPolicy
	return cycleDecisions{
		Rebalancer:      gate.Rebalancer(cfg, b),
		Evictor:         gate.Evictor(cfg, b),
		NodeOptimizer:   gate.NodeOptimizerAnalysis(cfg, b),
		Cordon:          gate.NodeAction(cfg, b, nop.AutoCordonIdleNodes, "autoCordonIdleNodes"),
		IdleDrain:       gate.NodeAction(cfg, b, nop.AutoDrainIdleNodes, "autoDrainIdleNodes"),
		SpotRatio:       gate.NodeAction(cfg, b, nop.SpotRatioTargetPct > 0, "spotRatioTargetPct"),
		Karpenter:       gate.NodeAction(cfg, b, nop.SpotRatioTargetPct > 0 && nop.KarpenterEnabled, "karpenterEnabled"),
		Interruption:    gate.NodeAction(cfg, b, nop.HandleSpotInterruptions, "handleSpotInterruptions"),
		Rightsizer:      gate.RightsizerAnalysis(cfg, b),
		RightsizerApply: gate.RightsizerApply(cfg, b),
		HPAManager:      gate.HPAManager(cfg, b),
	}
}

// nodeActionMode is the strongest mode of any node-action gate: orphan recovery
// may write only when some node action is Live.
func (d cycleDecisions) nodeActionMode() gate.Mode {
	m := gate.Off
	for _, x := range []gate.Decision{d.Rebalancer, d.Evictor, d.Cordon, d.IdleDrain, d.SpotRatio, d.Interruption} {
		if x.Mode == gate.Live {
			return gate.Live
		}
		if x.Mode == gate.Simulate {
			m = gate.Simulate
		}
	}
	return m
}

func runCycle(
	ctx context.Context,
	cfg *config.Config,
	k8s kubernetes.Interface,
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

	// 1. Fetch current policy from XamOps backend (normalized by the client)
	bundle, err := policyClient.FetchPolicy(ctx)
	if err != nil {
		log.Printf("ERROR: fetch policy: %v", err)
		return
	}

	if bundle.AutoscalerMode == "disabled" {
		log.Println("Automation mode=disabled — idle this cycle")
		return
	}

	// 2. Gate decisions, once per cycle
	d := decide(cfg, bundle)
	log.Printf("Gates — rebalancer=%s | evictor=%s | nodeopt=%s cordon=%s idleDrain=%s spotRatio=%s karpenter=%s interruption=%s | rightsizer=%s apply=%s | hpa=%s",
		d.Rebalancer, d.Evictor, d.NodeOptimizer, d.Cordon, d.IdleDrain, d.SpotRatio, d.Karpenter, d.Interruption,
		d.Rightsizer, d.RightsizerApply, d.HPAManager)

	// 3. Per-cycle drain machinery: one global budget, one executor, one ledger.
	var inflight []string
	if nodes, err := k8s.CoreV1().Nodes().List(ctx, metav1.ListOptions{}); err != nil {
		log.Printf("WARN: list nodes for drain budget: %v", err)
	} else {
		inflight = drain.InflightNodes(nodes.Items)
	}
	budget := drain.NewBudget(bundle.NodeOptimizerPolicy.MaxParallelDrains, inflight)
	planner := drain.NewFitPlanner(k8s, fitsim.Options{Guards: bundle.SafetyGuards, SelfNamespace: cfg.PodNamespace})
	exec := drain.NewExecutor(k8s, planner, cfg.PodNamespace)
	ledger := state.New(k8s, cfg.PodNamespace, cfg.StateConfigMap, cfg.DryRun)
	log.Printf("Drain budget capacity=%d inflight=%v planner=fitsim selfNamespace=%s",
		budget.Capacity(), inflight, cfg.PodNamespace)

	var batches []pusher.Batch
	acts := &cycleActions{}
	var drainReports []controller.DrainProgressReport

	// Orphaned drains from an earlier process are handled before anything else.
	acts.add("scale-in", 1, exec.RecoverOrphans(ctx, d.nodeActionMode())...)

	// ── 1. Rebalancer (highest priority — changes node topology) ─────────────
	armed := cfg.EnableRebalancer && bundle.AutoscalerMode == "full" && bundle.Rebalancer.Enabled
	if err := controller.ObserveRebalancerStatus(ctx, ledger, armed, bundle.Rebalancer.Status); err != nil {
		log.Printf("ERROR: rebalancer ledger: %v", err)
	}
	if d.Rebalancer.Mode != gate.Off {
		result, recs, err := rebalancer.Run(ctx, bundle.Rebalancer, bundle.SafetyGuards, d.Rebalancer, budget, exec, ledger)
		if err != nil {
			log.Printf("ERROR: rebalancer: %v", err)
		}
		acts.add("rebalance", 1, recs...)
		if result != nil {
			status := policy.RebalanceStatus{
				Status:          result.Status,
				StartedAt:       result.StartedAt.Format(time.RFC3339),
				NodesProcessed:  len(recs),
				NodesDrained:    len(result.NodesDrained),
				DurationMinutes: result.DurationMinutes,
				Error:           result.Error,
				DryRun:          result.DryRun,
				OperationID:     result.OperationID,
				NodesSimulated:  result.NodesSimulated,
				Verified:        result.Verified,
				Reason:          result.Reason,
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
		}
	}

	// ── 2. Evictor (utilisation-threshold scale-in through the executor) ─────
	if d.Evictor.Mode != gate.Off {
		result, recs, err := evictor.Run(ctx, bundle.EvictorPolicy, bundle.SafetyGuards, d.Evictor, budget, exec)
		if err != nil {
			log.Printf("ERROR: evictor: %v", err)
		}
		acts.add("scale-in", 1, recs...)
		if result != nil && len(result.CandidateNodes) > 0 {
			batches = append(batches, pusher.Batch{
				Collector:   "autoscaling-evictor-candidates",
				Items:       result.CandidateNodes,
				Count:       len(result.CandidateNodes),
				CollectedAt: time.Now(),
			})
		}
	}

	// ── 3. Rightsizer (floor clamp; apply only when gate.RightsizerApply is Live) ─
	if d.Rightsizer.Mode != gate.Off {
		results, recs, err := rightsizer.Run(ctx, bundle.WorkloadRightsizerPolicy, bundle.SafetyGuards, d.RightsizerApply)
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
		}
		acts.addRightsizer(recs)
	}

	// ── 4. HPA Manager (analysis only) ───────────────────────────────────────
	if d.HPAManager.Mode != gate.Off {
		recs, err := hpaMgr.Analyse(ctx, bundle.HPAManagerPolicy, bundle.SafetyGuards, cfg.DryRun)
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

	// ── 6. Node Optimizer (analysis; node actions via gate + budget + executor)
	if d.NodeOptimizer.Mode != gate.Off {
		report, err := nodeOpt.Analyse(ctx, bundle.SafetyGuards)
		if err != nil {
			log.Printf("ERROR: node-optimizer analyse: %v", err)
		}
		if report != nil {
			report.DryRun = cfg.DryRun
			batches = append(batches, pusher.Batch{
				Collector:   "node-optimizer-report",
				Items:       []interface{}{report},
				Count:       1,
				CollectedAt: time.Now(),
			})
			drainTimeout := time.Duration(cfg.DrainTimeoutSeconds) * time.Second
			// drainSet: candidates (deterministic order) -> fit-simulator selection within
			// the shared budget -> one set drain; the others are recorded refused/skipped.
			drainSet := func(eventType, trigger string, nodes []string, maxNodes int, mode gate.Mode) {
				if len(nodes) == 0 {
					return
				}
				dctx, cancel := context.WithTimeout(ctx, drainTimeout)
				defer cancel()
				res := nodeOpt.DrainNodes(dctx, trigger, nodes, maxNodes, mode, budget, exec)
				for _, out := range res.Outcomes() {
					acts.addOutcome(eventType, out)
				}
				drainReports = append(drainReports, res.Reports()...)
			}

			// Idle drain (budget order: idle drain, spot-ratio drain, interruption, cordon)
			if d.IdleDrain.Mode != gate.Off {
				var idle []string // report.IdleNodes is sorted by name
				for _, n := range report.IdleNodes {
					if n.RecommendDrain {
						idle = append(idle, n.NodeName)
					}
				}
				drainSet("scale-in", "idle-drain", idle, 0, d.IdleDrain.Mode)
			}

			// Spot ratio management (drain on-demand → let CA/Karpenter provision spot)
			if d.SpotRatio.Mode != gate.Off {
				candidates, needed := nodeOpt.SpotRatioDrainCandidates(report, bundle.NodeOptimizerPolicy.SpotRatioTargetPct)
				if len(candidates) > 0 {
					log.Printf("NodeOptimizer: spot ratio target=%d%% current=%d/%d — need %d, on-demand candidate(s) %v (%s)",
						bundle.NodeOptimizerPolicy.SpotRatioTargetPct, report.SpotNodes, report.TotalNodes,
						needed, candidates, d.SpotRatio.Mode)
					if d.Karpenter.Mode != gate.Off {
						acts.add("node-replace", 0, nodeOpt.PatchKarpenterSpotRatio(ctx, bundle.NodeOptimizerPolicy.SpotRatioTargetPct, d.Karpenter.Mode))
					}
					drainSet("node-replace", "spot-ratio-drain", candidates, needed, d.SpotRatio.Mode)
				}
			}

			// Spot interruption notices (same simulator, executor and guards; never "evict anyway")
			if d.Interruption.Mode != gate.Off {
				drainSet("spot-fallback", "spot-interruption", report.InterruptedNodes, 0, d.Interruption.Mode)
			}

			// Auto-cordon idle nodes
			if d.Cordon.Mode != gate.Off {
				for _, idle := range report.IdleNodes {
					if idle.RecommendDrain {
						out := nodeOpt.CordonIdleNode(ctx, idle.NodeName, d.Cordon.Mode, budget, exec)
						acts.addOutcome("scale-in", out)
					}
				}
			}
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

	// ── Push actions + their honest legacy events ─────────────────────────────
	if len(acts.records) > 0 {
		batches = append(batches, pusher.Batch{
			Collector:   "autoscaling-actions",
			Items:       acts.records,
			Count:       len(acts.records),
			CollectedAt: time.Now(),
		})
	}
	if len(acts.events) > 0 {
		batches = append(batches, pusher.Batch{
			Collector:   "autoscaling-events",
			Items:       acts.events,
			Count:       len(acts.events),
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
