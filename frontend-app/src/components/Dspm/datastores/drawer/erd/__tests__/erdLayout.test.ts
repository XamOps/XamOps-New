import { describe, expect, it } from 'vitest';
import type { DataFlowErd, DataFlowErdEdge, DataFlowErdNode } from '../../../../../../types/dspmDrawers';
import {
  CARD_FOOTER, CARD_HEADER, CARD_WIDTH, CHIP_ROW, ERD_FIT_MIN, EXTERNAL_CARD, MORE_ROW, PADDING, cardHeight, fitZoom,
  layoutErd, type ErdBox,
} from '../erdLayout';

// ─── synthetic diagrams (shapes only) ────────────────────────────────────────────────────────────

const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const node = (n: number, keyColumns: string[] = []): DataFlowErdNode => ({
  id: uuid(n), name: `table_${String(n).padStart(3, '0')}`, sensitiveCount: n % 3, keyColumns,
});
const edge = (index: number, source: number, target: number | string): DataFlowErdEdge => ({
  id: `fk-${index}`,
  sourceTableId: uuid(source),
  targetKey: typeof target === 'number' ? uuid(target) : target,
  external: typeof target === 'string',
});

/**
 * A star-shaped schema of 153 tables and 102 keys: tables 5 to 103 each reference one of five hubs (99
 * keys), table 104 references itself, tables 105 and 106 reference tables of another schema, and the
 * other 46 tables have no key at all.
 */
function starSchema(): DataFlowErd {
  const nodes = Array.from({ length: 153 }, (_, n) => {
    if (n < 5) return node(n, n === 0 ? ['id', 'a', 'b', 'c', 'd', 'e', 'f', 'g'] : ['id']);
    if (n <= 106) return node(n, ['ref_id']);
    return node(n);
  });
  const edges = [
    ...Array.from({ length: 99 }, (_, i) => edge(i, i + 5, i % 5)),
    edge(99, 104, 104),
    edge(100, 105, 'ext:accounting.ledger'),
    edge(101, 106, 'ext:accounting.payouts'),
  ];
  return {
    nodes,
    edges,
    externalTables: [
      { key: 'ext:accounting.ledger', schemaName: 'accounting', tableName: 'ledger' },
      { key: 'ext:accounting.payouts', schemaName: 'accounting', tableName: 'payouts' },
    ],
    nodesTruncated: false,
    edgesTruncated: false,
  };
}

const overlaps = (a: ErdBox, b: ErdBox) =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('cardHeight', () => {
  it('adds one row per key column up to six and a "+N more" row beyond', () => {
    expect(cardHeight(0)).toBe(CARD_HEADER + CARD_FOOTER);
    expect(cardHeight(2)).toBe(CARD_HEADER + 2 * CHIP_ROW + CARD_FOOTER);
    expect(cardHeight(6)).toBe(CARD_HEADER + 6 * CHIP_ROW + CARD_FOOTER);
    expect(cardHeight(9)).toBe(CARD_HEADER + 6 * CHIP_ROW + MORE_ROW + CARD_FOOTER);
  });
});

