package controller

import (
	"context"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	dynamicfake "k8s.io/client-go/dynamic/fake"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"

	"github.com/xamops/automation/internal/gate"
	"github.com/xamops/automation/internal/metrics"
	"github.com/xamops/automation/internal/policy"
)

// dynListKinds maps the GVRs the VPA path lists (VPAs + CNPG clusters) to their List
// kinds for the dynamic fake.
var dynListKinds = map[schema.GroupVersionResource]string{
	{Group: "autoscaling.k8s.io", Version: "v1", Resource: "verticalpodautoscalers"}: "VerticalPodAutoscalerList",
	{Group: "postgresql.cnpg.io", Version: "v1", Resource: "clusters"}:               "ClusterList",
}

func newDynFake(objs ...runtime.Object) *dynamicfake.FakeDynamicClient {
	return dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(), dynListKinds, objs...)
}

// vpaObj builds an unstructured VerticalPodAutoscaler with one container target
// recommendation.
func vpaObj(ns, targetKind, targetName, container, targetCPU, targetMem string) *unstructured.Unstructured {
	return &unstructured.Unstructured{Object: map[string]interface{}{
		"apiVersion": "autoscaling.k8s.io/v1",
		"kind":       "VerticalPodAutoscaler",
		"metadata":   map[string]interface{}{"name": targetName + "-vpa", "namespace": ns},
		"spec": map[string]interface{}{
			"targetRef":    map[string]interface{}{"kind": targetKind, "name": targetName},
			"updatePolicy": map[string]interface{}{"updateMode": "InPlaceOrRecreate"},
		},
		"status": map[string]interface{}{"recommendation": map[string]interface{}{
			"containerRecommendations": []interface{}{map[string]interface{}{
				"containerName": container,
				"target":        map[string]interface{}{"cpu": targetCPU, "memory": targetMem},
			}},
		}},
	}}
}

func ctrlPtr(b bool) *bool { return &b }

// burstablePod builds a Deployment-owned (ReplicaSet) running pod with a cpu+mem
// request (Burstable, no limits) for one container.
func burstablePod(ns, name, deploy, container, cpu, mem string) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name: name, Namespace: ns, UID: types.UID("uid-" + name),
			OwnerReferences: []metav1.OwnerReference{{
				Kind: "ReplicaSet", Name: deploy + "-abc123", UID: types.UID("rs-" + deploy),
				Controller: ctrlPtr(true),
			}},
		},
		Spec: corev1.PodSpec{Containers: []corev1.Container{{
			Name: container,
			Resources: corev1.ResourceRequirements{Requests: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse(cpu),
				corev1.ResourceMemory: resource.MustParse(mem),
			}},
		}}},
		Status: corev1.PodStatus{Phase: corev1.PodRunning, QOSClass: corev1.PodQOSBurstable},
	}
}

func resizeActions(c *fake.Clientset) int {
	n := 0
	for _, a := range c.Actions() {
		if a.GetVerb() == "patch" && a.GetResource().Resource == "pods" && a.GetSubresource() == "resize" {
			n++
		}
	}
	return n
}

func liveDecision() gate.Decision     { return gate.Decision{Mode: gate.Live, Reason: "test"} }
func simulateDecision() gate.Decision { return gate.Decision{Mode: gate.Simulate, Reason: "test"} }

func actingPolicy() policy.VPAManagerPolicy {
	return policy.VPAManagerPolicy{Enabled: true, AnalyseOnly: false, UpdateMode: "InPlaceOrRecreate"}
}

// T2: Simulate / DRY_RUN issues ZERO real resize calls (and no Delete, Eviction, or
// Deployment/StatefulSet patch).
func TestVpaSimulateIssuesNoResize(t *testing.T) {
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")
	c := fake.NewSimpleClientset(pod)
	dyn := newDynFake(vpaObj("default", "Deployment", "web", "app", "200m", "256Mi"))
	v := NewVPAManager(c, dyn, true /*dryRun*/)

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		simulateDecision(), nil, metrics.New())
	if err != nil {
		t.Fatalf("ApplyInPlace: %v", err)
	}
	if resizeActions(c) != 0 {
		t.Errorf("Simulate issued %d resize PATCH(es), want 0", resizeActions(c))
	}
	assertNoPodDelete(t, c)
	for _, a := range c.Actions() {
		if a.GetVerb() == "patch" && (a.GetResource().Resource == "deployments" || a.GetResource().Resource == "statefulsets") {
			t.Errorf("template patch issued: %v", a)
		}
		if a.GetVerb() == "create" && a.GetResource().Resource == "pods" && a.GetSubresource() == "eviction" {
			t.Errorf("eviction issued")
		}
	}
	if run.Summary.Simulated != 1 || run.Summary.Resized != 0 {
		t.Errorf("run summary = %+v, want 1 simulated / 0 resized", run.Summary)
	}
	if run.DryRun != true || run.SchemaVersion != 1 {
		t.Errorf("run shape = %+v", run)
	}
}

