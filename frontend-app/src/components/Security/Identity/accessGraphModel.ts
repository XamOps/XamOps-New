/**
 * accessGraphModel
 *
 * Turns the flat IAM inventory into the access graph's node/edge model and lays it out as a
 * left-to-right tree: account -> privilege lane -> principal bucket -> principal.
 *
 * <p><b>Why the account is the root and not a resource.</b> The reference design for this view
 * roots the tree on one data store and fans out through READ / WRITE / MANAGEMENT lanes. We
 * cannot draw that from this payload: IamPrivilegeAnalyzer records only <em>whether</em> a
 * statement was `Resource: *` and then discards the Resource elements, so no ARN reaches the
 * client, and it classifies actions as wildcard/escalation/admin rather than as read/write.
 * Rooting on a resource we do not have would mean inventing the edge labels, and an access
 * graph that guesses at who can write to a bucket is worse than one that declines to say.
 *
 * <p>So the lane carries the privilege vocabulary the analyzer actually produces
 * ({@link AccessLevel}), and the shape — root, one labelled lane per principal kind,
 * collapsible buckets with counts, then the principals themselves — is preserved.
 *
 * <p>Layout is computed here rather than handed to a layout engine. The tree is a fixed four
 * columns deep and every edge is containment, so positions are a deterministic function of the
 * data: no jitter between refreshes, no async settle before the first paint, and no 537 KB
 * graph engine pulled into the Security Center route. This follows the same call XamSecure's
 * NetworkGraph made for the same reason.
 */

import type { IamIdentity, IamUser, Policy, RiskLevel } from '../../../types/userManagement';
import { identityKey, identityName, isIamUser } from '../../../types/userManagement';

// ─── Privilege levels ────────────────────────────────────────────────────────

/**
 * How much a principal can do, worst first.
 *
 * <p>Derived from the traits IamPrivilegeAnalyzer stamps on each attached policy. This is the
 * lane label, and it is the one part of the graph that makes a claim about severity, so the
 * order below is deliberate: NOT_ASSESSED outranks SCOPED because an unread policy has not
 * earned the reassurance a scoped one has.
 */
export type AccessLevel =
  | 'ADMIN'
  | 'ESCALATION'
  | 'WILDCARD'
  | 'NOT_ASSESSED'
  | 'SCOPED'
  | 'NO_GRANTS';

/** Worst-first. Index doubles as the sort key for lane labels and bucket ordering. */
export const ACCESS_LEVEL_ORDER: AccessLevel[] = [
  'ADMIN',
  'ESCALATION',
  'WILDCARD',
  'NOT_ASSESSED',
  'SCOPED',
  'NO_GRANTS',
];

export interface AccessLevelToken {
  /** Short label for the lane pill. Uppercased at render time. */
  label: string;
  /** What this level means, for the pill's title attribute. */
  hint: string;
  /** Tailwind classes for the lane pill. */
  pill: string;
  /** Stroke for the edges feeding a bucket whose worst level is this. */
  stroke: string;
}

export const ACCESS_LEVEL_TOKENS: Record<AccessLevel, AccessLevelToken> = {
  ADMIN: {
    label: 'Full admin',
    hint: 'Grants Action:* on Resource:* — unrestricted control of the account.',
    pill: 'bg-red-50 border-red-200 text-red-700',
    stroke: '#dc2626',
  },
  ESCALATION: {
    label: 'Escalation',
    hint: 'Holds actions that let the principal grant itself more permission.',
    pill: 'bg-rose-50 border-rose-200 text-rose-700',
    stroke: '#e11d48',
  },
  WILDCARD: {
    label: 'Service wildcard',
    hint: 'Holds a service-wide wildcard such as s3:* rather than named actions.',
    pill: 'bg-orange-50 border-orange-200 text-orange-700',
    stroke: '#ea580c',
  },
  NOT_ASSESSED: {
    label: 'Not assessed',
    hint: 'No attached policy document could be read, so this access was never evaluated.',
    pill: 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.14)] text-[rgba(0,0,0,0.55)]',
    stroke: '#94a3b8',
  },
  SCOPED: {
    label: 'Scoped',
    hint: 'Every readable policy names its actions and resources explicitly.',
    pill: 'bg-emerald-50 border-emerald-200 text-emerald-700',
    stroke: '#059669',
  },
  NO_GRANTS: {
    label: 'No grants',
    hint: 'Nothing is attached, so this principal carries no permission at all.',
    pill: 'bg-[rgba(0,0,0,0.03)] border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.45)]',
    stroke: '#cbd5e1',
  },
};

