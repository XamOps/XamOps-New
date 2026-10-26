package consolidation

import (
	corev1 "k8s.io/api/core/v1"
)

// Synthetic golden fixtures. Each is written to testdata/<name>.json as a
// kubectl-style List (CONSOLIDATION_WRITE_TESTDATA=1) and its plan to
// testdata/golden/<name>.plan.json (CONSOLIDATION_UPDATE_GOLDEN=1).
var syntheticFixtures = map[string]func() *objects{
	"empty-and-drain":          emptyAndDrainFixture,
	"blocked-reasons":          blockedReasonsFixture,
	"last-node-in-group":       lastNodeInGroupFixture,
	"headroom":                 headroomFixture,
	"actor-karpenter":          actorKarpenterFixture,
	"actor-cluster-autoscaler": actorClusterAutoscalerFixture,
	"all-blocked":              allBlockedFixture,
	// Stateful / zone cases (FEAT-005 step 1): each carries a committed plan with the
	// new db-* and pvc-volume-unmodelled codes.
	"zonal-pv-places":  zonalPVPlacesFixture,
	"zonal-pv-strands": zonalPVStrandsFixture,
	"cnpg-cluster":     cnpgClusterFixture,
	"single-mysql":     singleMySQLFixture,
	"mongo-rs":         mongoRSFixture,
	"emptydir-db":      emptyDirDBFixture,
	"pdb-dense":        pdbDenseFixture,
}

// zoneNode adds a Ready stateful-pool node in zone with 4 CPU / 16Gi and 2
// DaemonSet pods (100m).
func (o *objects) zoneNode(name, group, zone string) *corev1.Node {
	n := o.node(name, group, "4", "16Gi")
	n.Labels[LabelZone] = zone
	o.dsPods(name, 2, "100m", "128Mi")
	return n
}

// zonalPVPlacesFixture: a drainable (ReplicaSet, non-DB) pod bound to a zonal PV in
// ap-south-1a drains to the in-zone receiver and never to the busy 1b node (same AZ
// places). The PV zone-affinity models, so there is NO pvc-volume refusal — the
// zone predicate in fits() does the work. z-src is underutilized; z-recv (1a) is a
// receiver with room; z-far (1b) is kept busy; z-anchor holds the group floor.
func zonalPVPlacesFixture() *objects {
	o := &objects{}
	o.zoneNode("z-src", "ng-z", "ap-south-1a")
	o.rs("team-z", "cache-rs", 3)
	p := o.pod("team-z", "cache-0", "z-src", "ReplicaSet", "cache-rs", "200m", "256Mi", map[string]string{"app": "cache"})
	setImage(p, "nginx:1.27")
	pvcVol(p, "data", "data-cache-0")
	o.zonalPVC("team-z", "data-cache-0", "ap-south-1a")
	// Two kept receivers (busy, so neither is an EMPTY candidate) each with ~1000m
	// free: z-far (1b) sorts first, so without the zone predicate the pod would land
	// there; the in-zone z-recv (1a) is the only valid target.
	o.receiver("z-far", "ng-z")
	o.nodes[len(o.nodes)-1].Labels[LabelZone] = "ap-south-1b"
	o.receiver("z-recv", "ng-z")
	o.nodes[len(o.nodes)-1].Labels[LabelZone] = "ap-south-1a"
	return o
}

// zonalPVStrandsFixture: the same drainable pod, but every other node is in a
// different AZ, so it strands rather than crossing AZs (cross-AZ strands). The node
// is blocked by the strand, not by a pvc refusal.
func zonalPVStrandsFixture() *objects {
	o := &objects{}
	o.zoneNode("z-src", "ng-z", "ap-south-1a")
	o.rs("team-z", "cache-rs", 3)
	p := o.pod("team-z", "cache-0", "z-src", "ReplicaSet", "cache-rs", "200m", "256Mi", map[string]string{"app": "cache"})
	setImage(p, "nginx:1.27")
	pvcVol(p, "data", "data-cache-0")
	o.zonalPVC("team-z", "data-cache-0", "ap-south-1a")
	// Both receivers are in 1b: the pod's 1a volume cannot follow it, so it strands.
	o.zoneNode("z-far-1", "ng-z", "ap-south-1b")
	o.zoneNode("z-far-2", "ng-z", "ap-south-1b")
	return o
}

