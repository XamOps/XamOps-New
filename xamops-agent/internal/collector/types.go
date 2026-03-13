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
