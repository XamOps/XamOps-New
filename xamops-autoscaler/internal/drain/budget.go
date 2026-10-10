// Package drain owns every node drain and cordon the autoscaler performs
// (plan.md D5-D7): one global per-cycle Budget, a Planner that must prove a drain is
// safe, and one Executor that drains through the Eviction API only (never
// Pods().Delete), verifies replacements by new pod UID and aborts + uncordons on any
// failure.
package drain

import (
	"sort"
	"sync"

	corev1 "k8s.io/api/core/v1"
)

// Node annotations written by the executor.
const (
	AnnotationOperation = "xamops.io/drain-operation"
	AnnotationState     = "xamops.io/drain-state"
	// AnnotationCordonedBy marks a cordon this autoscaler made, so recovery only
	// uncordons nodes it cordoned itself.
	AnnotationCordonedBy = "xamops.io/drain-cordoned-by"

	StateDraining = "draining"
	StateDrained  = "drained"
	StateAborted  = "aborted"
	StateCordoned = "cordoned"

	CordonedByXamops = "xamops-automation"
)

// Budget is the global per-cycle drain budget shared, in cycle order, by the
// rebalancer, the evictor and the node optimizer. Capacity comes from
// nodeOptimizerPolicy.maxParallelDrains (normalized to >= 1). Nodes left annotated
// draining (crash leftovers) count as in flight and consume capacity. Simulated
// drains consume budget too, so a dry run shows what live would do.
type Budget struct {
	mu       sync.Mutex
	capacity int
	reserved map[string]string // node -> controller
	inflight map[string]bool
}

// BudgetSnapshot is the budget state for telemetry.
type BudgetSnapshot struct {
	Capacity  int               `json:"capacity"`
	InFlight  []string          `json:"inFlight"`
	Reserved  map[string]string `json:"reserved"`
	Remaining int               `json:"remaining"`
}

// NewBudget returns a budget of capacity (<= 0 means 1) with inflightNodes already
// consuming slots.
func NewBudget(capacity int, inflightNodes []string) *Budget {
	if capacity <= 0 {
		capacity = 1
	}
	b := &Budget{capacity: capacity, reserved: map[string]string{}, inflight: map[string]bool{}}
	for _, n := range inflightNodes {
		b.inflight[n] = true
	}
	return b
}

// InflightNodes returns the nodes annotated xamops.io/drain-state=draining.
func InflightNodes(nodes []corev1.Node) []string {
	var out []string
	for _, n := range nodes {
		if n.Annotations[AnnotationState] == StateDraining {
			out = append(out, n.Name)
		}
	}
	sort.Strings(out)
	return out
}

func (b *Budget) usedLocked() int { return len(b.reserved) + len(b.inflight) }

// TryReserve reserves a slot for node on behalf of controller. It is idempotent for
// the same controller and node. A node already in flight or reserved by another
// controller is refused.
func (b *Budget) TryReserve(controller, node string) bool {
	if b == nil {
		return false
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if owner, ok := b.reserved[node]; ok {
		return owner == controller
	}
	if b.inflight[node] {
		return false
	}
	if b.usedLocked() >= b.capacity {
		return false
	}
	b.reserved[node] = controller
	return true
}

// Release frees a reservation (used when a planned drain is refused before any
// action was taken, so the slot can go to another candidate).
func (b *Budget) Release(controller, node string) {
	if b == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.reserved[node] == controller {
		delete(b.reserved, node)
	}
}

// Remaining returns the free slots.
func (b *Budget) Remaining() int {
	if b == nil {
		return 0
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if r := b.capacity - b.usedLocked(); r > 0 {
		return r
	}
	return 0
}

// Capacity returns the configured capacity.
func (b *Budget) Capacity() int {
	if b == nil {
		return 0
	}
	return b.capacity
}

// Snapshot returns a copy of the budget state.
func (b *Budget) Snapshot() BudgetSnapshot {
	if b == nil {
		return BudgetSnapshot{}
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	s := BudgetSnapshot{Capacity: b.capacity, Reserved: map[string]string{}, InFlight: []string{}}
	for n, c := range b.reserved {
		s.Reserved[n] = c
	}
	for n := range b.inflight {
		s.InFlight = append(s.InFlight, n)
	}
	sort.Strings(s.InFlight)
	if r := b.capacity - b.usedLocked(); r > 0 {
		s.Remaining = r
	}
	return s
}
