/**
 * BP-16 (design §8.1): the data-agent nodegroup-scaling row, and the pure helpers the
 * NodeGroupsPanel uses to show Min / Desired / Max / AMI when `source=AGENT` and to swap the
 * "needs the EKS node-group API" note for a "from the EKS API" line. Kept pure so both modes are
 * unit-testable without rendering the DataTable.
 */

export interface NodegroupScalingRow {
  name: string;
  status: string | null;
  capacityType: string | null;
  min: number | null;
  desired: number | null;
  max: number | null;
  asgNames: string[];
  amiType: string | null;
  instanceTypes: string[];
}

export interface NodegroupScalingResponse {
  source: 'AGENT' | 'NONE';
  collectedAt?: string;
  nodegroups: NodegroupScalingRow[];
}

export const NODE_GROUP_API_NOTE =
  'Scaling limits and AMI details need the EKS node-group API (not yet available in XamOps).';

/** The intro note: the AGENT line with age when a fresh row exists, else the "not available" note. */
export function nodeGroupNote(resp: NodegroupScalingResponse | null): string {
  if (resp && resp.source === 'AGENT') {
    const age = resp.collectedAt ? ` · ${resp.collectedAt}` : '';
    return `Scaling limits from the EKS API${age}`;
  }
  return NODE_GROUP_API_NOTE;
}

/** Whether the panel should render the Min/Desired/Max/AMI columns. */
export function hasAgentScaling(resp: NodegroupScalingResponse | null): boolean {
  return !!resp && resp.source === 'AGENT' && resp.nodegroups.length > 0;
}

/** Index the scaling rows by nodegroup name for per-group lookup in the table. */
export function indexByName(resp: NodegroupScalingResponse | null): Map<string, NodegroupScalingRow> {
  const map = new Map<string, NodegroupScalingRow>();
  if (resp && resp.source === 'AGENT') {
    for (const ng of resp.nodegroups) {
      map.set(ng.name, ng);
    }
  }
  return map;
}