// cnpgClusterFixture: a CloudNativePG Cluster of two instances (primary +
// synchronous/quorum standby) resolved through the Cluster CR status and
// spec.postgresql.synchronous. Both pods are refused (db-primary / db-sync-standby);
// their node is blocked.
func cnpgClusterFixture() *objects {
	o := &objects{}
	o.zoneNode("cnpg-1", "ng-db", "ap-south-1a")
	o.zoneNode("cnpg-2", "ng-db", "ap-south-1b")
	o.zoneNode("cnpg-anchor", "ng-db", "ap-south-1a")
	o.pod("team-db", "filler", "cnpg-anchor", "ReplicaSet", "filler-rs", "2200m", "4Gi", map[string]string{"app": "filler"})
	o.rs("team-db", "filler-rs", 2)
	o.cnpgCluster("team-db", "pg", "pg-1", 1)
	for _, inst := range []struct{ name, node, zone, role string }{
		{"pg-1", "cnpg-1", "ap-south-1a", "primary"},
		{"pg-2", "cnpg-2", "ap-south-1b", "replica"},
	} {
		p := o.pod("team-db", inst.name, inst.node, "Cluster", "pg", "200m", "256Mi", map[string]string{
			"cnpg.io/cluster": "pg", "cnpg.io/instanceRole": inst.role})
		p.OwnerReferences[0].APIVersion = "postgresql.cnpg.io/v1"
		setImage(p, "postgres:16.10")
		pvcVol(p, "pgdata", inst.name)
		o.zonalPVC("team-db", inst.name, inst.zone)
	}
	return o
}

// singleMySQLFixture: a single-instance MySQL StatefulSet with a zonal PVC and no
// PDB. Detected as a database of unknown role (no role label), refused
// db-role-unknown; the single replica also yields local-storage-no-replica? No —
// MySQL here has no emptyDir, so only db-role-unknown applies.
func singleMySQLFixture() *objects {
	o := &objects{}
	o.zoneNode("my-src", "ng-db", "ap-south-1a")
	o.zoneNode("my-anchor", "ng-db", "ap-south-1a")
	o.pod("team-db", "filler", "my-anchor", "ReplicaSet", "filler-rs", "2200m", "4Gi", map[string]string{"app": "filler"})
	o.rs("team-db", "filler-rs", 2)
	o.sts("team-db", "mysql", 1)
	p := o.pod("team-db", "mysql-0", "my-src", "StatefulSet", "mysql", "200m", "256Mi", map[string]string{"app": "mysql"})
	setImage(p, "mysql:8.4.6")
	pvcVol(p, "data", "data-mysql-0")
	o.zonalPVC("team-db", "data-mysql-0", "ap-south-1a")
	return o
}

// mongoRSFixture: a 3-replica MongoDB StatefulSet (no operator) with zonal PVCs,
// detected by image as a database of unknown role (db-role-unknown).
func mongoRSFixture() *objects {
	o := &objects{}
	o.sts("team-db", "mongo", 3)
	for i, zone := range []string{"ap-south-1a", "ap-south-1b", "ap-south-1c"} {
		node := "mongo-" + string(rune('1'+i))
		o.zoneNode(node, "ng-db", zone)
		name := "mongo-" + string(rune('0'+i))
		p := o.pod("team-db", name, node, "StatefulSet", "mongo", "200m", "256Mi", map[string]string{"app": "mongo"})
		setImage(p, "mongo:7.0.24")
		pvcVol(p, "data", "data-"+name)
		o.zonalPVC("team-db", "data-"+name, zone)
	}
	return o
}

// emptyDirDBFixture: a database on an emptyDir, refused db-emptydir regardless of
// replica count (here 2 replicas, so the look-only local-storage-no-replica rule
// does NOT fire — only the fail-closed db-emptydir refusal keeps it blocked).
func emptyDirDBFixture() *objects {
	o := &objects{}
	o.sts("team-db", "pg-unsafe", 2)
	for i, zone := range []string{"ap-south-1a", "ap-south-1b"} {
		node := "ed-" + string(rune('1'+i))
		o.zoneNode(node, "ng-db", zone)
		name := "pg-unsafe-" + string(rune('0'+i))
		p := o.pod("team-db", name, node, "StatefulSet", "pg-unsafe", "200m", "256Mi", map[string]string{"app": "pg-unsafe"})
		setImage(p, "postgres:16.10")
		emptyDirVol(p, "data")
	}
	o.zoneNode("ed-anchor", "ng-db", "ap-south-1a")
	o.pod("team-db", "filler", "ed-anchor", "ReplicaSet", "filler-rs", "2200m", "4Gi", map[string]string{"app": "filler"})
	o.rs("team-db", "filler-rs", 2)
	return o
}

