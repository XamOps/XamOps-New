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
