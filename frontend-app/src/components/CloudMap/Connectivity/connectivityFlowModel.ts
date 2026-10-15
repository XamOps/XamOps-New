/**
 * connectivityFlowModel
 *
 * Turns the Cloud Map's graph elements into a left-to-right drill-down and lays it out:
 * account → VPC → subnet → service → resource → what that resource is connected to.
 *
 * <p><b>Why this exists next to the Cytoscape canvas.</b> Live view draws every node and edge at
 * once, which is the right answer for "what is in this account" and the wrong one for "what
 * talks to this volume": on a real estate the answer is three edges inside a hairball of eight
 * hundred. This view keeps the containment path visible and puts one resource's actual
 * connections in their own column, so the relationship is read rather than traced.
 *
 * <p>Both views consume the same {@code /api/xamops/cloudmap/graph} response, so there is no
 * second source of truth about what is connected to what, and no new AWS calls.
 *
 * <p>Layout is computed here rather than handed to a layout engine. The tree is six fixed
 * columns of containment with one branch open, so positions are a deterministic function of the
 * data and the selection: identical across refreshes, no async settle before the first paint,
 * and every node stays a real focusable button.
 */

import type { EdgeData, GraphElement, NodeData } from '../../../types/cloudmap';
import { awsRegionName } from '../../../utils/awsRegionNames';

// ─── Geometry ────────────────────────────────────────────────────────────────

export const CFLOW = {
  ACCOUNT_W: 232,
  ACCOUNT_H: 68,
  REGION_W: 208,
  REGION_H: 50,
  VPC_W: 224,
  VPC_H: 52,
  SUBNET_W: 220,
  SUBNET_H: 52,
  SERVICE_W: 208,
  SERVICE_H: 46,
  RESOURCE_W: 248,
  RESOURCE_H: 52,
  PEER_W: 264,
  PEER_H: 52,
  /** Vertical gap between siblings in a column. */
  ROW_GAP: 10,
  /** Parent's right edge to the child column's left edge. */
  COL_GAP: 84,
  /** Where the shared vertical trunk sits inside that gap, measured from the parent. */
  TRUNK_OFFSET: 38,
  /** The "show all N" row that closes a capped column. */
  MORE_H: 28,
  PAD: 28,
} as const;

/** Column origins, derived so a width change cannot leave a column behind. */
export const CCOLS = {
  account: 0,
  get region() {
    return CFLOW.ACCOUNT_W + CFLOW.COL_GAP;
  },
  get vpc() {
    return this.region + CFLOW.REGION_W + CFLOW.COL_GAP;
  },
  get subnet() {
    return this.vpc + CFLOW.VPC_W + CFLOW.COL_GAP;
  },
  get service() {
    return this.subnet + CFLOW.SUBNET_W + CFLOW.COL_GAP;
  },
  get resource() {
    return this.service + CFLOW.SERVICE_W + CFLOW.COL_GAP;
  },
  get peer() {
    return this.resource + CFLOW.RESOURCE_W + CFLOW.COL_GAP;
  },
} as const;

/** How many rows a column shows before it offers "Show all". */
export const COLUMN_CAP = 14;

/** Prefix for the synthetic "outside any VPC" bucket, which is created per region. */
export const GLOBAL_VPC = '__global__';

/** The bucket holding one region's resources that sit in no VPC — S3, CloudFront, IAM. */
export const globalBucket = (region: string): string => `${GLOBAL_VPC}\u0000${region}`;

export const isGlobalBucket = (id: string): boolean => id.startsWith(GLOBAL_VPC);

/** Bucket id for resources attached to a VPC but not to any subnet inside it. */
export const VPC_LEVEL_SUBNET = '__vpc_level__';

/** What a region with no name is called — IAM, Route 53 and the other global services. */
export const GLOBAL_REGION = 'Global';

/**
 * Separator `useCloudMap` puts between the VPC it fetched and the node's own id.
 *
 * <p>The All-VPCs view issues one request per VPC and merges the responses, prefixing every id
 * with `<vpcId>::` so two VPCs that both reference a shared security group do not collapse into
 * one node. Details, however, are written by the backend before any of that and carry raw ids —
 * so a detail saying `vol-123` has to be matched against a node called `vpc-abc::vol-123`.
 * Comparing them directly found nothing, which is why inferred links were almost entirely
 * absent on the All-VPCs view while working on a single VPC.
 */
const SCOPE_SEP = '::';

/** A node id without its VPC scope. */
const bareId = (id: string): string => {
  const at = id.lastIndexOf(SCOPE_SEP);
  return at >= 0 ? id.slice(at + SCOPE_SEP.length) : id;
};

/** The VPC scope a node id was merged under, or "" when the payload is unscoped. */
const scopeOf = (id: string): string => {
  const at = id.lastIndexOf(SCOPE_SEP);
  return at >= 0 ? id.slice(0, at) : '';
};

// ─── Nodes ───────────────────────────────────────────────────────────────────

