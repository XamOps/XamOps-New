package policy

import "fmt"

// PolicyBundle mirrors EksAutoscalingService.PolicyBundle on the backend.
// The autoscaler reads this from /api/v1/agent/autoscaling-policy to know
// what mode to run in and what the current user-configured settings are.
type PolicyBundle struct {
	AutoscalerMode           string                   `json:"autoscalerMode"` // full | scoped | disabled
	ScaleOutPolicy           ScaleOutPolicy           `json:"scaleOutPolicy"`
	EvictorPolicy            EvictorPolicy            `json:"evictorPolicy"`
	WorkloadRightsizerPolicy WorkloadRightsizerPolicy `json:"workloadRightsizerPolicy"`
	HPAManagerPolicy         HPAManagerPolicy         `json:"hpaManagerPolicy"`
	VPAManagerPolicy         VPAManagerPolicy         `json:"vpaManagerPolicy"`
	NodeOptimizerPolicy      NodeOptimizerPolicy      `json:"nodeOptimizerPolicy"`
	NodeTemplates            []NodeTemplate           `json:"nodeTemplates"`
	Rebalancer               RebalancerConfig         `json:"rebalancer"`
	SafetyGuards             SafetyGuards             `json:"safetyGuards"`
}

type ScaleOutPolicy struct {
	Enabled                     bool `json:"enabled"`
	HeadroomCpuPercent          int  `json:"headroomCpuPercent"`
	HeadroomMemoryPercent       int  `json:"headroomMemoryPercent"`
	MinCpuCores                 int  `json:"minCpuCores"`
	MaxCpuCores                 int  `json:"maxCpuCores"`
	MinRamMib                   int  `json:"minRamMib"`
	MaxRamMib                   int  `json:"maxRamMib"`
	NodeReadinessTimeoutMinutes int  `json:"nodeReadinessTimeoutMinutes"`
}

type EvictorPolicy struct {
	Enabled bool `json:"enabled"`
	// Analyse mode: report consolidation candidates without cordoning or draining
	AnalyseOnly              bool `json:"analyseOnly"`
	AggressiveMode           bool `json:"aggressiveMode"`
	NodeCycleIntervalSeconds int  `json:"nodeCycleIntervalSeconds"`
	NodeGracePeriodMinutes   int  `json:"nodeGracePeriodMinutes"`
	TargetUtilizationCpu     int  `json:"targetUtilizationCpu"`
	TargetUtilizationMemory  int  `json:"targetUtilizationMemory"`
}

type WorkloadRightsizerPolicy struct {
	Enabled               bool   `json:"enabled"`
	CpuPercentile         int    `json:"cpuPercentile"`
	MemoryPercentile      int    `json:"memoryPercentile"`
	CpuOverheadPercent    int    `json:"cpuOverheadPercent"`
	MemoryOverheadPercent int    `json:"memoryOverheadPercent"`
	ApplyThresholdPercent int    `json:"applyThresholdPercent"`
	LookbackHours         int    `json:"lookbackHours"`
	ApplyMode             string `json:"applyMode"` // immediate | deferred
	SurgeDetection        bool   `json:"surgeDetection"`
	// Floors of the hard clamp (plan.md D9): a request is never decreased below them,
	// and never raised to them. ≤0 → default (25m / 250Mi, upstream VPA minimums).
	MinCpuMillicores int `json:"minCpuMillicores"`
	MinMemoryMib     int `json:"minMemoryMib"`
}

// Rightsizer clamp floor defaults (upstream VPA recommender minimums).
const (
	DefaultMinCpuMillicores = 25
	DefaultMinMemoryMib     = 250
)

type NodeTemplate struct {
	ID                     string              `json:"id"`
	Name                   string              `json:"name"`
	IsDefault              bool                `json:"isDefault"`
	CapacityType           string              `json:"capacityType"` // on-demand | spot | mixed
	UseSpotFallback        bool                `json:"useSpotFallback"`
	FallbackRestoreRateSec int                 `json:"fallbackRestoreRateSeconds"`
	MinCpuCores            int                 `json:"minCpuCores"`
	MaxCpuCores            int                 `json:"maxCpuCores"`
	MinRamMib              int                 `json:"minRamMib"`
	MaxRamMib              int                 `json:"maxRamMib"`
	Architectures          []string            `json:"architectures"`
	SpotAutomationEnabled  bool                `json:"spotAutomationEnabled"`
	CustomLabels           map[string]string   `json:"customLabels"`
	CustomTaints           []map[string]string `json:"customTaints"`
}

