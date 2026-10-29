package stateful

import (
	"strings"
	"time"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
)

// DefaultLagLimit is the replication-lag ceiling kept as the v2 contract. v1 never
// reads a lag number (computeLag returns ok=false), so this limit is only exercised
// through the computeLag seam in tests.
const DefaultLagLimit = 100 * time.Millisecond

// Well-known operator labels and the stateful-db heuristic inputs.
const (
	LabelCNPGCluster      = "cnpg.io/cluster"
	LabelCNPGInstanceRole = "cnpg.io/instanceRole"
	LabelSpiloRole        = "spilo-role" // Patroni / Zalando
	LabelPerconaRole      = "percona.com/role"
	LabelMongoRole        = "app.kubernetes.io/component" // Percona PSMDB / community charts
	LabelStatefulDB       = "xamops.io/stateful-db"

	// Role label values.
	roleCNPGPrimary  = "primary"
	roleCNPGReplica  = "replica"
	roleSpiloMaster  = "master"
	roleSpiloReplica = "replica"
)

// knownDBImagePrefixes are the container-image prefixes that mark a pod as a
// detected database (matched case-insensitively on the image path's final element,
// so a private registry mirror still matches).
var knownDBImagePrefixes = []string{
	"postgres", "postgresql", "mysql", "mariadb", "percona",
	"mongo", "mongodb", "redis", "valkey", "cockroach", "cassandra",
}

// Owner is the pod's controller owner reference (kind/name), nil when there is none.
type Owner struct {
	Kind string
	Name string
}

// PodFacts is everything Classify reads about a pod. fitsim builds it from the
// corev1.Pod and the resolved operator CR; stateful never imports fitsim so the
// packages do not form an import cycle.
type PodFacts struct {
	Namespace string
	Name      string
	Labels    map[string]string
	// ContainerImages are the pod's container images (app + init), lower-cased.
	ContainerImages []string
	// Owner is the controller owner (StatefulSet, CNPG Cluster, …), nil when none.
	Owner *Owner
	// HasEmptyDirAtDataPath reports a non-Memory emptyDir mount (v1 does not resolve
	// the data path, so any non-Memory emptyDir counts).
	HasEmptyDirAtDataPath bool
	// HasPVC reports that the pod mounts a PersistentVolumeClaim.
	HasPVC bool
	// ReadyReplicas is the owning controller's ready-replica count, or -1 when it is
	// unknown (owner object absent from the snapshot).
	ReadyReplicas int
}

// OperatorCR is the operator Custom Resource a pod belongs to (CNPG Cluster, …),
// resolved by the caller through the dynamic client. nil when none is found.
type OperatorCR struct {
	// Kind is the CR kind ("Cluster" for CNPG, …).
	Kind string
	// Name is metadata.name.
	Name string
	// Obj is the raw unstructured CR, read for status fields such as
	// status.currentPrimary.
	Obj *unstructured.Unstructured
}

// Verdict is the classification result. Class is allowed, protected or unknown;
// Code and Reason are set on a refusal (protected or unknown).
type Verdict struct {
	Class  string
	Code   string
	Reason string
}

// allowed is the single non-refusing verdict.
var allowed = Verdict{Class: ClassAllowed}

// Classify returns the pod's stateful-protection verdict. It is pure: the result
// depends only on its arguments. A non-database pod returns allowed; a detected
// database returns a protected/unknown verdict whose Code is a db-* refusal code.
func Classify(pod PodFacts, cr *OperatorCR, now time.Time) Verdict {
	role, isDB := detect(pod, cr)
	if !isDB {
		return allowed
	}

	// emptyDir-only DB: data loss on drain, refused regardless of role or replica
	// count, and non-overridable. Gated on !HasPVC so it fires only for a genuine
	// emptyDir-only DB; a PVC-backed DB that also mounts a scratch/OTel emptyDir
	// keeps its real data on the PVC and falls through to role resolution below.
	if pod.HasEmptyDirAtDataPath && !pod.HasPVC {
		return refuse(CodeDBEmptyDir, "database with an emptyDir at its data path (drain = data loss)")
	}

	switch role {
	case roleRolePrimary:
		return refuse(CodeDBPrimary, "database primary")
	case roleRoleSyncStandby:
		return refuse(CodeDBSyncStandby, "active synchronous standby")
	case roleRoleUnknown:
		return refuse(CodeDBRoleUnknown, "database role unknown — refused")
	}

	// A detected replica. Never evict the only ready replica.
	if pod.ReadyReplicas >= 0 && pod.ReadyReplicas <= 1 {
		return refuse(CodeDBOnlyReadyReplica, "only ready database replica")
	}

	// Lag gate. v1 reads no lag number, so every detected replica is refused on
	// db-lag-unknown; the computeLag seam exists so v2 can supply a real source.
	ms, ok := computeLag(cr, pod, now)
	if !ok {
		return refuse(CodeDBLagUnknown, "database replication lag unknown — refused")
	}
	if time.Duration(ms)*time.Millisecond > DefaultLagLimit {
		return refuse(CodeDBLagHigh, "database replication lag above limit")
	}
	return allowed
}

// role kinds, resolved by detect.
type roleKind int

const (
	roleRoleReplica roleKind = iota // a non-primary, non-sync replica (may still be refused on lag)
	roleRolePrimary
	roleRoleSyncStandby
	roleRoleUnknown // DB detected but role unresolved
)

