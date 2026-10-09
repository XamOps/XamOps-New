package policy

// PolicyBundle mirrors EksAutoscalingService.PolicyBundle on the backend.
// The autoscaler reads this from /api/v1/agent/autoscaling-policy to know
// what mode to run in and what the current user-configured settings are.
type PolicyBundle struct {
	AutoscalerMode           string                    `json:"autoscalerMode"`          // full | scoped | disabled
	ScaleOutPolicy           ScaleOutPolicy            `json:"scaleOutPolicy"`
	EvictorPolicy            EvictorPolicy             `json:"evictorPolicy"`
	WorkloadRightsizerPolicy WorkloadRightsizerPolicy  `json:"workloadRightsizerPolicy"`
	HPAManagerPolicy         HPAManagerPolicy          `json:"hpaManagerPolicy"`
	VPAManagerPolicy         VPAManagerPolicy          `json:"vpaManagerPolicy"`
	NodeOptimizerPolicy      NodeOptimizerPolicy       `json:"nodeOptimizerPolicy"`
	NodeTemplates            []NodeTemplate            `json:"nodeTemplates"`
	Rebalancer               RebalancerConfig          `json:"rebalancer"`
	SafetyGuards             SafetyGuards              `json:"safetyGuards"`
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
	Enabled                 bool `json:"enabled"`
	// Analyse mode: report consolidation candidates without cordoning or draining
	AnalyseOnly             bool `json:"analyseOnly"`
	AggressiveMode          bool `json:"aggressiveMode"`
	NodeCycleIntervalSeconds int  `json:"nodeCycleIntervalSeconds"`
	NodeGracePeriodMinutes  int  `json:"nodeGracePeriodMinutes"`
	TargetUtilizationCpu    int  `json:"targetUtilizationCpu"`
	TargetUtilizationMemory int  `json:"targetUtilizationMemory"`
}

type WorkloadRightsizerPolicy struct {
	Enabled              bool   `json:"enabled"`
	CpuPercentile        int    `json:"cpuPercentile"`
	MemoryPercentile     int    `json:"memoryPercentile"`
	CpuOverheadPercent   int    `json:"cpuOverheadPercent"`
	MemoryOverheadPercent int   `json:"memoryOverheadPercent"`
	ApplyThresholdPercent int   `json:"applyThresholdPercent"`
	LookbackHours        int    `json:"lookbackHours"`
	ApplyMode            string `json:"applyMode"` // immediate | deferred
	SurgeDetection       bool   `json:"surgeDetection"`
}

type NodeTemplate struct {
	ID                     string            `json:"id"`
	Name                   string            `json:"name"`
	IsDefault              bool              `json:"isDefault"`
	CapacityType           string            `json:"capacityType"` // on-demand | spot | mixed
	UseSpotFallback        bool              `json:"useSpotFallback"`
	FallbackRestoreRateSec int               `json:"fallbackRestoreRateSeconds"`
	MinCpuCores            int               `json:"minCpuCores"`
	MaxCpuCores            int               `json:"maxCpuCores"`
	MinRamMib              int               `json:"minRamMib"`
	MaxRamMib              int               `json:"maxRamMib"`
	Architectures          []string          `json:"architectures"`
	SpotAutomationEnabled  bool              `json:"spotAutomationEnabled"`
	CustomLabels           map[string]string `json:"customLabels"`
	CustomTaints           []map[string]string `json:"customTaints"`
}

type RebalancerConfig struct {
	Enabled              bool    `json:"enabled"`
	GracefulDraining     bool    `json:"gracefulDraining"`
	DrainTimeoutMinutes  int     `json:"drainTimeoutMinutes"`
	ScheduleEnabled      bool    `json:"scheduleEnabled"`
	ScheduleCron         string  `json:"scheduleCron"`
	LastRebalanceAt      string  `json:"lastRebalanceAt"`
	LastRebalanceSavings float64 `json:"lastRebalanceSavingsPercent"`
	LastRebalanceDuration int    `json:"lastRebalanceDuration"`
	Status               string  `json:"status"` // idle | running | failed | completed
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
	Enabled              bool `json:"enabled"`
	// Analyse mode: generate recommendations without creating HPAs
	AnalyseOnly          bool `json:"analyseOnly"`
	// Default bounds applied when XamOps creates an HPA
	DefaultMinReplicas   int  `json:"defaultMinReplicas"`   // default 1
	DefaultMaxReplicas   int  `json:"defaultMaxReplicas"`   // default 10
	DefaultTargetCPUPct  int  `json:"defaultTargetCpuPct"`  // default 80
	// Skip namespaces (additional to SafetyGuards.CriticalNamespaces)
	ExcludeNamespaces    []string `json:"excludeNamespaces,omitempty"`
}

// VPAManagerPolicy controls VPA object management behavior.
type VPAManagerPolicy struct {
	Enabled     bool   `json:"enabled"`
	// Default update mode for newly created VPA objects.
	// "Off" = recommendations only, no pod restarts.
	// "Initial" = apply on pod creation only.
	// "Recreate" / "Auto" = apply with pod restarts.
	UpdateMode  string `json:"updateMode"`  // Off | Initial | Recreate | Auto
	// If true, only collect existing VPA recommendations without creating new VPAs.
	AnalyseOnly bool   `json:"analyseOnly"`
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
	Namespace          string  `json:"namespace"`
	WorkloadName       string  `json:"workloadName"`
	WorkloadKind       string  `json:"workloadKind"` // Deployment | StatefulSet | DaemonSet
	ContainerName      string  `json:"containerName"`
	CurrentCPURequest  string  `json:"currentCpuRequest"`  // e.g. "200m"
	CurrentMemRequest  string  `json:"currentMemRequest"`  // e.g. "256Mi"
	RecommendedCPU     string  `json:"recommendedCpu"`
	RecommendedMem     string  `json:"recommendedMem"`
	P80CPU             float64 `json:"p80Cpu"`  // millicores
	P100Mem            float64 `json:"p100Mem"` // bytes
	EstimatedSavings   float64 `json:"estimatedSavingsPercent"`
	Applied            bool    `json:"applied"`
	AppliedAt          string  `json:"appliedAt,omitempty"`
	Reason             string  `json:"reason,omitempty"` // why skipped if not applied
}

// AutoscalingEvent pushed back to backend (collector: "autoscaling-events")
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
	Status          string  `json:"status"`           // running | completed | failed | idle
	StartedAt       string  `json:"startedAt"`
	CompletedAt     string  `json:"completedAt,omitempty"`
	NodesProcessed  int     `json:"nodesProcessed"`
	NodesDrained    int     `json:"nodesDrained"`
	SavingsPercent  float64 `json:"savingsPercent"`
	DurationMinutes int     `json:"durationMinutes"`
	Error           string  `json:"error,omitempty"`
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
			SpotRatioTargetPct:      0,    // off by default — user must opt in
			KarpenterEnabled:        false,
			MaxParallelDrains:       1,    // safe default: one node at a time
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
