package collector

import "time"

// AgentPayload is the complete data structure sent to the server
type AgentPayload struct {
	SchemaVersion  string      `json:"schemaVersion"`
	AgentVersion   string      `json:"agentVersion"`
	ClusterName    string      `json:"clusterName"`
	CloudAccountID string      `json:"cloudAccountId"`
	Provider       string      `json:"provider,omitempty"` // "AWS" | "GCP" — set by disk/standalone agents
	CollectedAt    time.Time   `json:"collectedAt"`
	Batches        []DataBatch `json:"batches"`
}

// DataBatch represents a batch of collected data from a single collector
type DataBatch struct {
	Collector   string      `json:"collector"`
	Items       interface{} `json:"items"`
	Count       int         `json:"count"`
	CollectedAt time.Time   `json:"collectedAt"`
}

// TrivyVulnerabilityReport represents a Trivy vulnerability report
type TrivyVulnerabilityReport struct {
	Name      string                 `json:"name"`
	Namespace string                 `json:"namespace"`
	Labels    map[string]string      `json:"labels,omitempty"`
	Report    TrivyReport            `json:"report"`
}

type TrivyReport struct {
	Summary         TrivySummary          `json:"summary"`
	Vulnerabilities []TrivyVulnerability  `json:"vulnerabilities,omitempty"`
}

type TrivySummary struct {
	CriticalCount int `json:"criticalCount"`
	HighCount     int `json:"highCount"`
	MediumCount   int `json:"mediumCount"`
	LowCount      int `json:"lowCount"`
	UnknownCount  int `json:"unknownCount"`
}

type TrivyVulnerability struct {
	VulnerabilityID  string  `json:"vulnerabilityID"`
	Severity         string  `json:"severity"`
	PkgName          string  `json:"pkgName"`
	InstalledVersion string  `json:"installedVersion"`
	FixedVersion     string  `json:"fixedVersion,omitempty"`
	Title            string  `json:"title,omitempty"`
	PrimaryLink      string  `json:"primaryLink,omitempty"`
	Score            float64 `json:"score,omitempty"`
}

// KubescapeScan represents a Kubescape configuration scan
type KubescapeScan struct {
	Name       string            `json:"name"`
	Namespace  string            `json:"namespace"`
	Labels     map[string]string `json:"labels,omitempty"`
	Severities KubescapeSeverity `json:"severities"`
	Controls   KubescapeControls `json:"controls"`
}

type KubescapeSeverity struct {
	Critical int `json:"critical"`
	High     int `json:"high"`
	Medium   int `json:"medium"`
	Low      int `json:"low"`
}

type KubescapeControls struct {
	Passed int `json:"passed"`
	Failed int `json:"failed"`
	Total  int `json:"total"`
}

// OpenCostAllocation represents OpenCost namespace allocation
type OpenCostAllocation struct {
	Namespace     string  `json:"namespace"`
	CPUCost       float64 `json:"cpuCost"`
	RAMCost       float64 `json:"ramCost"`
	PVCost        float64 `json:"pvCost"`
	NetworkCost   float64 `json:"networkCost"`
	TotalCost     float64 `json:"totalCost"`
	CPUEfficiency float64 `json:"cpuEfficiency,omitempty"`
	RAMEfficiency float64 `json:"ramEfficiency,omitempty"`
}

// KarpenterData represents Karpenter autoscaling data
type KarpenterData struct {
	Installed      bool              `json:"installed"`
	Version        string            `json:"version,omitempty"`
	NodePools      []KarpenterPool   `json:"nodePools,omitempty"`
	NodeClaims     []KarpenterClaim  `json:"nodeClaims,omitempty"`
	EC2NodeClasses []KarpenterClass  `json:"ec2NodeClasses,omitempty"`
	Error          string            `json:"error,omitempty"`
}

type KarpenterPool struct {
	Name   string                 `json:"name"`
	Spec   map[string]interface{} `json:"spec,omitempty"`
	Status map[string]interface{} `json:"status,omitempty"`
}

type KarpenterClaim struct {
	Name       string `json:"name"`
	NodePool   string `json:"nodePool"`
	NodeName   string `json:"nodeName,omitempty"`
	Phase      string `json:"phase"`
}

type KarpenterClass struct {
	Name string                 `json:"name"`
	Spec map[string]interface{} `json:"spec,omitempty"`
}

