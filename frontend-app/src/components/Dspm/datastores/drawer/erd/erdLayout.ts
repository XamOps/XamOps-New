import cytoscape from 'cytoscape';
// @ts-ignore — cytoscape-dagre ships no types
import cytoscapeDagre from 'cytoscape-dagre';
import type { DataFlowErd, DataFlowErdNode } from '../../../../../types/dspmDrawers';

cytoscape.use(cytoscapeDagre);

/*
 * The Graph View's layout (design 4.6, 6.8): pure, and the only module that loads cytoscape, so the
 * library arrives with the lazily loaded canvas. Tables that take part in a foreign key, and the dashed
 * boxes of tables outside the diagram, are placed left to right by dagre in a headless cytoscape
 * instance, which only computes positions and is destroyed at once. Tables without any key would form
 * one very tall rank, so they go into a grid below the connected block instead.
 */

export const CARD_WIDTH = 240, CARD_HEADER = 40, CHIP_ROW = 22, MAX_CHIPS = 6, MORE_ROW = 18, CARD_FOOTER = 30;
export const EXTERNAL_CARD = { w: 200, h: 44 }, NODE_SEP = 32, RANK_SEP = 96, GRID_GAP = 24, PADDING = 24;
/** The opening zoom's floor and jsdom fallback: the console opened the 153-table schema at 55% (4.6). */
export const ERD_FIT_MIN = 0.55;

/** A card (`external` false, `key` the node id) or a dashed box (`key` the external table's key), top-left based. */
export interface ErdBox { key: string; x: number; y: number; w: number; h: number; external: boolean }
/** One foreign key's connector: an SVG path in the drawing's coordinates. */
export interface ErdPath { id: string; d: string; external: boolean; sourceKey: string; targetKey: string }
export interface ErdLayout { width: number; height: number; boxes: ErdBox[]; paths: ErdPath[] }

const DAGRE = { name: 'dagre', rankDir: 'LR', nodeSep: NODE_SEP, rankSep: RANK_SEP, animate: false } as unknown as cytoscape.LayoutOptions;

/** The space between the connected block and the grid of tables without keys. */
const BLOCK_GAP = 2 * GRID_GAP;
/** The least horizontal pull of a connector's control points, so short connectors still curve. */
const CURVE_MIN = 40;
/** How far a self-reference's loop reaches right of its card, and half the gap between its two ends. */
const LOOP_REACH = 48, LOOP_HALF = 12;

const EMPTY: ErdLayout = { width: 0, height: 0, boxes: [], paths: [] };

/** A collapsed card's height: header, one row per key column up to six, "+N more" beyond, footer. */
export function cardHeight(keyColumns: number): number {
  return CARD_HEADER + CHIP_ROW * Math.min(keyColumns, MAX_CHIPS) + (keyColumns > MAX_CHIPS ? MORE_ROW : 0) + CARD_FOOTER;
}

