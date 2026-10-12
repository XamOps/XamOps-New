package collector

import (
	"context"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
)

func testNamespace(name string) *corev1.Namespace {
	return &corev1.Namespace{
		ObjectMeta: metav1.ObjectMeta{Name: name},
		Status:     corev1.NamespaceStatus{Phase: corev1.NamespaceActive},
	}
}

func testDeployment(ns, name string) *appsv1.Deployment {
	return &appsv1.Deployment{ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name}}
}

func testPod(ns, name string, phase corev1.PodPhase, conds ...corev1.PodCondition) *corev1.Pod {
	return &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name},
		Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "c", Image: "busybox"}}},
		Status:     corev1.PodStatus{Phase: phase, Conditions: conds},
	}
}

// unschedulableCond is the PodScheduled=False/Unschedulable condition both
// isPodUnschedulable and the autoscaling-metrics KPI count.
var unschedulableCond = corev1.PodCondition{
	Type:   corev1.PodScheduled,
	Status: corev1.ConditionFalse,
	Reason: corev1.PodReasonUnschedulable,
}

// testObjects: namespaces a/b/c with 2/1/0 Deployments; pods a: 2 Running,
// b: 1 Running + 1 Pending/Unschedulable, c: 1 Failed.
func testObjects() []runtime.Object {
	return []runtime.Object{
		testNamespace("a"), testNamespace("b"), testNamespace("c"),
		testDeployment("a", "a1"), testDeployment("a", "a2"), testDeployment("b", "b1"),
		testPod("a", "a1-x", corev1.PodRunning),
		testPod("a", "a2-x", corev1.PodRunning),
		testPod("b", "b1-x", corev1.PodRunning),
		testPod("b", "b1-pending", corev1.PodPending, unschedulableCond),
		testPod("c", "c-failed", corev1.PodFailed),
	}
}

func TestCollectK8sResourcesNamespaceCounts(t *testing.T) {
	client := fake.NewSimpleClientset(testObjects()...)

	batch, err := collectK8sResources(context.Background(), client, nil)
	if err != nil {
		t.Fatalf("collectK8sResources: %v", err)
	}
	items, ok := batch.Items.([]K8sResources)
	if !ok || len(items) != 1 {
		t.Fatalf("unexpected items %T %v", batch.Items, batch.Items)
	}
	res := items[0]

	want := map[string]struct{ deploy, pods, running int }{
		"a": {2, 2, 2},
		"b": {1, 2, 1},
		"c": {0, 1, 0},
	}
	if len(res.Namespaces) != len(want) {
		t.Fatalf("got %d namespaces, want %d", len(res.Namespaces), len(want))
	}
	for _, ns := range res.Namespaces {
		w, ok := want[ns.Name]
		if !ok {
			t.Fatalf("unexpected namespace %q", ns.Name)
		}
		if ns.DeploymentCount != w.deploy {
			t.Errorf("ns %s DeploymentCount = %d, want %d", ns.Name, ns.DeploymentCount, w.deploy)
		}
		if ns.PodCount != w.pods {
			t.Errorf("ns %s PodCount = %d, want %d", ns.Name, ns.PodCount, w.pods)
		}
		if ns.RunningPodCount != w.running {
			t.Errorf("ns %s RunningPodCount = %d, want %d", ns.Name, ns.RunningPodCount, w.running)
		}
	}
	if res.Summary.TotalDeployments != 3 {
		t.Errorf("TotalDeployments = %d, want 3", res.Summary.TotalDeployments)
	}
	if res.Summary.UnscheduledPods != 1 {
		t.Errorf("UnscheduledPods = %d, want 1", res.Summary.UnscheduledPods)
	}
}
