package invariants

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	coordinationv1 "k8s.io/api/coordination/v1"
	corev1 "k8s.io/api/core/v1"
	policyv1 "k8s.io/api/policy/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/util/intstr"
)

// Verdicts.
const (
	Pass         = "PASS"
	Fail         = "FAIL"
	NotEvaluated = "NOT-EVALUATED"
)

// The executor's drain-state annotation (internal/drain/budget.go).
const (
	annotationDrainState = "xamops.io/drain-state"
	stateDraining        = "draining"
)

// Options tune the checker.
type Options struct {
	MaxParallelDrains int
	LeaderTolerance   time.Duration
	LeaseName         string
}

// DefaultOptions: budget 1, 2 s leader tolerance, lease xamops-automation-leader.
func DefaultOptions() Options {
	return Options{MaxParallelDrains: 1, LeaderTolerance: 2 * time.Second, LeaseName: "xamops-automation-leader"}
}

// Breach is one interval during which an invariant did not hold. Instantaneous
// breaches (I1, I14) have Start == End.
type Breach struct {
	Start      time.Time `json:"start"`
	End        time.Time `json:"end"`
	DurationMs int64     `json:"durationMs"`
	Object     string    `json:"object"`
	Detail     string    `json:"detail"`
}

// Exclusion is an object the invariant deliberately did not judge.
type Exclusion struct {
	Object string `json:"object"`
	Reason string `json:"reason"`
}

// Result is the verdict of one invariant.
type Result struct {
	ID       string      `json:"id"`
	Name     string      `json:"name"`
	Verdict  string      `json:"verdict"`
	Detail   string      `json:"detail,omitempty"`
	Breaches []Breach    `json:"breaches"`
	Excluded []Exclusion `json:"excluded,omitempty"`
	// NotEvaluated lists individual items (I14 log lines) that could not be judged.
	NotEvaluated []string `json:"notEvaluated,omitempty"`
}

// Report is the checker output (invariants-report.json).
type Report struct {
	Options    Options          `json:"options"`
	Invariants []Result         `json:"invariants"`
	Coverage   []StreamCoverage `json:"coverage"`
	Errors     []string         `json:"errors,omitempty"`
}

// Get returns the result of invariant id.
func (r Report) Get(id string) Result {
	for _, x := range r.Invariants {
		if x.ID == id {
			return x
		}
	}
	return Result{}
}

// Text renders one verdict line per invariant plus its breaches.
func (r Report) Text() string {
	var b strings.Builder
	for _, x := range r.Invariants {
		fmt.Fprintf(&b, "%-4s %-13s %s", x.ID, x.Verdict, x.Name)
		if x.Detail != "" {
			fmt.Fprintf(&b, " — %s", x.Detail)
		}
		b.WriteString("\n")
		for _, br := range x.Breaches {
			fmt.Fprintf(&b, "       breach %s → %s (%d ms) %s: %s\n",
				br.Start.Format(time.RFC3339Nano), br.End.Format(time.RFC3339Nano), br.DurationMs, br.Object, br.Detail)
		}
		for _, e := range x.Excluded {
			fmt.Fprintf(&b, "       excluded %s: %s\n", e.Object, e.Reason)
		}
		for _, n := range x.NotEvaluated {
			fmt.Fprintf(&b, "       not evaluated: %s\n", n)
		}
	}
	for _, e := range r.Errors {
		fmt.Fprintf(&b, "error: %s\n", e)
	}
	return b.String()
}

// HAFloor is the minimum ready replica count of a workload with replicas desired
// replicas, given the PDBs that select it: the largest floor any PDB implies
// (minAvailable int/%, or replicas - maxUnavailable int/%, percentages rounded up),
// else max(replicas-1, 1); capped at replicas and never negative.
func HAFloor(replicas int32, pdbs []policyv1.PodDisruptionBudget) (int32, string) {
	if replicas <= 0 {
		return 0, "replicas=0"
	}
	if len(pdbs) == 0 {
		f := replicas - 1
		if f < 1 {
			f = 1
		}
		return f, "no PDB: replicas-1 (min 1)"
	}
	best, src, found := int32(0), "", false
	for _, p := range pdbs {
		var f int32
		var s string
		switch {
		case p.Spec.MinAvailable != nil:
			f = scaled(p.Spec.MinAvailable, replicas)
			s = fmt.Sprintf("pdb %s/%s minAvailable=%s", p.Namespace, p.Name, p.Spec.MinAvailable.String())
		case p.Spec.MaxUnavailable != nil:
			f = replicas - scaled(p.Spec.MaxUnavailable, replicas)
			s = fmt.Sprintf("pdb %s/%s maxUnavailable=%s", p.Namespace, p.Name, p.Spec.MaxUnavailable.String())
		default:
			continue
		}
		if !found || f > best {
			best, src, found = f, s, true
		}
	}
	if !found {
		f := replicas - 1
		if f < 1 {
			f = 1
		}
		return f, "PDB without minAvailable/maxUnavailable: replicas-1 (min 1)"
	}
	if best > replicas {
		best = replicas
	}
	if best < 0 {
		best = 0
	}
	return best, src
}