// detect returns the pod's role and whether it is a detected database at all.
func detect(pod PodFacts, cr *OperatorCR) (roleKind, bool) {
	// CloudNativePG: Cluster.status.currentPrimary takes precedence over the label.
	if _, ok := pod.Labels[LabelCNPGCluster]; ok {
		return cnpgRole(pod, cr), true
	}
	// Patroni / Zalando spilo-role.
	if v, ok := pod.Labels[LabelSpiloRole]; ok {
		switch strings.ToLower(v) {
		case roleSpiloMaster:
			return roleRolePrimary, true
		case roleSpiloReplica:
			return roleRoleReplica, true
		default:
			return roleRoleUnknown, true
		}
	}
	// Percona / operator allow-list.
	if v, ok := pod.Labels[LabelPerconaRole]; ok {
		switch strings.ToLower(v) {
		case "primary", "master":
			return roleRolePrimary, true
		case "sync", "syncstandby", "sync-standby":
			return roleRoleSyncStandby, true
		case "replica", "secondary", "arbiter":
			return roleRoleReplica, true
		default:
			return roleRoleUnknown, true
		}
	}

	// DB-ness heuristic: explicit label, known operator owner kind, or a DB image.
	if strings.EqualFold(pod.Labels[LabelStatefulDB], "true") {
		return roleRoleUnknown, true
	}
	if pod.Owner != nil && isOperatorOwnerKind(pod.Owner.Kind) {
		return roleRoleUnknown, true
	}
	if hasDBImage(pod.ContainerImages) {
		return roleRoleUnknown, true
	}
	return roleRoleReplica, false
}

// cnpgRole resolves a CNPG pod's role: status.currentPrimary wins over the label,
// and spec.postgresql.synchronous marks the active sync/quorum standby.
func cnpgRole(pod PodFacts, cr *OperatorCR) roleKind {
	if cr != nil && cr.Obj != nil {
		if primary, ok, _ := unstructured.NestedString(cr.Obj.Object, "status", "currentPrimary"); ok && primary != "" {
			if primary == pod.Name {
				return roleRolePrimary
			}
			// A non-primary CNPG pod: if the cluster runs synchronous replication,
			// the standbys are sync/quorum standbys (true for both "sync" and
			// "quorum" — CNPG reports quorum, so a == "sync" test would fail open).
			if cnpgSynchronous(cr) {
				return roleRoleSyncStandby
			}
			return roleRoleReplica
		}
	}
	switch strings.ToLower(pod.Labels[LabelCNPGInstanceRole]) {
	case roleCNPGPrimary:
		return roleRolePrimary
	case roleCNPGReplica:
		if cr != nil && cnpgSynchronous(cr) {
			return roleRoleSyncStandby
		}
		return roleRoleReplica
	default:
		return roleRoleUnknown
	}
}

// cnpgSynchronous reports whether the CNPG Cluster declares synchronous replication
// (spec.postgresql.synchronous present with a positive number, or dataDurability
// required). Any such cluster's standbys are treated as active sync/quorum standbys.
func cnpgSynchronous(cr *OperatorCR) bool {
	if cr == nil || cr.Obj == nil {
		return false
	}
	sync, ok, _ := unstructured.NestedMap(cr.Obj.Object, "spec", "postgresql", "synchronous")
	if !ok || sync == nil {
		return false
	}
	if n, ok, _ := unstructured.NestedInt64(cr.Obj.Object, "spec", "postgresql", "synchronous", "number"); ok && n > 0 {
		return true
	}
	if dd, ok, _ := unstructured.NestedString(cr.Obj.Object, "spec", "postgresql", "synchronous", "dataDurability"); ok {
		return strings.EqualFold(dd, "required")
	}
	// synchronous present but neither field recognised: treat as synchronous
	// (fail-closed — refuse the standby rather than guess it async).
	return true
}

func isOperatorOwnerKind(kind string) bool {
	switch kind {
	case "Cluster", // CNPG, Percona PG
		"PerconaServerMongoDB", "PerconaXtraDBCluster",
		"PostgresCluster", // Crunchy
		"MongoDBCommunity", "MongoDB":
		return true
	}
	return false
}

func hasDBImage(images []string) bool {
	for _, img := range images {
		// Take the final path element, strip any tag/digest, then prefix-match.
		name := img
		if i := strings.LastIndex(name, "/"); i >= 0 {
			name = name[i+1:]
		}
		if i := strings.IndexAny(name, ":@"); i >= 0 {
			name = name[:i]
		}
		for _, p := range knownDBImagePrefixes {
			if strings.HasPrefix(name, p) {
				return true
			}
		}
	}
	return false
}

func refuse(code, reason string) Verdict {
	class := ClassProtected
	if code == CodeDBRoleUnknown || code == CodeDBLagUnknown {
		class = ClassUnknown
	}
	return Verdict{Class: class, Code: code, Reason: reason}
}

// computeLag returns the pod's replication lag in milliseconds and whether it is
// known. v1 has no lag source (no CR-status lag field and no DB-credential path is
// permitted), so it ALWAYS returns ok=false and every detected replica is refused
// on db-lag-unknown. The seam exists so v2 can supply a real source behind it
// without touching Classify; tests inject a fake lag through lagHook.
func computeLag(cr *OperatorCR, pod PodFacts, now time.Time) (ms int64, ok bool) {
	if lagHook != nil {
		return lagHook(cr, pod, now)
	}
	return 0, false
}

// lagHook is the test seam for computeLag. Production leaves it nil (ok=false).
var lagHook func(cr *OperatorCR, pod PodFacts, now time.Time) (int64, bool)
