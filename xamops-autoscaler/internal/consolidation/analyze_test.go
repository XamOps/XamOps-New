package consolidation

import (
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"

	"github.com/xamops/automation/internal/fitsim"
)

// verdict finds a node in the plan: "EMPTY"/"DRAIN" (move), "blocked" or "kept".
func verdict(p Plan, node string) (string, []Blocker, string) {
	for _, m := range p.Moves {
		if m.Node.Name == node {
			return string(m.Type), nil, ""
		}
	}
	for _, b := range p.Blocked {
		if b.Node.Name == node {
			return "blocked", b.Blockers, ""
		}
	}
	for _, k := range p.Kept {
		if k.Node.Name == node {
			return "kept", nil, k.Reason
		}
	}
	return "missing", nil, ""
}

// candidateNode adds a 1 CPU / 4Gi DRAIN-sized node "cand" in ng-a with 2
// DaemonSet pods; base adds two kept receivers in ng-a.
func base() *objects {
	o := &objects{}
	o.receiver("recv-1", "ng-a")
	o.receiver("recv-2", "ng-a")
	return o
}

func (o *objects) candidateNode() *corev1.Node {
	n := o.node("cand", "ng-a", "1", "4Gi")
	o.dsPods("cand", 2, "100m", "128Mi")
	return n
}

// One case per blocker reason: the node "cand" is blocked with exactly that code and
// human reason.
func TestAnalyzeBlockerReasons(t *testing.T) {
	cases := []struct {
		name   string
		build  func() *objects
		pod    string
		code   string
		reason string
	}{
		{"bare pod", func() *objects {
			o := base()
			o.candidateNode()
			o.pod("team-a", "bare", "cand", "", "", "100m", "128Mi", nil)
			return o
		}, "team-a/bare", fitsim.ReasonNoController, "bare pod (no controller)"},
		{"emptyDir with 1 replica", func() *objects {
			o := base()
			o.candidateNode()
			o.rs("team-a", "c-rs", 1)
			p := o.pod("team-a", "c", "cand", "ReplicaSet", "c-rs", "100m", "128Mi", nil)
			p.Spec.Volumes = []corev1.Volume{{Name: "s", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}}}
			return o
		}, "team-a/c", CodeLocalStorageNoReplica, "local storage, no redundant replica"},
		{"kube-system without PDB", func() *objects {
			o := base()
			o.candidateNode()
			o.rs("kube-system", "dns-rs", 2)
			o.pod("kube-system", "dns", "cand", "ReplicaSet", "dns-rs", "100m", "128Mi", map[string]string{"k8s-app": "dns"})
			return o
		}, "kube-system/dns", CodeKubeSystemNoPDB, "kube-system pod without PDB"},
		{"PDB at limit", func() *objects {
			o := base()
			o.candidateNode()
			o.rs("team-a", "web-rs", 2)
			o.pdb("team-a", "web", map[string]string{"app": "web"}, 0)
			o.pod("team-a", "web-1", "cand", "ReplicaSet", "web-rs", "100m", "128Mi", map[string]string{"app": "web"})
			return o
		}, "team-a/web-1", "pdb-no-disruptions:team-a/web", "PodDisruptionBudget team-a/web at limit"},
		{"safe-to-evict=false", func() *objects {
			o := base()
			o.candidateNode()
			o.rs("team-a", "s-rs", 2)
			p := o.pod("team-a", "s", "cand", "ReplicaSet", "s-rs", "100m", "128Mi", nil)
			p.Annotations = map[string]string{fitsim.SafeToEvictKey: "false"}
			return o
		}, "team-a/s", fitsim.ReasonSafeToEvictFalse, "do-not-evict annotation"},
		{"karpenter do-not-disrupt", func() *objects {
			o := base()
			o.candidateNode()
			o.rs("team-a", "d-rs", 2)
			p := o.pod("team-a", "d", "cand", "ReplicaSet", "d-rs", "100m", "128Mi", nil)
			p.Annotations = map[string]string{AnnotationKarpenterDoNotDisrupt: "true"}
			return o
		}, "team-a/d", CodeDoNotDisrupt, "do-not-evict annotation"},
		{"cloudk8s do-not-evict", func() *objects {
			o := base()
			o.candidateNode()
			o.rs("team-a", "d-rs", 2)
			p := o.pod("team-a", "d", "cand", "ReplicaSet", "d-rs", "100m", "128Mi", nil)
			p.Annotations = map[string]string{AnnotationDoNotEvict: "TRUE"}
			return o
		}, "team-a/d", CodeDoNotDisrupt, "do-not-evict annotation"},
		{"removal-disabled", func() *objects {
			o := base()
			o.candidateNode().Labels[LabelRemovalDisabled] = "true"
			return o
		}, "", CodeRemovalDisabled, "removal disabled (label cloudk8s.xammer.in/removal-disabled)"},
		{"cordoned", func() *objects {
			o := base()
			o.candidateNode().Spec.Unschedulable = true
			return o
		}, "", CodeNodeCordoned, "node is cordoned"},
		{"not Ready", func() *objects {
			o := base()
			o.candidateNode().Status.Conditions[0].Status = corev1.ConditionUnknown
			return o
		}, "", CodeNodeNotReady, "node is not Ready"},
		{"Fargate", func() *objects {
			o := base()
			n := o.candidateNode()
			delete(n.Labels, LabelNodeGroup)
			n.Labels[LabelComputeType] = "fargate"
			return o
		}, "", CodeFargateNode, "Fargate node"},
		{"unmanaged", func() *objects {
			o := base()
			delete(o.candidateNode().Labels, LabelNodeGroup)
			return o
		}, "", CodeUnmanagedNode, "unmanaged node (no EKS node group or Karpenter node pool)"},
		{"last node in group", func() *objects {
			o := base()
			o.candidateNode().Labels[LabelNodeGroup] = "ng-solo"
			return o
		}, "", CodeLastNodeInGroup, "last node in node group ng-solo"},
		{"no room in plan", func() *objects {
			// One receiver with 1000m free at 85%; cand-a and cand-b each fit alone
			// (600m) but not together. cand-a goes first (name), cand is blocked.
			o := &objects{}
			o.receiver("recv-1", "ng-a")
			o.rs("team-a", "w-rs", 4)
			for _, n := range []string{"cand", "cand-a"} {
				o.node(n, "ng-a", "2", "8Gi")
				o.dsPods(n, 2, "100m", "128Mi")
			}
			o.pod("team-a", "w-1", "cand-a", "ReplicaSet", "w-rs", "600m", "256Mi", nil)
			o.pod("team-a", "w-2", "cand", "ReplicaSet", "w-rs", "601m", "256Mi", nil)
			return o
		}, "", CodeNoRoomInPlan, "pods don't fit once the other planned nodes are removed"},
		{"strand with headroom", func() *objects {
			// recv-1/recv-2 have 1600m free at 100% but 1000m at 85%.
			o := base()
			o.node("cand", "ng-a", "4", "16Gi")
			o.dsPods("cand", 2, "100m", "128Mi")
			o.rs("team-a", "b-rs", 2)
			o.pod("team-a", "big", "cand", "ReplicaSet", "b-rs", "1500m", "1Gi", nil)
			return o
		}, "team-a/big", CodeStrand, "no other node has room (keeping 15% headroom)"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := Analyze(tc.build().input(), testOptions(), goldenNow)
			v, bs, _ := verdict(p, "cand")
			if v != "blocked" {
				t.Fatalf("cand is %s, want blocked: %+v", v, p)
			}
			found := false
			for _, b := range bs {
				if b.Pod == tc.pod && b.Code == tc.code {
					found = true
					if b.Reason != tc.reason {
						t.Errorf("reason = %q, want %q", b.Reason, tc.reason)
					}
				}
			}
			if !found {
				t.Errorf("blockers %+v lack {pod %q code %q}", bs, tc.pod, tc.code)
			}
		})
	}
}