describe('layoutErd', () => {
  it('places every card of a 153-table, 102-key diagram without overlap', () => {
    const erd = starSchema();
    const layout = layoutErd(erd);

    expect(layout.boxes).toHaveLength(155);
    const byKey = new Map(layout.boxes.map(box => [box.key, box]));
    for (const table of erd.nodes) {
      const box = byKey.get(table.id)!;
      expect(box).toBeDefined();
      expect(box.w).toBe(CARD_WIDTH);
      expect(box.h).toBe(cardHeight(table.keyColumns.length));
    }
    for (const box of layout.boxes) {
      expect(box.x).toBeGreaterThanOrEqual(PADDING);
      expect(box.y).toBeGreaterThanOrEqual(PADDING);
      expect(box.x + box.w).toBeLessThanOrEqual(layout.width - PADDING);
      expect(box.y + box.h).toBeLessThanOrEqual(layout.height - PADDING);
    }
    for (let i = 0; i < layout.boxes.length; i += 1) {
      for (let j = i + 1; j < layout.boxes.length; j += 1) {
        expect(overlaps(layout.boxes[i], layout.boxes[j]), `${layout.boxes[i].key} / ${layout.boxes[j].key}`).toBe(false);
      }
    }
    expect(layout.paths).toHaveLength(102);
  });

  it('puts the tables without keys in a name-sorted grid below the connected block', () => {
    const layout = layoutErd(starSchema());
    const isolatedKeys = new Set(Array.from({ length: 46 }, (_, i) => uuid(107 + i)));
    const connected = layout.boxes.filter(box => !isolatedKeys.has(box.key));
    const grid = layout.boxes.filter(box => isolatedKeys.has(box.key));
    const blockBottom = Math.max(...connected.map(box => box.y + box.h));

    expect(grid).toHaveLength(46);
    for (const box of grid) expect(box.y).toBeGreaterThan(blockBottom);
    // Reading order (rows, then columns) follows the names.
    const reading = [...grid].sort((a, b) => a.y - b.y || a.x - b.x).map(box => box.key);
    expect(reading).toEqual([...isolatedKeys]);
    expect(grid[0].x).toBe(PADDING);
  });

  it('gives the same layout for the same diagram', () => {
    expect(layoutErd(starSchema())).toEqual(layoutErd(starSchema()));
  });

  it('places the boxes of tables outside the diagram, with dashed connectors that have no arrowhead', () => {
    const layout = layoutErd(starSchema());
    const external = layout.boxes.filter(box => box.external);
    expect(external.map(box => box.key)).toEqual(['ext:accounting.ledger', 'ext:accounting.payouts']);
    for (const box of external) expect([box.w, box.h]).toEqual([EXTERNAL_CARD.w, EXTERNAL_CARD.h]);

    const toLedger = layout.paths.find(path => path.id === 'fk-100')!;
    expect(toLedger).toMatchObject({ external: true, sourceKey: uuid(105), targetKey: 'ext:accounting.ledger' });
    expect(layout.paths.filter(path => path.external).map(path => path.id)).toEqual(['fk-100', 'fk-101']);
  });

  it('draws a connector from the source card to the target card, and a loop for a self-reference', () => {
    const layout = layoutErd(starSchema());
    const byKey = new Map(layout.boxes.map(box => [box.key, box]));
    const first = layout.paths.find(path => path.id === 'fk-0')!;
    const source = byKey.get(uuid(5))!;
    const hub = byKey.get(uuid(0))!;
    expect(first).toMatchObject({ external: false, sourceKey: uuid(5), targetKey: uuid(0) });
    // Left to right: the hub sits in a later rank, so the curve runs from the source's right edge to its left edge.
    expect(hub.x).toBeGreaterThan(source.x + source.w);
    expect(first.d.startsWith(`M ${source.x + source.w} ${source.y + source.h / 2} C `)).toBe(true);
    expect(first.d.endsWith(`, ${hub.x} ${hub.y + hub.h / 2}`)).toBe(true);

    const self = layout.paths.find(path => path.id === 'fk-99')!;
    const own = byKey.get(uuid(104))!;
    expect(self).toMatchObject({ sourceKey: uuid(104), targetKey: uuid(104) });
    expect(self.d.startsWith(`M ${own.x + own.w} `)).toBe(true);
  });

  it('lays out a diagram of tables without keys as a grid only', () => {
    const layout = layoutErd({
      nodes: [node(3), node(1), node(2)], edges: [], externalTables: [], nodesTruncated: false, edgesTruncated: false,
    });
    expect(layout.paths).toEqual([]);
    expect(layout.boxes.map(box => [box.key, box.x, box.y])).toEqual([
      [uuid(1), PADDING, PADDING],
      [uuid(2), PADDING + CARD_WIDTH + 24, PADDING],
      [uuid(3), PADDING + 2 * (CARD_WIDTH + 24), PADDING],
    ]);
  });

  it('skips an edge whose ends are not on the diagram, and gives an empty layout for an empty diagram', () => {
    const layout = layoutErd({
      nodes: [node(1), node(2)],
      edges: [edge(0, 1, 2), edge(1, 9, 2), edge(2, 1, 'ext:other.missing')],
      externalTables: [],
      nodesTruncated: false,
      edgesTruncated: false,
    });
    expect(layout.paths.map(path => path.id)).toEqual(['fk-0']);
    expect(layoutErd({ nodes: [], edges: [], externalTables: [], nodesTruncated: false, edgesTruncated: false }))
      .toEqual({ width: 0, height: 0, boxes: [], paths: [] });
  });
});

describe('fitZoom', () => {
  it('opens at the 55% floor without a measured width', () => {
    expect(fitZoom(3_696, 0)).toBe(ERD_FIT_MIN);
    expect(fitZoom(0, 1_000)).toBe(ERD_FIT_MIN);
  });

  it('clamps the fit to 55%..100%', () => {
    expect(fitZoom(3_696, 1_000)).toBe(0.55);
    expect(fitZoom(600, 1_000)).toBe(1);
  });

  it('rounds the fit down to a multiple of 0.05', () => {
    expect(fitZoom(1_200, 1_000)).toBe(0.75);   // (1000 - 48) / 1200 = 0.793
    expect(fitZoom(1_190, 1_000)).toBe(0.8);    // exactly 0.8
    for (let width = 900; width <= 1_800; width += 37) {
      const zoom = fitZoom(width, 1_000);
      expect(Math.round(zoom * 100) % 5).toBe(0);
      expect(zoom).toBeLessThanOrEqual(Math.max(ERD_FIT_MIN, (1_000 - 2 * PADDING) / width));
    }
  });
});
