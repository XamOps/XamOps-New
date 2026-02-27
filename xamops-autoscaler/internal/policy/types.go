package policy

// PolicyBundle mirrors EksAutoscalingService.PolicyBundle on the backend.
// The autoscaler reads this from /api/v1/agent/autoscaling-policy to know
// what mode to run in and what the current user-configured settings are.
type PolicyBundle struct {
	AutoscalerMode          string                    `json:"autoscalerMode"`          // full | scoped | disabled
	ScaleOutPolicy          ScaleOutPolicy            `json:"scaleOutPolicy"`
	EvictorPolicy           EvictorPolicy             `json:"evictorPolicy"`
	WorkloadRightsizerPolicy WorkloadRightsizerPolicy `json:"workloadRightsizerPolicy"`
	NodeTemplates           []NodeTemplate            `json:"nodeTemplates"`
	Rebalancer              RebalancerConfig          `json:"rebalancer"`
	SafetyGuards            SafetyGuards              `json:"safetyGuards"`
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

// Defaults returns a safe default PolicyBundle (used when backend not reachable)
func DefaultBundle() PolicyBundle {
	return PolicyBundle{
		AutoscalerMode: "disabled",
		EvictorPolicy: EvictorPolicy{
			Enabled:                 false,
			NodeCycleIntervalSeconds: 60,
			NodeGracePeriodMinutes:  5,
			TargetUtilizationCpu:    80,
			TargetUtilizationMemory: 80,
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
