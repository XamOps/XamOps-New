package collector

import "time"

// AgentPayload is the complete data structure sent to the server
type AgentPayload struct {
	SchemaVersion  string      `json:"schemaVersion"`
	AgentVersion   string      `json:"agentVersion"`
	ClusterName    string      `json:"clusterName"`
	CloudAccountID string      `json:"cloudAccountId"`
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
	VulnerabilityID  string `json:"vulnerabilityID"`
	Severity         string `json:"severity"`
	PkgName          string `json:"pkgName"`
	InstalledVersion string `json:"installedVersion"`
	FixedVersion     string `json:"fixedVersion,omitempty"`
	Title            string `json:"title,omitempty"`
	PrimaryLink      string `json:"primaryLink,omitempty"`
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