interface Placed {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface AccountFlowNode extends Placed {
  accountId: string;
  name: string;
  vpcCount: number;
  /** Real regions, excluding the pseudo-region holding global services. */
  regionCount: number;
  resourceCount: number;
  serviceCount: number;
  /** How many connections exist across the whole account, after the search. */
  linkCount: number;
}

/** One AWS region the account has resources in, plus the pseudo-region for global services. */
export interface RegionFlowNode extends Placed {
  /** The region code, or {@link GLOBAL_REGION}. Also the id at this level. */
  region: string;
  /** "Asia Pacific (Mumbai)" — the same wording the invoice uses. */
  label: string;
  vpcCount: number;
  serviceCount: number;
  resourceCount: number;
  selected: boolean;
}

export interface VpcFlowNode extends Placed {
  id: string;
  label: string;
  region: string;
  cidr: string;
  subnetCount: number;
  serviceCount: number;
  resourceCount: number;
  selected: boolean;
  /** True for the synthetic "not in a VPC" bucket, which has no real VPC behind it. */
  isBucket: boolean;
}

export interface SubnetFlowNode extends Placed {
  id: string;
  label: string;
  /** Availability zone, when the node carries one. */
  zone: string;
  cidr: string;
  serviceCount: number;
  resourceCount: number;
  selected: boolean;
  isBucket: boolean;
}

/** One service type inside a subnet — EC2 Instance, EBS Volume, Security Group, and so on. */
export interface ServiceFlowNode extends Placed {
  /** The resource type, which is also the id at this level. */
  type: string;
  resourceCount: number;
  /** How many of those resources have at least one connection. */
  connectedCount: number;
  /** How many carry a warning, so exposure is visible before drilling in. */
  warningCount: number;
  selected: boolean;
}

export interface ResourceFlowNode extends Placed {
  id: string;
  label: string;
  type: string;
  state: string;
  /** Up to two short facts worth showing on the card — private IP, size, engine, and so on. */
  facts: string[];
  /** Warning badges, e.g. a security group open to the whole internet. */
  warnings: string[];
  peerCount: number;
  selected: boolean;
}

/** One resource the selected resource is connected to, and how. */
export interface PeerFlowNode extends Placed {
  id: string;
  label: string;
  type: string;
  /** The relationship, e.g. "attaches to", "uses SG", "targets". */
  relation: string;
  /** True when the selected resource is the source of the edge. */
  outbound: boolean;
  /**
   * True when the relationship was inferred from a detail field rather than declared as an
   * edge by the backend. Surfaced so an inferred link is never passed off as a stated one.
   */
  derived: boolean;
  /**
   * True when the edge points at an id with no node in the graph — an IAM role or target group
   * the map does not draw. Shown anyway: "this instance assumes that role" is useful even when
   * the role is not a node, and dropping it would understate the connections.
   */
  external: boolean;
  /** Where this peer sits, for orientation. Empty when unknown. */
  location: string;
  /** How many connections the peer itself has, so a reader can see if it is worth following. */
  peerCount: number;
}

/** A capped column's footer. */
export interface MoreRow extends Placed {
  hidden: number;
}

export interface ConnectivityFlow {
  account: AccountFlowNode;
  regions: RegionFlowNode[];
  vpcs: VpcFlowNode[];
  subnets: SubnetFlowNode[];
  services: ServiceFlowNode[];
  resources: ResourceFlowNode[];
  peers: PeerFlowNode[];
  regionMore: MoreRow | null;
  vpcMore: MoreRow | null;
  subnetMore: MoreRow | null;
  serviceMore: MoreRow | null;
  resourceMore: MoreRow | null;
  selectedRegion: string | null;
  selectedVpc: string | null;
  selectedSubnet: string | null;
  selectedService: string | null;
  selectedResource: string | null;
  /** Regions matching the search, before the cap. Drives the empty state. */
  matchedRegions: number;
  matchedResources: number;
  width: number;
  height: number;
}

// ─── Element reading ─────────────────────────────────────────────────────────

/**
 * Splits the payload into nodes and edges.
 *
 * <p>By the presence of {@code source}/{@code target} rather than by {@code group}, because the
 * backend is not consistent about whether that field is {@code "nodes"} or {@code "node"} — the
 * Cytoscape renderer on the live view already learned this and tests the same way. Keying on the
 * fields that must exist for an edge to mean anything cannot drift.
 */
export function splitElements(elements: GraphElement[]): {
  nodes: NodeData[];
  edges: EdgeData[];
} {
  const nodes: NodeData[] = [];
  const edges: EdgeData[] = [];
  for (const el of elements) {
    if (!el || !el.data) continue;
    const data = el.data as Partial<EdgeData> & Partial<NodeData>;
    if (data.source && data.target) edges.push(el.data as EdgeData);
    else if (data.id) nodes.push(el.data as NodeData);
  }
  return { nodes, edges };
}

/**
 * Keys on a node that are structure rather than detail.
 *
 * <p>`CloudMapService.createNode` flattens the whole detail map onto `data` alongside these, so
 * "everything else is a detail" is the only way to enumerate details — and it is what lets this
 * view show facts and infer links for service types nobody hand-coded.
 */
const STRUCTURAL_KEYS = new Set(['id', 'label', 'type', 'parent', 'state', 'region', 'details']);

/** Placeholders the backend writes when a value is absent. */
const EMPTY_VALUES = new Set(['', 'n/a', 'none', 'null', '-', 'unassociated']);

const isEmptyValue = (value: string): boolean =>
  EMPTY_VALUES.has(value.trim().toLowerCase());

/** Every detail on a node, in payload order, with the placeholders dropped. */
export function detailsOf(node: NodeData): { key: string; value: string }[] {
  const out: { key: string; value: string }[] = [];
  const record = node as Record<string, unknown>;

  for (const [key, value] of Object.entries(record)) {
    if (STRUCTURAL_KEYS.has(key)) continue;
    if (typeof value !== 'string' || isEmptyValue(value)) continue;
    out.push({ key, value });
  }
  // Also read a nested map, which is the shape a future payload or a test may use.
  const nested = record.details;
  if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
    for (const [key, value] of Object.entries(nested as Record<string, unknown>)) {
      if (typeof value !== 'string' || isEmptyValue(value)) continue;
      if (out.some((d) => d.key === key)) continue;
      out.push({ key, value });
    }
  }
  return out;
}

/** A named detail, whatever casing the payload used. Empty string when absent. */
export function detailOf(node: NodeData, ...keys: string[]): string {
  const details = detailsOf(node);
  for (const key of keys) {
    const hit = details.find((d) => d.key.toLowerCase() === key.toLowerCase());
    if (hit) return hit.value;
  }
  return '';
}

/**
 * A structural field — `state` or `region`.
 *
 * <p>Read directly rather than through {@link detailOf}, because {@link detailsOf} excludes the
 * structural keys on purpose: they are not attributes the reader browses, and listing them in
 * the detail panel would repeat what the card header already says. Going through the detail
 * reader therefore always returned empty for these two, which silently blanked every state chip
 * and every VPC region.
 */
export function fieldOf(node: NodeData, key: 'state' | 'region'): string {
  const raw = (node as Record<string, unknown>)[key];
  return typeof raw === 'string' && !isEmptyValue(raw) ? raw : '';
}

/**
 * A node's display name, falling back to its id.
 *
 * <p>Exported for the cost drawer's compact flow, which names a focus resource the model does not
 * hand back as a peer. No logic change — both views have to agree about what a node is called.
 *
 * <p>{@link bareId} is deliberately NOT exported alongside it: it splits on the last `::` with no
 * test that the prefix is a VPC id, so outside this module — where ids are known to be either
 * unscoped or `vpc-xxx::`-scoped — it would truncate any ARN with an empty region or account
 * segment. A caller that needs to tolerate a scope prefix should test for one.
 */
export const labelOf = (node: NodeData): string =>
  node.label && node.label.trim() ? node.label : node.id;

/**
 * Short facts worth putting on a resource card.
 *
 * <p>Chosen by preference order rather than "the first two details", because the first details
 * a type happens to carry are often the least interesting — an EC2 card should lead with its
 * private IP, not with its platform. Anything not listed still appears in the detail panel.
 */
const FACT_KEYS = [
  // Reachability first. On a security group the ports and whether it is open to the internet
  // are the only facts anyone opens it for, and they outrank a rule count or a description.
  'Open Ports', 'Scheme', 'DNS Name',
  'Private IP', 'Public IP', 'Instance Type', 'Class', 'Node Type', 'NodeType',
  'Engine', 'Engine Type', 'Runtime', 'Version', 'Platform',
  'Size', 'Volume Size', 'Size (Bytes)', 'Stored Bytes', 'Record Count', 'Items',
  'CIDR Block', 'Availability Zone', 'Type', 'Multi-AZ', 'Desired', 'Running Tasks',
  'Nodes', 'Shards', 'Retention (Days)', 'Timeout', 'Memory', 'Public', 'Health Check',
];

/**
 * Details that are a warning rather than a measurement.
 *
 * <p>"Open To Internet: yes" is the one attribute on this map that a reader should not have to
 * find by scanning, so it is pulled out of the list and rendered as a badge.
 */
export const WARNING_DETAILS: Record<string, string> = {
  'Open To Internet': 'Open to 0.0.0.0/0',
};

function factsOf(node: NodeData, max = 2): string[] {
  const facts: string[] = [];
  for (const key of FACT_KEYS) {
    if (facts.length >= max) break;
    const value = detailOf(node, key);
    if (value) facts.push(`${key}: ${value}`);
  }
  return facts;
}

/** Warning badges this node has earned, e.g. a security group open to the whole internet. */
function warningsOf(node: NodeData): string[] {
  return Object.entries(WARNING_DETAILS)
    .filter(([key]) => {
      const value = detailOf(node, key).toLowerCase();
      return value === 'yes' || value === 'true';
    })
    .map(([, label]) => label);
}

// ─── Index ───────────────────────────────────────────────────────────────────

export interface Link {
  peerId: string;
  relation: string;
  outbound: boolean;
  /** Inferred from a detail field rather than declared by the backend. */
  derived: boolean;
}

/**
 * Everything the layout needs, computed once per payload.
 *
 * <p>Separate from the layout so that opening a node does not re-walk the parent chains or
 * rebuild the adjacency: on an eight-hundred-node account that work is the difference between a
 * click that lands in the same frame and one that does not.
 */
