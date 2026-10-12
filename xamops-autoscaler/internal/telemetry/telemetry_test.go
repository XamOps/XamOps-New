package telemetry

import (
	"encoding/json"
	"reflect"
	"regexp"
	"sort"
	"testing"

	"github.com/xamops/automation/internal/policy"
)

var outcomeWords = regexp.MustCompile(`(?i)completed|drained|consolidated|applied`)

func TestNonVerifiedRecordsNeverLookDone(t *testing.T) {
	type rec struct {
		action, status string
		dryRun, verif  bool
	}
	var cases []rec
	for _, action := range []string{"drain", "cordon", "uncordon", "rebalance", "patch-requests", "spot-interruption-drain", "karpenter-spot-ratio-patch"} {
		cases = append(cases,
			rec{action, policy.ActionSimulated, true, false},
			rec{action, policy.ActionSimulated, true, true}, // a "verified" simulation is still a simulation
			rec{action, policy.ActionRefused, true, false},
			rec{action, policy.ActionRefused, false, false},
			rec{action, policy.ActionSkipped, false, false},
			rec{action, policy.ActionSkipped, true, false},
			rec{action, policy.ActionSucceeded, false, false}, // live, unverified
			rec{action, policy.ActionSucceeded, true, true},   // dry-run can never be success
			rec{action, policy.ActionFailed, false, false},
			rec{action, policy.ActionAborted, false, false},
		)
	}
	for _, c := range cases {
		r := NewAction(policy.ControllerNodeOptimizer, c.action, "node/ip-10-0-0-1", c.dryRun)
		r = Finish(r, c.status, c.verif, "fit simulator not available in this build — live drains refused")
		ev := LegacyEvent(r, "scale-in", 1)
		if ev.Severity == "success" {
			t.Errorf("%+v: severity success", c)
		}
		if outcomeWords.MatchString(ev.Message) {
			t.Errorf("%+v: message claims an outcome: %q", c, ev.Message)
		}
		if ev.NodeCount != 0 {
			t.Errorf("%+v: nodeCount %d, want 0", c, ev.NodeCount)
		}
		if ev.ID != r.ID {
			t.Errorf("%+v: event id %q != action id %q", c, ev.ID, r.ID)
		}
		wantSev := "info"
		if c.status == policy.ActionFailed || c.status == policy.ActionAborted {
			wantSev = "warning"
		}
		if ev.Severity != wantSev {
			t.Errorf("%+v: severity %q, want %q", c, ev.Severity, wantSev)
		}
	}
}

func TestMessagePrefixes(t *testing.T) {
	sim := Finish(NewAction("rebalancer", "drain", "node/a", true), policy.ActionSimulated, false, "")
	if got := LegacyEvent(sim, "rebalance", 1).Message; got != "DRY-RUN (simulated): would drain node/a" {
		t.Errorf("simulated message = %q", got)
	}
	ref := Finish(NewAction("rebalancer", "drain", "node/a", false), policy.ActionRefused, false, "no plan")
	if got := LegacyEvent(ref, "rebalance", 1).Message; got != "Refused: drain node/a — no plan" {
		t.Errorf("refused message = %q", got)
	}
	unv := Finish(NewAction("rightsizer", "patch-requests", "deployments", false), policy.ActionSucceeded, false, "")
	if got := LegacyEvent(unv, "workload-resize", 0).Message; got != "patch requests deployments requested; outcome not verified" {
		t.Errorf("unverified message = %q", got)
	}
}

func TestVerifiedLiveIsSuccess(t *testing.T) {
	r := NewAction("evictor", "drain", "node/a", false)
	r.OperationID = "evictor-1"
	r.Verification = "replacements Running+Ready elsewhere"
	r = Finish(r, policy.ActionSucceeded, true, "")
	ev := LegacyEvent(r, "scale-in", 1)
	if ev.Severity != "success" || ev.NodeCount != 1 {
		t.Fatalf("got severity %q nodeCount %d", ev.Severity, ev.NodeCount)
	}
	if ev.Message != "Drained node/a (verified: replacements Running+Ready elsewhere)" {
		t.Errorf("message = %q", ev.Message)
	}
	var d map[string]interface{}
	if err := json.Unmarshal([]byte(ev.Details), &d); err != nil {
		t.Fatalf("details is not JSON: %v", err)
	}
	if !reflect.DeepEqual(sortedKeys(d), []string{"actionId", "dryRun", "operationId", "status", "verified"}) {
		t.Errorf("details keys = %v", sortedKeys(d))
	}
	if d["actionId"] != r.ID || d["dryRun"] != false || d["status"] != "succeeded" || d["verified"] != true || d["operationId"] != "evictor-1" {
		t.Errorf("details = %v", d)
	}
}

func TestActionRecordAlwaysSerializesDryRunAndVerified(t *testing.T) {
	b, err := json.Marshal(policy.ActionRecord{ID: "x"})
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	if v, ok := m["dryRun"]; !ok || v != false {
		t.Errorf("dryRun missing or wrong: %s", b)
	}
	if v, ok := m["verified"]; !ok || v != false {
		t.Errorf("verified missing or wrong: %s", b)
	}
}

func sortedKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func keysOf(t *testing.T, v interface{}) []string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	return sortedKeys(m)
}

// Contract tests. The backend parses these collectors strictly with a plain
// ObjectMapper (xamops-service EksAutoscalingService.java:93; readEvents :511 into
// AutoscalingEventDto, getRightsizingData :601 into WorkloadRecommendation): one
// extra key raises UnrecognizedPropertyException and the whole collector is dropped.
// Do not add keys here — put new data on ActionRecord ("autoscaling-actions").
func TestAutoscalingEventKeySetFrozen(t *testing.T) {
	ev := policy.AutoscalingEvent{ID: "i", Timestamp: "t", Type: "scale-in", Severity: "info",
		Message: "m", Details: "{}", NodeCount: 1, SavingsDelta: 1.5}
	want := []string{"details", "id", "message", "nodeCount", "savingsDelta", "severity", "timestamp", "type"}
	if got := keysOf(t, ev); !reflect.DeepEqual(got, want) {
		t.Errorf("AutoscalingEvent keys = %v, want %v (8 keys)", got, want)
	}
}

func TestWorkloadRightsizingResultKeySetFrozen(t *testing.T) {
	r := policy.WorkloadRightsizingResult{Namespace: "n", WorkloadName: "w", WorkloadKind: "Deployment",
		ContainerName: "c", CurrentCPURequest: "100m", CurrentMemRequest: "128Mi", RecommendedCPU: "50m",
		RecommendedMem: "64Mi", P80CPU: 1, P100Mem: 2, EstimatedSavings: 3, Applied: true,
		AppliedAt: "t", Reason: "r"}
	want := []string{"applied", "appliedAt", "containerName", "currentCpuRequest", "currentMemRequest",
		"estimatedSavingsPercent", "namespace", "p100Mem", "p80Cpu", "reason", "recommendedCpu",
		"recommendedMem", "workloadKind", "workloadName"}
	if got := keysOf(t, r); !reflect.DeepEqual(got, want) {
		t.Errorf("WorkloadRightsizingResult keys = %v, want %v (14 keys)", got, want)
	}
}