// ClusterMetadata represents basic cluster information
type ClusterMetadata struct {
	Nodes      []NodeInfo      `json:"nodes"`
	Namespaces []string        `json:"namespaces"`
	PodsSummary PodsSummary    `json:"podsSummary"`
}

type NodeInfo struct {
	Name     string            `json:"name"`
	Labels   map[string]string `json:"labels,omitempty"`
	Capacity NodeCapacity      `json:"capacity"`
	Phase    string            `json:"phase"`
}

type NodeCapacity struct {
	CPU    string `json:"cpu"`
	Memory string `json:"memory"`
	Pods   string `json:"pods"`
}

type PodsSummary struct {
	Total   int `json:"total"`
	Running int `json:"running"`
	Pending int `json:"pending"`
	Failed  int `json:"failed"`
}

// DbInstanceMetrics represents live server-level metrics for one DB instance.
// Collected alongside query stats — pushed as "db-instance-metrics" batch.
type DbInstanceMetrics struct {
	DSNAlias string `json:"dsnAlias"`
	DBEngine string `json:"dbEngine"`
	DBHost   string `json:"dbHost"`
	DBName   string `json:"dbName,omitempty"`   // current database name
	Region   string `json:"region,omitempty"`   // AWS region (from EC2 metadata)
	DBSizeBytes int64 `json:"dbSizeBytes,omitempty"` // total database size in bytes

	// EC2 host info (populated when DB runs on an EC2 instance)
	EC2InstanceType string `json:"ec2InstanceType,omitempty"` // e.g. "t3.medium"
	EC2InstanceId   string `json:"ec2InstanceId,omitempty"`   // e.g. "i-0abc123def456"

	// Connectivity
	CurrentConnections int64 `json:"currentConnections"`
	MaxConnections     int64 `json:"maxConnections"`
	MaxUsedConnections int64 `json:"maxUsedConnections,omitempty"`

	// Throughput (cumulative counters — consumer computes delta QPS)
	TotalQueries  int64 `json:"totalQueries"`
	TotalSlowQueries int64 `json:"totalSlowQueries,omitempty"`
	ComSelect     int64 `json:"comSelect,omitempty"`
	ComInsert     int64 `json:"comInsert,omitempty"`
	ComUpdate     int64 `json:"comUpdate,omitempty"`
	ComDelete     int64 `json:"comDelete,omitempty"`

	// InnoDB buffer pool (MySQL only)
	InnodbBufferPoolReads   int64   `json:"innodbBufferPoolReads,omitempty"`
	InnodbBufferPoolHitPct  float64 `json:"innodbBufferPoolHitPct,omitempty"` // 0–100

	// Locking (cumulative counters — consumer computes delta rate)
	InnodbRowLockWaits int64 `json:"innodbRowLockWaits,omitempty"` // MySQL: Innodb_row_lock_waits / PG: deadlocks proxy
	InnodbDeadlocks    int64 `json:"innodbDeadlocks,omitempty"`    // MySQL: Innodb_deadlocks / PG: sum(deadlocks)
	ThreadsRunning     int64 `json:"threadsRunning,omitempty"`     // MySQL: Threads_running / PG: active non-idle connections

	// Network
	BytesReceived int64 `json:"bytesReceived,omitempty"`
	BytesSent     int64 `json:"bytesSent,omitempty"`

	// Server info
	Uptime          int64  `json:"uptime"`          // seconds
	Version         string `json:"version"`
	CollectedAt     string `json:"collectedAt"`     // RFC3339
}

// DbTableSize represents storage usage for a single table — pushed as "db-table-sizes" batch.
type DbTableSize struct {
	DSNAlias       string `json:"dsnAlias"`
	DBEngine       string `json:"dbEngine"`
	DBHost         string `json:"dbHost"`
	SchemaName     string `json:"schemaName"`
	TableName      string `json:"tableName"`
	DataSizeBytes  int64  `json:"dataSizeBytes"`
	IndexSizeBytes int64  `json:"indexSizeBytes"`
	TotalSizeBytes int64  `json:"totalSizeBytes"`
	Rows           int64  `json:"rows"`
	CollectedAt    string `json:"collectedAt"`
}