export interface ConnectivityIndex {
  byId: Map<string, NodeData>;
  /** Containment: which VPC a node ultimately sits in, or a per-region global bucket. */
  vpcOf: Map<string, string>;
  /** Containment: which subnet a node sits in, or VPC_LEVEL_SUBNET. */
  subnetOf: Map<string, string>;
  vpcs: NodeData[];
  subnetsByVpc: Map<string, NodeData[]>;
  /** Resources — everything that is not a VPC or a subnet — keyed "vpc|subnet". */
  resourcesByCell: Map<string, NodeData[]>;
  adjacency: Map<string, Link[]>;
  /** Which region a VPC or global bucket belongs to. */
  regionOfVpc: Map<string, string>;
  /** VPC and bucket ids per region. */
  vpcsByRegion: Map<string, string[]>;
  resourceCount: number;
  /** Distinct resource types present, for the account summary. */
  serviceCount: number;
  /** Distinct connections, counted once per pair-and-relation. */
  linkCount: number;
}

const CONTAINER_TYPES = new Set(['VPC', 'Subnet', 'Availability Zone']);

/**
 * Label the backend puts on "this gateway is the default exit for that subnet".
 *
 * <p>Handled specially rather than drawn as-is: it points at a subnet, and this view does not
 * put containers in the connections column. It is expanded into the subnet's resources, which
 * is what turns "egress for subnet-abc" into "egress for prod-api-01".
 */
const EGRESS_RELATION = 'egress for';

/**
 * What "behind this gateway" is worth listing.
 *
 * <p>A routed subnet holds plenty that never opens an outbound connection — volumes, endpoints,
 * the instance ENIs that would restate their own instances — so expanding everything in it
 * would bury the answer in its own supporting cast. These are the things that originate traffic
 * and therefore the things that can be running up the bill.
 *
 * <p>Known gap: a Fargate task exists on this map only as a network interface, so it is not
 * reached here. Listing every ENI to catch it costs more clarity than it buys.
 */
const EGRESS_SOURCE_TYPES = new Set([
  'EC2 Instance',
  'RDS Instance',
  'Lambda Function',
  'ECS Service',
  'EKS Cluster',
  'ElastiCache Cluster',
]);

/**
 * Ceiling on resources listed behind one gateway.
 *
 * <p>The peer column has no paging, and one NAT gateway routinely fronts every private subnet
 * in a VPC — a few hundred instances on a large account. Past a couple of dozen the column stops
 * being a list of suspects and becomes a wall.
 */
const MAX_EGRESS_PEERS = 25;

/**
 * Detail keys that restate containment rather than a connection.
 *
 * <p>The tree already draws these, and inferring an edge from them would put every resource's
 * own VPC and subnet in its connections column — burying the real links under structure the
 * reader is already looking at.
 */
const CONTAINMENT_KEYS = new Set([
  'vpc id', 'vpc', 'subnet id', 'subnet', 'availability zone', 'availability zones',
  // A load balancer or auto scaling group lists every subnet it spans. Those are the shape of
  // the resource, not things it talks to — drawing them as connections would put three subnet
  // nodes in the column ahead of the target group that actually matters.
  'subnets',
]);

/**
 * Combines two copies of the same node into one.
 *
 * <p>Neither copy is reliably the better one, so this takes the best field from each rather
 * than picking a winner: the describe-derived copy usually carries the CIDR and availability
 * zone, the inventory-derived copy usually carries state and the {@code Name} label. A label
 * equal to the id is treated as absent, because that is what the backend falls back to when it
 * has no name — preferring it would replace "UAT-VPC" with "vpc-0d729a800a19ad06d".
 */
function mergeNodes(first: NodeData, second: NodeData): NodeData {
  const named = (n: NodeData) => Boolean(n.label && n.label.trim() && n.label !== n.id);
  const nestedFirst = (first as Record<string, unknown>).details;
  const nestedSecond = (second as Record<string, unknown>).details;
  const merged: NodeData = {
    // Second first, so first wins any remaining conflict.
    ...second,
    ...first,
    label: named(first) ? first.label : named(second) ? second.label : first.label,
    type: first.type || second.type,
    parent: first.parent ?? second.parent,
  } as NodeData;
  if (nestedFirst || nestedSecond) {
    (merged as Record<string, unknown>).details = {
      ...(nestedSecond as object | undefined),
      ...(nestedFirst as object | undefined),
    };
  }
  return merged;
}

