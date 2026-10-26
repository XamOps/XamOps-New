package removal

import (
	"strings"

	"github.com/xamops/automation/internal/consolidation"
)

// Decision is how a drained, emptied node is removed.
type Decision int

const (
	// DeferToActor: Karpenter or Cluster Autoscaler is present — XamOps cordons and
	// drains, then does NOT terminate; the actor removes the empty node (I12).
	DeferToActor Decision = iota
	// XamopsASG: neither actor present — XamOps calls
	// TerminateInstanceInAutoScalingGroup with decrement (I11).
	XamopsASG
	// Refuse: not XamOps-managed, at minimum size, or an unknown/unresolvable state
	// (fail-closed).
	Refuse
)

func (d Decision) String() string {
	switch d {
	case DeferToActor:
		return "defer-to-actor"
	case XamopsASG:
		return "xamops-asg"
	case Refuse:
		return "refuse"
	default:
		return "unknown"
	}
}

// DecideRemoval is the pure actor-removal decision (no I/O), encoding I11/I12/I13.
// It returns the decision and a short reason string.
//
//   - Karpenter / CA / MULTIPLE detected  -> DeferToActor.
//   - NONE: XamopsASG, subject to three fail-closed refusals — an unmanaged node
//     (I13: no EKS managed nodegroup), a nodegroup already at its minimum (I11),
//     and an unresolvable instance id.
//   - Any other / unknown actor state     -> Refuse.
func DecideRemoval(actor consolidation.Actor, node NodeFacts) (Decision, string) {
	switch actor.Kind {
	case consolidation.ActorKarpenter:
		return DeferToActor, "actor=KARPENTER — cordoned + drained; Karpenter removes the empty node"
	case consolidation.ActorClusterAutoscaler:
		return DeferToActor, "actor=CLUSTER_AUTOSCALER — cordoned + drained; Cluster Autoscaler removes the empty node"
	case consolidation.ActorMultiple:
		return DeferToActor, "actor=MULTIPLE — cordoned + drained; deferring removal to the node autoscaler"
	case consolidation.ActorNone:
		// fall through to the ASG path below.
	default:
		return Refuse, "actor unknown — refusing removal (fail-closed)"
	}

	// actor == NONE: the XamOps-owned ASG terminate path, with fail-closed refusals.
	if node.ManagedNodegroup == "" {
		return Refuse, "unmanaged-node — not an EKS managed nodegroup instance"
	}
	if node.DesiredCapacity <= node.MinSize {
		return Refuse, "nodegroup-at-min — desired capacity already at the nodegroup minimum"
	}
	return XamopsASG, "actor=NONE — terminating via XamOps ASG with decrement"
}

// parseInstanceID extracts the EC2 instance id from a providerID
// (aws:///<az>/i-0abc…): the final non-empty "/"-separated segment, which must have
// an i- prefix. Anything else fails closed.
func parseInstanceID(providerID string) (string, bool) {
	if providerID == "" {
		return "", false
	}
	segs := strings.Split(providerID, "/")
	last := ""
	for i := len(segs) - 1; i >= 0; i-- {
		if segs[i] != "" {
			last = segs[i]
			break
		}
	}
	if !strings.HasPrefix(last, "i-") || len(last) <= len("i-") {
		return "", false
	}
	return last, true
}

// ParseInstanceID is the exported form of parseInstanceID for the controller (it
// turns a node's providerID into the instance id the provider Lookup/Terminate
// calls need, refusing fail-closed on a non-i- segment).
func ParseInstanceID(providerID string) (string, bool) { return parseInstanceID(providerID) }