// DbIndexUsage represents per-index scan statistics for a single index.
// Pushed as "db-index-usage" batch.
// Postgres: pg_stat_user_indexes. MySQL: performance_schema.table_io_waits_summary_by_index_usage.
type DbIndexUsage struct {
	DSNAlias       string `json:"dsnAlias"`
	DBEngine       string `json:"dbEngine"`
	DBHost         string `json:"dbHost"`
	SchemaName     string `json:"schemaName"`
	TableName      string `json:"tableName"`
	IndexName      string `json:"indexName"`
	IdxScan        int64  `json:"idxScan"`              // number of index scans initiated
	IdxTupRead     int64  `json:"idxTupRead,omitempty"` // tuples read via index (Postgres)
	IdxTupFetch    int64  `json:"idxTupFetch,omitempty"`// live tuples fetched (Postgres)
	IsUnused       bool   `json:"isUnused"`             // idxScan == 0 since last reset
	IndexSizeBytes int64  `json:"indexSizeBytes,omitempty"`
	CollectedAt    string `json:"collectedAt"`
}

// DbTableActivity represents read/write activity counters for a single table.
// Pushed as "db-table-activity" batch.
// Postgres: pg_stat_user_tables. MySQL: performance_schema.table_io_waits_summary_by_table.
type DbTableActivity struct {
	DSNAlias    string `json:"dsnAlias"`
	DBEngine    string `json:"dbEngine"`
	DBHost      string `json:"dbHost"`
	SchemaName  string `json:"schemaName"`
	TableName   string `json:"tableName"`
	SeqScan     int64  `json:"seqScan"`              // full-table sequential scans
	SeqTupRead  int64  `json:"seqTupRead,omitempty"` // rows read by seq scans (Postgres)
	IdxScan     int64  `json:"idxScan"`              // index scans
	IdxTupFetch int64  `json:"idxTupFetch,omitempty"`// rows fetched via index (Postgres)
	NTupIns     int64  `json:"nTupIns"`
	NTupUpd     int64  `json:"nTupUpd"`
	NTupDel     int64  `json:"nTupDel"`
	NTupHotUpd  int64  `json:"nTupHotUpd,omitempty"` // HOT updates — no index churn (Postgres)
	CollectedAt string `json:"collectedAt"`
}

// DbVacuumStat represents vacuum/analyze status and table bloat for a single table.
// PostgreSQL only — collected from pg_stat_user_tables.
// Pushed as "db-vacuum-stats" batch.
type DbVacuumStat struct {
	DSNAlias        string  `json:"dsnAlias"`
	DBEngine        string  `json:"dbEngine"`
	DBHost          string  `json:"dbHost"`
	SchemaName      string  `json:"schemaName"`
	TableName       string  `json:"tableName"`
	NLiveTup        int64   `json:"nLiveTup"`
	NDeadTup        int64   `json:"nDeadTup"`
	DeadTupRatio    float64 `json:"deadTupRatio"` // nDeadTup / (nLiveTup + nDeadTup)
	LastVacuum      string  `json:"lastVacuum,omitempty"`
	LastAutoVacuum  string  `json:"lastAutoVacuum,omitempty"`
	LastAnalyze     string  `json:"lastAnalyze,omitempty"`
	LastAutoAnalyze string  `json:"lastAutoAnalyze,omitempty"`
	CollectedAt     string  `json:"collectedAt"`
}

// DbUserActivity represents connection and query activity for a single database user.
// Collected from pg_stat_activity (Postgres) or information_schema.PROCESSLIST (MySQL).
// Pushed as "db-user-activity" batch.
type DbUserActivity struct {
	DSNAlias          string  `json:"dsnAlias"`
	DBEngine          string  `json:"dbEngine"`
	DBHost            string  `json:"dbHost"`
	Username          string  `json:"username"`
	ActiveConnections int64   `json:"activeConnections"`
	IdleConnections   int64   `json:"idleConnections"`
	TotalConnections  int64   `json:"totalConnections"`
	CallCount         int64   `json:"callCount,omitempty"`        // cumulative queries by this user
	TotalTimeSeconds  float64 `json:"totalTimeSeconds,omitempty"` // cumulative exec time in seconds
	CollectedAt       string  `json:"collectedAt"`
}

