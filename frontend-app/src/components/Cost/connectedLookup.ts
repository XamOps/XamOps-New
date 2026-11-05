/**
 * What to ask the connected-resources endpoint for a resource id taken from the Cost and Usage
 * Report.
 *
 * <p>The endpoint switches on an inventory display type ("EC2 Instance", "NAT Gateway", ...) and
 * each branch expects the id in a particular form. The report gives neither: its service column is
 * a billing product ("Amazon Elastic Compute Cloud" covers instances, volumes and NAT gateways
 * alike), and its resource id is a bare id for some types and a full ARN for others. So both the
 * type and the id the endpoint wants are derived here from the id's own shape.
 *
 * <p>Returns null for anything the endpoint has no branch for, so the caller can say discovery is
 * unavailable for that type rather than claim nothing is connected.
 */
export interface ConnectedLookup {
  /** The display type the endpoint switches on. */
  type: string;
  /** The id in the form that type's branch expects. */
  id: string;
  /** The region named in the ARN, when the id is one. */
  region?: string;
}

export const connectedLookupFor = (resourceId: string): ConnectedLookup | null => {
  const id = resourceId.trim();
  if (/^i-[0-9a-f]+$/.test(id)) return { type: 'EC2 Instance', id };
  if (/^vol-[0-9a-f]+$/.test(id)) return { type: 'EBS Volume', id };
  if (/^nat-[0-9a-f]+$/.test(id)) return { type: 'NAT Gateway', id };
  if (!id.startsWith('arn:')) return null;

  // arn:partition:service:region:account:resource — the resource part may itself contain colons.
  const parts = id.split(':');
  if (parts.length < 6) return null;
  const service = parts[2];
  const region = parts[3] || undefined;
  const resource = parts.slice(5).join(':');

  switch (service) {
    case 'ec2':
      if (resource.startsWith('instance/')) return { type: 'EC2 Instance', id: resource.slice(9), region };
      if (resource.startsWith('volume/')) return { type: 'EBS Volume', id: resource.slice(7), region };
      if (resource.startsWith('natgateway/')) return { type: 'NAT Gateway', id: resource.slice(11), region };
      return null;
    case 'elasticloadbalancing':
      // The load balancer branch matches on the full ARN.
      return resource.startsWith('loadbalancer/') ? { type: 'Load Balancer', id, region } : null;
    case 'rds':
      return resource.startsWith('db:') ? { type: 'RDS Instance', id: resource.slice(3), region } : null;
    case 'lambda':
      // Strip a version or alias qualifier: function:name:42 -> name.
      return resource.startsWith('function:')
        ? { type: 'Lambda Function', id: resource.slice(9).split(':')[0], region }
        : null;
    case 'eks':
      return resource.startsWith('cluster/') ? { type: 'EKS Cluster', id: resource.slice(8), region } : null;
    case 'ecs':
      // The ECS branch takes the cluster ARN as is.
      return resource.startsWith('cluster/') ? { type: 'ECS Cluster', id, region } : null;
    case 'autoscaling': {
      const m = /autoScalingGroupName\/(.+)$/.exec(resource);
      return m ? { type: 'Auto Scaling Group', id: m[1], region } : null;
    }
    default:
      return null;
  }
};
