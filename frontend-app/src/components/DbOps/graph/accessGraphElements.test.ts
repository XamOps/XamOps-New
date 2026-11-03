import { describe, it, expect } from 'vitest';
import { buildAccessGraphElements, accessHiddenLabel } from './accessGraphElements';
import type { AccessGraphDocDto } from '../../../hooks/useDbOpsData';

const doc: AccessGraphDocDto = {
  dsnAlias: 'db-0',
  collectedAt: '2026-10-29T12:00:00Z',
  stale: false,
  nodes: [
    { id: 'ds:db-0', kind: 'datastore', label: 'pg.internal', data: { dbHost: 'pg.internal', roleCount: 2, principalCount: 1 }, severity: null },
    { id: 'priv:READ', kind: 'privilege', label: 'Read', data: { privilegeClass: 'READ', objectCount: 5, hasPublic: false }, severity: null },
    { id: 'principal:root', kind: 'principal', label: 'root', data: { canLogin: true, isSuperuser: true, flags: ['superuser'] }, severity: 'critical' },
  ],
  edges: [
    { id: 'ds:db-0->priv:READ', source: 'ds:db-0', target: 'priv:READ', kind: 'datastore-class' },
  ],
  insights: [],
  meta: { hiddenPrincipalCount: 2, truncated: true, totalPrincipals: 5 },
  availableDsnAliases: ['db-0'],
};

describe('buildAccessGraphElements', () => {
  it('maps nodes to cytoscape data + kind/severity classes', () => {
    const els = buildAccessGraphElements(doc);
    const root = els.find((e) => e.data.id === 'principal:root')!;
    expect(root.data.label).toBe('root');
    expect(root.data.isSuperuser).toBe(true);
    expect(root.classes).toBe('principal critical');

    const ds = els.find((e) => e.data.id === 'ds:db-0')!;
    expect(ds.classes).toBe('datastore'); // no severity → kind only
  });

  it('every edge source/target references an emitted node', () => {
    const els = buildAccessGraphElements(doc);
    const ids = new Set(els.filter((e) => !e.data.source).map((e) => e.data.id));
    els.filter((e) => e.data.source).forEach((e) => {
      expect(ids.has(e.data.source as string)).toBe(true);
      expect(ids.has(e.data.target as string)).toBe(true);
    });
  });

  it('derives the hidden-principals affordance from meta', () => {
    expect(accessHiddenLabel(doc.meta)).toBe('2 more principals — raise topN or search');
    expect(accessHiddenLabel({ hiddenPrincipalCount: 0, truncated: false, totalPrincipals: 3 })).toBeNull();
  });
});