// scaled resolves an int-or-percent against replicas, rounding percentages up.
func scaled(v *intstr.IntOrString, replicas int32) int32 {
	n, err := intstr.GetScaledValueFromIntOrPercent(v, int(replicas), true)
	if err != nil {
		return 0
	}
	return int32(n)
}

// clockSkewNote: I14 compares two clock domains. Action times are container-runtime
// log timestamps (node clock); lease-holder times are workstation receive times
// (plus watch delivery latency). The leader tolerance has to absorb that skew.
const clockSkewNote = "action times are node log timestamps, lease times are workstation receive times: the tolerance must absorb node/workstation clock skew plus watch latency"

var actionLine = regexp.MustCompile(`Drain\[[^\]]+\]: (succeeded|aborted|failed) \(verified=|\bDrain: \S+/\S+ replaced by `)

type workload struct {
	kind, ns, name string
	replicas       int32
	ready          int32
	// floorReplicas is the replica count the HA floor is judged against. On a
	// scale-up it stays at the previous count until readyReplicas reaches the new
	// floor, so new pods that are not Ready yet are not an I2 breach.
	floorReplicas int32
	tmpl          map[string]string
	healthySeen   bool
	open          *Breach
	minReady      int32
}

func (w *workload) id() string { return w.kind + "/" + w.ns + "/" + w.name }

type holder struct {
	ts       time.Time
	identity string
}

type action struct {
	ts         time.Time
	pod, line  string
	recordDesc string
}

type checker struct {
	opts Options
	errs []string

	pods      map[string]*corev1.Pod
	pdbs      map[string]*policyv1.PodDisruptionBudget
	workloads map[string]*workload
	draining  map[string]bool
	holders   []holder
	actions   []action

	i1, i2, i6 []Breach
	i6open     *Breach
	i6max      int
}

// Check evaluates I1, I2, I6 and I14 on every record of tl.
func Check(tl *Timeline, opts Options) Report {
	if opts.MaxParallelDrains <= 0 {
		opts.MaxParallelDrains = 1
	}
	if opts.LeaseName == "" {
		opts.LeaseName = DefaultOptions().LeaseName
	}
	c := &checker{
		opts: opts, pods: map[string]*corev1.Pod{}, pdbs: map[string]*policyv1.PodDisruptionBudget{},
		workloads: map[string]*workload{}, draining: map[string]bool{},
	}
	var last time.Time
	for _, r := range tl.Records {
		last = r.TS
		switch r.Type {
		case "BOOKMARK", "ERROR":
			continue
		}
		switch r.Stream {
		case StreamPods:
			c.pod(r)
		case StreamPDBs:
			c.pdb(r)
		case StreamDeployments, StreamStatefulSets:
			c.workload(r)
		case StreamNodes:
			c.node(r)
		case StreamLeases:
			c.lease(r)
		case StreamAgentLogs:
			c.log(r)
		}
	}
	// Breaches still open at the end of the window end at the last record.
	for _, w := range c.workloads {
		c.closeWorkload(w, last, " (still open at end of window)")
	}
	if c.i6open != nil {
		c.closeI6(last, " (still open at end of window)")
	}

	rep := Report{Options: opts, Errors: c.errs}
	for _, s := range Streams {
		rep.Coverage = append(rep.Coverage, *tl.Coverage[s])
	}
	rep.Invariants = []Result{c.resultI1(tl), c.resultI2(tl), c.resultI6(tl), c.resultI14(tl)}
	return rep
}

func (c *checker) decode(r Record, into interface{}) bool {
	if err := json.Unmarshal(r.Object, into); err != nil {
		c.errs = append(c.errs, fmt.Sprintf("%s %s at %s: %v", r.Stream, r.Type, r.TS.Format(time.RFC3339Nano), err))
		return false
	}
	return true
}

func key(ns, name string) string { return ns + "/" + name }

func podReady(p *corev1.Pod) bool {
	for _, cond := range p.Status.Conditions {
		if cond.Type == corev1.PodReady {
			return cond.Status == corev1.ConditionTrue
		}
	}
	return false
}

