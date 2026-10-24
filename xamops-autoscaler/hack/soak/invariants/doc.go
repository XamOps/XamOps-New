// Package invariants evaluates the soak invariants I1, I2, I6 and I14 from the
// timestamped JSONL streams that soakwatch records (one file per resource in a run
// directory).
//
// Every invariant is evaluated on every record of the merged timeline, never on
// samples, so a breach shorter than any polling interval is still caught and its
// duration is measured to the nanosecond of the receive timestamps.
//
// Record format, one JSON object per line:
//
//	{"ts":"<RFC3339Nano UTC>","stream":"<name>","type":"ADDED|MODIFIED|DELETED|BOOKMARK|ERROR","object":{...}}
//	{"ts":"<RFC3339Nano UTC>","stream":"agent-logs","type":"LOG","pod":"<pod>","line":"<text>"}
//
// Stream files: nodes.jsonl, pdbs.jsonl, deployments.jsonl, statefulsets.jsonl,
// replicasets.jsonl, pods.jsonl, events.jsonl, leases.jsonl, agent-logs.jsonl, plus an
// optional watch-meta.json (per-stream restart counts).
//
// Invariants:
//
//	I1  no PDB violated: a Ready pod selected by a PDB starts terminating while the
//	    PDB allows 0 disruptions and the pod is not in status.disruptedPods.
//	I2  HA floor per Deployment/StatefulSet: readyReplicas never drops below the
//	    floor (PDB minAvailable / maxUnavailable, else replicas-1, min 1) once the
//	    workload has been at or above it in the window.
//	I6  concurrent drains: nodes annotated xamops.io/drain-state=draining never
//	    exceed MaxParallelDrains.
//	I14 only the holder of the Lease (default xamops-automation-leader) acts: every
//	    drain outcome / replacement log line comes from a pod that held the lease
//	    within LeaderTolerance of the line.
//
// Usage:
//
//	tl, err := invariants.Load(dir)
//	report := invariants.Check(tl, invariants.DefaultOptions())
package invariants