// T3a: a container whose resizePolicy=RestartContainer for a MOVED resource is refused,
// never resized.
func TestVpaRefusesRestartContainerPolicy(t *testing.T) {
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")
	pod.Spec.Containers[0].ResizePolicy = []corev1.ContainerResizePolicy{
		{ResourceName: corev1.ResourceCPU, RestartPolicy: corev1.RestartContainer},
	}
	c := fake.NewSimpleClientset(pod)
	dyn := newDynFake(vpaObj("default", "Deployment", "web", "app", "200m", "256Mi"))
	v := NewVPAManager(c, dyn, false)
	mset := metrics.New()

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, mset)
	if err != nil {
		t.Fatal(err)
	}
	if resizeActions(c) != 0 {
		t.Errorf("resize issued despite RestartContainer policy")
	}
	if run.Summary.Refused != 1 {
		t.Errorf("run refused = %d, want 1 (%+v)", run.Summary.Refused, run.Workloads)
	}
	if len(run.Workloads) != 1 || run.Workloads[0].Status != vpaStatusRefused {
		t.Fatalf("workloads = %+v", run.Workloads)
	}
	if got := run.Workloads[0].Reason; got != "refused: resizePolicy=RestartContainer (restart required — out of v1)" {
		t.Errorf("reason = %q", got)
	}
}

// T3b: a CPU-only change on a plain container is resized in place with ONE resize PATCH.
func TestVpaResizesCpuFreely(t *testing.T) {
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")
	c := fake.NewSimpleClientset(pod)
	// mem target empty → only CPU moves.
	dyn := newDynFake(vpaObj("default", "Deployment", "web", "app", "200m", ""))
	v := NewVPAManager(c, dyn, false)
	mset := metrics.New()

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, mset)
	if err != nil {
		t.Fatal(err)
	}
	if resizeActions(c) != 1 {
		t.Fatalf("resize PATCHes = %d, want 1 (writes: %v)", resizeActions(c), writeActions(c))
	}
	if run.Summary.Resized != 1 {
		t.Errorf("run resized = %d, want 1 (%+v)", run.Summary.Resized, run.Workloads)
	}
}

// T5a: a recommendation that would flip Guaranteed→Burstable is refused.
func TestVpaRefusesQosChange(t *testing.T) {
	// Guaranteed container: request == limit for both cpu & mem.
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")
	pod.Spec.Containers[0].Resources.Limits = corev1.ResourceList{
		corev1.ResourceCPU:    resource.MustParse("100m"),
		corev1.ResourceMemory: resource.MustParse("128Mi"),
	}
	pod.Status.QOSClass = corev1.PodQOSGuaranteed
	c := fake.NewSimpleClientset(pod)
	// Raising the CPU request above the limit would make request>limit; use a target
	// below the limit but only moving cpu, so limit stays and request<limit →
	// Guaranteed→Burstable shift. 50m request vs 100m limit.
	dyn := newDynFake(vpaObj("default", "Deployment", "web", "app", "50m", "128Mi"))
	v := NewVPAManager(c, dyn, false)

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, metrics.New())
	if err != nil {
		t.Fatal(err)
	}
	if resizeActions(c) != 0 {
		t.Errorf("resize issued despite QoS change")
	}
	if run.Summary.Refused != 1 {
		t.Fatalf("refused = %d (%+v)", run.Summary.Refused, run.Workloads)
	}
}

// T5b: a request that would exceed the container's limit is refused.
func TestVpaRefusesRequestExceedsLimit(t *testing.T) {
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")
	pod.Spec.Containers[0].Resources.Limits = corev1.ResourceList{
		corev1.ResourceCPU: resource.MustParse("200m"),
	}
	c := fake.NewSimpleClientset(pod)
	// Target CPU 300m > 200m limit.
	dyn := newDynFake(vpaObj("default", "Deployment", "web", "app", "300m", ""))
	v := NewVPAManager(c, dyn, false)

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, metrics.New())
	if err != nil {
		t.Fatal(err)
	}
	if resizeActions(c) != 0 {
		t.Errorf("resize issued despite request>limit")
	}
	if run.Summary.Refused != 1 || run.Workloads[0].Reason != "refused: request would exceed limit" {
		t.Fatalf("refused = %d reason=%q", run.Summary.Refused, run.Workloads[0].Reason)
	}
}