// DiskUsage represents disk mount point usage for an EC2 instance.
// Collected by the disk collector using df -BG + EC2 instance metadata.
// Pushed as "disk-usage" batch.
type DiskUsage struct {
	InstanceID  string  `json:"instanceId"`  // e.g. "i-0abc123def456"
	VolumeID    string  `json:"volumeId"`    // e.g. "vol-0abc123def456" (NVMe only; empty for Xen)
	Device      string  `json:"device"`      // e.g. "/dev/nvme0n1p1" or "/dev/xvda1"
	MountPoint  string  `json:"mountPoint"`  // e.g. "/"
	TotalGB     float64 `json:"totalGb"`
	UsedGB      float64 `json:"usedGb"`
	FreeGB      float64 `json:"freeGb"`
	UsedPercent float64 `json:"usedPercent"` // 0-100
	CollectedAt string  `json:"collectedAt"` // RFC3339
}

// ─────────────────────────────────────────────────────────────────────────────
// K8s Resources — full cluster snapshot (collector: "k8s-resources")
// Replaces the limited ClusterMetadata type.
// ─────────────────────────────────────────────────────────────────────────────

// K8sResources is the top-level payload for the "k8s-resources" batch.
type K8sResources struct {
	Nodes        []NodeDetail        `json:"nodes"`
	Namespaces   []NamespaceDetail   `json:"namespaces"`
	Pods         []PodDetail         `json:"pods"`
	Deployments  []DeploymentDetail  `json:"deployments"`
	StatefulSets []StatefulSetDetail `json:"statefulSets"`
	DaemonSets   []DaemonSetDetail   `json:"daemonSets"`
	Services     []ServiceDetail     `json:"services"`
	Ingresses    []IngressDetail     `json:"ingresses"`
	HPAs         []HPADetail         `json:"hpas"`
	PVCs         []PVCDetail         `json:"pvcs"`
	Jobs         []JobDetail         `json:"jobs"`
	CronJobs     []CronJobDetail     `json:"cronJobs"`
	Events       []EventDetail       `json:"events"`
	NodeMetrics  []NodeMetricDetail  `json:"nodeMetrics"`
	PodMetrics   []PodMetricDetail   `json:"podMetrics"`
	Summary      K8sSummary          `json:"summary"`
	K8sVersion   string              `json:"k8sVersion"`
}

type K8sSummary struct {
	TotalNodes       int `json:"totalNodes"`
	ReadyNodes       int `json:"readyNodes"`
	SpotNodes        int `json:"spotNodes"`
	TotalPods        int `json:"totalPods"`
	RunningPods      int `json:"runningPods"`
	PendingPods      int `json:"pendingPods"`
	FailedPods       int `json:"failedPods"`
	TotalDeployments int `json:"totalDeployments"`
	TotalServices    int `json:"totalServices"`
	TotalNamespaces  int `json:"totalNamespaces"`
	TotalPVCs        int `json:"totalPvcs"`
	UnscheduledPods  int `json:"unscheduledPods"`
}

type NodeDetail struct {
	Name              string            `json:"name"`
	Labels            map[string]string `json:"labels,omitempty"`
	Annotations       map[string]string `json:"annotations,omitempty"`
	Roles             []string          `json:"roles"`
	InstanceType      string            `json:"instanceType,omitempty"` // e.g. t3.medium
	CapacityType      string            `json:"capacityType,omitempty"` // on-demand | spot
	Zone              string            `json:"zone,omitempty"`
	Region            string            `json:"region,omitempty"`
	OperatingSystem   string            `json:"operatingSystem,omitempty"`
	KernelVersion     string            `json:"kernelVersion,omitempty"`
	ContainerRuntime  string            `json:"containerRuntime,omitempty"`
	KubeletVersion    string            `json:"kubeletVersion,omitempty"`
	CapacityCPU       string            `json:"capacityCpu"`
	CapacityMemory    string            `json:"capacityMemory"`
	CapacityPods      string            `json:"capacityPods"`
	AllocatableCPU    string            `json:"allocatableCpu"`
	AllocatableMemory string            `json:"allocatableMemory"`
	AllocatablePods   string            `json:"allocatablePods"`
	InternalIP        string            `json:"internalIp,omitempty"`
	ExternalIP        string            `json:"externalIp,omitempty"`
	Hostname          string            `json:"hostname,omitempty"`
	Conditions        []NodeCondition   `json:"conditions"`
	Taints            []NodeTaint       `json:"taints,omitempty"`
	Unschedulable     bool              `json:"unschedulable"`
	Age               string            `json:"age"` // RFC3339 creationTimestamp
}