export function buildConnectivityIndex(elements: GraphElement[]): ConnectivityIndex {
  const { nodes: rawNodes, edges } = splitElements(elements);

  /*
   * Deduplicated by id before anything else.
   *
   * The payload genuinely repeats nodes, for two reasons. A single-VPC response emits the VPC
   * twice — once from the describe that builds the VPC and subnet scaffolding, and again
   * because the resource filter matches the VPC's own inventory row — and the "All VPCs" view
   * concatenates one response per VPC, so anything reachable from two of them (a shared
   * security group, a peered VPC) arrives more than once.
   *
   * Cytoscape absorbs this silently: adding an element whose id already exists is a no-op, so
   * the live canvas has always drawn one of each. Nothing else in the payload needed to care,
   * which is why it went unnoticed until this view, which iterates the array directly, drew
   * every VPC twice and doubled every count above it.
   */
  const byId = new Map<string, NodeData>();
  for (const node of rawNodes) {
    const existing = byId.get(node.id);
    byId.set(node.id, existing ? mergeNodes(existing, node) : node);
  }
  const nodes = [...byId.values()];

  /**
   * Nearest ancestor of a given type, following `parent`.
   *
   * Walks rather than reading one level because the backend nests availability zones between a
   * VPC and its subnets for some accounts, so a subnet's `parent` is not always the VPC. A
   * visited set guards against a cycle in the payload, which would otherwise hang the layout
   * rather than merely drawing it wrong.
   */
  const ancestorOfType = (start: NodeData, type: string): string | null => {
    const seen = new Set<string>([start.id]);
    let cursor: NodeData | undefined = start;
    while (cursor) {
      if (cursor !== start && cursor.type === type) return cursor.id;
      const parentId = cursor.parent;
      if (!parentId || seen.has(parentId)) return null;
      seen.add(parentId);
      cursor = byId.get(parentId);
    }
    return null;
  };

  const vpcOf = new Map<string, string>();
  const subnetOf = new Map<string, string>();
  const vpcs: NodeData[] = [];
  const subnetsByVpc = new Map<string, NodeData[]>();
  const resourcesByCell = new Map<string, NodeData[]>();
  const regionOfVpc = new Map<string, string>();
  const types = new Set<string>();
  let resourceCount = 0;

  for (const node of nodes) {
    if (node.type === 'VPC') {
      vpcs.push(node);
      vpcOf.set(node.id, node.id);
      // Stamped by useCloudMap from the VPC list, which is the authoritative answer —
      // CloudMapService builds a VPC node without one. Falls back below to the region of
      // whatever is inside it.
      const region = fieldOf(node, 'region');
      if (region) regionOfVpc.set(node.id, region);
    }
  }

  for (const node of nodes) {
    if (node.type === 'VPC') continue;

    const containingVpc = ancestorOfType(node, 'VPC');
    // A resource outside every VPC is filed under its own region rather than one global heap:
    // an S3 bucket in Mumbai and one in Ohio are not the same locality, and lumping them
    // together is the thing the region level exists to undo.
    const vpcId = containingVpc
      ?? globalBucket(fieldOf(node, 'region') || GLOBAL_REGION);
    vpcOf.set(node.id, vpcId);

    if (!containingVpc) {
      regionOfVpc.set(vpcId, fieldOf(node, 'region') || GLOBAL_REGION);
    } else if (!regionOfVpc.has(containingVpc)) {
      // The VPC node carried no region, so borrow one from a resource inside it.
      const region = fieldOf(node, 'region');
      if (region) regionOfVpc.set(containingVpc, region);
    }

    if (node.type === 'Subnet') {
      const list = subnetsByVpc.get(vpcId);
      if (list) list.push(node);
      else subnetsByVpc.set(vpcId, [node]);
      subnetOf.set(node.id, node.id);
      continue;
    }
    if (CONTAINER_TYPES.has(node.type)) {
      // An availability zone is scaffolding, not a resource: it holds subnets and is not
      // something anyone drills into or connects to.
      continue;
    }

    const subnetId = ancestorOfType(node, 'Subnet') ?? VPC_LEVEL_SUBNET;
    subnetOf.set(node.id, subnetId);

    const cell = `${vpcId}|${subnetId}`;
    const list = resourcesByCell.get(cell);
    if (list) list.push(node);
    else resourcesByCell.set(cell, [node]);
    types.add(node.type ?? 'Resource');
    resourceCount++;
  }

  // ── Adjacency ─────────────────────────────────────────────────────────────

  /*
   * Both directions, so selecting either end of "volume attaches to instance" shows the other.
   * The relation reads from the selected node outward, which is why the direction is kept
   * rather than normalised away.
   */
  const adjacency = new Map<string, Link[]>();
  /** Pairs already linked, in either direction, so a derived edge never restates a stated one. */
  const linkedPairs = new Set<string>();
  /**
   * Distinct pair-and-relation combinations, which is what the account summary counts.
   *
   * <p>Not pairs alone: two security groups joined by both an ingress and an egress rule are two
   * connections, and the peer column shows them as two rows — so counting pairs made the
   * headline number smaller than what the reader could see in front of them.
   */
  const linkedRelations = new Set<string>();
  const pairKey = (a: string, b: string) => (a < b ? `${a}\u0000${b}` : `${b}\u0000${a}`);

  const push = (from: string, peerId: string, relation: string, outbound: boolean,
                derived: boolean) => {
    const list = adjacency.get(from);
    const entry: Link = { peerId, relation, outbound, derived };
    if (list) {
      if (!list.some((e) => e.peerId === peerId && e.relation === relation)) list.push(entry);
    } else {
      adjacency.set(from, [entry]);
    }
  };

  /**
   * Whether two nodes are already joined by the tree.
   *
   * <p>Applied to declared edges as well as inferred ones. A load balancer names every subnet it
   * spans and an auto scaling group every subnet it launches into, so the one that happens to be
   * its own parent would otherwise sit in its connections column restating the branch the reader
   * is already looking at. The other subnets are genuinely interesting and still show.
   */
  const isContainment = (a: string, b: string) =>
    vpcOf.get(a) === b || subnetOf.get(a) === b
    || vpcOf.get(b) === a || subnetOf.get(b) === a;

  const link = (source: string, target: string, relation: string, derived: boolean) => {
    if (source === target) return;
    if (isContainment(source, target)) return;
    push(source, target, relation, true, derived);
    push(target, source, relation, false, derived);
    linkedPairs.add(pairKey(source, target));
    linkedRelations.add(`${pairKey(source, target)}\u0000${relation}`);
  };

  // Declared edges first, so they own the relationship wording.
  for (const edge of edges) {
    // `egress for` points at a subnet, and a subnet is scaffolding: not followable, and its
    // containment is already drawn by the tree. It is expanded into the resources below
    // instead, which is the answer the reader actually wanted.
    if (edge.label === EGRESS_RELATION) continue;
    const relation = edge.label && edge.label.trim() ? edge.label : 'connects to';
    link(edge.source, edge.target, relation, false);
  }

  /*
   * Which resources actually sit behind a gateway.
   *
   * The backend states "this NAT gateway is the default exit for that subnet". The question
   * people arrive with is one hop further on — "which of my servers is running up a $344 NAT
   * bill" — and answering it needed the reader to notice the subnet, remember it, and go
   * looking. Both halves are already here: the edge names the subnet, and the tree already
   * knows what the subnet contains, so the join is arithmetic rather than new data.
   *
   * Marked derived, because it is: AWS states the routing, not the consequence. A resource in
   * a routed subnet is *eligible* to egress through the gateway, which is not the same as
   * having done so — an instance that never opens an outbound connection appears here and
   * costs nothing. It narrows the suspects; it does not convict one.
   */
  const egressPeerCount = new Map<string, number>();
  for (const edge of edges) {
    if (edge.label !== EGRESS_RELATION) continue;
    const gatewayId = edge.source;
    const subnetId = edge.target;
    const vpcId = vpcOf.get(subnetId) ?? vpcOf.get(gatewayId);
    if (!vpcId) continue;
    for (const resource of resourcesByCell.get(`${vpcId}|${subnetId}`) ?? []) {
      if (!EGRESS_SOURCE_TYPES.has(resource.type)) continue;
      if (linkedPairs.has(pairKey(gatewayId, resource.id))) continue;
      const drawn = egressPeerCount.get(gatewayId) ?? 0;
      if (drawn >= MAX_EGRESS_PEERS) break;
      egressPeerCount.set(gatewayId, drawn + 1);
      link(gatewayId, resource.id, EGRESS_RELATION, true);
    }
  }

  /*
   * Then relationships inferred from the details themselves.
   *
   * `createAllEdges` on the backend hand-writes an edge per service pair, which covers about a
   * dozen types — so an S3 bucket, a DynamoDB table, a Route 53 zone or an ENI showed no
   * connections at all even when its own details named the resource it belonged to. The details
   * are already on the wire and already carry those ids, so the coverage gap was in the
   * translation, not the data.
   *
   * A link is only inferred when the value is the id of a node that actually exists, which is
   * what keeps this from inventing relationships: "Engine: postgres" matches nothing and is
   * ignored, while "Volume ID: vol-abc" matches a real volume. Containment keys are skipped
   * because the tree already draws them, and a pair already joined by a declared edge is left
   * alone so the backend's wording wins.
   *
   * Inferred links are flagged, so the UI can say which is which rather than presenting a
   * guess as a statement.
   */
  /*
   * Details carry raw ids; the All-VPCs merge scopes node ids by the VPC they were fetched
   * under. So a token is resolved by exact id first and then by its bare form, preferring a
   * candidate merged under the same VPC as the node referring to it — a shared security group
   * legitimately exists once per VPC response, and linking to the wrong copy would draw a
   * connection reaching into another VPC's branch.
   *
   * Without this, inferred links worked when a single VPC was selected and silently found
   * nothing at all on the All-VPCs view, which is the view the page opens on.
   */
  const byBareId = new Map<string, string[]>();
  for (const id of byId.keys()) {
    const bare = bareId(id);
    const list = byBareId.get(bare);
    if (list) list.push(id);
    else byBareId.set(bare, [id]);
  }

  const resolve = (token: string, fromId: string): string | null => {
    if (byId.has(token)) return token;
    const candidates = byBareId.get(bareId(token));
    if (!candidates || candidates.length === 0) return null;
    const scope = scopeOf(fromId);
    const sameScope = candidates.find((c) => scopeOf(c) === scope);
    if (sameScope) return sameScope;
    // Ambiguous across scopes with no local copy: declining beats guessing, because a wrong
    // edge is indistinguishable from a real one once drawn.
    return candidates.length === 1 ? candidates[0] : null;
  };

  for (const node of nodes) {
    for (const { key, value } of detailsOf(node)) {
      if (CONTAINMENT_KEYS.has(key.toLowerCase())) continue;
      for (const token of value.split(',')) {
        const trimmed = token.trim();
        if (!trimmed) continue;
        const targetId = resolve(trimmed, node.id);
        if (!targetId || targetId === node.id) continue;
        const target = byId.get(targetId);
        if (!target) continue;
        /*
         * Never a container.
         *
         * A load balancer lists the subnets it spans and an auto scaling group lists the ones it
         * launches into, under keys that are not named "Subnet ID" — so the key denylist above
         * does not catch them. Those are real facts, but they are structure, and putting a
         * subnet in the connections column is wrong twice: the tree already shows containment,
         * and a container is not a drill-down target, so the node would not even be followable.
         * The value stays visible as an attribute in the detail panel.
         */
        if (CONTAINER_TYPES.has(target.type)) continue;
        if (linkedPairs.has(pairKey(node.id, targetId))) continue;
        link(node.id, targetId, key, true);
      }
    }
  }

  // Regions, with their VPCs and buckets. A VPC still without a region — its node carried none
  // and it holds nothing to borrow from — is filed under Global rather than dropped.
  const vpcsByRegion = new Map<string, string[]>();
  for (const vpc of vpcs) {
    if (!regionOfVpc.has(vpc.id)) regionOfVpc.set(vpc.id, GLOBAL_REGION);
  }
  for (const [vpcId, region] of regionOfVpc) {
    const list = vpcsByRegion.get(region);
    if (list) list.push(vpcId);
    else vpcsByRegion.set(region, [vpcId]);
  }

  return {
    byId,
    vpcOf,
    subnetOf,
    vpcs,
    subnetsByVpc,
    resourcesByCell,
    adjacency,
    regionOfVpc,
    vpcsByRegion,
    resourceCount,
    serviceCount: types.size,
    linkCount: linkedRelations.size,
  };
}

