package config

import (
	"errors"
	"os"
	"strconv"
)

// Config holds all agent configuration
type Config struct {
	// Server connection
	ServerURL      string
	APIKey         string
	ClusterName    string
	CloudAccountID string

	// Collection settings
	CollectInterval   int // seconds
	PushInterval      int // seconds
	HeartbeatInterval int // seconds
	BatchSize         int
	MaxRetries        int
	RetryDelay        int // seconds

	// Collector toggles
	CollectorTrivy        bool
	CollectorKubescape    bool
	CollectorOpenCost     bool
	CollectorKarpenter    bool
	CollectorMetrics      bool
	CollectorAutoscaling       bool // push autoscaling-metrics + autoscaling-events
	CollectorK8sResources      bool // full K8s resource snapshot (nodes/pods/deployments/etc.)
	CollectorPrometheusMetrics bool // in-cluster Prometheus: utilization, cost, Falco, top pods
	CollectorDB                bool // DB query stats collector (opt-in)
	CollectorDisk              bool // EC2 disk usage collector (opt-in, for EBS rightsizing)

	// DB collector settings
	// DB_DSN_0, DB_DSN_1, ... — one DSN per database instance
	// MySQL:    "user:pass@tcp(rds-host:3306)/dbname"
	// Postgres: "postgres://user:pass@rds-host:5432/dbname?sslmode=require"
	DBDSNs          []string
	DBQueryLimit    int // max queries to collect per DB instance (default 50)
	DBCollectInterval int // seconds between DB query stat collections (default 300)

	// OpenCost settings
	OpenCostServiceURL string
	OpenCostWindow     string

	// Prometheus settings — in-cluster Prometheus endpoint.
	// Used by both prometheus-metrics collector and autoscaling-metrics collector.
	PrometheusURL string // e.g. http://prometheus-server.monitoring.svc.cluster.local:9090

	// Logging
	LogLevel string
}

// Load reads configuration from environment variables
func Load() (*Config, error) {
	cfg := &Config{
		// Required
		ServerURL:      getEnvRequired("XAMOPS_SERVER_URL"),
		APIKey:         getEnvRequired("XAMOPS_API_KEY"),
		ClusterName:    getEnvRequired("CLUSTER_NAME"),
		CloudAccountID: getEnvRequired("CLOUD_ACCOUNT_ID"),

		// Intervals
		CollectInterval:   getEnvInt("COLLECT_INTERVAL", 300),
		PushInterval:      getEnvInt("PUSH_INTERVAL", 300),
		HeartbeatInterval: getEnvInt("HEARTBEAT_INTERVAL", 60),
		BatchSize:         getEnvInt("BATCH_SIZE", 100),
		MaxRetries:        getEnvInt("MAX_RETRIES", 3),
		RetryDelay:        getEnvInt("RETRY_DELAY", 5),

		// Collectors
		CollectorTrivy:       getEnvBool("COLLECTOR_TRIVY", true),
		CollectorKubescape:   getEnvBool("COLLECTOR_KUBESCAPE", true),
		CollectorOpenCost:    getEnvBool("COLLECTOR_OPENCOST", true),
		CollectorKarpenter:   getEnvBool("COLLECTOR_KARPENTER", true),
		CollectorMetrics:     getEnvBool("COLLECTOR_METRICS", true),
		CollectorAutoscaling:       getEnvBool("COLLECTOR_AUTOSCALING", false),        // opt-in
		CollectorK8sResources:      getEnvBool("COLLECTOR_K8S_RESOURCES", true),       // on by default
		CollectorPrometheusMetrics: getEnvBool("COLLECTOR_PROMETHEUS_METRICS", true),  // on by default
		CollectorDB:                getEnvBool("COLLECTOR_DB", false),                 // opt-in
		CollectorDisk:              getEnvBool("COLLECTOR_DISK", false),               // opt-in

		// DB settings
		DBDSNs:            getEnvDSNs(),
		DBQueryLimit:      getEnvInt("DB_QUERY_LIMIT", 50),
		DBCollectInterval: getEnvInt("DB_COLLECT_INTERVAL", 300),

		// OpenCost
		OpenCostServiceURL: getEnv("OPENCOST_SERVICE_URL", "http://opencost.opencost.svc.cluster.local:9003"),
		OpenCostWindow:     getEnv("OPENCOST_ALLOCATION_WINDOW", "1d"),

		// Prometheus — optional; autoscaling collector uses it if set
		PrometheusURL: getEnv("PROMETHEUS_URL", "http://prometheus-server.monitoring.svc.cluster.local:9090"),

		// Logging
		LogLevel: getEnv("LOG_LEVEL", "info"),
	}

	// Validate required fields
	if cfg.ServerURL == "" {
		return nil, errors.New("XAMOPS_SERVER_URL is required")
	}
	if cfg.APIKey == "" {
		return nil, errors.New("XAMOPS_API_KEY is required")
	}
	if cfg.ClusterName == "" {
		return nil, errors.New("CLUSTER_NAME is required")
	}
	if cfg.CloudAccountID == "" {
		return nil, errors.New("CLOUD_ACCOUNT_ID is required")
	}

	return cfg, nil
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvRequired(key string) string {
	return os.Getenv(key)
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if i, err := strconv.Atoi(value); err == nil {
			return i
		}
	}
	return defaultValue
}

func getEnvBool(key string, defaultValue bool) bool {
	if value := os.Getenv(key); value != "" {
		return value == "true" || value == "1" || value == "yes"
	}
	return defaultValue
}

// getEnvDSNs reads DB_DSN_0, DB_DSN_1, ... up to 20 entries
func getEnvDSNs() []string {
	dsns := []string{}
	for i := 0; i < 20; i++ {
		key := "DB_DSN_" + strconv.Itoa(i)
		if v := os.Getenv(key); v != "" {
			dsns = append(dsns, v)
		}
	}
	return dsns
}