type RebalancerConfig struct {
	Enabled               bool    `json:"enabled"`
	GracefulDraining      bool    `json:"gracefulDraining"`
	DrainTimeoutMinutes   int     `json:"drainTimeoutMinutes"`
	ScheduleEnabled       bool    `json:"scheduleEnabled"`
	ScheduleCron          string  `json:"scheduleCron"`
	LastRebalanceAt       string  `json:"lastRebalanceAt"`
	LastRebalanceSavings  float64 `json:"lastRebalanceSavingsPercent"`
	LastRebalanceDuration int     `json:"lastRebalanceDuration"`
	Status                string  `json:"status"` // idle | running | failed | completed
	// OperationID optionally names one rebalance operation. When empty the autoscaler
	// derives "gen-<N>" from its ledger (N increments on each observed
	// non-running -> running edge). A live outcome for an ID is terminal: the same
	// operation is never re-run, a new run needs a new ID or a new running edge.
	OperationID string `json:"operationId"`
}

type SafetyGuards struct {
	RespectPodDisruptionBudgets bool     `json:"respectPodDisruptionBudgets"`
	ProtectSingleReplicas       bool     `json:"protectSingleReplicas"`
	ProtectStatefulSets         bool     `json:"protectStatefulSets"`
	ProtectCriticalNamespaces   bool     `json:"protectCriticalNamespaces"`
	CriticalNamespaces          []string `json:"criticalNamespaces"`
}

// HPAManagerPolicy controls HPA creation/management behavior.
type HPAManagerPolicy struct {
	Enabled bool `json:"enabled"`
	// Analyse mode: generate recommendations without creating HPAs
	AnalyseOnly bool `json:"analyseOnly"`
	// Default bounds applied when XamOps creates an HPA
	DefaultMinReplicas  int `json:"defaultMinReplicas"`  // default 1
	DefaultMaxReplicas  int `json:"defaultMaxReplicas"`  // default 10
	DefaultTargetCPUPct int `json:"defaultTargetCpuPct"` // default 80
	// Skip namespaces (additional to SafetyGuards.CriticalNamespaces)
	ExcludeNamespaces []string `json:"excludeNamespaces,omitempty"`
}

// VPAManagerPolicy controls VPA object management behavior.
type VPAManagerPolicy struct {
	Enabled bool `json:"enabled"`
	// Default update mode for newly created VPA objects.
	// "Off" = recommendations only, no pod restarts.
	// "Initial" = apply on pod creation only.
	// "Recreate" / "Auto" = apply with pod restarts.
	UpdateMode string `json:"updateMode"` // Off | Initial | Recreate | Auto
	// If true, only collect existing VPA recommendations without creating new VPAs.
	AnalyseOnly bool `json:"analyseOnly"`
}

// NodeOptimizerPolicy controls node idle detection and spot management.
type NodeOptimizerPolicy struct {
	Enabled bool `json:"enabled"`
	// CPU threshold below which a node is considered idle (percent)
	IdleCPUThresholdPct float64 `json:"idleCpuThresholdPct"` // default 10
	// Memory threshold below which a node is considered idle (percent)
	IdleMemThresholdPct float64 `json:"idleMemThresholdPct"` // default 20
	// Automatically cordon idle nodes (still requires manual drain approval via UI)
	AutoCordonIdleNodes bool `json:"autoCordonIdleNodes"`
	// Automatically drain (not just cordon) idle nodes — PDB-aware safe drain
	AutoDrainIdleNodes bool `json:"autoDrainIdleNodes"`
	// Automatically handle spot interruption notices (cordon + drain within 2-min AWS window)
	HandleSpotInterruptions bool `json:"handleSpotInterruptions"`
	// Target percentage of nodes to run as spot (0 = don't manage spot ratio).
	// e.g. 60 means XamOps will try to maintain 60% spot nodes by safely draining
	// on-demand nodes one at a time, allowing Karpenter/CA to provision replacements.
	SpotRatioTargetPct int `json:"spotRatioTargetPct"`
	// Whether Karpenter is the cluster autoscaler (enables NodePool CRD patching)
	KarpenterEnabled bool `json:"karpenterEnabled"`
	// Maximum nodes to drain in parallel for spot conversion (safety limit, default 1)
	MaxParallelDrains int `json:"maxParallelDrains"`
}