// pod applies a pod record and checks I1 when the pod newly starts terminating.
func (c *checker) pod(r Record) {
	p := &corev1.Pod{}
	if !c.decode(r, p) {
		return
	}
	k := key(p.Namespace, p.Name)
	prev := c.pods[k]
	terminating := false
	if r.Type == "DELETED" {
		delete(c.pods, k)
		terminating = prev != nil && prev.DeletionTimestamp == nil
	} else {
		c.pods[k] = p
		terminating = p.DeletionTimestamp != nil && prev != nil && prev.DeletionTimestamp == nil
	}
	// A pod first seen already terminating has no known prior Ready state: not judged.
	if !terminating || !podReady(prev) {
		return
	}
	for _, pdb := range c.pdbs {
		if pdb.Namespace != p.Namespace || !selects(pdb, prev.Labels) {
			continue
		}
		if pdb.Status.DisruptionsAllowed > 0 {
			continue
		}
		if _, budgeted := pdb.Status.DisruptedPods[p.Name]; budgeted {
			continue
		}
		c.i1 = append(c.i1, Breach{Start: r.TS, End: r.TS, Object: "pod " + k,
			Detail: fmt.Sprintf("Ready pod started terminating (%s) while pdb %s allowed 0 disruptions and did not list it in disruptedPods; %s",
				strings.ToLower(r.Type), key(pdb.Namespace, pdb.Name), c.i1Attribution(prev.Spec.NodeName))})
	}
}

// i1Attribution says whether an I1 breach happened on a node XamOps was draining.
// The checker cannot see who deleted a pod: ReplicaSet scale-down, rolling-update
// and node-termination deletes are not governed by PDBs, so a breach on a node that
// was not draining needs attribution against events.jsonl before it counts as an
// eviction bypass.
func (c *checker) i1Attribution(node string) string {
	if node == "" {
		return "node unknown: attribute against events.jsonl"
	}
	if c.draining[node] {
		return fmt.Sprintf("node %s was draining under XamOps", node)
	}
	return fmt.Sprintf("node %s was not draining under XamOps: may be a controller delete (scale-down, rollout, node termination); attribute against events.jsonl", node)
}

func selects(pdb *policyv1.PodDisruptionBudget, l map[string]string) bool {
	if pdb.Spec.Selector == nil {
		return false
	}
	sel, err := metav1.LabelSelectorAsSelector(pdb.Spec.Selector)
	if err != nil || sel.Empty() {
		return false
	}
	return sel.Matches(labels.Set(l))
}

func (c *checker) pdb(r Record) {
	p := &policyv1.PodDisruptionBudget{}
	if !c.decode(r, p) {
		return
	}
	k := key(p.Namespace, p.Name)
	if r.Type == "DELETED" {
		delete(c.pdbs, k)
	} else {
		c.pdbs[k] = p
	}
	for _, w := range c.workloads {
		if w.ns == p.Namespace {
			c.evalWorkload(w, r.TS)
		}
	}
}

func (c *checker) workload(r Record) {
	w := &workload{}
	var meta metav1.ObjectMeta
	switch r.Stream {
	case StreamDeployments:
		d := &appsv1.Deployment{}
		if !c.decode(r, d) {
			return
		}
		meta, w.kind, w.tmpl, w.ready = d.ObjectMeta, "Deployment", d.Spec.Template.Labels, d.Status.ReadyReplicas
		w.replicas = 1
		if d.Spec.Replicas != nil {
			w.replicas = *d.Spec.Replicas
		}
	default:
		s := &appsv1.StatefulSet{}
		if !c.decode(r, s) {
			return
		}
		meta, w.kind, w.tmpl, w.ready = s.ObjectMeta, "StatefulSet", s.Spec.Template.Labels, s.Status.ReadyReplicas
		w.replicas = 1
		if s.Spec.Replicas != nil {
			w.replicas = *s.Spec.Replicas
		}
	}
	w.ns, w.name = meta.Namespace, meta.Name
	prev := c.workloads[w.id()]
	if r.Type == "DELETED" {
		if prev != nil {
			c.closeWorkload(prev, r.TS, " (workload deleted)")
			delete(c.workloads, w.id())
		}
		return
	}
	if prev != nil {
		prev.replicas, prev.ready, prev.tmpl = w.replicas, w.ready, w.tmpl
		if w.replicas < prev.floorReplicas {
			prev.floorReplicas = w.replicas // scale-down applies at once
		}
		w = prev
	} else {
		w.floorReplicas = w.replicas
		c.workloads[w.id()] = w
	}
	c.evalWorkload(w, r.TS)
}