// pdbDenseFixture: one underutilized node whose web pods are each selected by two
// PDBs (one at its limit), so the pod is blocked by multiple-pdbs and the at-limit
// PDB; a receiver keeps the group above the floor.
func pdbDenseFixture() *objects {
	o := &objects{}
	o.node("pd-src", "ng-a", "4", "16Gi")
	o.dsPods("pd-src", 2, "100m", "128Mi")
	web := map[string]string{"app": "web", "tier": "frontend"}
	o.rs("team-a", "web-rs", 4)
	o.pod("team-a", "web-1", "pd-src", "ReplicaSet", "web-rs", "200m", "256Mi", web)
	o.pod("team-a", "web-2", "pd-src", "ReplicaSet", "web-rs", "200m", "256Mi", web)
	o.pdb("team-a", "web-app", map[string]string{"app": "web"}, 1)
	o.pdb("team-a", "web-tier", map[string]string{"tier": "frontend"}, 0)
	o.receiver("pd-recv-1", "ng-a")
	o.receiver("pd-recv-2", "ng-a")
	return o
}

// receiver adds a 4 CPU / 16Gi node of group with 2 DaemonSet pods (100m) and a
// 2200m filler pod: 60% requested (kept), 1000m free at 85%, 1600m free at 100%.
func (o *objects) receiver(name, group string) {
	o.node(name, group, "4", "16Gi")
	o.dsPods(name, 2, "100m", "128Mi")
	o.pod("team-z", "filler-"+name, name, "ReplicaSet", "filler-rs", "2200m", "4Gi", map[string]string{"app": "filler"})
}

// emptyAndDrainFixture: node group ng-a, 4 nodes of 2 CPU / 8Gi, 2 DaemonSet pods
// (100m/128Mi) each.
//
//	node-1: DaemonSet pods only                         -> EMPTY
//	node-2: web-1, web-2 (250m)       35% CPU          -> DRAIN (web-1 -> node-3, web-2 -> node-4)
//	node-3: api-1 (900m) + web-3      67.5% CPU        -> kept (350m free at 85%)
//	node-4: api-2 (900m) + web-4      67.5% CPU        -> kept
func emptyAndDrainFixture() *objects {
	o := &objects{}
	for _, n := range []string{"node-1", "node-2", "node-3", "node-4"} {
		o.node(n, "ng-a", "2", "8Gi")
		o.dsPods(n, 2, "100m", "128Mi")
	}
	web := map[string]string{"app": "web"}
	o.rs("team-a", "web-rs", 4)
	o.pdb("team-a", "web", web, 1)
	o.pod("team-a", "web-1", "node-2", "ReplicaSet", "web-rs", "250m", "512Mi", web)
	o.pod("team-a", "web-2", "node-2", "ReplicaSet", "web-rs", "250m", "512Mi", web)
	o.pod("team-a", "web-3", "node-3", "ReplicaSet", "web-rs", "250m", "512Mi", web)
	o.pod("team-a", "web-4", "node-4", "ReplicaSet", "web-rs", "250m", "512Mi", web)
	o.rs("team-a", "api-rs", 2)
	o.pod("team-a", "api-1", "node-3", "ReplicaSet", "api-rs", "900m", "2Gi", map[string]string{"app": "api"})
	o.pod("team-a", "api-2", "node-4", "ReplicaSet", "api-rs", "900m", "2Gi", map[string]string{"app": "api"})
	return o
}

