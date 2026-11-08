/**
 * The Access Graph's layout (design 6.12): a pure function from the grouped graph, the expanded groups
 * and the search to unscaled pixel boxes and connectors. The tree has three fixed levels plus the
 * principals, so no layout engine is needed; the canvas draws the boxes as HTML and the connectors as
 * SVG in these coordinates, and zoom is a CSS scale on top.
 */

import type { DatastoreAccessGraph, DatastoreAccessGroup } from '../../../../types/dspmDatastores';

/** Space around the drawing. Column x values below are measured from it. */
export const PADDING = 24;
/** Vertical space between two groups' blocks. */
export const GROUP_GAP = 24;
export const ROOT_BOX = { x: 0, w: 180, h: 96 } as const;
export const PERMISSION_BOX = { x: 260, w: 220, h: 72 } as const;
export const TYPE_BOX = { x: 540, w: 200, h: 72 } as const;
export const PRINCIPAL_BOX = { x: 800, w: 260, h: 28, gap: 8 } as const;
/** A collapsed group's block: the height of its Permissions and type boxes. */
const COLLAPSED_BLOCK = 72;

export const ZOOM_DEFAULT = 0.7;
export const ZOOM_STEP = 0.1;
export const ZOOM_MIN = 0.2;
export const ZOOM_MAX = 1.5;

export const ROOT_ID = 'root';

export interface Box {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export type GraphNode =
  | (Box & { kind: 'root' })
  | (Box & { kind: 'permissions'; group: DatastoreAccessGroup })
  | (Box & { kind: 'type'; group: DatastoreAccessGroup; expanded: boolean })
  | (Box & { kind: 'principal'; group: DatastoreAccessGroup; name: string; match: boolean });

/** A connector from the right middle of one box to the left middle of the next. */
export interface GraphEdge {
  id: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface GraphLayout {
  width: number;
  height: number;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

/** Rule 2: 72 when collapsed, else max(72, n × 28 + (n − 1) × 8) for n listed principals. */
function blockHeight(listed: number | null): number {
  if (listed === null) return COLLAPSED_BLOCK;
  return Math.max(COLLAPSED_BLOCK, listed * PRINCIPAL_BOX.h + (listed - 1) * PRINCIPAL_BOX.gap);
}

function connect(from: Box, to: Box): GraphEdge {
  return { id: `${from.id}->${to.id}`, x1: from.x + from.w, y1: from.y + from.h / 2, x2: to.x, y2: to.y + to.h / 2 };
}

/**
 * Lays the graph out left to right: root, then per group a Permissions box and a type box, then the
 * group's principals when it is expanded.
 *
 * 1. With a non-empty trimmed `query`, only groups with a principal whose name contains it (any case)
 *    stay; they count as expanded and list only the matching principals, flagged `match`.
 * 2. A block is 72 high collapsed, else max(72, n × 28 + (n − 1) × 8).
 * 3. Blocks stack top to bottom in `groups` order; the Permissions and type boxes are centred in their
 *    block and the principals stack from its top.
 * 4. The root is centred on the total height. Edges run root → each Permissions box, Permissions →
 *    type, and type → each listed principal.
 * 5. An empty graph gives the root alone.
 *
 * The stack of blocks is centred against the root when it is shorter than the root, so a single
 * collapsed group lines up with it.
 */
export function accessGraphLayout(
  graph: Pick<DatastoreAccessGraph, 'groups'>,
  expanded: ReadonlySet<string>,
  query: string,
): GraphLayout {
  const needle = query.trim().toLowerCase();
  const blocks = graph.groups.flatMap(group => {
    if (needle === '') {
      const open = expanded.has(group.id);
      return [{ group, open, principals: open ? group.principals.map(name => ({ name, match: false })) : [] }];
    }
    const matching = group.principals.filter(name => name.toLowerCase().includes(needle));
    return matching.length === 0 ? [] : [{ group, open: true, principals: matching.map(name => ({ name, match: true })) }];
  });

  const heights = blocks.map(block => blockHeight(block.open ? block.principals.length : null));
  const stacked = heights.reduce((sum, height) => sum + height, 0) + GROUP_GAP * Math.max(0, blocks.length - 1);
  const content = Math.max(ROOT_BOX.h, stacked);
  const height = content + 2 * PADDING;

  const root: GraphNode = {
    kind: 'root', id: ROOT_ID,
    x: PADDING + ROOT_BOX.x, y: PADDING + (content - ROOT_BOX.h) / 2, w: ROOT_BOX.w, h: ROOT_BOX.h,
  };
  const nodes: GraphNode[] = [root];
  const edges: GraphEdge[] = [];

  let top = PADDING + (content - stacked) / 2;
  blocks.forEach((block, index) => {
    const { group } = block;
    const middle = top + heights[index] / 2;
    const permissions: GraphNode = {
      kind: 'permissions', id: `${group.id}|permissions`, group,
      x: PADDING + PERMISSION_BOX.x, y: middle - PERMISSION_BOX.h / 2, w: PERMISSION_BOX.w, h: PERMISSION_BOX.h,
    };
    const type: GraphNode = {
      kind: 'type', id: `${group.id}|type`, group, expanded: block.open,
      x: PADDING + TYPE_BOX.x, y: middle - TYPE_BOX.h / 2, w: TYPE_BOX.w, h: TYPE_BOX.h,
    };
    nodes.push(permissions, type);
    edges.push(connect(root, permissions), connect(permissions, type));
    block.principals.forEach((principal, position) => {
      const node: GraphNode = {
        kind: 'principal', id: `${group.id}|principal:${position}`, group, name: principal.name, match: principal.match,
        x: PADDING + PRINCIPAL_BOX.x, y: top + position * (PRINCIPAL_BOX.h + PRINCIPAL_BOX.gap),
        w: PRINCIPAL_BOX.w, h: PRINCIPAL_BOX.h,
      };
      nodes.push(node);
      edges.push(connect(type, node));
    });
    top += heights[index] + GROUP_GAP;
  });

  const width = Math.max(...nodes.map(node => node.x + node.w)) + PADDING;
  return { width, height, nodes, edges };
}

/** The connector as a cubic curve that leaves and enters horizontally. */
export function edgePath(edge: GraphEdge): string {
  const mid = (edge.x1 + edge.x2) / 2;
  return `M ${edge.x1} ${edge.y1} C ${mid} ${edge.y1}, ${mid} ${edge.y2}, ${edge.x2} ${edge.y2}`;
}

/**
 * One zoom step, rounded to one decimal so floating-point drift never leaves 0.20000000000000004
 * behind, and clamped to 20%..150%. Five steps down from 70% reach 20% exactly.
 */
export function zoomStep(zoom: number, direction: 'up' | 'down'): number {
  const next = Math.round((zoom + (direction === 'up' ? ZOOM_STEP : -ZOOM_STEP)) * 10) / 10;
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, next));
}
