/**
 * Live view topology shaping.
 *
 * CloudMapService returns an inventory with relationships: every resource the scan found,
 * parented by VPC and subnet, plus one edge per relationship it can infer. Drawn as-is that
 * is 40 cards and 60 labelled edges for a modest three-tier VPC, and the reader cannot tell
 * which of those lines is the path a request takes. This module turns the inventory into an
 * architecture diagram before Cytoscape sees it:
 *
 *   - Subnets are grouped by Availability Zone, so a VPC reads as AZ columns.
 *   - An Internet node is added above every VPC that has an internet gateway, so the path
 *     a request takes into the VPC has a visible start.
 *   - Supporting resources sit on optional layers (security groups, routing, storage,
 *     scaling). Off by default, so the default picture is compute, data and the gateways.
 *   - While a layer is off, the facts it carried are kept as direct edges instead of lost:
 *     a load balancer's target group becomes "forwards to" each target, and security group
 *     rules become "can reach" between the resources those groups protect. That second one
 *     is the connectivity question people open this page to answer.
 *   - Edges that only restate containment (a load balancer "attached to" the subnet it is
 *     drawn inside) are dropped, and reciprocal duplicates (volume "attaches to" instance and
 *     instance "mounts" volume) collapse to one.
 *
 * Pure and frontend-only: the backend contract is unchanged, and the connectivity view keeps
 * reading the raw elements.
 */

export type LiveLayer = 'security' | 'routing' | 'storage' | 'scaling' | 'interfaces';

export type LiveLayers = Record<LiveLayer, boolean>;

export const DEFAULT_LIVE_LAYERS: LiveLayers = {
  security: false,
  routing: false,
  storage: false,
  scaling: false,
  interfaces: false,
};

/** What an edge means on the canvas. Drives line style and the legend. */
export type FlowKind =
  /** A request path: internet to gateway, load balancer to target. */
  | 'traffic'
  /** Allowed by security group rules, not observed traffic. */
  | 'reach'
  /** A gateway carrying a subnet's default route. */
  | 'egress'
  /** Route table relationships (Routing layer). */
  | 'routing'
  /** Raw security group membership and rules (Security layer). */
  | 'security'
  /** Volumes, elastic IPs (Storage layer). */
  | 'storage'
  /** Auto scaling and target group membership (Scaling layer). */
  | 'scaling';

export const INTERNET_NODE_ID = '__cm-internet';
const AZ_PREFIX = '::az::';

type El = { group?: string; data: Record<string, any>; position?: { x: number; y: number } };

const LAYER_OF_TYPE: Record<string, LiveLayer> = {
  'Security Group': 'security',
  'Route Table': 'routing',
  'EBS Volume': 'storage',
  'Elastic IP': 'storage',
  'Target Group': 'scaling',
  'Auto Scaling Group': 'scaling',
  // Hundreds per VPC under EKS pod networking, with no relationships of their own: on UAT
  // they were 520 of 714 resources and buried every instance and gateway.
  'Network Interface': 'interfaces',
};

/** Layer and meaning for each relationship label CloudMapService.createAllEdges emits. */
const EDGE_RULES: Record<string, { layer: LiveLayer | null; kind: FlowKind }> = {
  'routes to': { layer: null, kind: 'traffic' },
  targets: { layer: null, kind: 'traffic' },
  behind: { layer: null, kind: 'traffic' },
  'egress for': { layer: null, kind: 'egress' },
  routes: { layer: 'routing', kind: 'routing' },
  'default via': { layer: 'routing', kind: 'routing' },
  'attached to': { layer: 'routing', kind: 'routing' },
  'uses SG': { layer: 'security', kind: 'security' },
  admits: { layer: 'security', kind: 'security' },
  'allowed out to': { layer: 'security', kind: 'security' },
  'attaches to': { layer: 'storage', kind: 'storage' },
  mounts: { layer: 'storage', kind: 'storage' },
  'uses EIP': { layer: 'storage', kind: 'storage' },
  'assigned to': { layer: 'storage', kind: 'storage' },
  manages: { layer: 'scaling', kind: 'scaling' },
  'registers to': { layer: 'scaling', kind: 'scaling' },
  'launches into': { layer: 'scaling', kind: 'scaling' },
};

/** Types that can carry a VPC relationship without a "VPC ID" detail of their own. */
const VPC_NATIVE = new Set(['VPC', 'Subnet', 'Availability Zone', 'Scope', 'Region']);

