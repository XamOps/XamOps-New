package fitsim

import (
	"context"
	"encoding/json"
	"testing"

	"k8s.io/client-go/kubernetes/fake"
)

// BuildSnapshot over a fake clientset issues only list calls and yields the same
// plans as FromObjects over the same objects (ControllerReplicas resolved from the
// listed ReplicaSets).
func TestBuildSnapshotMatchesFromObjects(t *testing.T) {
	o := section4Fixture()
	c := fake.NewSimpleClientset(o.runtimeObjects()...)
	s, err := BuildSnapshot(context.Background(), c, nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, a := range c.Actions() {
		if a.GetVerb() != "list" {
			t.Errorf("unexpected %s %s", a.GetVerb(), a.GetResource().Resource)
		}
	}
	if len(c.Actions()) != 7 {
		t.Errorf("expected 7 lists (nodes, pods, pdbs, rs, sts, pvc, pv), got %d", len(c.Actions()))
	}
	ref := o.snapshot()
	for _, n := range []string{"node-a", "node-b", "node-c", "node-d"} {
		a, _ := json.Marshal(SimulateDrain(s, []string{n}, testOptions()))
		b, _ := json.Marshal(SimulateDrain(ref, []string{n}, testOptions()))
		if string(a) != string(b) {
			t.Errorf("%s: BuildSnapshot plan differs:\n%s\n%s", n, a, b)
		}
	}
	var withReplicas int
	for _, p := range s.Pods {
		if p.ControllerReplicas != nil {
			withReplicas++
		}
	}
	if withReplicas != 14 { // every non-DaemonSet pod of the fixture
		t.Errorf("pods with resolved replicas = %d, want 14", withReplicas)
	}
}

// A ReplicaSet with the right name but another UID does not resolve the replicas.
func TestReplicasRequireMatchingOwnerUID(t *testing.T) {
	o := &objects{}
	o.rs("apps", "rs", 1)
	o.node("n", "1", "1Gi", 10)
	p := o.pod("apps", "p", "n", "ReplicaSet", "rs", "10m", "1Mi", nil)
	p.OwnerReferences[0].UID = "other-uid"
	s := o.snapshot()
	if s.Pods[0].ControllerReplicas != nil {
		t.Errorf("replicas resolved across a UID mismatch")
	}
}
