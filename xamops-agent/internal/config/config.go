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
	CollectorAutoscaling  bool // NEW: push autoscaling-metrics + autoscaling-events

	// OpenCost settings
	OpenCostServiceURL string
	OpenCostWindow     string

	// Prometheus settings (for autoscaling metrics)
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
		CollectorAutoscaling: getEnvBool("COLLECTOR_AUTOSCALING", false), // opt-in

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