/**
 * Classify one principal by the strongest trait across everything attached to it.
 *
 * <p>Strongest rather than most common: a principal with nine scoped policies and one
 * AdministratorAccess is an administrator, and averaging that away is exactly the failure the
 * card canvas was built to fix.
 */
export function accessLevelOf(policies: Policy[] | null | undefined): AccessLevel {
  const list = policies ?? [];
  if (list.length === 0) return 'NO_GRANTS';

  if (list.some((p) => p.adminAccess)) return 'ADMIN';
  if (list.some((p) => (p.escalationActions?.length ?? 0) > 0)) return 'ESCALATION';
  if (list.some((p) => (p.wildcardActions?.length ?? 0) > 0)) return 'WILDCARD';

  // Only when nothing was readable. A single readable, scoped policy is evidence; the rest
  // being unreadable still leaves the principal's real reach unknown, so require all-unread
  // before claiming the whole principal is unassessed.
  if (list.every((p) => p.documentAnalyzed === false)) return 'NOT_ASSESSED';

  return 'SCOPED';
}

/** Worst of a set, for a bucket's summary. Empty set resolves to NO_GRANTS. */
export function worstAccessLevel(levels: AccessLevel[]): AccessLevel {
  let worst: AccessLevel = 'NO_GRANTS';
  let worstIdx = ACCESS_LEVEL_ORDER.length;
  for (const level of levels) {
    const idx = ACCESS_LEVEL_ORDER.indexOf(level);
    if (idx >= 0 && idx < worstIdx) {
      worstIdx = idx;
      worst = level;
    }
  }
  return worst;
}

/** Distinct levels present, worst first, for the lane pill's "A / B / C" label. */
export function sortLevels(levels: Iterable<AccessLevel>): AccessLevel[] {
  return [...new Set(levels)].sort(
    (a, b) => ACCESS_LEVEL_ORDER.indexOf(a) - ACCESS_LEVEL_ORDER.indexOf(b),
  );
}

// ─── Synthesized groups ──────────────────────────────────────────────────────

/**
 * A group, reconstructed client-side.
 *
 * <p>The payload has no group entity — only {@code IamUser.groups} name strings and
 * {@code Policy.inheritedFrom} on the grants the backend merged down into each member. Those
 * two fields are enough to rebuild membership and the group's own grants, which is what makes
 * a groups lane possible at all. Grants are deduped because the same group policy is repeated
 * on every member.
 */
export interface GroupEntity {
  name: string;
  /** Display names of the members, sorted. */
  members: string[];
  /** Identity keys of the members, for cross-highlighting. */
  memberKeys: string[];
  /** The group's grants, deduped across members. */
  policies: Policy[];
}

function policyDedupeKey(p: Policy): string {
  // policyArn is null for inline policies, so fall back to name + type.
  return p.policyArn || `${p.policyName}::${p.type ?? 'inline'}`;
}

