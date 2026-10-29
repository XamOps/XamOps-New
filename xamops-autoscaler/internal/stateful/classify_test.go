package stateful

import (
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

var now = time.Date(2026, 10, 25, 0, 0, 0, 0, time.UTC)

func cnpgCluster(name, currentPrimary string, synchronous map[string]interface{}) *OperatorCR {
	obj := map[string]interface{}{
		"apiVersion": "postgresql.cnpg.io/v1",
		"kind":       "Cluster",
		"metadata":   map[string]interface{}{"name": name, "namespace": "db"},
		"status":     map[string]interface{}{"currentPrimary": currentPrimary},
	}
	if synchronous != nil {
		obj["spec"] = map[string]interface{}{"postgresql": map[string]interface{}{"synchronous": synchronous}}
	}
	return &OperatorCR{Kind: "Cluster", Name: name, Obj: &unstructured.Unstructured{Object: obj}}
}

func verdict(t *testing.T, got Verdict, wantClass, wantCode string) {
	t.Helper()
	if got.Class != wantClass || got.Code != wantCode {
		t.Errorf("got {class=%s code=%s}, want {class=%s code=%s}", got.Class, got.Code, wantClass, wantCode)
	}
}

func TestNonDatabaseAllowed(t *testing.T) {
	v := Classify(PodFacts{Namespace: "web", Name: "nginx-0", ContainerImages: []string{"nginx:1.27"}, ReadyReplicas: 3}, nil, now)
	verdict(t, v, ClassAllowed, "")
}

func TestCNPGPrimaryByStatus(t *testing.T) {
	cr := cnpgCluster("pg", "pg-1", nil)
	pod := PodFacts{Namespace: "db", Name: "pg-1", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "primary"}, ReadyReplicas: 2}
	verdict(t, Classify(pod, cr, now), ClassProtected, CodeDBPrimary)
}

func TestCNPGStatusOverridesLabel(t *testing.T) {
	// Label says primary, but status.currentPrimary says pg-2 — status wins, so pg-1
	// is a standby. With synchronous replication it is a sync standby.
	cr := cnpgCluster("pg", "pg-2", map[string]interface{}{"number": int64(1), "dataDurability": "required"})
	pod := PodFacts{Namespace: "db", Name: "pg-1", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "primary"}, ReadyReplicas: 2}
	verdict(t, Classify(pod, cr, now), ClassProtected, CodeDBSyncStandby)
}

func TestCNPGQuorumStandbyProtected(t *testing.T) {
	// CNPG reports synchronous via dataDurability=required (quorum) — a == "sync"
	// test would fail open; this must still protect the standby.
	cr := cnpgCluster("pg", "pg-1", map[string]interface{}{"dataDurability": "required"})
	pod := PodFacts{Namespace: "db", Name: "pg-2", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "replica"}, ReadyReplicas: 2}
	verdict(t, Classify(pod, cr, now), ClassProtected, CodeDBSyncStandby)
}

func TestCNPGAsyncReplicaRefusedOnLagUnknown(t *testing.T) {
	// No synchronous block -> async replica. v1 reads no lag, so db-lag-unknown.
	cr := cnpgCluster("pg", "pg-1", nil)
	pod := PodFacts{Namespace: "db", Name: "pg-2", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "replica"}, ReadyReplicas: 3}
	verdict(t, Classify(pod, cr, now), ClassUnknown, CodeDBLagUnknown)
}

func TestSpiloRoles(t *testing.T) {
	master := PodFacts{Name: "pg-0", Labels: map[string]string{LabelSpiloRole: "master"}, ReadyReplicas: 2}
	verdict(t, Classify(master, nil, now), ClassProtected, CodeDBPrimary)
	replica := PodFacts{Name: "pg-1", Labels: map[string]string{LabelSpiloRole: "replica"}, ReadyReplicas: 3}
	verdict(t, Classify(replica, nil, now), ClassUnknown, CodeDBLagUnknown)
}

