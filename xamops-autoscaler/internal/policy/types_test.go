package policy

import (
	"encoding/json"
	"strings"
	"testing"
)

// A zero-value consolidationPolicy decode (section present but every field zero, or
// section absent) is clamped by Normalize to the safe defaults, with one warning per
// repaired field.
func TestNormalizeClampsConsolidationPolicy(t *testing.T) {
	var b PolicyBundle
	if err := json.Unmarshal([]byte(`{"consolidationPolicy":{"enabled":true}}`), &b); err != nil {
		t.Fatal(err)
	}
	// Decoded zero values before Normalize.
	if b.ConsolidationPolicy.ConsolidateAfterSeconds != 0 ||
		b.ConsolidationPolicy.MaxConcurrentDisruptions != 0 ||
		b.ConsolidationPolicy.DwellSeconds != 0 {
		t.Fatalf("expected zero-value decode, got %+v", b.ConsolidationPolicy)
	}

	warns := b.Normalize()

	cp := b.ConsolidationPolicy
	if cp.ConsolidateAfterSeconds != DefaultConsolidateAfterSeconds {
		t.Errorf("consolidateAfterSeconds = %d, want %d", cp.ConsolidateAfterSeconds, DefaultConsolidateAfterSeconds)
	}
	if cp.MaxConcurrentDisruptions != DefaultMaxConcurrentDisruptions {
		t.Errorf("maxConcurrentDisruptions = %d, want %d", cp.MaxConcurrentDisruptions, DefaultMaxConcurrentDisruptions)
	}
	if cp.DwellSeconds != DefaultConsolidationDwellSecond {
		t.Errorf("dwellSeconds = %d, want %d", cp.DwellSeconds, DefaultConsolidationDwellSecond)
	}
	if !cp.Enabled {
		t.Errorf("Normalize must not change the enabled flag")
	}

	// One warning per clamped field names the field.
	for _, want := range []string{"consolidateAfterSeconds", "maxConcurrentDisruptions", "dwellSeconds"} {
		found := false
		for _, w := range warns {
			if strings.Contains(w, want) {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("no warning names %q (warnings: %v)", want, warns)
		}
	}
}

// Positive values are left untouched (no clamp, no warning for this section).
func TestNormalizeKeepsPositiveConsolidationPolicy(t *testing.T) {
	b := PolicyBundle{ConsolidationPolicy: ConsolidationPolicy{
		Enabled: true, ConsolidateAfterSeconds: 120, MaxConcurrentDisruptions: 3, DwellSeconds: 90,
	}}
	b.WorkloadRightsizerPolicy.MinCpuMillicores = 10
	b.WorkloadRightsizerPolicy.MinMemoryMib = 10
	b.NodeOptimizerPolicy.MaxParallelDrains = 1

	warns := b.Normalize()
	cp := b.ConsolidationPolicy
	if cp.ConsolidateAfterSeconds != 120 || cp.MaxConcurrentDisruptions != 3 || cp.DwellSeconds != 90 {
		t.Fatalf("positive values changed: %+v", cp)
	}
	for _, w := range warns {
		if strings.Contains(w, "consolidationPolicy") {
			t.Errorf("unexpected consolidation warning: %q", w)
		}
	}
}

// DefaultBundle seeds a safe, OFF consolidation policy with the clamp defaults.
func TestDefaultBundleConsolidationSeed(t *testing.T) {
	cp := DefaultBundle().ConsolidationPolicy
	if cp.Enabled {
		t.Errorf("DefaultBundle consolidationPolicy.enabled must be false")
	}
	if cp.ConsolidateAfterSeconds != DefaultConsolidateAfterSeconds ||
		cp.MaxConcurrentDisruptions != DefaultMaxConcurrentDisruptions ||
		cp.DwellSeconds != DefaultConsolidationDwellSecond {
		t.Errorf("DefaultBundle seed = %+v", cp)
	}
	if DefaultBundle().ConsolidationEnabled() {
		t.Errorf("ConsolidationEnabled() must be false by default")
	}
}