type NodeCondition struct {
	Type    string `json:"type"`
	Status  string `json:"status"`
	Message string `json:"message,omitempty"`
	Reason  string `json:"reason,omitempty"`
}

type NodeTaint struct {
	Key    string `json:"key"`
	Value  string `json:"value,omitempty"`
	Effect string `json:"effect"`
}

type NamespaceDetail struct {
	Name              string            `json:"name"`
	Labels            map[string]string `json:"labels,omitempty"`
	Status            string            `json:"status"`
	Age               string            `json:"age"`
	PodCount          int               `json:"podCount"`
	RunningPodCount   int               `json:"runningPodCount"`
	DeploymentCount   int               `json:"deploymentCount"`
}

type PodDetail struct {
	Name            string            `json:"name"`
	Namespace       string            `json:"namespace"`
	NodeName        string            `json:"nodeName,omitempty"`
	Labels          map[string]string `json:"labels,omitempty"`
	Phase           string            `json:"phase"`
	QosClass        string            `json:"qosClass,omitempty"`
	Reason          string            `json:"reason,omitempty"`
	Message         string            `json:"message,omitempty"`
	ControllerKind  string            `json:"controllerKind,omitempty"`  // Deployment | StatefulSet | DaemonSet | Job | None
	ControllerName  string            `json:"controllerName,omitempty"`
	PodIP           string            `json:"podIp,omitempty"`
	StartedAt       string            `json:"startedAt,omitempty"` // RFC3339
	Age             string            `json:"age"`
	Containers      []ContainerDetail `json:"containers"`
	InitContainers  []ContainerDetail `json:"initContainers,omitempty"`
	Unschedulable   bool              `json:"unschedulable"`
}

type ContainerDetail struct {
	Name            string            `json:"name"`
	Image           string            `json:"image"`
	Ready           bool              `json:"ready"`
	RestartCount    int32             `json:"restartCount"`
	State           string            `json:"state"` // running | waiting | terminated
	WaitingReason   string            `json:"waitingReason,omitempty"`
	CPURequest      string            `json:"cpuRequest,omitempty"`
	CPULimit        string            `json:"cpuLimit,omitempty"`
	MemoryRequest   string            `json:"memoryRequest,omitempty"`
	MemoryLimit     string            `json:"memoryLimit,omitempty"`
	Ports           []ContainerPort   `json:"ports,omitempty"`
}

type ContainerPort struct {
	Name          string `json:"name,omitempty"`
	ContainerPort int32  `json:"containerPort"`
	Protocol      string `json:"protocol"`
}

type DeploymentDetail struct {
	Name              string            `json:"name"`
	Namespace         string            `json:"namespace"`
	Labels            map[string]string `json:"labels,omitempty"`
	Replicas          int32             `json:"replicas"`
	ReadyReplicas     int32             `json:"readyReplicas"`
	AvailableReplicas int32             `json:"availableReplicas"`
	UpdatedReplicas   int32             `json:"updatedReplicas"`
	Strategy          string            `json:"strategy"` // RollingUpdate | Recreate
	Age               string            `json:"age"`
	Images            []string          `json:"images,omitempty"` // container images
}

type StatefulSetDetail struct {
	Name            string            `json:"name"`
	Namespace       string            `json:"namespace"`
	Labels          map[string]string `json:"labels,omitempty"`
	Replicas        int32             `json:"replicas"`
	ReadyReplicas   int32             `json:"readyReplicas"`
	ServiceName     string            `json:"serviceName,omitempty"`
	Age             string            `json:"age"`
	Images          []string          `json:"images,omitempty"`
}

type DaemonSetDetail struct {
	Name                   string            `json:"name"`
	Namespace              string            `json:"namespace"`
	Labels                 map[string]string `json:"labels,omitempty"`
	DesiredNumberScheduled int32             `json:"desiredNumberScheduled"`
	NumberReady            int32             `json:"numberReady"`
	NumberUnavailable      int32             `json:"numberUnavailable"`
	Age                    string            `json:"age"`
	Images                 []string          `json:"images,omitempty"`
}