/** The first item per key, in input order. */
function uniqueBy<T>(items: readonly T[], key: (item: T) => string): T[] {
  const seen = new Set<string>();
  return items.filter(item => {
    const k = key(item);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

const byName = (a: DataFlowErdNode, b: DataFlowErdNode) => {
  const x = (a.name ?? '').toLowerCase();
  const y = (b.name ?? '').toLowerCase();
  return x < y ? -1 : x > y ? 1 : 0;
};

/** Top-left corners of the dagre block, shifted so it starts at (PADDING, PADDING). */
function layoutConnected(sized: ReadonlyArray<Omit<ErdBox, 'x' | 'y'>>, links: ReadonlyArray<[string, string]>): ErdBox[] {
  const ids = new Map(sized.map((box, i) => [box.key, `b${i}`]));
  const elements: cytoscape.ElementDefinition[] = [
    ...sized.map((box, i) => ({ group: 'nodes' as const, data: { id: `b${i}`, w: box.w, h: box.h } })),
    ...links.map(([source, target], i) => ({
      group: 'edges' as const,
      data: { id: `e${i}`, source: ids.get(source)!, target: ids.get(target)! },
    })),
  ];
  const cy = cytoscape({
    headless: true,
    styleEnabled: true,
    elements,
    style: [{ selector: 'node', style: { width: 'data(w)', height: 'data(h)', shape: 'rectangle' } }],
  });
  let corners: Array<{ x: number; y: number }>;
  try {
    cy.layout(DAGRE).run();
    corners = sized.map((box, i) => {
      const center = cy.getElementById(`b${i}`).position();
      return { x: center.x - box.w / 2, y: center.y - box.h / 2 };
    });
  } finally {
    cy.destroy();   // without it a cytoscape timer keeps running (4.6)
  }
  const minX = Math.min(...corners.map(c => c.x));
  const minY = Math.min(...corners.map(c => c.y));
  return sized.map((box, i) => ({
    ...box,
    x: Math.round(corners[i].x - minX) + PADDING,
    y: Math.round(corners[i].y - minY) + PADDING,
  }));
}

/** A cubic Bézier between facing sides: source right to target left, or the reverse when the target lies to the left. */
function curve(s: ErdBox, t: ErdBox): string {
  const forward = t.x + t.w / 2 >= s.x + s.w / 2;
  const sx = forward ? s.x + s.w : s.x;
  const tx = forward ? t.x : t.x + t.w;
  const sy = s.y + s.h / 2;
  const ty = t.y + t.h / 2;
  const pull = Math.max(CURVE_MIN, Math.abs(tx - sx) / 2) * (forward ? 1 : -1);
  return `M ${sx} ${sy} C ${sx + pull} ${sy}, ${tx - pull} ${ty}, ${tx} ${ty}`;
}

/** A self-reference: a loop on the card's right edge. */
function loop(b: ErdBox): string {
  const x = b.x + b.w;
  const mid = b.y + b.h / 2;
  return `M ${x} ${mid - LOOP_HALF} C ${x + LOOP_REACH} ${mid - 2 * LOOP_HALF}, ${x + LOOP_REACH} ${mid + 2 * LOOP_HALF}, ${x} ${mid + LOOP_HALF}`;
}

/**
 * Positions every card and box and draws one connector per foreign key. The same input gives the same
 * output: elements enter dagre in the diagram's order and the grid is sorted by name. Heights are the
 * collapsed ones; an expanded card overlays its neighbours and moves nothing (4.6). An edge whose ends
 * are not on the diagram is skipped, as is a repeated node id or external key (the first one counts).
 */
export function layoutErd(erd: DataFlowErd): ErdLayout {
  const nodes = uniqueBy(erd.nodes, node => node.id);
  if (nodes.length === 0) return EMPTY;
  const nodeIds = new Set(nodes.map(node => node.id));
  const externals = uniqueBy(erd.externalTables.filter(table => !nodeIds.has(table.key)), table => table.key);
  const externalKeys = new Set(externals.map(table => table.key));
  const edges = erd.edges.filter(edge => nodeIds.has(edge.sourceTableId)
    && (nodeIds.has(edge.targetKey) || externalKeys.has(edge.targetKey)));

  const linked = new Set<string>();
  for (const edge of edges) {
    linked.add(edge.sourceTableId);
    linked.add(edge.targetKey);
  }
  const sized: Array<Omit<ErdBox, 'x' | 'y'>> = [
    ...nodes.filter(node => linked.has(node.id))
      .map(node => ({ key: node.id, w: CARD_WIDTH, h: cardHeight(node.keyColumns.length), external: false })),
    ...externals.map(table => ({ key: table.key, w: EXTERNAL_CARD.w, h: EXTERNAL_CARD.h, external: true })),
  ];
  const connected = sized.length > 0
    ? layoutConnected(sized, edges.map(edge => [edge.sourceTableId, edge.targetKey] as [string, string]))
    : [];

  const blockRight = connected.reduce((right, box) => Math.max(right, box.x + box.w), PADDING);
  const blockBottom = connected.reduce((bottom, box) => Math.max(bottom, box.y + box.h), PADDING);
  const isolated = nodes.filter(node => !linked.has(node.id)).sort(byName);
  const grid: ErdBox[] = [];
  const columns = Math.max(4, Math.floor((blockRight - PADDING + GRID_GAP) / (CARD_WIDTH + GRID_GAP)));
  let top = connected.length > 0 ? blockBottom + BLOCK_GAP : PADDING;
  for (let start = 0; start < isolated.length; start += columns) {
    let rowHeight = 0;
    isolated.slice(start, start + columns).forEach((node, column) => {
      const h = cardHeight(node.keyColumns.length);
      grid.push({ key: node.id, x: PADDING + column * (CARD_WIDTH + GRID_GAP), y: top, w: CARD_WIDTH, h, external: false });
      rowHeight = Math.max(rowHeight, h);
    });
    top += rowHeight + GRID_GAP;
  }

  const boxes = [...connected, ...grid];
  const byKey = new Map(boxes.map(box => [box.key, box]));
  const looped = new Set<string>();
  const paths = edges.map((edge): ErdPath => {
    const source = byKey.get(edge.sourceTableId)!;
    const target = byKey.get(edge.targetKey)!;
    if (source === target) looped.add(source.key);
    return {
      id: edge.id,
      d: source === target ? loop(source) : curve(source, target),
      external: target.external,
      sourceKey: source.key,
      targetKey: target.key,
    };
  });

  const right = boxes.reduce((max, box) => Math.max(max, box.x + box.w + (looped.has(box.key) ? LOOP_REACH : 0)), 0);
  const bottom = boxes.reduce((max, box) => Math.max(max, box.y + box.h), 0);
  return { width: right + PADDING, height: bottom + PADDING, boxes, paths };
}

/**
 * The opening zoom: the largest multiple of 0.05 at which the drawing fits the viewport's width, kept
 * within 55%..100%. Without a measured width (jsdom) it is the 55% floor.
 */
export function fitZoom(layoutWidth: number, viewportWidth: number): number {
  if (viewportWidth <= 0 || layoutWidth <= 0) return ERD_FIT_MIN;
  const fit = Math.floor(((viewportWidth - 2 * PADDING) / layoutWidth) * 20 + 1e-9) / 20;
  return Math.min(1, Math.max(ERD_FIT_MIN, Math.round(fit * 100) / 100));
}