/**
 * Where a node sits in the tree, so the view can jump straight to it.
 *
 * <p>Used to follow a connection: clicking a peer re-roots the drill-down on it, which is how
 * the reader walks a path hop by hop instead of returning to the account each time.
 */
export function locate(
  index: ConnectivityIndex,
  nodeId: string,
): { region: string; vpc: string; subnet: string; service: string; resource: string } | null {
  const node = index.byId.get(nodeId);
  if (!node || CONTAINER_TYPES.has(node.type)) return null;
  const vpc = index.vpcOf.get(nodeId) ?? globalBucket(GLOBAL_REGION);
  return {
    region: index.regionOfVpc.get(vpc) ?? GLOBAL_REGION,
    vpc,
    subnet: index.subnetOf.get(nodeId) ?? VPC_LEVEL_SUBNET,
    service: node.type ?? 'Resource',
    resource: nodeId,
  };
}

// ─── Search ──────────────────────────────────────────────────────────────────

/** Whether a node matches the term — label, id, type, or any detail value. */
const nodeMatches = (node: NodeData, term: string): boolean => {
  if (node.id.toLowerCase().includes(term)) return true;
  if (labelOf(node).toLowerCase().includes(term)) return true;
  if ((node.type ?? '').toLowerCase().includes(term)) return true;
  // Details too, so searching an IP or a CIDR finds the resource carrying it.
  return detailsOf(node).some((d) => d.value.toLowerCase().includes(term));
};

// ─── Build ───────────────────────────────────────────────────────────────────

export interface BuildConnectivityArgs {
  index: ConnectivityIndex;
  accountId: string;
  accountName: string;
  selectedRegion: string | null;
  selectedVpc: string | null;
  selectedSubnet: string | null;
  selectedService: string | null;
  selectedResource: string | null;
  /** Lower-cased, trimmed. Empty for no filter. */
  search: string;
  showAllRegions: boolean;
  showAllVpcs: boolean;
  showAllSubnets: boolean;
  showAllServices: boolean;
  showAllResources: boolean;
}

/**
 * Places every node, given the data and what is open.
 *
 * <p>Children are centred on their parent, which is what makes the picture read as one branch
 * rather than as columns side by side. Centring can push a column above the canvas top, so the
 * whole layout is shifted once at the end — clamping each column independently would silently
 * break the alignment between a parent and its own children.
 */