// WorkloadRightsizingResult is what the autoscaler pushes back (collector: "autoscaling-rightsizing")
type WorkloadRightsizingResult struct {
	Namespace         string  `json:"namespace"`
	WorkloadName      string  `json:"workloadName"`
	WorkloadKind      string  `json:"workloadKind"` // Deployment | StatefulSet | DaemonSet
	ContainerName     string  `json:"containerName"`
	CurrentCPURequest string  `json:"currentCpuRequest"` // e.g. "200m"
	CurrentMemRequest string  `json:"currentMemRequest"` // e.g. "256Mi"
	RecommendedCPU    string  `json:"recommendedCpu"`
	RecommendedMem    string  `json:"recommendedMem"`
	P80CPU            float64 `json:"p80Cpu"`  // millicores
	P100Mem           float64 `json:"p100Mem"` // bytes
	EstimatedSavings  float64 `json:"estimatedSavingsPercent"`
	Applied           bool    `json:"applied"`
	AppliedAt         string  `json:"appliedAt,omitempty"`
	Reason            string  `json:"reason,omitempty"` // why skipped if not applied
}

// AutoscalingEvent pushed back to backend (collector: "autoscaling-events").
//
// The key set is FROZEN: the backend parses this collector into AutoscalingEventDto
// with a plain ObjectMapper (EksAutoscalingService.java:93, readEvents :511), so one
// extra key makes it discard the whole activity feed. New data (dryRun, verified, …)
// goes into the paired ActionRecord ("autoscaling-actions") and into Details as JSON
// text. Contract test: internal/telemetry/telemetry_test.go.
type AutoscalingEvent struct {
	ID           string  `json:"id"`
	Timestamp    string  `json:"timestamp"`
	Type         string  `json:"type"`     // scale-out | scale-in | rebalance | workload-resize | spot-fallback | node-replace
	Severity     string  `json:"severity"` // info | warning | success
	Message      string  `json:"message"`
	Details      string  `json:"details,omitempty"`
	NodeCount    int     `json:"nodeCount,omitempty"`
	SavingsDelta float64 `json:"savingsDelta,omitempty"`
}

// RebalanceStatus pushed back (collector: "autoscaling-rebalance-status")
type RebalanceStatus struct {
	Status          string  `json:"status"` // running | completed | failed | idle
	StartedAt       string  `json:"startedAt"`
	CompletedAt     string  `json:"completedAt,omitempty"`
	NodesProcessed  int     `json:"nodesProcessed"`
	NodesDrained    int     `json:"nodesDrained"`
	SavingsPercent  float64 `json:"savingsPercent"`
	DurationMinutes int     `json:"durationMinutes"`
	Error           string  `json:"error,omitempty"`
	// The backend reads this collector as a Map (lenient), so new keys are safe.
	// Status additionally takes simulated | refused.
	DryRun         bool   `json:"dryRun"`
	OperationID    string `json:"operationId,omitempty"`
	NodesSimulated int    `json:"nodesSimulated"`
	Verified       bool   `json:"verified"`
	Reason         string `json:"reason,omitempty"`
}

// ActionRecord statuses.
const (
	ActionSimulated = "simulated"
	ActionSucceeded = "succeeded"
	ActionFailed    = "failed"
	ActionRefused   = "refused"
	ActionSkipped   = "skipped"
	ActionAborted   = "aborted"
)

// Controller names used in ActionRecord.Controller.
const (
	ControllerRebalancer    = "rebalancer"
	ControllerEvictor       = "evictor"
	ControllerNodeOptimizer = "node-optimizer"
	ControllerRightsizer    = "rightsizer"
	ControllerHPAManager    = "hpa-manager"
	ControllerVPAManager    = "vpa-manager"
)

// ActionRecord is one action or simulation, pushed as collector "autoscaling-actions".
// Every legacy AutoscalingEvent has a paired record with the same ID. DryRun and
// Verified are always serialized (no omitempty) so a reader can never mistake a
// simulation or an unverified request for a verified live outcome.
type ActionRecord struct {
	ID           string                 `json:"id"`
	Controller   string                 `json:"controller"`
	Action       string                 `json:"action"`
	Target       string                 `json:"target"`
	DryRun       bool                   `json:"dryRun"`
	Status       string                 `json:"status"` // simulated | succeeded | failed | refused | skipped | aborted
	Verified     bool                   `json:"verified"`
	Verification string                 `json:"verification,omitempty"`
	OperationID  string                 `json:"operationId,omitempty"`
	Reason       string                 `json:"reason,omitempty"`
	StartedAt    string                 `json:"startedAt"`
	FinishedAt   string                 `json:"finishedAt,omitempty"`
	Details      map[string]interface{} `json:"details,omitempty"`
}

