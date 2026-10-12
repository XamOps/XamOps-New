// Package state persists the rebalancer operation ledger (plan.md D4) in a ConfigMap
// so that an operation runs at most once across cycles and process restarts.
//
// The backend sets rebalancer.status=running and never resets it, so status alone
// cannot identify an operation. The ledger assigns "gen-<N>" where N increments on
// each observed non-running -> running edge (unless the policy names an operationId)
// and records live outcomes, which are terminal.
//
// In read-only mode (DRY_RUN) the ledger reads the ConfigMap but never issues create,
// update or patch: changes live in an in-memory overlay only.
package state

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"sync"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/util/retry"

	"github.com/xamops/automation/internal/policy"
)

// DataKey is the ConfigMap key holding the rebalancer ledger.
const DataKey = "rebalancer.json"

// MaxOperations is how many operations the ledger keeps (most recent by startedAt).
const MaxOperations = 20

// Operation states. completed|refused|failed|aborted are terminal. running is written
// before a live drain starts, so a crash mid-operation is detected (and not re-run)
// by the next process.
const (
	StateRunning   = "running"
	StateCompleted = "completed"
	StateRefused   = "refused"
	StateFailed    = "failed"
	StateAborted   = "aborted"
)

// Operation is one rebalance operation outcome.
type Operation struct {
	State      string   `json:"state"`
	Mode       string   `json:"mode"` // live | simulate
	StartedAt  string   `json:"startedAt"`
	FinishedAt string   `json:"finishedAt,omitempty"`
	Nodes      []string `json:"nodes,omitempty"`
	Reason     string   `json:"reason,omitempty"`
}

// RebalancerState is the JSON document stored under DataKey.
type RebalancerState struct {
	Generation         int64                `json:"generation"`
	LastObservedStatus string               `json:"lastObservedStatus"`
	Operations         map[string]Operation `json:"operations"`
}

// IsTerminal reports whether an operation state may never be re-run.
func IsTerminal(state string) bool {
	switch state {
	case StateCompleted, StateRefused, StateFailed, StateAborted:
		return true
	}
	return false
}

// Ledger is the ConfigMap-backed operation ledger.
type Ledger struct {
	k8s      kubernetes.Interface
	ns, name string
	readOnly bool

	mu     sync.Mutex
	st     RebalancerState
	loaded bool
}

// New returns a ledger over ConfigMap ns/name. readOnly (DRY_RUN) never writes.
func New(k8s kubernetes.Interface, ns, name string, readOnly bool) *Ledger {
	return &Ledger{k8s: k8s, ns: ns, name: name, readOnly: readOnly}
}

func decode(cm *corev1.ConfigMap) (RebalancerState, error) {
	st := RebalancerState{Operations: map[string]Operation{}}
	if cm == nil || cm.Data[DataKey] == "" {
		return st, nil
	}
	if err := json.Unmarshal([]byte(cm.Data[DataKey]), &st); err != nil {
		return RebalancerState{Operations: map[string]Operation{}}, fmt.Errorf("decode %s: %w", DataKey, err)
	}
	if st.Operations == nil {
		st.Operations = map[string]Operation{}
	}
	return st, nil
}

func encode(st RebalancerState) (string, error) {
	prune(&st)
	b, err := json.Marshal(st)
	return string(b), err
}

// prune keeps the MaxOperations most recent operations (startedAt desc, then id).
func prune(st *RebalancerState) {
	if len(st.Operations) <= MaxOperations {
		return
	}
	ids := make([]string, 0, len(st.Operations))
	for id := range st.Operations {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool {
		a, b := st.Operations[ids[i]].StartedAt, st.Operations[ids[j]].StartedAt
		if a != b {
			return a > b
		}
		return ids[i] > ids[j]
	})
	for _, id := range ids[MaxOperations:] {
		delete(st.Operations, id)
	}
}

func copyState(st RebalancerState) RebalancerState {
	out := RebalancerState{Generation: st.Generation, LastObservedStatus: st.LastObservedStatus,
		Operations: make(map[string]Operation, len(st.Operations))}
	for k, v := range st.Operations {
		out.Operations[k] = v
	}
	return out
}

// Load reads the ConfigMap (missing = empty ledger). Called implicitly by the other
// methods on first use.
func (l *Ledger) Load(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.loadLocked(ctx)
}