type ServiceDetail struct {
	Name                string            `json:"name"`
	Namespace           string            `json:"namespace"`
	Labels              map[string]string `json:"labels,omitempty"`
	Type                string            `json:"type"` // ClusterIP | NodePort | LoadBalancer | ExternalName
	ClusterIP           string            `json:"clusterIp,omitempty"`
	ExternalIPs         []string          `json:"externalIps,omitempty"`
	LoadBalancerHostname string           `json:"loadBalancerHostname,omitempty"`
	LoadBalancerIP       string           `json:"loadBalancerIp,omitempty"`
	ExternalName        string            `json:"externalName,omitempty"`
	Ports               []ServicePort     `json:"ports,omitempty"`
	Age                 string            `json:"age"`
}

type ServicePort struct {
	Name       string `json:"name,omitempty"`
	Port       int32  `json:"port"`
	TargetPort string `json:"targetPort"`
	NodePort   int32  `json:"nodePort,omitempty"`
	Protocol   string `json:"protocol"`
}

type IngressDetail struct {
	Name        string        `json:"name"`
	Namespace   string        `json:"namespace"`
	Labels      map[string]string `json:"labels,omitempty"`
	ClassName   string        `json:"className,omitempty"`
	Rules       []IngressRule `json:"rules,omitempty"`
	HasTLS      bool          `json:"hasTls"`
	Age         string        `json:"age"`
}

type IngressRule struct {
	Host  string        `json:"host,omitempty"`
	Paths []IngressPath `json:"paths,omitempty"`
}

type IngressPath struct {
	Path        string `json:"path"`
	PathType    string `json:"pathType"`
	ServiceName string `json:"serviceName"`
	ServicePort string `json:"servicePort"`
}

type HPADetail struct {
	Name            string            `json:"name"`
	Namespace       string            `json:"namespace"`
	Labels          map[string]string `json:"labels,omitempty"`
	TargetKind      string            `json:"targetKind"` // Deployment | StatefulSet
	TargetName      string            `json:"targetName"`
	MinReplicas     int32             `json:"minReplicas"`
	MaxReplicas     int32             `json:"maxReplicas"`
	CurrentReplicas int32             `json:"currentReplicas"`
	DesiredReplicas int32             `json:"desiredReplicas"`
	CPUTargetPct    int32             `json:"cpuTargetPct,omitempty"`
	CurrentCPUPct   int32             `json:"currentCpuPct,omitempty"`
	Age             string            `json:"age"`
	Conditions      []HPACondition    `json:"conditions,omitempty"`
}

type HPACondition struct {
	Type    string `json:"type"`
	Status  string `json:"status"`
	Message string `json:"message,omitempty"`
	Reason  string `json:"reason,omitempty"`
}

type PVCDetail struct {
	Name             string            `json:"name"`
	Namespace        string            `json:"namespace"`
	Labels           map[string]string `json:"labels,omitempty"`
	Status           string            `json:"status"` // Bound | Pending | Lost
	VolumeName       string            `json:"volumeName,omitempty"`
	StorageClassName string            `json:"storageClassName,omitempty"`
	AccessModes      []string          `json:"accessModes"`
	Capacity         string            `json:"capacity,omitempty"`
	Age              string            `json:"age"`
}

type JobDetail struct {
	Name           string            `json:"name"`
	Namespace      string            `json:"namespace"`
	Labels         map[string]string `json:"labels,omitempty"`
	Completions    int32             `json:"completions"`
	Active         int32             `json:"active"`
	Succeeded      int32             `json:"succeeded"`
	Failed         int32             `json:"failed"`
	StartTime      string            `json:"startTime,omitempty"`
	CompletionTime string            `json:"completionTime,omitempty"`
	Age            string            `json:"age"`
}

type CronJobDetail struct {
	Name             string  `json:"name"`
	Namespace        string  `json:"namespace"`
	Schedule         string  `json:"schedule"`
	Suspend          bool    `json:"suspend"`
	LastScheduleTime string  `json:"lastScheduleTime,omitempty"`
	ActiveJobs       int     `json:"activeJobs"`
	Age              string  `json:"age"`
}

