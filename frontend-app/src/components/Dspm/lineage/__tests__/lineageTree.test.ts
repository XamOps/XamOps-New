import { describe, expect, it } from 'vitest';
import {
  MESSAGE_W, NODE_GAP, NODE_H, NODE_W, PAGER, PAGER_GAP, ROW_GAP, TREE_PAD, TREE_ZOOM_MAX, TREE_ZOOM_MIN,
  layoutTree, rowY, treeFitZoom, type TreeRowInput,
} from '../lineageTree';

const row = (over: Partial<TreeRowInput>): TreeRowInput => ({
  level: 'policy', keys: [], chosen: null, prev: false, next: false, message: false, ...over,
});

const CATEGORY = row({ level: 'category', keys: ['SENSITIVE_FINDINGS'], chosen: 'SENSITIVE_FINDINGS' });

describe('layoutTree', () => {
  it('sizes a row of nodes, adding each pager, and centres every row', () => {
    const layout = layoutTree([CATEGORY, row({ keys: ['a', 'b', 'c'], prev: true, next: true })], null);
    const policyWidth = 3 * NODE_W + 2 * NODE_GAP + 2 * (PAGER + PAGER_GAP);
    expect(layout.width).toBe(2 * TREE_PAD + policyWidth);

    const [category, policies] = layout.rows;
    expect(category.nodes[0].x).toBe((layout.width - NODE_W) / 2);
    const start = (layout.width - policyWidth) / 2;
    expect(policies.prev).toBe(start);
    expect(policies.nodes.map(n => n.x)).toEqual([0, 1, 2].map(j => start + PAGER + PAGER_GAP + j * (NODE_W + NODE_GAP)));
    expect(policies.next).toBe(start + PAGER + PAGER_GAP + 3 * NODE_W + 2 * NODE_GAP + PAGER_GAP);
  });

  it('leaves the pagers out when the row has none', () => {
    const layout = layoutTree([CATEGORY, row({ keys: ['a', 'b'] })], null);
    expect(layout.rows[1].prev).toBeNull();
    expect(layout.rows[1].next).toBeNull();
    expect(layout.width).toBe(2 * TREE_PAD + 2 * NODE_W + NODE_GAP);
  });

  it('draws a message row as one centred box', () => {
    const layout = layoutTree([CATEGORY, row({ message: true, prev: true, next: true })], null);
    expect(layout.width).toBe(2 * TREE_PAD + MESSAGE_W);
    expect(layout.rows[1]).toMatchObject({ nodes: [], prev: null, next: null, message: TREE_PAD });
    expect(layout.edges).toEqual([expect.objectContaining({ key: 'policy:message', active: false })]);
  });

  it('places row i at TREE_PAD + i × (NODE_H + ROW_GAP), and the height under the last row', () => {
    const layout = layoutTree([CATEGORY, row({ keys: ['a'], chosen: 'a' }), row({ level: 'datastore', keys: ['d'] })], null);
    expect(layout.rows.map(r => r.y)).toEqual([0, 1, 2].map(rowY));
    expect(rowY(2)).toBe(TREE_PAD + 2 * (NODE_H + ROW_GAP));
    expect(layout.height).toBe(rowY(2) + NODE_H + TREE_PAD);
  });

  it('places the band ROW_GAP under the last row, centred, and widens the drawing for it', () => {
    const layout = layoutTree([CATEGORY, row({ keys: ['a'], chosen: 'a' })], { width: 900, height: 200 });
    expect(layout.width).toBe(2 * TREE_PAD + 900);
    expect(layout.band).toEqual({ x: TREE_PAD, y: rowY(1) + NODE_H + ROW_GAP, width: 900, height: 200 });
    expect(layout.height).toBe(rowY(1) + NODE_H + ROW_GAP + 200 + TREE_PAD);
  });

  it('starts the curves at the chosen node, marks the chosen one active, and keys them by level', () => {
    const layout = layoutTree([
      CATEGORY,
      row({ keys: ['a', 'b', 'c'], chosen: 'b' }),
      row({ level: 'datastore', keys: ['x', 'y'] }),
    ], null);
    const policies = layout.rows[1];
    const b = policies.nodes[1];
    const datastoreEdges = layout.edges.filter(e => e.key.startsWith('datastore:'));
    expect(datastoreEdges.map(e => e.key)).toEqual(['datastore:x', 'datastore:y']);
    const x1 = b.x + NODE_W / 2;
    const y1 = policies.y + NODE_H;
    const x = layout.rows[2].nodes[0].x + NODE_W / 2;
    const y2 = layout.rows[2].y;
    expect(datastoreEdges[0].d).toBe(`M ${x1} ${y1} C ${x1} ${y1 + ROW_GAP / 2}, ${x} ${y2 - ROW_GAP / 2}, ${x} ${y2}`);
    expect(layout.edges.filter(e => e.active).map(e => e.key)).toEqual(['policy:b']);
  });

  it('anchors the curves at the row centre when the chosen node is not on the page', () => {
    const layout = layoutTree([
      CATEGORY,
      row({ keys: ['a', 'b', 'c'], chosen: null }),
      row({ level: 'datastore', keys: ['x'] }),
    ], null);
    const edge = layout.edges.find(e => e.key === 'datastore:x')!;
    expect(edge.d.startsWith(`M ${layout.width / 2} ${layout.rows[1].y + NODE_H} `)).toBe(true);
    expect(layout.edges.some(e => e.active && e.key.startsWith('policy:'))).toBe(false);
  });

  it('joins the band with one active curve, kept 24 px inside its edges', () => {
    const wide = row({ level: 'file', keys: ['f1', 'f2', 'f3', 'f4', 'f5', 'f6', 'f7'], chosen: 'f1' });
    const layout = layoutTree([CATEGORY, wide], { width: 300, height: 100 });
    const band = layout.edges.find(e => e.key === 'band')!;
    expect(band.active).toBe(true);
    const fromX = layout.rows[1].nodes[0].x + NODE_W / 2;
    expect(fromX).toBeLessThan(layout.band!.x + 24);
    expect(band.d.endsWith(`${layout.band!.x + 24} ${layout.band!.y}`)).toBe(true);
  });
});

describe('treeFitZoom', () => {
  it('is 1 without a viewport width (jsdom) or when the drawing fits', () => {
    expect(treeFitZoom(2000, 0)).toBe(1);
    expect(treeFitZoom(800, 1000)).toBe(1);
    expect(treeFitZoom(1000, 1000)).toBe(TREE_ZOOM_MAX);
  });

  it('is the largest hundredth that fits', () => {
    expect(treeFitZoom(1100, 1000)).toBe(0.9);
    expect(treeFitZoom(1000, 910)).toBe(0.91);
  });

  it('never goes below 10%', () => {
    expect(treeFitZoom(100_000, 500)).toBe(TREE_ZOOM_MIN);
  });
});
