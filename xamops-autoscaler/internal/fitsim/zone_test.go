package fitsim

import (
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

const zoneLabel = "topology.kubernetes.io/zone"

func zonedNode(o *objects, name, zone string) {
	n := o.node(name, "4", "8Gi", 30)
	n.Labels[zoneLabel] = zone
}

// boundPVC returns a bound PVC and its PV carrying a single zone node-affinity term.
func boundPVC(ns, name, zone string) (corev1.PersistentVolumeClaim, corev1.PersistentVolume) {
	pvName := "pv-" + name
	pvc := corev1.PersistentVolumeClaim{
		TypeMeta:   metav1.TypeMeta{Kind: "PersistentVolumeClaim", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Namespace: ns, Name: name},
		Spec:       corev1.PersistentVolumeClaimSpec{VolumeName: pvName},
		Status:     corev1.PersistentVolumeClaimStatus{Phase: corev1.ClaimBound},
	}
	pv := corev1.PersistentVolume{
		TypeMeta:   metav1.TypeMeta{Kind: "PersistentVolume", APIVersion: "v1"},
		ObjectMeta: metav1.ObjectMeta{Name: pvName},
		Spec: corev1.PersistentVolumeSpec{NodeAffinity: &corev1.VolumeNodeAffinity{
			Required: &corev1.NodeSelector{NodeSelectorTerms: []corev1.NodeSelectorTerm{{
				MatchExpressions: []corev1.NodeSelectorRequirement{{
					Key: zoneLabel, Operator: corev1.NodeSelectorOpIn, Values: []string{zone},
				}},
			}}},
		}},
	}
	return pvc, pv
}

// attachPVC gives pod one PVC volume.
func attachPVC(p *corev1.Pod, claim string) {
	p.Spec.Volumes = append(p.Spec.Volumes, corev1.Volume{
		Name:         "data",
		VolumeSource: corev1.VolumeSource{PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: claim}},
	})
}

// A non-DB zonal-PV pod is placed only on a same-AZ node; a cross-AZ target strands.
func TestZonalPVSameAZPlaces(t *testing.T) {
	o := &objects{}
	zonedNode(o, "node-1a", "ap-south-1a") // drain source
	zonedNode(o, "node-1a-2", "ap-south-1a")
	o.rs("app", "web-rs", 3)
	p := o.pod("app", "web-0", "node-1a", "ReplicaSet", "web-rs", "100m", "128Mi", map[string]string{"app": "web"})
	attachPVC(p, "web-0-pvc")
	pvc, pv := boundPVC("app", "web-0-pvc", "ap-south-1a")

	snap := FromObjectsWithCRs(o.nodes, o.pods, o.pdbs, o.rss, o.stss,
		[]corev1.PersistentVolumeClaim{pvc}, []corev1.PersistentVolume{pv}, nil)
	plan := SimulateDrain(snap, []string{"node-1a"}, testOptions())
	if len(plan.Refused) != 0 {
		t.Fatalf("modellable zonal PVC should NOT be refused: %+v", plan.Refused)
	}
	if !plan.Drainable {
		t.Fatalf("same-AZ node-1a-2 should accept the pod: %s", plan.Summary)
	}
	if len(plan.Moves) != 1 || plan.Moves[0].To != "node-1a-2" {
		t.Fatalf("want one move to node-1a-2, got %+v", plan.Moves)
	}
}

func TestZonalPVCrossAZStrands(t *testing.T) {
	o := &objects{}
	zonedNode(o, "node-1a", "ap-south-1a") // drain source
	zonedNode(o, "node-1b", "ap-south-1b") // only target, wrong AZ
	o.rs("app", "web-rs", 3)
	p := o.pod("app", "web-0", "node-1a", "ReplicaSet", "web-rs", "100m", "128Mi", map[string]string{"app": "web"})
	attachPVC(p, "web-0-pvc")
	pvc, pv := boundPVC("app", "web-0-pvc", "ap-south-1a")

	snap := FromObjectsWithCRs(o.nodes, o.pods, o.pdbs, o.rss, o.stss,
		[]corev1.PersistentVolumeClaim{pvc}, []corev1.PersistentVolume{pv}, nil)
	plan := SimulateDrain(snap, []string{"node-1a"}, testOptions())
	if plan.Drainable {
		t.Fatalf("cross-AZ only target must strand the pod, got drainable: %s", plan.Summary)
	}
	if len(plan.Strands) != 1 || plan.Strands[0] != "app/web-0" {
		t.Fatalf("want app/web-0 stranded, got %+v", plan.Strands)
	}
}

// An unbound PVC (no PV) stays refused with the narrower pvc-volume-unmodelled code.
func TestUnmodellablePVCRefused(t *testing.T) {
	o := &objects{}
	zonedNode(o, "node-1a", "ap-south-1a")
	zonedNode(o, "node-1a-2", "ap-south-1a")
	o.rs("app", "web-rs", 3)
	p := o.pod("app", "web-0", "node-1a", "ReplicaSet", "web-rs", "100m", "128Mi", map[string]string{"app": "web"})
	attachPVC(p, "missing-pvc")

	snap := FromObjectsWithCRs(o.nodes, o.pods, o.pdbs, o.rss, o.stss, nil, nil, nil)
	plan := SimulateDrain(snap, []string{"node-1a"}, testOptions())
	if len(plan.Refused) != 1 || plan.Refused[0].Reason != ReasonPVCVolumeUnmodelled {
		t.Fatalf("want one %s refusal, got %+v", ReasonPVCVolumeUnmodelled, plan.Refused)
	}
	if strings.Contains(plan.Refused[0].Reason, ReasonPVCVolume+";") || plan.Refused[0].Reason == ReasonPVCVolume {
		t.Fatalf("blanket pvc-volume should be narrowed, got %q", plan.Refused[0].Reason)
	}
}

// A detected CNPG primary is refused with db-primary BEFORE the zone predicate, even
// though its PV zone is fully modellable.
func TestDBVerdictRefusedBeforeZone(t *testing.T) {
	o := &objects{}
	zonedNode(o, "node-1a", "ap-south-1a")
	zonedNode(o, "node-1a-2", "ap-south-1a")
	o.sts("db", "pg", 2)
	p := o.pod("db", "pg-1", "node-1a", "StatefulSet", "pg", "100m", "128Mi",
		map[string]string{"cnpg.io/cluster": "pg", "cnpg.io/instanceRole": "primary"})
	attachPVC(p, "pg-1-pvc")
	pvc, pv := boundPVC("db", "pg-1-pvc", "ap-south-1a")

	cr := unstructured.Unstructured{Object: map[string]interface{}{
		"apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
		"metadata": map[string]interface{}{"name": "pg", "namespace": "db"},
		"status":   map[string]interface{}{"currentPrimary": "pg-1"},
	}}
	snap := FromObjectsWithCRs(o.nodes, o.pods, o.pdbs, o.rss, o.stss,
		[]corev1.PersistentVolumeClaim{pvc}, []corev1.PersistentVolume{pv}, []unstructured.Unstructured{cr})
	plan := SimulateDrain(snap, []string{"node-1a"}, testOptions())
	if len(plan.Refused) != 1 || plan.Refused[0].Reason != "db-primary" {
		t.Fatalf("want one db-primary refusal, got %+v", plan.Refused)
	}
}