// The strand case is a headroom effect: without the 15% headroom the pod fits.
func TestAnalyzeHeadroomStrandFitsAtFullAllocatable(t *testing.T) {
	o := headroomFixture()
	in := o.input()
	full := fitsim.FromObjects(in.Nodes, in.Pods, in.PDBs, in.ReplicaSets, in.StatefulSets)
	if p := fitsim.SimulateDrain(full, []string{"src-big"}, testOptions()); !p.Drainable {
		t.Fatalf("src-big is not drainable at 100%% allocatable: %s", p.Summary)
	}
	if v, bs, _ := verdict(Analyze(in, testOptions(), goldenNow), "src-big"); v != "blocked" || bs[0].Code != CodeStrand {
		t.Errorf("src-big = %s %+v, want blocked strand", v, bs)
	}
}

// Placement and percentages use effective requests (allocatedResources included),
// never usage: the moved pod carries its request and the node's requested % counts
// DaemonSet requests.
func TestAnalyzeUsesRequestsNotUsage(t *testing.T) {
	o := emptyAndDrainFixture()
	// A resize in flight: allocatedResources above the spec request counts.
	for i := range o.pods {
		if o.pods[i].Name == "web-1" {
			o.pods[i].Status.ContainerStatuses = []corev1.ContainerStatus{{Name: "app",
				AllocatedResources: corev1.ResourceList{corev1.ResourceCPU: mustQ("300m")}}}
		}
	}
	p := Analyze(o.input(), testOptions(), goldenNow)
	var drain *Move
	for i := range p.Moves {
		if p.Moves[i].Type == MoveDrain {
			drain = &p.Moves[i]
		}
	}
	if drain == nil || drain.Node.Name != "node-2" {
		t.Fatalf("moves = %+v, want a DRAIN of node-2", p.Moves)
	}
	// 200m DaemonSets + 300m + 250m of 2000m.
	if drain.Node.CPURequestedPct != 37.5 {
		t.Errorf("cpuRequestedPct = %v, want 37.5", drain.Node.CPURequestedPct)
	}
	for _, pm := range drain.Pods {
		want := int64(250)
		if pm.Pod == "team-a/web-1" {
			want = 300
		}
		if pm.CPUMillis != want {
			t.Errorf("%s cpuMillis = %d, want %d", pm.Pod, pm.CPUMillis, want)
		}
	}
}