func (c *checker) matchingPDBs(ns string, l map[string]string) []policyv1.PodDisruptionBudget {
	var out []policyv1.PodDisruptionBudget
	keys := make([]string, 0, len(c.pdbs))
	for k := range c.pdbs {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		p := c.pdbs[k]
		if p.Namespace == ns && selects(p, l) {
			out = append(out, *p)
		}
	}
	return out
}

func (c *checker) evalWorkload(w *workload, ts time.Time) {
	pdbs := c.matchingPDBs(w.ns, w.tmpl)
	if w.replicas <= 0 {
		c.closeWorkload(w, ts, "")
		return
	}
	// A pending scale-up completes once the new floor is reached.
	if newFloor, _ := HAFloor(w.replicas, pdbs); w.ready >= newFloor {
		w.floorReplicas = w.replicas
	}
	floor, src := HAFloor(w.floorReplicas, pdbs)
	if w.floorReplicas < w.replicas {
		src += fmt.Sprintf("; scale-up to %d pending, judged against replicas=%d", w.replicas, w.floorReplicas)
	}
	if w.ready >= floor {
		w.healthySeen = true
		c.closeWorkload(w, ts, "")
		return
	}
	if !w.healthySeen {
		return
	}
	if w.open == nil {
		w.open = &Breach{Start: ts, Object: w.id(),
			Detail: fmt.Sprintf("readyReplicas=%d < floor=%d of replicas=%d (%s)", w.ready, floor, w.replicas, src)}
		w.minReady = w.ready
	} else if w.ready < w.minReady {
		w.minReady = w.ready
		w.open.Detail = fmt.Sprintf("readyReplicas=%d < floor=%d of replicas=%d (%s)", w.ready, floor, w.replicas, src)
	}
}

func (c *checker) closeWorkload(w *workload, ts time.Time, suffix string) {
	if w.open == nil {
		return
	}
	b := *w.open
	b.End = ts
	b.DurationMs = ts.Sub(b.Start).Milliseconds()
	b.Detail += suffix
	c.i2 = append(c.i2, b)
	w.open = nil
}

func (c *checker) node(r Record) {
	n := &corev1.Node{}
	if !c.decode(r, n) {
		return
	}
	if r.Type == "DELETED" || n.Annotations[annotationDrainState] != stateDraining {
		delete(c.draining, n.Name)
	} else {
		c.draining[n.Name] = true
	}
	count := len(c.draining)
	if count > c.opts.MaxParallelDrains {
		names := make([]string, 0, count)
		for k := range c.draining {
			names = append(names, k)
		}
		sort.Strings(names)
		if c.i6open == nil {
			c.i6open = &Breach{Start: r.TS}
			c.i6max = 0
		}
		if count > c.i6max {
			c.i6max = count
			c.i6open.Object = "nodes " + strings.Join(names, ",")
			c.i6open.Detail = fmt.Sprintf("%d nodes draining at once, budget %d", count, c.opts.MaxParallelDrains)
		}
	} else if c.i6open != nil {
		c.closeI6(r.TS, "")
	}
}

func (c *checker) closeI6(ts time.Time, suffix string) {
	b := *c.i6open
	b.End = ts
	b.DurationMs = ts.Sub(b.Start).Milliseconds()
	b.Detail += suffix
	c.i6 = append(c.i6, b)
	c.i6open = nil
}

func (c *checker) lease(r Record) {
	l := &coordinationv1.Lease{}
	if !c.decode(r, l) || l.Name != c.opts.LeaseName {
		return
	}
	id := ""
	if r.Type != "DELETED" && l.Spec.HolderIdentity != nil {
		id = *l.Spec.HolderIdentity
	}
	if n := len(c.holders); n > 0 && c.holders[n-1].identity == id {
		return
	}
	c.holders = append(c.holders, holder{ts: r.TS, identity: id})
}

func (c *checker) log(r Record) {
	line := strings.TrimSpace(r.Line)
	if strings.HasPrefix(line, "{") || !actionLine.MatchString(line) {
		return
	}
	c.actions = append(c.actions, action{ts: r.TS, pod: r.Pod, line: line,
		recordDesc: fmt.Sprintf("%s %s: %s", r.TS.Format(time.RFC3339Nano), r.Pod, line)})
}