export function buildConnectivityFlow(args: BuildConnectivityArgs): ConnectivityFlow {
  const { index, accountId, accountName, search } = args;

  const resourceIn = (vpcId: string, subnetId: string): NodeData[] => {
    const all = index.resourcesByCell.get(`${vpcId}|${subnetId}`) ?? [];
    return search ? all.filter((n) => nodeMatches(n, search)) : all;
  };

  const typesIn = (vpcId: string, subnetId: string): Map<string, NodeData[]> => {
    const grouped = new Map<string, NodeData[]>();
    for (const node of resourceIn(vpcId, subnetId)) {
      const type = node.type ?? 'Resource';
      const list = grouped.get(type);
      if (list) list.push(node);
      else grouped.set(type, [node]);
    }
    return grouped;
  };

  /** Subnet ids that hold at least one surviving resource, plus the VPC-level bucket. */
  const subnetKeysFor = (vpcId: string): string[] => {
    const keys: string[] = [];
    for (const subnet of index.subnetsByVpc.get(vpcId) ?? []) {
      if (resourceIn(vpcId, subnet.id).length > 0) keys.push(subnet.id);
    }
    if (resourceIn(vpcId, VPC_LEVEL_SUBNET).length > 0) keys.push(VPC_LEVEL_SUBNET);
    return keys;
  };

  const statsFor = (vpcId: string): { resources: number; services: number } => {
    const types = new Set<string>();
    let resources = 0;
    for (const key of subnetKeysFor(vpcId)) {
      for (const node of resourceIn(vpcId, key)) {
        types.add(node.type ?? 'Resource');
        resources++;
      }
    }
    return { resources, services: types.size };
  };

  // ── VPC entries, per region ───────────────────────────────────────────────
  interface VpcEntry {
    id: string;
    label: string;
    region: string;
    cidr: string;
    isBucket: boolean;
    subnetCount: number;
    serviceCount: number;
    resourceCount: number;
  }

  /** Every VPC and bucket in one region that survives the search. */
  const entriesForRegion = (region: string): VpcEntry[] => {
    const out: VpcEntry[] = [];
    for (const vpcId of index.vpcsByRegion.get(region) ?? []) {
      const stats = statsFor(vpcId);
      if (isGlobalBucket(vpcId)) {
        if (stats.resources === 0) continue;
        out.push({
          id: vpcId,
          label: 'Outside any VPC',
          region,
          cidr: '',
          isBucket: true,
          subnetCount: 0,
          serviceCount: stats.services,
          resourceCount: stats.resources,
        });
        continue;
      }
      const vpc = index.byId.get(vpcId);
      if (!vpc) continue;
      // A VPC whose own name matches is kept even when nothing inside it does, so searching a
      // VPC name shows the VPC rather than nothing.
      if (search && stats.resources === 0 && !nodeMatches(vpc, search)) continue;
      out.push({
        id: vpcId,
        label: labelOf(vpc),
        region,
        cidr: detailOf(vpc, 'CIDR Block'),
        isBucket: false,
        subnetCount: subnetKeysFor(vpcId).filter((k) => k !== VPC_LEVEL_SUBNET).length,
        serviceCount: stats.services,
        resourceCount: stats.resources,
      });
    }
    return out.sort((a, b) =>
      b.resourceCount - a.resourceCount || a.label.localeCompare(b.label));
  };

  // ── Region column ─────────────────────────────────────────────────────────
  interface RegionEntry {
    region: string;
    vpcCount: number;
    serviceCount: number;
    resourceCount: number;
  }

  const regionEntries: RegionEntry[] = [];
  for (const region of index.vpcsByRegion.keys()) {
    const forRegion = entriesForRegion(region);
    if (forRegion.length === 0) continue;
    const types = new Set<string>();
    let resources = 0;
    for (const entry of forRegion) {
      resources += entry.resourceCount;
      for (const key of subnetKeysFor(entry.id)) {
        for (const node of resourceIn(entry.id, key)) types.add(node.type ?? 'Resource');
      }
    }
    regionEntries.push({
      region,
      vpcCount: forRegion.filter((e) => !e.isBucket).length,
      serviceCount: types.size,
      resourceCount: resources,
    });
  }
  // Busiest region first, with the pseudo-region for global services last however big it is —
  // it is not a place, and sorting it among real regions invites reading it as one.
  regionEntries.sort((a, b) => {
    if ((a.region === GLOBAL_REGION) !== (b.region === GLOBAL_REGION)) {
      return a.region === GLOBAL_REGION ? 1 : -1;
    }
    return b.resourceCount - a.resourceCount || a.region.localeCompare(b.region);
  });

  const activeRegion = regionEntries.some((r) => r.region === args.selectedRegion)
    ? args.selectedRegion
    : null;

  const drawnRegions = args.showAllRegions
    ? regionEntries
    : regionEntries.slice(0, COLUMN_CAP);
  const regionList = activeRegion && !drawnRegions.some((r) => r.region === activeRegion)
    ? [...drawnRegions, regionEntries.find((r) => r.region === activeRegion)!]
    : drawnRegions;

  let regionCursor = 0;
  const regions: RegionFlowNode[] = regionList.map((r) => {
    const node: RegionFlowNode = {
      region: r.region,
      label: r.region === GLOBAL_REGION ? 'Global services' : awsRegionName(r.region),
      vpcCount: r.vpcCount,
      serviceCount: r.serviceCount,
      resourceCount: r.resourceCount,
      selected: r.region === activeRegion,
      x: CCOLS.region,
      y: regionCursor,
      w: CFLOW.REGION_W,
      h: CFLOW.REGION_H,
    };
    regionCursor += CFLOW.REGION_H + CFLOW.ROW_GAP;
    return node;
  });

  let regionMore: MoreRow | null = null;
  if (regionEntries.length > regionList.length) {
    regionMore = {
      hidden: regionEntries.length - regionList.length,
      x: CCOLS.region, y: regionCursor, w: CFLOW.REGION_W, h: CFLOW.MORE_H,
    };
    regionCursor += CFLOW.MORE_H + CFLOW.ROW_GAP;
  }
  const regionBottom = Math.max(0, regionCursor - CFLOW.ROW_GAP);

  // ── VPC column, scoped to the open region ─────────────────────────────────
  const entries: VpcEntry[] = activeRegion ? entriesForRegion(activeRegion) : [];

  const activeVpcId = entries.some((e) => e.id === args.selectedVpc) ? args.selectedVpc : null;

  const drawnVpcs = args.showAllVpcs ? entries : entries.slice(0, COLUMN_CAP);
  const vpcList = activeVpcId && !drawnVpcs.some((e) => e.id === activeVpcId)
    ? [...drawnVpcs, entries.find((e) => e.id === activeVpcId)!]
    : drawnVpcs;

  const fallbackMid = regionBottom / 2;

  /** Stacks a column centred on its parent's middle. */
  const stack = <T,>(
    items: T[],
    anchorMid: number,
    rowH: number,
    extraH: number,
    place: (item: T, y: number) => void,
  ): number => {
    const blockH = items.length * rowH + Math.max(0, items.length - 1) * CFLOW.ROW_GAP + extraH;
    let y = anchorMid - blockH / 2;
    for (const item of items) {
      place(item, y);
      y += rowH + CFLOW.ROW_GAP;
    }
    return y;
  };

  // ── VPC column, centred on the open region ───────────────────────────────
  const vpcs: VpcFlowNode[] = [];
  let vpcMore: MoreRow | null = null;
  {
    const anchor = regions.find((r) => r.selected);
    const hidden = entries.length - vpcList.length;
    const endY = stack(
      vpcList,
      anchor ? anchor.y + anchor.h / 2 : fallbackMid,
      CFLOW.VPC_H,
      hidden > 0 ? CFLOW.MORE_H + CFLOW.ROW_GAP : 0,
      (e, y) => vpcs.push({
        id: e.id,
        label: e.label,
        region: e.region,
        cidr: e.cidr,
        subnetCount: e.subnetCount,
        serviceCount: e.serviceCount,
        resourceCount: e.resourceCount,
        selected: e.id === activeVpcId,
        isBucket: e.isBucket,
        x: CCOLS.vpc, y, w: CFLOW.VPC_W, h: CFLOW.VPC_H,
      }),
    );
    if (hidden > 0) {
      vpcMore = { hidden, x: CCOLS.vpc, y: endY, w: CFLOW.VPC_W, h: CFLOW.MORE_H };
    }
  }

  // ── Subnet column ────────────────────────────────────────────────────────
  const subnets: SubnetFlowNode[] = [];
  let subnetMore: MoreRow | null = null;
  let activeSubnetId: string | null = null;

  if (activeVpcId) {
    const subnetById = new Map(
      (index.subnetsByVpc.get(activeVpcId) ?? []).map((s) => [s.id, s] as const),
    );
    const ordered = subnetKeysFor(activeVpcId)
      .map((key) => {
        const node = key === VPC_LEVEL_SUBNET ? null : subnetById.get(key);
        const grouped = typesIn(activeVpcId, key);
        return {
          id: key,
          label: node ? labelOf(node) : 'Not in a subnet',
          zone: node ? detailOf(node, 'Availability Zone') : '',
          cidr: node ? detailOf(node, 'CIDR Block') : '',
          isBucket: !node,
          serviceCount: grouped.size,
          resourceCount: resourceIn(activeVpcId, key).length,
        };
      })
      .sort((a, b) => b.resourceCount - a.resourceCount || a.label.localeCompare(b.label));

    activeSubnetId = ordered.some((s) => s.id === args.selectedSubnet) ? args.selectedSubnet : null;

    const drawn = args.showAllSubnets ? ordered : ordered.slice(0, COLUMN_CAP);
    const list = activeSubnetId && !drawn.some((s) => s.id === activeSubnetId)
      ? [...drawn, ordered.find((s) => s.id === activeSubnetId)!]
      : drawn;

    const anchor = vpcs.find((v) => v.selected);
    const hidden = ordered.length - list.length;
    const endY = stack(
      list,
      anchor ? anchor.y + anchor.h / 2 : fallbackMid,
      CFLOW.SUBNET_H,
      hidden > 0 ? CFLOW.MORE_H + CFLOW.ROW_GAP : 0,
      (s, y) => subnets.push({
        ...s,
        selected: s.id === activeSubnetId,
        x: CCOLS.subnet, y, w: CFLOW.SUBNET_W, h: CFLOW.SUBNET_H,
      }),
    );
    if (hidden > 0) {
      subnetMore = { hidden, x: CCOLS.subnet, y: endY, w: CFLOW.SUBNET_W, h: CFLOW.MORE_H };
    }
  }

  // ── Service column ───────────────────────────────────────────────────────
  const services: ServiceFlowNode[] = [];
  let serviceMore: MoreRow | null = null;
  let activeService: string | null = null;

  if (activeVpcId && activeSubnetId) {
    const grouped = typesIn(activeVpcId, activeSubnetId);
    const ordered = [...grouped.entries()]
      .map(([type, list]) => ({
        type,
        resourceCount: list.length,
        connectedCount: list.filter((n) => (index.adjacency.get(n.id) ?? []).length > 0).length,
        warningCount: list.filter((n) => warningsOf(n).length > 0).length,
      }))
      .sort((a, b) => b.resourceCount - a.resourceCount || a.type.localeCompare(b.type));

    activeService = ordered.some((s) => s.type === args.selectedService)
      ? args.selectedService
      : null;

    const drawn = args.showAllServices ? ordered : ordered.slice(0, COLUMN_CAP);
    const list = activeService && !drawn.some((s) => s.type === activeService)
      ? [...drawn, ordered.find((s) => s.type === activeService)!]
      : drawn;

    const anchor = subnets.find((s) => s.selected);
    const hidden = ordered.length - list.length;
    const endY = stack(
      list,
      anchor ? anchor.y + anchor.h / 2 : fallbackMid,
      CFLOW.SERVICE_H,
      hidden > 0 ? CFLOW.MORE_H + CFLOW.ROW_GAP : 0,
      (s, y) => services.push({
        ...s,
        selected: s.type === activeService,
        x: CCOLS.service, y, w: CFLOW.SERVICE_W, h: CFLOW.SERVICE_H,
      }),
    );
    if (hidden > 0) {
      serviceMore = { hidden, x: CCOLS.service, y: endY, w: CFLOW.SERVICE_W, h: CFLOW.MORE_H };
    }
  }

  // ── Resource column ──────────────────────────────────────────────────────
  const resources: ResourceFlowNode[] = [];
  let resourceMore: MoreRow | null = null;
  let activeResourceId: string | null = null;

  if (activeVpcId && activeSubnetId && activeService) {
    const all = (typesIn(activeVpcId, activeSubnetId).get(activeService) ?? [])
      .slice()
      .sort((a, b) => labelOf(a).localeCompare(labelOf(b)));

    activeResourceId = all.some((r) => r.id === args.selectedResource)
      ? args.selectedResource
      : null;

    const drawn = args.showAllResources ? all : all.slice(0, COLUMN_CAP);
    const list = activeResourceId && !drawn.some((r) => r.id === activeResourceId)
      ? [...drawn, all.find((r) => r.id === activeResourceId)!]
      : drawn;

    const anchor = services.find((s) => s.selected);
    const hidden = all.length - list.length;
    const endY = stack(
      list,
      anchor ? anchor.y + anchor.h / 2 : fallbackMid,
      CFLOW.RESOURCE_H,
      hidden > 0 ? CFLOW.MORE_H + CFLOW.ROW_GAP : 0,
      (r, y) => resources.push({
        id: r.id,
        label: labelOf(r),
        type: r.type ?? 'Resource',
        state: fieldOf(r, 'state') || detailOf(r, 'State', 'Status'),
        facts: factsOf(r),
        warnings: warningsOf(r),
        peerCount: (index.adjacency.get(r.id) ?? []).length,
        selected: r.id === activeResourceId,
        x: CCOLS.resource, y, w: CFLOW.RESOURCE_W, h: CFLOW.RESOURCE_H,
      }),
    );
    if (hidden > 0) {
      resourceMore = { hidden, x: CCOLS.resource, y: endY, w: CFLOW.RESOURCE_W, h: CFLOW.MORE_H };
    }
  }

  // ── Peer column ──────────────────────────────────────────────────────────
  const peers: PeerFlowNode[] = [];
  if (activeResourceId) {
    // Declared relationships first, then inferred, each alphabetical — so the stated facts sit
    // at the top of the column rather than interleaved with the guesses.
    const links = (index.adjacency.get(activeResourceId) ?? []).slice().sort((a, b) =>
      Number(a.derived) - Number(b.derived) || a.relation.localeCompare(b.relation));

    const anchor = resources.find((r) => r.selected);
    stack(
      links,
      anchor ? anchor.y + anchor.h / 2 : fallbackMid,
      CFLOW.PEER_H,
      0,
      (link, y) => {
        const node = index.byId.get(link.peerId);
        const peerVpc = index.vpcOf.get(link.peerId);
        const peerSubnet = index.subnetOf.get(link.peerId);
        // Where the peer lives, so a connection reaching out of the open subnet says so —
        // that is the case this view exists to make visible.
        let location = '';
        if (node) {
          const peerRegion = peerVpc ? index.regionOfVpc.get(peerVpc) : undefined;
          // Region first: a connection crossing regions is the most surprising thing this view
          // can show, and calling it merely "another VPC" would bury that.
          if (peerRegion && activeRegion && peerRegion !== activeRegion) {
            location = peerRegion === GLOBAL_REGION
              ? 'Global service'
              : `Another region · ${peerRegion}`;
          } else if (peerVpc && isGlobalBucket(peerVpc)) location = 'Outside any VPC';
          else if (peerVpc !== activeVpcId) location = 'Another VPC';
          else if (peerSubnet === VPC_LEVEL_SUBNET) location = 'Not in a subnet';
          else if (peerSubnet !== activeSubnetId) location = 'Another subnet';
          else location = 'Same subnet';
        }
        peers.push({
          id: link.peerId,
          label: node ? labelOf(node) : link.peerId,
          type: node ? node.type ?? 'Resource' : 'Not on the map',
          relation: link.relation,
          outbound: link.outbound,
          derived: link.derived,
          external: !node,
          location,
          peerCount: (index.adjacency.get(link.peerId) ?? []).length,
          x: CCOLS.peer, y, w: CFLOW.PEER_W, h: CFLOW.PEER_H,
        });
      },
    );
  }

  // ── Account node, centred on the region column ───────────────────────────
  // Summed across regions, not across the open region's VPCs — the account node describes the
  // account, and reading its totals off whichever branch happens to be open made the same
  // account claim a different size depending on where the reader had clicked.
  const matchedResources = regionEntries.reduce((sum, r) => sum + r.resourceCount, 0);
  const account: AccountFlowNode = {
    accountId,
    name: accountName || accountId,
    vpcCount: regionEntries.reduce((sum, r) => sum + r.vpcCount, 0),
    regionCount: regionEntries.filter((r) => r.region !== GLOBAL_REGION).length,
    resourceCount: search ? matchedResources : index.resourceCount,
    serviceCount: index.serviceCount,
    linkCount: index.linkCount,
    x: CCOLS.account,
    y: fallbackMid - CFLOW.ACCOUNT_H / 2,
    w: CFLOW.ACCOUNT_W,
    h: CFLOW.ACCOUNT_H,
  };

  // ── Normalise into the canvas ────────────────────────────────────────────
  const all: Placed[] = [
    account, ...regions, ...vpcs, ...subnets, ...services, ...resources, ...peers,
    ...(regionMore ? [regionMore] : []),
    ...(vpcMore ? [vpcMore] : []),
    ...(subnetMore ? [subnetMore] : []),
    ...(serviceMore ? [serviceMore] : []),
    ...(resourceMore ? [resourceMore] : []),
  ];
  const minY = all.reduce((min, n) => Math.min(min, n.y), Infinity);
  const shift = CFLOW.PAD - (Number.isFinite(minY) ? minY : 0);
  for (const n of all) n.y += shift;

  const bottom = all.reduce((max, n) => Math.max(max, n.y + n.h), 0);
  // Width follows what is drawn. Reserving every column unconditionally left a collapsed flow
  // declaring itself ~2100px wide with most of it empty, so fit-to-width scaled the tree down
  // to make room for nothing.
  const right = peers.length > 0
    ? CCOLS.peer + CFLOW.PEER_W
    : resources.length > 0 || resourceMore
      ? CCOLS.resource + CFLOW.RESOURCE_W
      : services.length > 0 || serviceMore
        ? CCOLS.service + CFLOW.SERVICE_W
        : subnets.length > 0 || subnetMore
          ? CCOLS.subnet + CFLOW.SUBNET_W
          : vpcs.length > 0 || vpcMore
            ? CCOLS.vpc + CFLOW.VPC_W
            : CCOLS.region + CFLOW.REGION_W;

  return {
    account, regions, vpcs, subnets, services, resources, peers,
    regionMore, vpcMore, subnetMore, serviceMore, resourceMore,
    selectedRegion: activeRegion,
    selectedVpc: activeVpcId,
    selectedSubnet: activeSubnetId,
    selectedService: activeService,
    selectedResource: activeResourceId,
    matchedRegions: regionEntries.length,
    matchedResources,
    width: right + CFLOW.PAD,
    height: bottom + CFLOW.PAD,
  };
}