type EventDetail struct {
	Name           string `json:"name"`
	Namespace      string `json:"namespace"`
	Reason         string `json:"reason"`
	Message        string `json:"message"`
	Type           string `json:"type"` // Normal | Warning
	Count          int32  `json:"count"`
	InvolvedObject string `json:"involvedObject"`
	ObjectKind     string `json:"objectKind"`
	FirstTime      string `json:"firstTime,omitempty"`
	LastTime       string `json:"lastTime,omitempty"`
}

type NodeMetricDetail struct {
	NodeName     string  `json:"nodeName"`
	CPUUsage     string  `json:"cpuUsage"`     // e.g. "250m"
	MemoryUsage  string  `json:"memoryUsage"`  // e.g. "1024Mi"
	CPUPercent   float64 `json:"cpuPercent"`   // 0-100
	MemoryPercent float64 `json:"memoryPercent"` // 0-100
}

type PodMetricDetail struct {
	PodName      string           `json:"podName"`
	Namespace    string           `json:"namespace"`
	Containers   []ContainerUsage `json:"containers"`
}

type ContainerUsage struct {
	Name        string `json:"name"`
	CPUUsage    string `json:"cpuUsage"`
	MemoryUsage string `json:"memoryUsage"`
}

// ─────────────────────────────────────────────────────────────────────────────
// Prometheus Metrics — in-cluster Prometheus query results (collector: "prometheus-metrics")
// ─────────────────────────────────────────────────────────────────────────────

// PrometheusMetrics is the top-level payload for the "prometheus-metrics" batch.
type PrometheusMetrics struct {
	ClusterCPUUsagePct    float64                   `json:"clusterCpuUsagePct"`    // 0-100
	ClusterMemUsagePct    float64                   `json:"clusterMemUsagePct"`    // 0-100
	NodeUtilization       []NodeUtilizationMetric   `json:"nodeUtilization"`
	NamespaceUtilization  []NamespaceUsageMetric    `json:"namespaceUtilization"`
	CostMetrics           *ClusterCostMetrics       `json:"costMetrics,omitempty"`
	FalcoEvents           []FalcoEvent              `json:"falcoEvents,omitempty"`
	PodRestartRates       []PodRestartMetric        `json:"podRestartRates,omitempty"`
	TopCPUPods            []PodResourceMetric       `json:"topCpuPods,omitempty"`
	TopMemoryPods         []PodResourceMetric       `json:"topMemoryPods,omitempty"`
	NetworkMetrics        []NamespaceNetworkMetric  `json:"networkMetrics,omitempty"`
	CollectedAt           string                    `json:"collectedAt"` // RFC3339
}

type NodeUtilizationMetric struct {
	NodeName        string  `json:"nodeName"`
	CPUUsagePct     float64 `json:"cpuUsagePct"`
	MemUsagePct     float64 `json:"memUsagePct"`
	CPUCoresUsed    float64 `json:"cpuCoresUsed"`
	MemGBUsed       float64 `json:"memGbUsed"`
}

type NamespaceUsageMetric struct {
	Namespace       string  `json:"namespace"`
	CPUCores        float64 `json:"cpuCores"`
	MemGB           float64 `json:"memGb"`
	CPURequestCores float64 `json:"cpuRequestCores"`
	MemRequestGB    float64 `json:"memRequestGb"`
}

type ClusterCostMetrics struct {
	TotalHourlyCost        float64 `json:"totalHourlyCost"`
	CPUHourlyCost          float64 `json:"cpuHourlyCost"`
	MemoryHourlyCost       float64 `json:"memoryHourlyCost"`
	MonthlyEstimate        float64 `json:"monthlyEstimate"`
	IdleNodeCostHourly     float64 `json:"idleNodeCostHourly"`
	WastedCPUPercent       float64 `json:"wastedCpuPercent"`
	WastedMemoryPercent    float64 `json:"wastedMemoryPercent"`
}

type FalcoEvent struct {
	Rule        string            `json:"rule"`
	Priority    string            `json:"priority"`   // Emergency | Alert | Critical | Error | Warning | Notice | Informational | Debug
	Output      string            `json:"output"`
	Source      string            `json:"source,omitempty"`
	Tags        []string          `json:"tags,omitempty"`
	Fields      map[string]string `json:"fields,omitempty"`
	Namespace   string            `json:"namespace,omitempty"`
	Pod         string            `json:"pod,omitempty"`
	Count       float64           `json:"count"`       // aggregated count from Prometheus
	CollectedAt string            `json:"collectedAt"` // RFC3339
}

