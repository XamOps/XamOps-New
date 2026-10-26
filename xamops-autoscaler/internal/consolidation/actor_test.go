package consolidation

import (
	"reflect"
	"testing"
)

func TestDetectActor(t *testing.T) {
	cases := []struct {
		name     string
		build    func(o *objects)
		kind     string
		evidence []string
	}{
		{"karpenter node label", func(o *objects) {
			o.rawNode("n1", "2", "4Gi", 29, map[string]string{LabelKarpenterNodePool: "default"})
			o.rawNode("n2", "2", "4Gi", 29, map[string]string{LabelKarpenterProv: "legacy"})
		}, ActorKarpenter, []string{
			"node label karpenter.sh/nodepool on 1 node(s)",
			"node label karpenter.sh/provisioner-name on 1 node(s)",
		}},
		{"karpenter deployment by name", func(o *objects) {
			o.deployment("karpenter", "karpenter", nil, nil)
		}, ActorKarpenter, []string{"Deployment karpenter/karpenter"}},
		{"karpenter deployment by label", func(o *objects) {
			o.deployment("kube-system", "kp", i32(1), map[string]string{"app.kubernetes.io/name": "karpenter"})
		}, ActorKarpenter, []string{"Deployment kube-system/kp"}},
		{"cluster autoscaler by name", func(o *objects) {
			o.deployment("kube-system", "cluster-autoscaler-aws", i32(1), nil)
		}, ActorClusterAutoscaler, []string{"Deployment kube-system/cluster-autoscaler-aws"}},
		{"cluster autoscaler by app label", func(o *objects) {
			o.deployment("kube-system", "ca", i32(1), map[string]string{"app": "aws-cluster-autoscaler"})
		}, ActorClusterAutoscaler, []string{"Deployment kube-system/ca"}},
		{"cluster autoscaler by name label", func(o *objects) {
			o.deployment("kube-system", "ca", nil, map[string]string{"app.kubernetes.io/name": "cluster-autoscaler"})
		}, ActorClusterAutoscaler, []string{"Deployment kube-system/ca"}},
		{"both", func(o *objects) {
			o.rawNode("n1", "2", "4Gi", 29, map[string]string{LabelKarpenterNodePool: "default"})
			o.deployment("kube-system", "cluster-autoscaler", i32(1), nil)
		}, ActorMultiple, []string{
			"Deployment kube-system/cluster-autoscaler",
			"node label karpenter.sh/nodepool on 1 node(s)",
		}},
		{"none", func(o *objects) {
			o.node("n1", "ng-a", "2", "4Gi")
			o.deployment("default", "web", i32(3), nil)
		}, ActorNone, []string{}},
		{"cluster autoscaler scaled to 0 is not detected", func(o *objects) {
			o.deployment("kube-system", "cluster-autoscaler", i32(0), map[string]string{"app": "cluster-autoscaler"})
		}, ActorNone, []string{}},
		{"karpenter scaled to 0 is not detected", func(o *objects) {
			o.deployment("karpenter", "karpenter", i32(0), nil)
		}, ActorNone, []string{}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			o := &objects{}
			tc.build(o)
			a := DetectActor(o.nodes, o.deps)
			if a.Kind != tc.kind || !reflect.DeepEqual(a.Evidence, tc.evidence) {
				t.Errorf("actor = %+v, want kind %s evidence %v", a, tc.kind, tc.evidence)
			}
			if a.Note == "" {
				t.Error("empty note")
			}
			if tc.kind == ActorNone && a.Note != NoneNote {
				t.Errorf("NONE note = %q", a.Note)
			}
		})
	}
}
