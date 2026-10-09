package config

import (
	"errors"
	"os"
	"strconv"
)

// Config holds all configuration for the XamOps Autoscaling Controller.
type Config struct {
	// XamOps server — same as xamops-agent
	ServerURL      string
	APIKey         string
	ClusterName    string
	CloudAccountID string

	// How often to poll XamOps backend for policy changes (seconds)
	PolicyPollInterval int

	// How often to push metrics/events back to XamOps (seconds)
	MetricsPushInterval int

	// Heartbeat interval (seconds)
	HeartbeatInterval int

	// Retry settings
	MaxRetries int
	RetryDelay int

	// Feature flags — all default to false for safety.
	// Enable gradually via XamOps UI (autoscaling mode field) or these env vars.
	EnableEvictor      bool // utilisation-threshold node scale-in + pod eviction
	EnableRightsizer   bool // resource request patching (VPA-style, manual)
	EnableRebalancer   bool // node rebalancing (drain + replace)
	EnableHPAManager   bool // create/update/delete HPAs based on XamOps recommendations
	EnableVPAManager   bool // create/manage VPA objects (requires VPA CRD installed)
	EnableNodeOptimizer bool // idle node detection, spot candidate analysis, spot interruption handling

	// DryRun — when true the controller logs all actions but makes no actual changes.
	// Strongly recommended on first deployment. Disable once you trust the recommendations.
	DryRun bool

	// Prometheus — for Rightsizer lookback queries and current utilization
	PrometheusURL string

	// Drain timeout per node (seconds)
	DrainTimeoutSeconds int

	// Logging
	LogLevel string
}

func Load() (*Config, error) {
	cfg := &Config{
		ServerURL:      getEnvRequired("XAMOPS_SERVER_URL"),
		APIKey:         getEnvRequired("XAMOPS_API_KEY"),
		ClusterName:    getEnvRequired("CLUSTER_NAME"),
		CloudAccountID: getEnvRequired("CLOUD_ACCOUNT_ID"),

		PolicyPollInterval:  getEnvInt("POLICY_POLL_INTERVAL", 60),
		MetricsPushInterval: getEnvInt("METRICS_PUSH_INTERVAL", 120),
		HeartbeatInterval:   getEnvInt("HEARTBEAT_INTERVAL", 60),
		MaxRetries:          getEnvInt("MAX_RETRIES", 3),
		RetryDelay:          getEnvInt("RETRY_DELAY", 5),

		// All controllers default to false — must be explicitly opted in
		EnableEvictor:       getEnvBool("ENABLE_EVICTOR", false),
		EnableRightsizer:    getEnvBool("ENABLE_RIGHTSIZER", false),
		EnableRebalancer:    getEnvBool("ENABLE_REBALANCER", false),
		EnableHPAManager:    getEnvBool("ENABLE_HPA_MANAGER", false),
		EnableVPAManager:    getEnvBool("ENABLE_VPA_MANAGER", false),
		EnableNodeOptimizer: getEnvBool("ENABLE_NODE_OPTIMIZER", false),

		// DryRun defaults to TRUE for safety
		DryRun: getEnvBool("DRY_RUN", true),

		PrometheusURL:       getEnv("PROMETHEUS_URL", "http://prometheus-server.monitoring.svc.cluster.local:9090"),
		DrainTimeoutSeconds: getEnvInt("DRAIN_TIMEOUT_SECONDS", 1200), // 20 min default

		LogLevel: getEnv("LOG_LEVEL", "info"),
	}

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

func getEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getEnvRequired(key string) string { return os.Getenv(key) }

func getEnvInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return def
}

func getEnvBool(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		return v == "true" || v == "1" || v == "yes"
	}
	return def
}