// Precedence: node-level blockers before EMPTY, EMPTY before the utilization test,
// and exactly 50% is not below the threshold.
func TestAnalyzePrecedence(t *testing.T) {
	o := base()
	// DaemonSet-only node above 50%: still EMPTY.
	o.node("ds-heavy", "ng-a", "1", "4Gi")
	o.dsPods("ds-heavy", 3, "300m", "128Mi")
	// Empty but not Ready: blocked, not EMPTY.
	n := o.node("empty-notready", "ng-a", "1", "4Gi")
	n.Status.Conditions[0].Status = corev1.ConditionFalse
	// Exactly 50% CPU: kept.
	o.node("half", "ng-a", "2", "8Gi")
	o.rs("team-a", "h-rs", 3)
	o.pod("team-a", "h", "half", "ReplicaSet", "h-rs", "1000m", "256Mi", nil)
	// 49.9% CPU and low memory: DRAIN.
	o.node("under", "ng-a", "2", "8Gi")
	o.pod("team-a", "u", "under", "ReplicaSet", "h-rs", "998m", "256Mi", nil)

	p := Analyze(o.input(), testOptions(), goldenNow)
	for node, want := range map[string]string{
		"ds-heavy": "EMPTY", "empty-notready": "blocked", "half": "kept", "under": "DRAIN",
		"recv-1": "kept", "recv-2": "kept",
	} {
		if v, _, _ := verdict(p, node); v != want {
			t.Errorf("%s = %s, want %s", node, v, want)
		}
	}
	if _, _, reason := verdict(p, "half"); reason != "requests above 50% of allocatable (CPU 50.0%, memory 3.1%)" {
		t.Errorf("half kept reason = %q", reason)
	}
}

// Every fitsim reason code and every package code has a human reason.
func TestReasonCoversEveryCode(t *testing.T) {
	codes := []string{
		fitsim.ReasonPodAffinity, fitsim.ReasonPodAntiAffinity, fitsim.ReasonTopologySpread,
		fitsim.ReasonPVCVolume, fitsim.ReasonEphemeralVolume, fitsim.ReasonHostPort,
		fitsim.ReasonExtendedResource + ":nvidia.com/gpu",
		fitsim.ReasonCriticalNamespace, fitsim.ReasonSelfNamespace, fitsim.ReasonStatefulSet,
		fitsim.ReasonSingleReplica, fitsim.ReasonReplicasUnknown, fitsim.ReasonNoController,
		fitsim.ReasonJobOwner, fitsim.ReasonUnknownOwnerKind + ":CronTab", fitsim.ReasonSafeToEvictFalse,
		fitsim.ReasonMultiplePDBs + ":a/x,a/y", fitsim.ReasonPDBNoDisruptions + ":a/x", fitsim.ReasonPDBStale + ":a/x",
		CodeStrand, CodeLocalStorageNoReplica, CodeKubeSystemNoPDB, CodeDoNotDisrupt,
		CodeNodeNotReady, CodeNodeCordoned, CodeRemovalDisabled, CodeFargateNode, CodeUnmanagedNode,
		CodeNoRoomInPlan, CodePlanLimitReached,
	}
	for _, c := range codes {
		r := Reason(c)
		if r == c || r == "" || strings.Contains(r, "%") && !strings.Contains(r, "% headroom") {
			t.Errorf("Reason(%q) = %q", c, r)
		}
	}
	for code, want := range map[string]string{
		"pdb-no-disruptions:ns/name":  "PodDisruptionBudget ns/name at limit",
		"no-controller":               "bare pod (no controller)",
		"safe-to-evict-false":         "do-not-evict annotation",
		"strand":                      "no other node has room (keeping 15% headroom)",
		"unknown-owner-kind:CronTab":  "owner kind CronTab is not supported",
		"extended-resource:a.com/x,b": "requests extended resource a.com/x,b",
		"something-new":               "something-new",
	} {
		if got := Reason(code); got != want {
			t.Errorf("Reason(%q) = %q, want %q", code, got, want)
		}
	}
}

// v1 defines exactly two move types.
func TestOnlyEmptyAndDrainMoveTypes(t *testing.T) {
	if MoveEmpty != "EMPTY" || MoveDrain != "DRAIN" {
		t.Fatalf("move types = %q, %q", MoveEmpty, MoveDrain)
	}
}

// Empty lists marshal as [] (no omitempty, no null).
func TestPlanMarshalsEmptyListsAsArrays(t *testing.T) {
	b := planJSON(t, Analyze(Input{}, testOptions(), goldenNow))
	for _, want := range []string{`"moves": []`, `"blocked": []`, `"kept": []`, `"evidence": []`, `"generatedAt": "2026-10-24T00:00:00Z"`} {
		if !strings.Contains(string(b), want) {
			t.Errorf("plan lacks %s:\n%s", want, b)
		}
	}
	if strings.Contains(string(b), "null") {
		t.Errorf("plan has a null:\n%s", b)
	}
}