func TestRoleUnknownFailsClosed(t *testing.T) {
	// A plain mongo pod by image, no recognised role label -> db-role-unknown.
	pod := PodFacts{Name: "mongo-0", ContainerImages: []string{"mongo:7.0"}, ReadyReplicas: 3}
	verdict(t, Classify(pod, nil, now), ClassUnknown, CodeDBRoleUnknown)
}

func TestStatefulDBLabelHeuristic(t *testing.T) {
	pod := PodFacts{Name: "x-0", Labels: map[string]string{LabelStatefulDB: "true"}, ReadyReplicas: 3}
	verdict(t, Classify(pod, nil, now), ClassUnknown, CodeDBRoleUnknown)
}

func TestOperatorOwnerKindHeuristic(t *testing.T) {
	pod := PodFacts{Name: "psmdb-0", Owner: &Owner{Kind: "PerconaServerMongoDB", Name: "psmdb"}, ReadyReplicas: 3}
	verdict(t, Classify(pod, nil, now), ClassUnknown, CodeDBRoleUnknown)
}

func TestOnlyReadyReplicaRefused(t *testing.T) {
	// A detected DB replica (percona role=replica) with <=1 ready replica.
	pod := PodFacts{Name: "x-0", Labels: map[string]string{LabelPerconaRole: "replica"}, ReadyReplicas: 1}
	verdict(t, Classify(pod, nil, now), ClassProtected, CodeDBOnlyReadyReplica)
}

func TestEmptyDirDBRefusedRegardlessOfRole(t *testing.T) {
	// An emptyDir-only DB (no PVC) is refused db-emptydir, even a primary with many
	// replicas: the emptyDir gate wins when there is no PVC backing the data.
	cr := cnpgCluster("pg", "pg-1", nil)
	pod := PodFacts{Namespace: "db", Name: "pg-1", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "primary"},
		HasEmptyDirAtDataPath: true, ReadyReplicas: 3}
	verdict(t, Classify(pod, cr, now), ClassProtected, CodeDBEmptyDir)
}

func TestPVCBackedDBWithEmptyDirResolvesByRole(t *testing.T) {
	// A PVC-backed DB that also mounts a scratch/OTel emptyDir keeps its real data on
	// the PVC, so the emptyDir gate no longer masks the role: a CNPG primary (by
	// label) resolves to db-primary, not db-emptydir.
	cr := cnpgCluster("pg", "pg-1", nil)
	pod := PodFacts{Namespace: "db", Name: "pg-1", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "primary"},
		HasEmptyDirAtDataPath: true, HasPVC: true, ReadyReplicas: 3}
	verdict(t, Classify(pod, cr, now), ClassProtected, CodeDBPrimary)
}

func TestMemoryEmptyDirIsNotDBEmptyDir(t *testing.T) {
	// A memory-medium emptyDir is not flagged by the snapshot builder, so a detected
	// DB with only a memory emptyDir falls through to the role path. Here the pod has
	// no emptyDir flag set and is a role-unknown DB image.
	pod := PodFacts{Name: "redis-0", ContainerImages: []string{"redis:7"}, ReadyReplicas: 3}
	verdict(t, Classify(pod, nil, now), ClassUnknown, CodeDBRoleUnknown)
}

// The computeLag seam: inject a fake lag to exercise db-lag-high and the
// below-limit (allowed) branch for an async replica.
func TestComputeLagSeam(t *testing.T) {
	cr := cnpgCluster("pg", "pg-1", nil)
	pod := PodFacts{Namespace: "db", Name: "pg-2", Labels: map[string]string{LabelCNPGCluster: "pg", LabelCNPGInstanceRole: "replica"}, ReadyReplicas: 3}

	// High lag -> db-lag-high.
	lagHook = func(*OperatorCR, PodFacts, time.Time) (int64, bool) { return 250, true }
	verdict(t, Classify(pod, cr, now), ClassProtected, CodeDBLagHigh)

	// Below-limit lag -> allowed (the only v2 happy path, unreachable in v1 without
	// the hook).
	lagHook = func(*OperatorCR, PodFacts, time.Time) (int64, bool) { return 10, true }
	verdict(t, Classify(pod, cr, now), ClassAllowed, "")

	lagHook = nil
}