// ─── Edge geometry ───────────────────────────────────────────────────────────

/**
 * Right-angle connector from one node's right edge to another's left edge.
 *
 * <p>Orthogonal rather than curved because this is a containment tree: elbows sharing one
 * vertical trunk make "these fourteen subnets come off this VPC" readable at a glance, where a
 * bezier fan of the same edges crosses itself.
 */
export function flowEdge(
  x1: number, y1: number, x2: number, y2: number, trunkX: number,
): string {
  if (Math.abs(y1 - y2) < 0.5) return `M ${x1} ${y1} L ${x2} ${y2}`;
  const r = Math.min(10, Math.abs(y2 - y1) / 2, Math.abs(trunkX - x1), Math.abs(x2 - trunkX));
  const down = y2 > y1;
  const dir = down ? 1 : -1;
  return [
    `M ${x1} ${y1}`,
    `L ${trunkX - r} ${y1}`,
    `A ${r} ${r} 0 0 ${down ? 1 : 0} ${trunkX} ${y1 + r * dir}`,
    `L ${trunkX} ${y2 - r * dir}`,
    `A ${r} ${r} 0 0 ${down ? 0 : 1} ${trunkX + r} ${y2}`,
    `L ${x2} ${y2}`,
  ].join(' ');
}

/** The trunk x for edges leaving a column that ends at `parentRight`. */
export const trunkFor = (parentRight: number): number => parentRight + CFLOW.TRUNK_OFFSET;