/** Display order of subnet tiers, top to bottom: the way traffic enters a VPC. */
export const TIER_ORDER: Record<string, number> = { Public: 0, Private: 1, Isolated: 2 };

const isEdge = (el: El) => el.group === 'edges' || (!!el.data?.source && !!el.data?.target);

export interface LiveTopology {
  elements: El[];
  /** Resources each layer would add, so the toolbar can say what is hidden. */
  hidden: Record<LiveLayer, number>;
}

/** Strip the per-VPC scope prefix loadAllGraphs adds, for display only. */
const bareId = (id: string) => {
  const i = id.lastIndexOf('::');
  return i >= 0 ? id.slice(i + 2) : id;
};

export function shapeLiveTopology(input: El[], layers: LiveLayers = DEFAULT_LIVE_LAYERS): LiveTopology {
  const hidden: Record<LiveLayer, number> = { security: 0, routing: 0, storage: 0, scaling: 0, interfaces: 0 };

  // Dedupe first: the all-VPCs merge can report a cross-VPC node from both sides.
  const nodes = new Map<string, El>();
  const rawEdges: El[] = [];
  for (const el of input) {
    if (!el?.data?.id) continue;
    if (isEdge(el)) rawEdges.push(el);
    else if (!nodes.has(el.data.id)) nodes.set(el.data.id, { ...el, group: 'nodes', data: { ...el.data } });
  }

  /*
   * Gateways the scan did not return, recovered from the route tables that name them.
   *
   * A route table's default route names its internet gateway or NAT gateway by id, but the
   * inventory scan does not always return the gateway itself — on UAT every route table
   * pointed at an igw- and nat- id with no node behind it. Their "default via" and "egress
   * for" edges were then dropped as dangling, and the one path that explains how a VPC
   * reaches the internet disappeared. The route table is AWS's own statement that the
   * gateway exists, so it is drawn, marked as inferred and with no status of its own.
   */
  const GATEWAY_BY_PREFIX: Array<[string, string]> = [
    ['igw-', 'Internet Gateway'], ['nat-', 'NAT Gateway'], ['tgw-', 'Transit Gateway'], ['vgw-', 'VPN Gateway'],
  ];
  for (const e of rawEdges) {
    const { source, target, label } = e.data;
    if (label !== 'default via' && label !== 'egress for') continue;
    const gw = label === 'default via' ? target : source;
    const anchor = label === 'default via' ? source : target;
    if (nodes.has(gw) || !nodes.has(anchor)) continue;
    const raw = bareId(gw);
    const kind = GATEWAY_BY_PREFIX.find(([prefix]) => raw.startsWith(prefix));
    if (!kind) continue;
    // Route tables hang off the VPC; for a subnet anchor, its parent is the VPC too.
    const vpc = nodes.get(anchor)!.data.parent;
    nodes.set(gw, {
      group: 'nodes',
      data: {
        id: gw, label: raw, type: kind[1], inferred: true,
        ...(vpc ? { parent: vpc, 'VPC ID': bareId(vpc) } : {}),
        Source: 'Inferred from the route table that targets it',
      },
    });
  }

  /*
   * Resources with no VPC of their own are not inside the VPC that happened to ask for them.
   *
   * withConnectedNeighbours widens a VPC response by one hop, and loadAllGraphs parents any
   * parentless node in that response to the requesting VPC. An S3 bucket or IAM-adjacent
   * resource therefore drew inside prod-vpc, which is false. Lifting them back out lets the
   * global scope box collect them, which is what the backend comment intends.
   */
  for (const n of nodes.values()) {
    const d = n.data;
    if (!d.parent || VPC_NATIVE.has(d.type) || d.isContainer) continue;
    const parent = nodes.get(d.parent);
    const ownVpc = d['VPC ID'];
    if (parent?.data.type === 'VPC' && (!ownVpc || ownVpc === 'N/A')) delete d.parent;
  }

  // ── Which nodes are on the canvas ─────────────────────────────────────────
  const visible = new Set<string>();
  for (const [id, n] of nodes) {
    const layer = LAYER_OF_TYPE[n.data.type];
    if (layer && !layers[layer]) { hidden[layer]++; continue; }
    visible.add(id);
  }

  // ── VPC membership, computed before subnets move under AZ containers ──────
  const vpcOf = (id: string): string | undefined => {
    let cur: string | undefined = id;
    let guard = 0;
    while (cur && guard++ < 16) {
      const d: Record<string, any> | undefined = nodes.get(cur)?.data;
      if (!d) return undefined;
      if (d.type === 'VPC') return cur;
      cur = d.parent;
    }
    return undefined;
  };

  /*
   * Collapse VPCs that hold nothing but networking scaffolding.
   *
   * Every region's default VPC arrives with three to six subnets and a route table and no
   * workload. On UAT that was most of 30 VPCs, and drawn in full they took most of the
   * canvas while saying nothing. They become a small labelled box that still says how many
   * subnets they have, so nobody wonders where they went.
   */
  const SCAFFOLD = new Set(['VPC', 'Subnet', 'Availability Zone', 'Route Table', 'Internet Gateway', 'Security Group', 'Scope']);
  const resourcesIn = new Map<string, number>();
  const subnetsIn = new Map<string, number>();
  for (const [id, nd] of nodes) {
    const vpc = vpcOf(id);
    if (!vpc || vpc === id) continue;
    if (nd.data.type === 'Subnet') subnetsIn.set(vpc, (subnetsIn.get(vpc) ?? 0) + 1);
    if (!SCAFFOLD.has(nd.data.type) && !nd.data.inferred) resourcesIn.set(vpc, (resourcesIn.get(vpc) ?? 0) + 1);
  }
  const collapsed = new Set<string>();
  for (const [id, nd] of nodes) {
    if (nd.data.type !== 'VPC' || (resourcesIn.get(id) ?? 0) > 0) continue;
    collapsed.add(id);
    const subnets = subnetsIn.get(id) ?? 0;
    nd.data.collapsed = true;
    nd.data.displayLabel = `${nd.data.label}\n${subnets} subnet${subnets === 1 ? '' : 's'} · no resources`;
  }
  if (collapsed.size > 0) {
    for (const id of [...visible]) {
      const vpc = vpcOf(id);
      if (vpc && vpc !== id && collapsed.has(vpc)) visible.delete(id);
    }
  }

  // ── Availability Zone columns ─────────────────────────────────────────────
  const azNodes: El[] = [];
  const azIds = new Set<string>();
  for (const n of nodes.values()) {
    const d = n.data;
    if (d.type !== 'Subnet' || !d.parent || !visible.has(d.id)) continue;
    const az = d['Availability Zone'];
    if (!az || az === 'N/A') continue;
    const azId = `${d.parent}${AZ_PREFIX}${az}`;
    if (!azIds.has(azId)) {
      azIds.add(azId);
      azNodes.push({
        group: 'nodes',
        data: { id: azId, label: az, type: 'Availability Zone', containerType: 'az', isContainer: true, parent: d.parent },
      });
    }
    d.parent = azId;
  }

  // Subnet captions carry the facts that make a subnet meaningful on a diagram.
  for (const n of nodes.values()) {
    const d = n.data;
    if (d.type !== 'Subnet') continue;
    d.tier = d.Tier && d.Tier !== 'N/A' ? d.Tier : undefined;
    d.cidr = d.CIDR && d.CIDR !== 'N/A' ? d.CIDR : undefined;
    d.displayLabel = [d.label, d.tier, d.cidr].filter(Boolean).join('  ·  ');
  }

  // VPC header: name, CIDR when the backend sent one, and the subnet / AZ shape.
  for (const n of nodes.values()) {
    const d = n.data;
    if (d.type !== 'VPC' || d.collapsed) continue;
    const azCount = azNodes.filter((a) => a.data.parent === d.id).length;
    d.displayLabel = [d.label, azCount ? `${azCount} AZ${azCount === 1 ? '' : 's'}` : null].filter(Boolean).join('  ·  ');
  }

  // ── Edges ─────────────────────────────────────────────────────────────────
  const ancestorsOf = (id: string): Set<string> => {
    const out = new Set<string>();
    let cur = nodes.get(id)?.data.parent;
    let guard = 0;
    while (cur && guard++ < 16) { out.add(cur); cur = nodes.get(cur)?.data.parent; }
    return out;
  };

  const out: El[] = [];
  const seen = new Set<string>();
  const pushEdge = (source: string, target: string, label: string, kind: FlowKind, derived = false) => {
    if (source === target || !visible.has(source) || !visible.has(target)) return;
    // Containment already says this; a line from a card to its own box reads as a loop.
    if (ancestorsOf(source).has(target) || ancestorsOf(target).has(source)) return;
    const key = `${source}|${target}|${kind}`;
    const reverse = `${target}|${source}|${kind}`;
    if (seen.has(key) || (kind === 'storage' && seen.has(reverse))) return;
    // A request path already proves reachability; a second "can reach" line on it is noise.
    if (kind === 'reach' && (seen.has(`${source}|${target}|traffic`) || seen.has(`${target}|${source}|traffic`))) return;
    seen.add(key);
    out.push({
      group: 'edges',
      data: { id: `${source}__${kind}__${target}`, source, target, label, kind, ...(derived ? { derived: true } : {}) },
    });
  };

  // Membership indexes for the derived edges.
  const sgMembers = new Map<string, string[]>();
  const tgTargets = new Map<string, string[]>();
  const lbToTg: Array<[string, string]> = [];
  const sgAdmits: Array<[string, string]> = [];
  for (const e of rawEdges) {
    const { source, target, label } = e.data;
    if (!nodes.has(source) || !nodes.has(target)) continue;
    if (label === 'uses SG') (sgMembers.get(target) ?? sgMembers.set(target, []).get(target)!).push(source);
    if (label === 'targets') (tgTargets.get(source) ?? tgTargets.set(source, []).get(source)!).push(target);
    if (label === 'routes to' && nodes.get(target)?.data.type === 'Target Group') lbToTg.push([source, target]);
    if (label === 'admits') sgAdmits.push([source, target]);
  }

  // Interfaces name the instance they are attached to; that is their only relationship.
  if (layers.interfaces) {
    for (const n of nodes.values()) {
      if (n.data.type !== 'Network Interface') continue;
      const inst = n.data['Attached to'];
      if (!inst || inst === 'N/A') continue;
      const scoped = [...nodes.keys()].find((k) => k === inst || k.endsWith(`::${inst}`));
      if (scoped) pushEdge(n.data.id, scoped, 'attached to', 'storage');
    }
  }

  for (const e of rawEdges) {
    const { source, target, label } = e.data;
    const rule = EDGE_RULES[label] ?? { layer: null, kind: 'traffic' as FlowKind };
    if (rule.layer && !layers[rule.layer]) continue;
    pushEdge(source, target, label, rule.kind);
  }

  // Target groups hidden: the load balancer forwards straight to what the group targets.
  if (!layers.scaling) {
    for (const [lb, tg] of lbToTg) {
      for (const t of tgTargets.get(tg) ?? []) pushEdge(lb, t, 'forwards to', 'traffic', true);
    }
  }

  /*
   * Security groups hidden: say who can reach whom.
   *
   * "sg-db admits sg-app" means anything in sg-app may open connections to anything in
   * sg-db. Expanded through membership that is the edge an operator actually wants —
   * prod-web-01 can reach prod-postgres — and it stays honest about being a permission,
   * not observed traffic, by drawing as a dashed "can reach" line.
   */
  if (!layers.security) {
    for (const [dest, src] of sgAdmits) {
      const from = sgMembers.get(src) ?? [];
      const to = sgMembers.get(dest) ?? [];
      for (const a of from) for (const b of to) pushEdge(a, b, 'can reach', 'reach', true);
    }
  }

  // ── Internet ─────────────────────────────────────────────────────────────
  const gateways = [...visible].filter((id) => nodes.get(id)?.data.type === 'Internet Gateway');
  const extra: El[] = [];
  /*
   * One Internet entry per VPC, drawn directly above it.
   *
   * A single shared node had to sit somewhere, and with 30 VPCs across many regions its
   * lines crossed the entire canvas to reach each gateway. The internet is not a place on
   * this map, so each VPC gets its own entry point and its lines stay short and vertical.
   */
  const internetFor = new Map<string, string>();
  for (const g of gateways) {
    const vpc = vpcOf(g) ?? '';
    let inet = internetFor.get(vpc);
    if (!inet) {
      inet = vpc ? `${INTERNET_NODE_ID}::${vpc}` : INTERNET_NODE_ID;
      internetFor.set(vpc, inet);
      // No state: a status dot on "the internet" would claim something nobody measured.
      extra.push({ group: 'nodes', data: { id: inet, label: 'Internet', type: 'Internet', ...(vpc ? { forVpc: vpc } : {}) } });
      visible.add(inet);
    }
    pushEdge(inet, g, 'ingress', 'traffic', true);
  }

  // Short ids for the inspector header, without the scope prefix.
  for (const id of visible) {
    const n = nodes.get(id);
    if (n && !n.data.resourceId) n.data.resourceId = bareId(id);
  }

  const shownNodes = [...nodes.values()].filter((n) => visible.has(n.data.id));
  return { elements: [...extra, ...azNodes, ...shownNodes, ...out], hidden };
}