export function buildGroups(users: IamUser[]): GroupEntity[] {
  const byName = new Map<string, { members: Map<string, string>; policies: Map<string, Policy> }>();

  const ensure = (name: string) => {
    let g = byName.get(name);
    if (!g) {
      g = { members: new Map(), policies: new Map() };
      byName.set(name, g);
    }
    return g;
  };

  for (const user of users) {
    for (const groupName of user.groups ?? []) {
      if (!groupName) continue;
      ensure(groupName).members.set(user.userId, user.userName);
    }
    // A grant tagged with inheritedFrom names a group that may not appear in groups[] — the
    // membership list and the merge are populated independently server-side, so take the
    // union rather than trusting either alone.
    for (const policy of user.attachedPolicies ?? []) {
      if (!policy.inheritedFrom) continue;
      const g = ensure(policy.inheritedFrom);
      g.members.set(user.userId, user.userName);
      g.policies.set(policyDedupeKey(policy), policy);
    }
  }

  return [...byName.entries()]
    .map(([name, g]) => ({
      name,
      members: [...g.members.values()].sort((a, b) => a.localeCompare(b)),
      memberKeys: [...g.members.keys()],
      policies: [...g.policies.values()],
    }))
    .sort((a, b) => b.policies.length - a.policies.length || a.name.localeCompare(b.name));
}

// ─── Graph model ─────────────────────────────────────────────────────────────

export type BucketId = 'groups' | 'roles' | 'users';

/** What a leaf node stands for. Drives the inspector and the node's icon. */
export type LeafKind = 'group' | 'role' | 'user';