/** A focus and its peers packed into two columns at the origin. */
export interface CompactFlow {
  /** The focused resource, at x 0, vertically centred on the peer stack. */
  focus: Placed;
  /** The drawn peers, in the model's own order, one column to the right. */
  peers: (PeerFlowNode & Placed)[];
  /** The folded-away row, when anything is hidden. */
  more: MoreRow | null;
  /** Shared vertical trunk for the elbows, so both views bend in the same place. */
  trunkX: number;
  width: number;
  height: number;
}

/**
 * Re-stacks one resource and the peers actually being drawn into two columns at the origin.
 *
 * <p>Why not use {@link buildConnectivityFlow}'s own coordinates: that function places a
 * six-column canvas and centres every column on the branch above it, so on the measured
 * `xamops-prod-db-redis` instance in 605134457560 ("xammer-local") — 18 connections, 16 of them
 * SSM session rows — the peer column is about 1,120px tall and the focus card sits some 560px
 * down it. A compact view draws a *subset* of those peers (the inferred ones folded away), so its
 * rows have to close up, and a 560px lead-in of empty space is the first thing a reader sees in a
 * drawer that is only as tall as the viewport.
 *
 * <p>It lives here, beside the canvas's own placement, so the two views cannot drift apart on
 * spacing: the gaps are `CFLOW`'s, the trunk is {@link trunkFor} and the elbows are
 * {@link flowEdge}. Pure, hence unit-tested rather than inspected in a browser.
 */
export function compactConnectivityFlow(args: {
  /** The peers to draw, in the model's order; their fields are carried through untouched. */
  peers: PeerFlowNode[];
  /** How many peers were folded away, so the "show more" row can say so. 0 for none. */
  hidden: number;
  focusW?: number;
  focusH?: number;
  peerW?: number;
  peerH?: number;
}): CompactFlow {
  const focusW = args.focusW ?? CFLOW.RESOURCE_W;
  const focusH = args.focusH ?? CFLOW.RESOURCE_H;
  const peerW = args.peerW ?? CFLOW.PEER_W;
  const peerH = args.peerH ?? CFLOW.PEER_H;

  const peerX = focusW + CFLOW.COL_GAP;
  const pitch = peerH + CFLOW.ROW_GAP;

  const peers = args.peers.map((peer, i) => ({
    ...peer,
    x: peerX,
    y: i * pitch,
    w: peerW,
    h: peerH,
  }));

  const more: MoreRow | null = args.hidden > 0
    ? {
      hidden: args.hidden,
      x: peerX,
      y: peers.length > 0 ? peers.length * pitch : 0,
      w: peerW,
      h: CFLOW.MORE_H,
    }
    : null;

  const rows: Placed[] = more ? [...peers, more] : peers;
  const stackBottom = rows.reduce((max, row) => Math.max(max, row.y + row.h), 0);

  const focus: Placed = { x: 0, y: (stackBottom - focusH) / 2, w: focusW, h: focusH };
  // A focus taller than its own stack would otherwise be placed above the origin and clipped.
  // Same single-shift normalisation `buildConnectivityFlow` ends with: move everything down once
  // rather than letting any row carry a negative y.
  if (focus.y < 0) {
    const shift = -focus.y;
    focus.y = 0;
    for (const row of rows) row.y += shift;
  }

  const bottom = rows.reduce((max, row) => Math.max(max, row.y + row.h), focus.y + focus.h);

  return {
    focus,
    peers,
    more,
    trunkX: trunkFor(focusW),
    // No second column is drawn when there is nothing to put in it, so none is claimed either.
    width: rows.length > 0 ? peerX + peerW : focusW,
    height: bottom,
  };
}

/** The branch a selection sits on, all five levels. */
export interface Branch {
  region: string | null;
  vpc: string | null;
  subnet: string | null;
  service: string | null;
  resource: string | null;
}

/**
 * The first branch a search matched, so a filtered tree opens on a result.
 *
 * <p>Walks the levels in order, re-deriving each from a layout that has the ones above it open.
 * Guessing instead — taking the first VPC and then its first subnet from the unfiltered data —
 * lands on a branch the filter excluded, which reads as a search that found the wrong thing.
 */
export function firstConnectivityBranch(
  index: ConnectivityIndex,
  accountId: string,
  term: string,
): Branch {
  const base = {
    index,
    accountId,
    accountName: '',
    search: term,
    showAllRegions: true,
    showAllVpcs: true,
    showAllSubnets: true,
    showAllServices: true,
    showAllResources: true,
  };
  const at = (b: Partial<Branch>) => buildConnectivityFlow({
    ...base,
    selectedRegion: b.region ?? null,
    selectedVpc: b.vpc ?? null,
    selectedSubnet: b.subnet ?? null,
    selectedService: b.service ?? null,
    selectedResource: null,
  });

  const empty: Branch = { region: null, vpc: null, subnet: null, service: null, resource: null };

  const region = at({}).regions[0]?.region ?? null;
  if (!region) return empty;

  const vpc = at({ region }).vpcs[0]?.id ?? null;
  if (!vpc) return { ...empty, region };

  const subnet = at({ region, vpc }).subnets[0]?.id ?? null;
  if (!subnet) return { ...empty, region, vpc };

  const service = at({ region, vpc, subnet }).services[0]?.type ?? null;
  if (!service) return { ...empty, region, vpc, subnet };

  const resource = at({ region, vpc, subnet, service }).resources[0]?.id ?? null;
  return { region, vpc, subnet, service, resource };
}