// blockedReasonsFixture: one node per blocker in node group ng-a, plus two kept
// receivers. Pod-blocked nodes are 1 CPU / 4Gi with 2 DaemonSet pods and one 100m
// pod (30% CPU, DRAIN candidates) so they cannot receive n-strand's 1500m pod; the
// receivers have 1600m free at 100% but only 1000m at 85%, so n-strand strands.
func blockedReasonsFixture() *objects {
	o := &objects{}
	o.receiver("recv-1", "ng-a")
	o.receiver("recv-2", "ng-a")
	small := func(name string) {
		o.node(name, "ng-a", "1", "4Gi")
		o.dsPods(name, 2, "100m", "128Mi")
	}

	small("n-bare")
	o.pod("team-a", "bare-1", "n-bare", "", "", "100m", "128Mi", nil)

	small("n-local")
	o.rs("team-a", "cache-rs", 1)
	p := o.pod("team-a", "cache-1", "n-local", "ReplicaSet", "cache-rs", "100m", "128Mi", nil)
	p.Spec.Volumes = []corev1.Volume{{Name: "scratch", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}}}

	small("n-kube-system")
	o.rs("kube-system", "coredns-rs", 2)
	o.pod("kube-system", "coredns-1", "n-kube-system", "ReplicaSet", "coredns-rs", "100m", "128Mi", map[string]string{"k8s-app": "kube-dns"})

	small("n-pdb")
	web := map[string]string{"app": "web"}
	o.rs("team-b", "web-rs", 2)
	o.pdb("team-b", "web", web, 0)
	o.pod("team-b", "web-1", "n-pdb", "ReplicaSet", "web-rs", "100m", "128Mi", web)

	small("n-safe-to-evict")
	o.rs("team-a", "safe-rs", 2)
	p = o.pod("team-a", "safe-1", "n-safe-to-evict", "ReplicaSet", "safe-rs", "100m", "128Mi", nil)
	p.Annotations = map[string]string{"cluster-autoscaler.kubernetes.io/safe-to-evict": "false"}

	small("n-do-not-disrupt")
	o.rs("team-a", "dnd-rs", 2)
	p = o.pod("team-a", "dnd-1", "n-do-not-disrupt", "ReplicaSet", "dnd-rs", "100m", "128Mi", nil)
	p.Annotations = map[string]string{AnnotationKarpenterDoNotDisrupt: "true"}

	small("n-do-not-evict")
	o.rs("team-a", "dne-rs", 2)
	p = o.pod("team-a", "dne-1", "n-do-not-evict", "ReplicaSet", "dne-rs", "100m", "128Mi", nil)
	p.Annotations = map[string]string{AnnotationDoNotEvict: "true"}

	small("n-pvc")
	o.sts("team-c", "db", 3)
	p = o.pod("team-c", "db-0", "n-pvc", "StatefulSet", "db", "100m", "128Mi", nil)
	p.Spec.Volumes = []corev1.Volume{{Name: "data", VolumeSource: corev1.VolumeSource{
		PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: "data-db-0"}}}}

	small("n-statefulset")
	o.sts("team-c", "queue", 3)
	o.pod("team-c", "queue-0", "n-statefulset", "StatefulSet", "queue", "100m", "128Mi", nil)

	small("n-host-port")
	o.rs("team-a", "agent-rs", 2)
	p = o.pod("team-a", "agent-1", "n-host-port", "ReplicaSet", "agent-rs", "100m", "128Mi", nil)
	p.Spec.Containers[0].Ports = []corev1.ContainerPort{{ContainerPort: 9100, HostPort: 9100}}

	o.node("n-strand", "ng-a", "4", "16Gi")
	o.dsPods("n-strand", 2, "100m", "128Mi")
	o.rs("team-a", "big-rs", 2)
	o.pod("team-a", "big-1", "n-strand", "ReplicaSet", "big-rs", "1500m", "1Gi", nil)

	n := o.node("n-removal-disabled", "ng-a", "1", "4Gi")
	n.Labels[LabelRemovalDisabled] = "true"

	n = o.node("n-cordoned", "ng-a", "1", "4Gi")
	n.Spec.Unschedulable = true

	n = o.node("n-not-ready", "ng-a", "1", "4Gi")
	n.Status.Conditions[0].Status = corev1.ConditionFalse

	n = o.node("n-fargate", "", "1", "4Gi")
	n.Labels[LabelComputeType] = "fargate"

	o.node("n-unmanaged", "", "1", "4Gi")
	return o
}

// lastNodeInGroupFixture: ng-a has one node (a DRAIN candidate), ng-b two EMPTY
// nodes, ng-c two kept receivers. b-1 is removed; a-1 and b-2 are the last node of
// their group.
func lastNodeInGroupFixture() *objects {
	o := &objects{}
	o.node("a-1", "ng-a", "2", "8Gi")
	o.dsPods("a-1", 2, "100m", "128Mi")
	o.rs("team-a", "app-rs", 3)
	o.pod("team-a", "app-1", "a-1", "ReplicaSet", "app-rs", "200m", "256Mi", nil)
	for _, n := range []string{"b-1", "b-2"} {
		o.node(n, "ng-b", "2", "8Gi")
		o.dsPods(n, 2, "100m", "128Mi")
	}
	o.receiver("c-1", "ng-c")
	o.receiver("c-2", "ng-c")
	return o
}