// T5c: a server rejection (422) on the resize PATCH is a refusal, never escalated to a
// restart/eviction/template patch.
func TestVpaRefusesWhenServerRejectsResize(t *testing.T) {
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")
	c := fake.NewSimpleClientset(pod)
	c.PrependReactor("patch", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if action.GetSubresource() == "resize" {
			return true, nil, apiStatusError(422)
		}
		return false, nil, nil
	})
	dyn := newDynFake(vpaObj("default", "Deployment", "web", "app", "200m", "256Mi"))
	v := NewVPAManager(c, dyn, false)
	mset := metrics.New()

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, mset)
	if err != nil {
		t.Fatal(err)
	}
	if run.Summary.Refused != 1 || run.Summary.Resized != 0 {
		t.Fatalf("run = %+v / %+v", run.Summary, run.Workloads)
	}
	// No eviction/template-patch fallback.
	assertNoPodDelete(t, c)
	for _, a := range c.Actions() {
		if a.GetVerb() == "patch" && (a.GetResource().Resource == "deployments" || a.GetResource().Resource == "statefulsets") {
			t.Errorf("template patch fallback after server reject: %v", a)
		}
	}
}

// T8: with N > cap eligible pods exactly vpaMaxResizesPerCycle resizes are issued and
// the rest record 'skipped: vpa resize cap reached'.
func TestVpaResizeCapPerCycle(t *testing.T) {
	var objs []runtime.Object
	n := vpaMaxResizesPerCycle + 2
	for i := 0; i < n; i++ {
		// distinct workloads so each has its own VPA; cpu-only move.
		objs = append(objs, burstablePod("default",
			padName("web", i)+"-abc123-1", padName("web", i), "app", "100m", "128Mi"))
	}
	c := fake.NewSimpleClientset(objs...)
	var vpas []runtime.Object
	for i := 0; i < n; i++ {
		vpas = append(vpas, vpaObj("default", "Deployment", padName("web", i), "app", "200m", ""))
	}
	dyn := newDynFake(vpas...)
	v := NewVPAManager(c, dyn, false)

	run, _, err := v.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, metrics.New())
	if err != nil {
		t.Fatal(err)
	}
	if resizeActions(c) != vpaMaxResizesPerCycle {
		t.Errorf("resize PATCHes = %d, want %d", resizeActions(c), vpaMaxResizesPerCycle)
	}
	if run.Summary.Resized != vpaMaxResizesPerCycle || run.Summary.Skipped != 2 {
		t.Errorf("run summary = %+v, want %d resized / 2 skipped", run.Summary, vpaMaxResizesPerCycle)
	}
	skips := 0
	for _, w := range run.Workloads {
		if w.Status == vpaStatusSkipped {
			skips++
			if w.Reason != "skipped: vpa resize cap reached" {
				t.Errorf("skip reason = %q", w.Reason)
			}
		}
	}
	if skips != 2 {
		t.Errorf("skip rows = %d, want 2", skips)
	}
}

func padName(base string, i int) string {
	return base + string(rune('a'+i))
}

// T12: IncVpa* counters fire only on live outcomes, never under Simulate.
func TestVpaMetricsOnlyOnLive(t *testing.T) {
	pod := burstablePod("default", "web-abc123-1", "web", "app", "100m", "128Mi")

	// Simulate: zero resized counter.
	cSim := fake.NewSimpleClientset(pod.DeepCopy())
	vSim := NewVPAManager(cSim, newDynFake(vpaObj("default", "Deployment", "web", "app", "200m", "")), true)
	msetSim := metrics.New()
	if _, _, err := vSim.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		simulateDecision(), nil, msetSim); err != nil {
		t.Fatal(err)
	}
	if got := counterValue(t, msetSim, "xamops_vpa_resized_total"); got != 0 {
		t.Errorf("simulate incremented resized counter to %v", got)
	}

	// Live: one resized counter.
	cLive := fake.NewSimpleClientset(pod.DeepCopy())
	vLive := NewVPAManager(cLive, newDynFake(vpaObj("default", "Deployment", "web", "app", "200m", "")), false)
	msetLive := metrics.New()
	if _, _, err := vLive.ApplyInPlace(context.Background(), actingPolicy(), policy.SafetyGuards{},
		liveDecision(), nil, msetLive); err != nil {
		t.Fatal(err)
	}
	if got := counterValue(t, msetLive, "xamops_vpa_resized_total"); got != 1 {
		t.Errorf("live resized counter = %v, want 1", got)
	}
}

// statefulset owner helper for completeness (keeps appsv1 imported for owner reads).
var _ = appsv1.ReplicaSet{}
