// Package removal is the node-removal actor split for the consolidation engine
// (consolidation design §1, invariants I11/I12/I13). After a node is cordoned and
// drained, exactly one actor removes it: XamOps defers to Karpenter or Cluster
// Autoscaler when one is present, else it calls TerminateInstanceInAutoScalingGroup
// with ShouldDecrementDesiredCapacity=true — never launching a replacement — and
// refuses fail-closed for an unmanaged node, a nodegroup already at its minimum, or
// an unknown actor state.
//
// Node removal sits behind the CloudProvider interface so unit and integration
// tests never touch EC2/ASG (FakeProvider) and the soak can inject failures. The
// removal decision itself is the pure DecideRemoval, table-tested from NodeFacts.
package removal

import "context"

// CloudProvider is the cloud-side surface node removal needs. AWSProvider is the
// production implementation; FakeProvider backs every test.
type CloudProvider interface {
	// Lookup maps an EC2 instance id to its ASG, that ASG's tags, MinSize and
	// DesiredCapacity, and the managed-nodegroup name (empty if none).
	Lookup(ctx context.Context, instanceID string) (NodeFacts, error)
	// TerminateAndDecrement terminates the instance with
	// ShouldDecrementDesiredCapacity=true. It NEVER launches a replacement and NEVER
	// raises/sets desired/min/max. It is idempotent on an already-terminating
	// instance.
	TerminateAndDecrement(ctx context.Context, instanceID, asg string) error
}

// NodeFacts is what Lookup resolves about an instance: its ASG and that ASG's
// tags/sizes, plus the managed-nodegroup name when the ASG is an EKS managed
// nodegroup's ASG.
type NodeFacts struct {
	InstanceID      string
	ASG             string
	Tags            map[string]string
	MinSize         int
	DesiredCapacity int
	// ManagedNodegroup is the EKS managed-nodegroup name (from the ASG's
	// eks:nodegroup-name tag and the nodegroup inventory); "" when the ASG is not a
	// managed nodegroup's ASG (self-managed, Fargate, …).
	ManagedNodegroup string
}
