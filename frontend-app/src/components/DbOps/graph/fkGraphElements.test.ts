import { describe, it, expect } from 'vitest';
import { buildFkGraphElements, fkSparseMessage, fkHiddenLabel } from './fkGraphElements';
import type { FkGraphDocDto } from '../../../hooks/useDbOpsData';

const populated: FkGraphDocDto = {
  dsnAlias: 'db-0',
  collectedAt: '2026-10-29T12:00:00Z',
  stale: false,
  nodes: [
    { id: 'public.a', schema: 'public', table: 'a', sizeBytes: 1000, rowEstimate: 100, activityScore: 1.0, boundary: false },
    { id: 'public.b', schema: 'public', table: 'b', sizeBytes: 500, rowEstimate: 50, activityScore: 0.1, boundary: false },
    { id: 'other.c', schema: 'other', table: 'c', sizeBytes: 0, rowEstimate: 0, activityScore: null, boundary: true },
  ],
  edges: [
    { id: 'fk_b_a', source: 'public.b', target: 'public.a', columns: ['a_id'], onDelete: 'CASCADE', onUpdate: 'NO ACTION' },
    { id: 'fk_b_c', source: 'public.b', target: 'other.c', columns: ['c_id'], onDelete: 'NO ACTION', onUpdate: 'NO ACTION' },
    // stray edge referencing a non-existent node — builder must drop it defensively
    { id: 'fk_x_y', source: 'public.x', target: 'public.y', columns: [], onDelete: '', onUpdate: '' },
  ],
  meta: { declaredFkCount: 3, tableCount: 2, boundaryNodeCount: 1, droppedEdgeCount: 1, truncated: true },
  availableDsnAliases: ['db-0'],
};

describe('buildFkGraphElements', () => {
  it('keeps only edges whose endpoints are emitted nodes (edges ⊆ nodes)', () => {
    const els = buildFkGraphElements(populated);
    const nodeIds = new Set(els.filter((e) => !e.data.source).map((e) => e.data.id));
    const edges = els.filter((e) => e.data.source);
    expect(edges).toHaveLength(2); // the stray fk_x_y is dropped
    edges.forEach((e) => {
      expect(nodeIds.has(e.data.source as string)).toBe(true);
      expect(nodeIds.has(e.data.target as string)).toBe(true);
    });
  });

  it('flags boundary nodes with a muted class', () => {
    const els = buildFkGraphElements(populated);
    const boundary = els.find((e) => e.data.id === 'other.c')!;
    expect(boundary.classes).toContain('boundary');
    const normal = els.find((e) => e.data.id === 'public.a')!;
    expect(normal.classes).toBe('table');
  });

  it('shows the honest sparse message only when there are no edges', () => {
    expect(fkSparseMessage(populated)).toBeNull();
    const sparse: FkGraphDocDto = {
      ...populated, edges: [], nodes: [populated.nodes[0]],
      meta: { declaredFkCount: 0, tableCount: 1, boundaryNodeCount: 0, droppedEdgeCount: 0, truncated: false },
    };
    expect(fkSparseMessage(sparse)).toContain('0 foreign keys');
    expect(fkSparseMessage(sparse)).toContain('application code');
  });

  it('derives the hidden-relationships banner from droppedEdgeCount', () => {
    expect(fkHiddenLabel(populated.meta)).toBe('1 relationship hidden — narrow the schema or raise topN');
    expect(fkHiddenLabel({ declaredFkCount: 1, tableCount: 1, boundaryNodeCount: 0, droppedEdgeCount: 0, truncated: false })).toBeNull();
  });
});
