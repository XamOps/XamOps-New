// Package stateful is the always-on, fail-closed database/stateful protection
// layer (stateful-DB-fixtures report §5, invariant I15). It answers one question
// with a pure function over Kubernetes objects (never DB credentials): must this
// pod be refused because evicting or draining it risks a database?
//
// The layer is consulted in fitsim's per-pod classification, so every planner
// consumer inherits it (the look-only analyzer, the evictor, the rebalancer, the
// node optimizer, the interruption path and the consolidation engine). It emits a
// Refused-class verdict so a protected pod makes its whole node non-drainable, and
// it is NOT gated: policy toggles can only add protection, never remove it.
//
// Detection reads operator role labels, operator Custom Resource status, and
// container images — a known-DB image, a known operator owner kind, or the explicit
// label xamops.io/stateful-db=true all mark a pod as a detected database. The
// verdict is {protected, allowed, unknown} and unknown ⇒ refuse: a detected
// database whose role cannot be resolved is refused (db-role-unknown), and because
// v1 reads no replication-lag number (computeLag always returns ok=false), every
// detected replica is refused on db-lag-unknown. Role-based protection does all the
// protecting in v1; lag only ever adds refusals.
package stateful

// Verdict classes. unknown ⇒ refuse, exactly like protected.
const (
	ClassAllowed   = "allowed"
	ClassProtected = "protected"
	ClassUnknown   = "unknown"
)

// Verdict reason codes. A refused pod carries exactly one Code; Reason is the human
// sentence surfaced through consolidation/reasons.go.
const (
	CodeDBPrimary          = "db-primary"            // detected database primary
	CodeDBSyncStandby      = "db-sync-standby"       // active synchronous/quorum standby
	CodeDBRoleUnknown      = "db-role-unknown"       // DB detected, role unresolved — fail-closed
	CodeDBOnlyReadyReplica = "db-only-ready-replica" // detected DB / STS-with-PVC, ready replicas <= 1
	CodeDBLagUnknown       = "db-lag-unknown"        // lag missing/unparseable/stale — fail-closed
	CodeDBLagHigh          = "db-lag-high"           // lag above the limit (default 100 ms)
	CodeDBEmptyDir         = "db-emptydir"           // detected DB with a non-Memory emptyDir at its data path
)

// IsRefusalCode reports whether code is one of this package's Refused-class codes.
func IsRefusalCode(code string) bool {
	switch code {
	case CodeDBPrimary, CodeDBSyncStandby, CodeDBRoleUnknown, CodeDBOnlyReadyReplica,
		CodeDBLagUnknown, CodeDBLagHigh, CodeDBEmptyDir:
		return true
	}
	return false
}