func (l *Ledger) loadLocked(ctx context.Context) error {
	if l.loaded {
		return nil
	}
	cm, err := l.k8s.CoreV1().ConfigMaps(l.ns).Get(ctx, l.name, metav1.GetOptions{})
	if err != nil && !apierrors.IsNotFound(err) {
		return fmt.Errorf("get ledger configmap %s/%s: %w", l.ns, l.name, err)
	}
	if apierrors.IsNotFound(err) {
		cm = nil
	}
	st, err := decode(cm)
	if err != nil {
		return err
	}
	l.st = st
	l.loaded = true
	return nil
}

// mutate applies fn to the stored state. fn returns whether it changed anything.
// Live: Get + Update with resourceVersion (create if missing), retried on conflict.
// Read-only: fn is applied to the in-memory overlay only.
func (l *Ledger) mutate(ctx context.Context, fn func(st *RebalancerState) bool) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := l.loadLocked(ctx); err != nil {
		return err
	}
	if l.readOnly {
		fn(&l.st)
		return nil
	}
	retriable := func(err error) bool { return apierrors.IsConflict(err) || apierrors.IsAlreadyExists(err) }
	return retry.OnError(retry.DefaultRetry, retriable, func() error {
		cms := l.k8s.CoreV1().ConfigMaps(l.ns)
		cm, err := cms.Get(ctx, l.name, metav1.GetOptions{})
		if apierrors.IsNotFound(err) {
			st := RebalancerState{Operations: map[string]Operation{}}
			// A state equivalent to "no ConfigMap" (only a non-running status seen) is
			// not worth a create: observing idle must not write anything.
			if !fn(&st) || (st.Generation == 0 && len(st.Operations) == 0 && st.LastObservedStatus != "running") {
				l.st = st
				return nil
			}
			data, err := encode(st)
			if err != nil {
				return err
			}
			_, err = cms.Create(ctx, &corev1.ConfigMap{
				ObjectMeta: metav1.ObjectMeta{
					Name:      l.name,
					Namespace: l.ns,
					Labels: map[string]string{
						"app":                          "xamops-automation",
						"app.kubernetes.io/managed-by": "xamops",
					},
				},
				Data: map[string]string{DataKey: data},
			}, metav1.CreateOptions{})
			if err == nil {
				l.st = st
			}
			return err
		}
		if err != nil {
			return err
		}
		st, err := decode(cm)
		if err != nil {
			return err
		}
		if !fn(&st) {
			l.st = st
			return nil
		}
		data, err := encode(st)
		if err != nil {
			return err
		}
		cm = cm.DeepCopy()
		if cm.Data == nil {
			cm.Data = map[string]string{}
		}
		cm.Data[DataKey] = data
		if _, err := cms.Update(ctx, cm, metav1.UpdateOptions{}); err != nil {
			return err
		}
		l.st = st
		return nil
	})
}

// Observe records the rebalancer status seen this cycle. A non-running -> running
// edge increments the generation. Call it every cycle while ENABLE_REBALANCER is on,
// whatever the status, or edges cannot be seen.
func (l *Ledger) Observe(ctx context.Context, status string) error {
	return l.mutate(ctx, func(st *RebalancerState) bool {
		changed := false
		if status == "running" && st.LastObservedStatus != "running" {
			st.Generation++
			changed = true
		}
		if st.LastObservedStatus != status {
			st.LastObservedStatus = status
			changed = true
		}
		return changed
	})
}

// Generation returns the current generation counter.
func (l *Ledger) Generation(ctx context.Context) (int64, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := l.loadLocked(ctx); err != nil {
		return 0, err
	}
	return l.st.Generation, nil
}

// OperationID returns cfg.OperationID when set, else "gen-<generation>".
func (l *Ledger) OperationID(ctx context.Context, cfg policy.RebalancerConfig) (string, error) {
	if cfg.OperationID != "" {
		return cfg.OperationID, nil
	}
	gen, err := l.Generation(ctx)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("gen-%d", gen), nil
}

// Get returns a recorded operation.
func (l *Ledger) Get(ctx context.Context, opID string) (Operation, bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := l.loadLocked(ctx); err != nil {
		return Operation{}, false, err
	}
	op, ok := l.st.Operations[opID]
	return op, ok, nil
}

// Record stores an operation outcome (in-memory only when read-only).
func (l *Ledger) Record(ctx context.Context, opID string, op Operation) error {
	return l.mutate(ctx, func(st *RebalancerState) bool {
		if st.Operations == nil {
			st.Operations = map[string]Operation{}
		}
		st.Operations[opID] = op
		return true
	})
}

// Snapshot returns a copy of the current state (for logs and tests).
func (l *Ledger) Snapshot(ctx context.Context) (RebalancerState, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := l.loadLocked(ctx); err != nil {
		return RebalancerState{}, err
	}
	return copyState(l.st), nil
}