// heldWithin reports whether pod held the lease at any point in [t-tol, t+tol].
func (c *checker) heldWithin(pod string, t time.Time, tol time.Duration) bool {
	lo, hi := t.Add(-tol), t.Add(tol)
	for i, h := range c.holders {
		if h.identity != pod {
			continue
		}
		start := h.ts
		if start.After(hi) {
			continue
		}
		if i+1 < len(c.holders) && c.holders[i+1].ts.Before(lo) {
			continue
		}
		return true
	}
	return false
}

func verdict(breaches []Breach) string {
	if len(breaches) > 0 {
		return Fail
	}
	return Pass
}

func notEvaluated(id, name string, missing ...string) Result {
	return Result{ID: id, Name: name, Verdict: NotEvaluated, Breaches: []Breach{},
		Detail: "missing or empty stream(s): " + strings.Join(missing, ", ")}
}

func missing(tl *Timeline, streams ...string) []string {
	var out []string
	for _, s := range streams {
		if !tl.Has(s) {
			out = append(out, s)
		}
	}
	return out
}

func nonNil(b []Breach) []Breach {
	if b == nil {
		return []Breach{}
	}
	return b
}

func (c *checker) resultI1(tl *Timeline) Result {
	const name = "no PDB violated"
	if m := missing(tl, StreamPods, StreamPDBs); len(m) > 0 {
		return notEvaluated("I1", name, m...)
	}
	res := Result{ID: "I1", Name: name, Verdict: verdict(c.i1), Breaches: nonNil(c.i1)}
	if len(c.i1) > 0 {
		res.Detail = "a FAIL needs attribution: the checker cannot tell an eviction bypass from a controller delete (see each breach)"
	}
	return res
}

func (c *checker) resultI2(tl *Timeline) Result {
	const name = "HA floor (PDB minAvailable/maxUnavailable, else replicas-1, min 1)"
	if !tl.Has(StreamDeployments) && !tl.Has(StreamStatefulSets) {
		return notEvaluated("I2", name, StreamDeployments, StreamStatefulSets)
	}
	res := Result{ID: "I2", Name: name, Verdict: verdict(c.i2), Breaches: nonNil(c.i2)}
	if !tl.Has(StreamPDBs) {
		res.Detail = "no pdbs stream: every workload judged against replicas-1"
	}
	ids := make([]string, 0, len(c.workloads))
	for id, w := range c.workloads {
		if !w.healthySeen && w.replicas > 0 {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	for _, id := range ids {
		res.Excluded = append(res.Excluded, Exclusion{Object: id, Reason: "never healthy in window"})
	}
	sort.SliceStable(res.Breaches, func(i, j int) bool { return res.Breaches[i].Start.Before(res.Breaches[j].Start) })
	return res
}

func (c *checker) resultI6(tl *Timeline) Result {
	name := fmt.Sprintf("concurrent drains <= %d", c.opts.MaxParallelDrains)
	if m := missing(tl, StreamNodes); len(m) > 0 {
		return notEvaluated("I6", name, m...)
	}
	return Result{ID: "I6", Name: name, Verdict: verdict(c.i6), Breaches: nonNil(c.i6)}
}

func (c *checker) resultI14(tl *Timeline) Result {
	name := fmt.Sprintf("only the %s holder acts (tolerance %s)", c.opts.LeaseName, c.opts.LeaderTolerance)
	if m := missing(tl, StreamLeases, StreamAgentLogs); len(m) > 0 {
		return notEvaluated("I14", name, m...)
	}
	res := Result{ID: "I14", Name: name, Breaches: []Breach{}}
	evaluated := 0
	for _, a := range c.actions {
		if len(c.holders) == 0 || a.ts.Before(c.holders[0].ts) {
			res.NotEvaluated = append(res.NotEvaluated, a.recordDesc+" (before the first lease record)")
			continue
		}
		evaluated++
		if !c.heldWithin(a.pod, a.ts, c.opts.LeaderTolerance) {
			res.Breaches = append(res.Breaches, Breach{Start: a.ts, End: a.ts, Object: "pod " + a.pod,
				Detail: fmt.Sprintf("acted without holding lease %s within %s: %s", c.opts.LeaseName, c.opts.LeaderTolerance, a.line)})
		}
	}
	switch {
	case len(res.Breaches) > 0:
		res.Verdict = Fail
	case evaluated == 0:
		res.Verdict = NotEvaluated
		res.Detail = "no agent action line could be judged in the window"
	default:
		res.Verdict = Pass
		res.Detail = fmt.Sprintf("%d action line(s) judged", evaluated)
	}
	if evaluated > 0 {
		res.Detail = strings.TrimPrefix(res.Detail+"; "+clockSkewNote, "; ")
	}
	return res
}