export interface LeafNode {
  /** Unique across the graph. */
  id: string;
  kind: LeafKind;
  label: string;
  level: AccessLevel;
  /** Absent on groups, which the analyzer never scores. */
  riskLevel?: RiskLevel | null;
  flagged: boolean;
  /** Number of attached grants, shown as the node's count chip. */
  grantCount: number;
  /** AWS users only; undefined means "not applicable", not "disabled". */
  mfaEnabled?: boolean;
  /** The identity behind a user/role leaf, for the inspector. */
  identity?: IamIdentity;
  /** The group behind a group leaf, for the inspector. */
  group?: GroupEntity;
  // Layout, filled by layoutAccessGraph.
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface BucketNode {
  id: BucketId;
  label: string;
  icon: string;
  /** Every leaf in this bucket, before the visible-count cap. */
  leaves: LeafNode[];
  /** Distinct privilege levels inside, worst first. This is the lane pill's text. */
  levels: AccessLevel[];
  /** Worst level inside, which colours the lane's edges. */
  worst: AccessLevel;
  /** How many leaves carry a HIGH/CRITICAL finding. */
  flaggedCount: number;
  /** Leaves withheld by the cap. Zero when everything is drawn. */
  hiddenCount: number;
  // Layout.
  x: number;
  y: number;
  w: number;
  h: number;
  /** Centre y of the lane pill, which is the bucket's own centre. */
  laneY: number;
  /**
   * Top of the "show all" row, or null when there is nothing to reveal.
   *
   * <p>Positioned by the layout rather than stacked under the column at render time: the row
   * is part of the block's height, and a bucket that grew by 26px without telling the layout
   * would overlap the lane below it.
   */
  moreY: number | null;
}

export interface AccessGraph {
  rootLabel: string;
  rootSubLabel: string;
  buckets: BucketNode[];
  /** Total leaves across every bucket, before capping. */
  totalLeaves: number;
  // Layout.
  rootX: number;
  rootY: number;
  rootW: number;
  rootH: number;
  /** Content bounds, for fit-to-view and the SVG viewBox. */
  width: number;
  height: number;
}

// ─── Layout constants ────────────────────────────────────────────────────────

export const LAYOUT = {
  ROOT_W: 216,
  ROOT_H: 52,
  /** Vertical trunk that every lane branches off, measured from the root's right edge. */
  TRUNK_GAP: 52,
  LANE_PILL_W: 188,
  LANE_PILL_H: 22,
  /** Gap between the lane pill and the bucket. */
  LANE_TO_BUCKET_GAP: 44,
  BUCKET_W: 152,
  BUCKET_H: 34,
  /** Trunk for the bucket-to-leaf fan, measured from the bucket's right edge. */
  LEAF_TRUNK_GAP: 40,
  LEAF_W: 244,
  /** One text line. Two-line labels get LEAF_H_2 instead. */
  LEAF_H: 32,
  LEAF_H_2: 46,
  /** Vertical gap between sibling leaves. */
  LEAF_GAP: 9,
  /** Vertical gap between one bucket's block and the next. */
  BUCKET_GAP: 34,
  /** The "show all N" row under a capped column. */
  MORE_H: 26,
  /** Canvas padding around the content bounds. */
  PAD: 28,
} as const;

/** Derived column origins. */
export const COLS = {
  root: 0,
  trunk: LAYOUT.ROOT_W + LAYOUT.TRUNK_GAP,
  lane: LAYOUT.ROOT_W + LAYOUT.TRUNK_GAP + 14,
  get bucket() {
    return this.lane + LAYOUT.LANE_PILL_W + LAYOUT.LANE_TO_BUCKET_GAP;
  },
  get leafTrunk() {
    return this.bucket + LAYOUT.BUCKET_W + LAYOUT.LEAF_TRUNK_GAP;
  },
  get leaf() {
    return this.leafTrunk + 22;
  },
} as const;

/** How many leaves a bucket shows before it offers "Show all". */
export const LEAF_CAP = 24;

// ─── Text measurement ────────────────────────────────────────────────────────

/**
 * Exact text width, so a leaf's height is known before it is rendered.
 *
 * <p>Measured rather than estimated from a per-character average: IAM names are dominated by
 * either narrow hex runs ("56c2c418-f791-49ad") or wide upper-case service prefixes
 * ("XamOps-App-EC2-Role"), and an average sized for one wraps the other onto a phantom second
 * line, leaving a gap in the column. A detached 2D context measures without touching layout.
 */
const measureCache = new Map<string, number>();
let measureCtx: CanvasRenderingContext2D | null | undefined;

const LEAF_FONT = '600 12px Inter, system-ui, -apple-system, sans-serif';

function textWidth(text: string): number {
  const cached = measureCache.get(text);
  if (cached !== undefined) return cached;

  if (measureCtx === undefined) {
    try {
      measureCtx = document.createElement('canvas').getContext('2d');
      if (measureCtx) measureCtx.font = LEAF_FONT;
    } catch {
      measureCtx = null;
    }
  }

  // No canvas (jsdom without the 2d context, or a hardened environment): fall back to an
  // average. Slightly wrong line counts beat throwing inside a layout pass.
  const width = measureCtx ? measureCtx.measureText(text).width : text.length * 6.6;
  measureCache.set(text, width);
  return width;
}

/** Leaf height for a label, capped at two lines (the node clamps the overflow). */
function leafHeight(label: string): number {
  // 20px of horizontal chrome: icon well, count chip, padding.
  const usable = LAYOUT.LEAF_W - 78;
  return textWidth(label) > usable ? LAYOUT.LEAF_H_2 : LAYOUT.LEAF_H;
}

// ─── Build ───────────────────────────────────────────────────────────────────

function leafFromIdentity(identity: IamIdentity, showRisk: boolean): LeafNode {
  const user = isIamUser(identity) ? (identity as IamUser) : null;
  const label = identityName(identity);
  const policies = identity.attachedPolicies ?? [];

  return {
    id: `${user ? 'user' : 'role'}:${identityKey(identity)}`,
    kind: user ? 'user' : 'role',
    label,
    level: accessLevelOf(policies),
    riskLevel: showRisk ? identity.riskLevel ?? null : null,
    flagged: showRisk && Boolean(identity.excessPermissions),
    grantCount: policies.length,
    mfaEnabled: user ? user.mfaEnabled : undefined,
    identity,
    x: 0,
    y: 0,
    w: LAYOUT.LEAF_W,
    h: leafHeight(label),
  };
}

function leafFromGroup(group: GroupEntity): LeafNode {
  return {
    id: `group:${group.name}`,
    kind: 'group',
    label: group.name,
    level: accessLevelOf(group.policies),
    // Groups are never scored: the analyzer assesses the principals that inherit from them,
    // and borrowing a member's level here would show the same finding twice.
    riskLevel: null,
    flagged: false,
    grantCount: group.policies.length,
    group,
    x: 0,
    y: 0,
    w: LAYOUT.LEAF_W,
    h: leafHeight(group.name),
  };
}

export interface BuildAccessGraphArgs {
  /** Already filtered and sorted by useUserManagement. */
  users: IamIdentity[];
  roles: IamIdentity[];
  /** Unfiltered users, so group membership survives a search on user names. */
  allUsers: IamUser[];
  accountName: string;
  accountId: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  labels: { users: string; roles: string };
  /** False on an unassessed account: hides risk colouring rather than showing everything green. */
  showRisk: boolean;
  /** Bucket ids currently open. */
  expanded: Set<BucketId>;
  /** Bucket ids showing past LEAF_CAP. */
  showAll: Set<BucketId>;
  /** Lower-cased search term, applied to group names only (identities arrive pre-filtered). */
  groupQuery: string;
  /** When true, drop groups with no over-privileged grant to match the identity filter. */
  flaggedOnly: boolean;
}

export function buildAccessGraph(args: BuildAccessGraphArgs): AccessGraph {
  const {
    users, roles, allUsers, accountName, accountId, provider, labels,
    showRisk, expanded, showAll, groupQuery, flaggedOnly,
  } = args;

  const groupEntities = buildGroups(allUsers).filter((g) => {
    if (groupQuery && !g.name.toLowerCase().includes(groupQuery)) return false;
    // "Over-privileged only" means the same thing here as on a card: this grant path carries
    // excess access. A group qualifies on its own grants, since it has no risk score.
    if (flaggedOnly) {
      const level = accessLevelOf(g.policies);
      return level === 'ADMIN' || level === 'ESCALATION' || level === 'WILDCARD';
    }
    return true;
  });

  const bucketSpecs: Array<{ id: BucketId; label: string; icon: string; leaves: LeafNode[] }> = [
    {
      id: 'groups',
      label: 'groups',
      icon: 'mdi:account-group-outline',
      leaves: groupEntities.map(leafFromGroup),
    },
    {
      id: 'roles',
      label: 'roles',
      icon: 'mdi:shield-account-outline',
      leaves: roles.map((r) => leafFromIdentity(r, showRisk)),
    },
    {
      id: 'users',
      label: 'users',
      icon: 'mdi:account-outline',
      leaves: users.map((u) => leafFromIdentity(u, showRisk)),
    },
  ];

  // An empty bucket is dropped rather than drawn as a zero: a lane that leads nowhere reads as
  // a rendering fault, and GCP/Azure legitimately have no groups at all.
  const present = bucketSpecs.filter((b) => b.leaves.length > 0);

  const buckets: BucketNode[] = present.map((spec) => ({
    id: spec.id,
    label: spec.label,
    icon: spec.icon,
    leaves: spec.leaves,
    levels: sortLevels(spec.leaves.map((l) => l.level)),
    worst: worstAccessLevel(spec.leaves.map((l) => l.level)),
    flaggedCount: spec.leaves.filter((l) => l.flagged).length,
    hiddenCount: 0,
    x: COLS.bucket,
    y: 0,
    w: LAYOUT.BUCKET_W,
    h: LAYOUT.BUCKET_H,
    laneY: 0,
    moreY: null,
  }));

  // ── Vertical placement, one bucket block at a time ────────────────────────
  let cursor = LAYOUT.PAD;

  for (const bucket of buckets) {
    const open = expanded.has(bucket.id);
    const all = showAll.has(bucket.id);
    const visible = open ? visibleLeaves(bucket, all) : [];
    bucket.hiddenCount = open ? bucket.leaves.length - visible.length : 0;

    let blockH: number;
    if (visible.length > 0) {
      let leafY = cursor;
      for (const leaf of visible) {
        leaf.x = COLS.leaf;
        leaf.y = leafY;
        leafY += leaf.h + LAYOUT.LEAF_GAP;
      }
      if (bucket.hiddenCount > 0) {
        bucket.moreY = leafY;
        leafY += LAYOUT.MORE_H + LAYOUT.LEAF_GAP;
      }
      blockH = leafY - LAYOUT.LEAF_GAP - cursor;
    } else {
      blockH = LAYOUT.BUCKET_H;
    }

    // Bucket and lane pill centre on their own leaves, so a 30-role column reads as one branch
    // rather than as a bucket stapled to the top of a list.
    bucket.y = cursor + blockH / 2 - LAYOUT.BUCKET_H / 2;
    bucket.laneY = cursor + blockH / 2;

    cursor += blockH + LAYOUT.BUCKET_GAP;
  }

  const contentBottom = buckets.length > 0 ? cursor - LAYOUT.BUCKET_GAP : LAYOUT.PAD;
  const height = contentBottom + LAYOUT.PAD;

  // Root centres on the whole tree.
  const rootY = buckets.length > 0
    ? (buckets[0].laneY + buckets[buckets.length - 1].laneY) / 2 - LAYOUT.ROOT_H / 2
    : LAYOUT.PAD;

  const totalLeaves = buckets.reduce((sum, b) => sum + b.leaves.length, 0);
  const identityCount = users.length + roles.length;

  return {
    rootLabel: accountName || accountId || 'Selected account',
    rootSubLabel: `${provider} · ${identityCount} ${
      identityCount === 1 ? 'identity' : 'identities'
    } · ${labels.users.toLowerCase()} and ${labels.roles.toLowerCase()}`,
    buckets,
    totalLeaves,
    rootX: COLS.root,
    rootY,
    rootW: LAYOUT.ROOT_W,
    rootH: LAYOUT.ROOT_H,
    width: COLS.leaf + LAYOUT.LEAF_W + LAYOUT.PAD,
    height,
  };
}

/** The leaves a bucket actually draws, honouring the cap unless the user lifted it. */
export function visibleLeaves(bucket: BucketNode, all: boolean): LeafNode[] {
  return all ? bucket.leaves : bucket.leaves.slice(0, LEAF_CAP);
}

// ─── Edge geometry ───────────────────────────────────────────────────────────

/**
 * Right-angle connector from one node's right edge to another's left edge.
 *
 * <p>Orthogonal rather than curved because the graph is a containment tree: an elbow with a
 * shared vertical trunk makes "these three lanes come off one root" readable at a glance,
 * where bezier fans of the same data cross each other.
 */
export function elbowPath(
  x1: number, y1: number, x2: number, y2: number, trunkX: number,
): string {
  if (Math.abs(y1 - y2) < 0.5) return `M ${x1} ${y1} L ${x2} ${y2}`;
  const r = Math.min(8, Math.abs(y2 - y1) / 2, Math.abs(trunkX - x1), Math.abs(x2 - trunkX));
  const down = y2 > y1;
  const sweepIn = down ? 1 : 0;
  const sweepOut = down ? 0 : 1;
  const dir = down ? 1 : -1;
  return [
    `M ${x1} ${y1}`,
    `L ${trunkX - r} ${y1}`,
    `A ${r} ${r} 0 0 ${sweepIn} ${trunkX} ${y1 + r * dir}`,
    `L ${trunkX} ${y2 - r * dir}`,
    `A ${r} ${r} 0 0 ${sweepOut} ${trunkX + r} ${y2}`,
    `L ${x2} ${y2}`,
  ].join(' ');
}