// Normalize repairs values that would make the controllers unsafe or undefined and
// returns one human-readable warning per repair. It never injects or drops
// criticalNamespaces: an explicit list (even an empty one) is the operator's choice.
func (b *PolicyBundle) Normalize() []string {
	var warnings []string
	if b.NodeOptimizerPolicy.MaxParallelDrains <= 0 {
		warnings = append(warnings, fmt.Sprintf(
			"nodeOptimizerPolicy.maxParallelDrains=%d is not positive — using 1 (global drain budget per cycle)",
			b.NodeOptimizerPolicy.MaxParallelDrains))
		b.NodeOptimizerPolicy.MaxParallelDrains = 1
	}
	rs := &b.WorkloadRightsizerPolicy
	if rs.MinCpuMillicores <= 0 {
		warnings = append(warnings, fmt.Sprintf(
			"workloadRightsizerPolicy.minCpuMillicores=%d is not positive — using %d", rs.MinCpuMillicores, DefaultMinCpuMillicores))
		rs.MinCpuMillicores = DefaultMinCpuMillicores
	}
	if rs.MinMemoryMib <= 0 {
		warnings = append(warnings, fmt.Sprintf(
			"workloadRightsizerPolicy.minMemoryMib=%d is not positive — using %d", rs.MinMemoryMib, DefaultMinMemoryMib))
		rs.MinMemoryMib = DefaultMinMemoryMib
	}
	if b.SafetyGuards.ProtectCriticalNamespaces && len(b.SafetyGuards.CriticalNamespaces) == 0 {
		warnings = append(warnings,
			"safetyGuards.criticalNamespaces is empty — only the autoscaler's own namespace stays protected")
	}
	return warnings
}

// DefaultBundle returns a safe default PolicyBundle (used when backend not reachable).
func DefaultBundle() PolicyBundle {
	return PolicyBundle{
		AutoscalerMode: "disabled",
		EvictorPolicy: EvictorPolicy{
			Enabled:                  false,
			AnalyseOnly:              true, // safe default — recommendations only
			NodeCycleIntervalSeconds: 60,
			NodeGracePeriodMinutes:   5,
			TargetUtilizationCpu:     80,
			TargetUtilizationMemory:  80,
		},
		WorkloadRightsizerPolicy: WorkloadRightsizerPolicy{
			Enabled:               false,
			CpuPercentile:         80,
			MemoryPercentile:      100,
			CpuOverheadPercent:    10,
			MemoryOverheadPercent: 10,
			ApplyThresholdPercent: 5,
			LookbackHours:         72,
			ApplyMode:             "deferred",
			SurgeDetection:        true,
			MinCpuMillicores:      DefaultMinCpuMillicores,
			MinMemoryMib:          DefaultMinMemoryMib,
		},
		HPAManagerPolicy: HPAManagerPolicy{
			Enabled:             false,
			AnalyseOnly:         true, // safe default — recommendations only
			DefaultMinReplicas:  1,
			DefaultMaxReplicas:  10,
			DefaultTargetCPUPct: 80,
		},
		VPAManagerPolicy: VPAManagerPolicy{
			Enabled:     false,
			UpdateMode:  "Off", // recommendations only, no pod restarts
			AnalyseOnly: true,
		},
		NodeOptimizerPolicy: NodeOptimizerPolicy{
			Enabled:                 false,
			IdleCPUThresholdPct:     10,
			IdleMemThresholdPct:     20,
			AutoCordonIdleNodes:     false,
			AutoDrainIdleNodes:      false,
			HandleSpotInterruptions: false,
			SpotRatioTargetPct:      0, // off by default — user must opt in
			KarpenterEnabled:        false,
			MaxParallelDrains:       1, // safe default: one node at a time
		},
		SafetyGuards: SafetyGuards{
			RespectPodDisruptionBudgets: true,
			ProtectSingleReplicas:       true,
			ProtectStatefulSets:         true,
			ProtectCriticalNamespaces:   true,
			CriticalNamespaces:          []string{"kube-system", "monitoring", "ingress-nginx", "xamops-system"},
		},
		Rebalancer: RebalancerConfig{Status: "idle"},
	}
}