// headroomFixture: recv-1 has 1600m free at 100% and 1000m at 85%. src-big's
// 1500m pod fits at 100% but not at 85% (strand); src-small's 600m pod fits
// (DRAIN). src-small (2 CPU) is too small to take the 1500m pod.
func headroomFixture() *objects {
	o := &objects{}
	o.receiver("recv-1", "ng-a")
	o.node("src-big", "ng-a", "4", "16Gi")
	o.dsPods("src-big", 2, "100m", "128Mi")
	o.rs("team-a", "big-rs", 3)
	o.pod("team-a", "big-1", "src-big", "ReplicaSet", "big-rs", "1500m", "1Gi", nil)
	o.node("src-small", "ng-a", "2", "8Gi")
	o.dsPods("src-small", 2, "100m", "128Mi")
	o.rs("team-a", "small-rs", 3)
	o.pod("team-a", "small-1", "src-small", "ReplicaSet", "small-rs", "600m", "512Mi", nil)
	return o
}

// actorKarpenterFixture: three Karpenter nodes of node pool default (spot) and the
// karpenter Deployment. k-1 is EMPTY, k-2 and k-3 are kept.
func actorKarpenterFixture() *objects {
	o := &objects{}
	for _, n := range []string{"k-1", "k-2", "k-3"} {
		o.rawNode(n, "2", "4Gi", 29, map[string]string{
			LabelKarpenterNodePool: "default",
			LabelKarpenterCapacity: "spot",
			LabelInstanceType:      "c6i.large",
			LabelZone:              "ap-south-1b",
		})
		o.dsPods(n, 2, "100m", "128Mi")
	}
	o.rs("team-a", "app-rs", 2)
	o.pod("team-a", "app-1", "k-2", "ReplicaSet", "app-rs", "1000m", "1Gi", nil)
	o.pod("team-a", "app-2", "k-3", "ReplicaSet", "app-rs", "1000m", "1Gi", nil)
	o.deployment("karpenter", "karpenter", i32(2), map[string]string{"app.kubernetes.io/name": "karpenter"})
	return o
}

// actorClusterAutoscalerFixture: the cluster-autoscaler Deployment (1 replica) and a
// scaled-to-zero aws-cluster-autoscaler Deployment that does not count. ca-1 is a
// DRAIN (web-1 -> ca-2), ca-2 and ca-3 are kept.
func actorClusterAutoscalerFixture() *objects {
	o := &objects{}
	for _, n := range []string{"ca-1", "ca-2", "ca-3"} {
		o.node(n, "ng-a", "2", "8Gi")
		o.dsPods(n, 2, "100m", "128Mi")
	}
	o.rs("team-a", "web-rs", 3)
	o.pod("team-a", "web-1", "ca-1", "ReplicaSet", "web-rs", "200m", "256Mi", map[string]string{"app": "web"})
	o.rs("team-a", "filler-rs", 2)
	o.pod("team-a", "filler-1", "ca-2", "ReplicaSet", "filler-rs", "1100m", "2Gi", nil)
	o.pod("team-a", "filler-2", "ca-3", "ReplicaSet", "filler-rs", "1100m", "2Gi", nil)
	o.deployment("kube-system", "cluster-autoscaler", i32(1), map[string]string{"app": "cluster-autoscaler"})
	o.deployment("kube-system", "legacy-autoscaler", i32(0), map[string]string{"app.kubernetes.io/name": "aws-cluster-autoscaler"})
	return o
}

// allBlockedFixture: every node is blocked (two bare pods, one cordoned node), so
// moves and kept are empty lists.
func allBlockedFixture() *objects {
	o := &objects{}
	for _, n := range []string{"x-1", "x-2", "x-3"} {
		o.node(n, "ng-a", "2", "8Gi")
		o.dsPods(n, 2, "100m", "128Mi")
	}
	o.nodes[1].Spec.Unschedulable = true
	o.pod("team-a", "bare-1", "x-1", "", "", "100m", "128Mi", nil)
	o.pod("team-a", "bare-3", "x-3", "", "", "100m", "128Mi", nil)
	return o
}