type PodRestartMetric struct {
	PodName       string  `json:"podName"`
	Namespace     string  `json:"namespace"`
	ContainerName string  `json:"containerName"`
	RestartRate   float64 `json:"restartRate"` // restarts per hour
}

type PodResourceMetric struct {
	PodName      string  `json:"podName"`
	Namespace    string  `json:"namespace"`
	ContainerName string `json:"containerName,omitempty"`
	Value        float64 `json:"value"` // CPU in cores or Memory in bytes
}

type NamespaceNetworkMetric struct {
	Namespace     string  `json:"namespace"`
	BytesInPerSec float64 `json:"bytesInPerSec"`
	BytesOutPerSec float64 `json:"bytesOutPerSec"`
}

// DbQueryStat represents a single normalized query from performance_schema or pg_stat_statements
type DbQueryStat struct {
	// Connection info
	DSNAlias  string `json:"dsnAlias"`  // e.g. "prod-mysql-0"
	DBEngine  string `json:"dbEngine"`  // "mysql" | "postgres"
	DBHost    string `json:"dbHost"`    // hostname extracted from DSN (no credentials)

	// Query identity
	QueryDigest string `json:"queryDigest"` // normalized query text (no literals)

	// Execution stats
	CallCount        int64   `json:"callCount"`
	TotalTimeSeconds float64 `json:"totalTimeSeconds"`
	AvgTimeSeconds   float64 `json:"avgTimeSeconds"`
	MaxTimeSeconds   float64 `json:"maxTimeSeconds"`

	// Row stats
	RowsExamined int64 `json:"rowsExamined"` // MySQL only
	RowsSent     int64 `json:"rowsSent"`     // MySQL: rows_sent / PG: rows

	// I/O stats (PostgreSQL)
	SharedBlksHit  int64 `json:"sharedBlksHit,omitempty"`
	SharedBlksRead int64 `json:"sharedBlksRead,omitempty"`

	// Flags
	NoIndexUsed  bool `json:"noIndexUsed,omitempty"`  // MySQL: SUM_NO_INDEX_USED > 0
	LockTimeSeconds float64 `json:"lockTimeSeconds,omitempty"` // MySQL only
}

// DbWaitEvent — one aggregated wait category per DSN per collection cycle.
// Pushed as "db-wait-events" batch.
type DbWaitEvent struct {
	DSNAlias      string `json:"dsnAlias"`
	DBEngine      string `json:"dbEngine"`
	DBHost        string `json:"dbHost"`
	WaitCategory  string `json:"waitCategory"`  // IO | Lock | CPU | Network | Other
	WaitEventName string `json:"waitEventName"`
	SessionCount  int    `json:"sessionCount"`
	CollectedAt   string `json:"collectedAt"`
}

// DbDiagnostic — connection + privilege health for one DSN.
// Pushed as "db-diagnostics" batch every collection cycle.
type DbDiagnostic struct {
	DSNAlias     string `json:"dsnAlias"`
	DBEngine     string `json:"dbEngine"`
	DBHost       string `json:"dbHost"`
	Reachable    bool   `json:"reachable"`
	// ErrorType: "" (healthy) | "connection" | "privilege" | "extension_missing"
	ErrorType    string `json:"errorType"`
	ErrorMessage string `json:"errorMessage"`
	CollectedAt  string `json:"collectedAt"`
}

// AgentCommand — one pending command from the backend command queue.
type AgentCommand struct {
	CommandID   string `json:"commandId"`
	Type        string `json:"type"`       // "explain"
	DSNAlias    string `json:"dsnAlias"`
	QueryText   string `json:"queryText"`
	DBEngine    string `json:"dbEngine"`
}

// ExplainResult — result of an on-demand EXPLAIN pushed back to the server.
// Pushed as "db-explain-result" batch (single item).
type ExplainResult struct {
	CommandID   string `json:"commandId"`
	DSNAlias    string `json:"dsnAlias"`
	PlanJSON    string `json:"planJson"`   // raw JSON string from EXPLAIN
	Error       string `json:"error"`      // empty on success
	CollectedAt string `json:"collectedAt"`
}
