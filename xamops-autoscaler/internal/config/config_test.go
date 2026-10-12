package config

import "testing"

func setRequired(t *testing.T) {
	t.Helper()
	t.Setenv("XAMOPS_SERVER_URL", "http://stub.invalid:8080")
	t.Setenv("XAMOPS_API_KEY", "test-key")
	t.Setenv("CLUSTER_NAME", "test-cluster")
	t.Setenv("CLOUD_ACCOUNT_ID", "123456789012")
}

func TestLoadDefaultsAreSafe(t *testing.T) {
	setRequired(t)
	t.Setenv("ENABLE_RIGHTSIZER_APPLY", "")
	t.Setenv("DRY_RUN", "")
	t.Setenv("POD_NAMESPACE", "")
	t.Setenv("STATE_CONFIGMAP", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.EnableRightsizerApply {
		t.Error("ENABLE_RIGHTSIZER_APPLY unset must default to false")
	}
	if !cfg.DryRun {
		t.Error("DRY_RUN unset must default to true")
	}
	if cfg.PodNamespace != "xamops-system" {
		t.Errorf("PodNamespace = %q, want xamops-system", cfg.PodNamespace)
	}
	if cfg.StateConfigMap != "xamops-automation-state" {
		t.Errorf("StateConfigMap = %q, want xamops-automation-state", cfg.StateConfigMap)
	}
}

func TestLoadRightsizerApplyTrue(t *testing.T) {
	setRequired(t)
	t.Setenv("ENABLE_RIGHTSIZER_APPLY", "true")
	t.Setenv("POD_NAMESPACE", "custom-ns")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !cfg.EnableRightsizerApply {
		t.Error("ENABLE_RIGHTSIZER_APPLY=true must enable apply")
	}
	if cfg.PodNamespace != "custom-ns" {
		t.Errorf("PodNamespace = %q, want custom-ns", cfg.PodNamespace)
	}
}
