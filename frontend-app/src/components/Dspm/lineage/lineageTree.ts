/**
 * Geometry of the Data Lineage tree (build 4 design 4.4, 6.6): rows of node cards top-down, each row
 * centred, cubic curves from the chosen node of a row to every node of the next, and the file's lineage
 * band below the last row. Pure, so `LineageTreeCanvas` only scales what this returns.
 */

import type { RowLevel } from './lineageQuery';

export const NODE_W = 216;
export const NODE_H = 84;
export const NODE_GAP = 20;
export const ROW_GAP = 72;
export const TREE_PAD = 32;
/** A round "‹" / "›" button's size. */
export const PAGER = 32;
export const PAGER_GAP = 12;
/** A row's loading, error or empty box. */
export const MESSAGE_W = 360;
export const TREE_ZOOM_MIN = 0.1;
export const TREE_ZOOM_MAX = 1;
/** How far inside the band's edges the curve into it lands. */
const BAND_INSET = 24;

/** `root` is the public path's project node (build 7); like `category`, it has no pagers. */
export type TreeLevel = 'root' | 'category' | RowLevel;

/** Whether a tree row is one of the paged rows (not the root or the category). */
export function isRowLevel(level: TreeLevel): level is RowLevel {
  return level !== 'root' && level !== 'category';
}

export interface TreeRowInput {
  level: TreeLevel;
  /** The node keys, in drawing order. */
  keys: string[];
  /** The chosen node's key, or null when no node of this page is chosen. */
  chosen: string | null;
  /** A "‹" button before the nodes. */
  prev: boolean;
  /** A "›" button after the nodes. */
  next: boolean;
  /** A message box instead of nodes (no pagers). */
  message: boolean;
}

export interface TreeLayoutRow {
  level: TreeLevel;
  y: number;
  nodes: { key: string; x: number }[];
  /** The "‹" button's x, or null. */
  prev: number | null;
  /** The "›" button's x, or null. */
  next: number | null;
  /** The message box's x, or null. */
  message: number | null;
}

export interface TreeLayout {
  width: number;
  height: number;
  rows: TreeLayoutRow[];
  band: { x: number; y: number; width: number; height: number } | null;
  edges: { key: string; d: string; active: boolean }[];
}

/** The y of row `index`. */
export const rowY = (index: number) => TREE_PAD + index * (NODE_H + ROW_GAP);

function rowWidth(row: TreeRowInput): number {
  if (row.message) return MESSAGE_W;
  const n = row.keys.length;
  const nodes = n === 0 ? 0 : n * NODE_W + (n - 1) * NODE_GAP;
  return nodes + (row.prev ? PAGER + PAGER_GAP : 0) + (row.next ? PAGER + PAGER_GAP : 0);
}

const curve = (x1: number, y1: number, x2: number, y2: number) =>
  `M ${x1} ${y1} C ${x1} ${y1 + ROW_GAP / 2}, ${x2} ${y2 - ROW_GAP / 2}, ${x2} ${y2}`;

/**
 * Row `i` at `y = TREE_PAD + i × (NODE_H + ROW_GAP)`, centred in the drawing: "‹", the nodes, "›". The
 * band sits `ROW_GAP` under the last row, centred. A row's curves start at the bottom centre of the row
 * above's chosen node, or at that row's centre when its chosen node is not on its page, and end at the top
 * centre of each node (or the message box); the curve into the chosen node is active. The band gets one
 * active curve from the last row's anchor to its top edge, kept `BAND_INSET` inside it.
 */
export function layoutTree(rows: TreeRowInput[], band: { width: number; height: number } | null): TreeLayout {
  const widths = rows.map(rowWidth);
  const width = 2 * TREE_PAD + Math.max(0, ...widths, band?.width ?? 0);

  const laid: TreeLayoutRow[] = rows.map((row, i) => {
    const y = rowY(i);
    const start = (width - widths[i]) / 2;
    if (row.message) return { level: row.level, y, nodes: [], prev: null, next: null, message: start };
    const first = start + (row.prev ? PAGER + PAGER_GAP : 0);
    const nodes = row.keys.map((key, j) => ({ key, x: first + j * (NODE_W + NODE_GAP) }));
    const end = first + (row.keys.length === 0 ? 0 : row.keys.length * NODE_W + (row.keys.length - 1) * NODE_GAP);
    return {
      level: row.level,
      y,
      nodes,
      prev: row.prev ? start : null,
      next: row.next ? end + PAGER_GAP : null,
      message: null,
    };
  });

  /** The bottom centre the curves out of row `i` start at. */
  const anchor = (i: number) => {
    const row = laid[i];
    const chosen = rows[i].message ? undefined : row.nodes.find(n => n.key === rows[i].chosen);
    return { x: chosen ? chosen.x + NODE_W / 2 : width / 2, y: row.y + NODE_H };
  };

  const edges: TreeLayout['edges'] = [];
  for (let i = 1; i < laid.length; i += 1) {
    const from = anchor(i - 1);
    const row = laid[i];
    if (row.message !== null) {
      edges.push({ key: `${row.level}:message`, d: curve(from.x, from.y, row.message + MESSAGE_W / 2, row.y), active: false });
      continue;
    }
    for (const node of row.nodes) {
      edges.push({
        key: `${row.level}:${node.key}`,
        d: curve(from.x, from.y, node.x + NODE_W / 2, row.y),
        active: node.key === rows[i].chosen,
      });
    }
  }

  const lastY = laid.length === 0 ? TREE_PAD - ROW_GAP - NODE_H : laid[laid.length - 1].y;
  let placedBand: TreeLayout['band'] = null;
  if (band) {
    placedBand = { x: (width - band.width) / 2, y: lastY + NODE_H + ROW_GAP, width: band.width, height: band.height };
    if (laid.length > 0) {
      const from = anchor(laid.length - 1);
      const x = Math.min(placedBand.x + band.width - BAND_INSET, Math.max(placedBand.x + BAND_INSET, from.x));
      edges.push({ key: 'band', d: curve(from.x, from.y, x, placedBand.y), active: true });
    }
  }

  const bottom = placedBand ? placedBand.y + placedBand.height : lastY + NODE_H;
  return { width, height: bottom + TREE_PAD, rows: laid, band: placedBand, edges };
}

/**
 * The zoom a level opens at: 1 when the drawing fits or the viewport has no width (jsdom); else the
 * largest hundredth not above `viewport / width`, clamped to 10%..100%.
 */
export function treeFitZoom(width: number, viewportWidth: number): number {
  if (viewportWidth <= 0 || width <= viewportWidth) return 1;
  const fitted = Math.floor((viewportWidth / width) * 100 + 1e-9) / 100;
  return Math.min(TREE_ZOOM_MAX, Math.max(TREE_ZOOM_MIN, fitted));
}
