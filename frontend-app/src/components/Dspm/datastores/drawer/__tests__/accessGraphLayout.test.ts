import { describe, expect, it } from 'vitest';
import type { DatastoreAccessGroup } from '../../../../../types/dspmDatastores';
import {
  ROOT_ID, ZOOM_DEFAULT, ZOOM_MAX, ZOOM_MIN, accessGraphLayout, edgePath, zoomStep, type GraphLayout,
} from '../accessGraphLayout';

// Synthetic groups shaped like the grouper's output (names invented).
function group(id: string, principalType: string, principals: string[], permissions = ['READ']): DatastoreAccessGroup {
  return { id, permissions, principalType, actionOnly: false, count: principals.length, principals };
}

const READ_ROLES = group('READ|ROLE', 'ROLE', ['analytics-reader', 'billing-reader', 'reporting-role']);
const WRITE_USERS = group('READ,WRITE|USER', 'USER', ['pat.example', 'sam.example'], ['READ', 'WRITE']);
const GRAPH = { groups: [READ_ROLES, WRITE_USERS] };
const NONE: ReadonlySet<string> = new Set();

/** `[id, x, y, w, h]` per node, in layout order. */
const boxes = (layout: GraphLayout) => layout.nodes.map(node => [node.id, node.x, node.y, node.w, node.h]);

describe('accessGraphLayout', () => {
  it('places collapsed groups deterministically, the root centred on the total height', () => {
    const layout = accessGraphLayout(GRAPH, NONE, '');
    expect(boxes(layout)).toEqual([
      [ROOT_ID, 24, 60, 180, 96],
      ['READ|ROLE|permissions', 284, 24, 220, 72],
      ['READ|ROLE|type', 564, 24, 200, 72],
      ['READ,WRITE|USER|permissions', 284, 120, 220, 72],
      ['READ,WRITE|USER|type', 564, 120, 200, 72],
    ]);
    expect(layout.width).toBe(788);
    expect(layout.height).toBe(216);
    expect(accessGraphLayout(GRAPH, NONE, '')).toEqual(layout);
  });

  it('grows an expanded block to its principals, stacked from the top of the block', () => {
    const layout = accessGraphLayout(GRAPH, new Set(['READ|ROLE']), '');
    // max(72, 3 × 28 + 2 × 8) = 100
    expect(boxes(layout)).toEqual([
      [ROOT_ID, 24, 74, 180, 96],
      ['READ|ROLE|permissions', 284, 38, 220, 72],
      ['READ|ROLE|type', 564, 38, 200, 72],
      ['READ|ROLE|principal:0', 824, 24, 260, 28],
      ['READ|ROLE|principal:1', 824, 60, 260, 28],
      ['READ|ROLE|principal:2', 824, 96, 260, 28],
      ['READ,WRITE|USER|permissions', 284, 148, 220, 72],
      ['READ,WRITE|USER|type', 564, 148, 200, 72],
    ]);
    expect(layout.height).toBe(244);
    expect(layout.width).toBe(1108);
    const types = layout.nodes.filter(node => node.kind === 'type');
    expect(types.map(node => node.kind === 'type' && node.expanded)).toEqual([true, false]);

    // Two principals need 64 px, so the block keeps its collapsed 72.
    const two = accessGraphLayout(GRAPH, new Set(['READ,WRITE|USER']), '');
    expect(two.height).toBe(216);
  });

  it('keeps only groups with a matching principal while searching, forced open with the matches flagged', () => {
    const layout = accessGraphLayout(GRAPH, NONE, '  SAM ');
    const principals = layout.nodes.filter(node => node.kind === 'principal');
    expect(principals.map(node => node.kind === 'principal' && [node.name, node.match])).toEqual([['sam.example', true]]);
    expect(layout.nodes.map(node => node.id)).toEqual([
      ROOT_ID, 'READ,WRITE|USER|permissions', 'READ,WRITE|USER|type', 'READ,WRITE|USER|principal:0',
    ]);
    const type = layout.nodes.find(node => node.kind === 'type');
    expect(type?.kind === 'type' && type.expanded).toBe(true);

    const readers = accessGraphLayout(GRAPH, NONE, 'reader');
    expect(readers.nodes.filter(node => node.kind === 'principal').map(node => node.kind === 'principal' && node.name))
      .toEqual(['analytics-reader', 'billing-reader']);
    // A blank query lists nothing beyond the expanded set.
    expect(accessGraphLayout(GRAPH, NONE, '   ').nodes.some(node => node.kind === 'principal')).toBe(false);
  });

  it('returns the root alone for an empty graph and for a search that matches nobody', () => {
    const empty = accessGraphLayout({ groups: [] }, NONE, '');
    expect(boxes(empty)).toEqual([[ROOT_ID, 24, 24, 180, 96]]);
    expect(empty.edges).toEqual([]);
    expect(empty.width).toBe(228);
    expect(empty.height).toBe(144);
    expect(accessGraphLayout(GRAPH, NONE, 'nobody').nodes.map(node => node.id)).toEqual([ROOT_ID]);
  });

  it('connects root → Permissions → type → each listed principal', () => {
    expect(accessGraphLayout(GRAPH, NONE, '').edges).toHaveLength(4);
    const expanded = accessGraphLayout(GRAPH, new Set(['READ|ROLE', 'READ,WRITE|USER']), '');
    expect(expanded.edges).toHaveLength(4 + 3 + 2);
    const [first] = accessGraphLayout(GRAPH, NONE, '').edges;
    expect(first).toEqual({ id: `${ROOT_ID}->READ|ROLE|permissions`, x1: 204, y1: 108, x2: 284, y2: 60 });
    expect(edgePath(first)).toBe('M 204 108 C 244 108, 244 60, 284 60');
  });
});

describe('zoomStep', () => {
  it('reaches exactly 20% after five steps down from 70%, and stays there', () => {
    const seen: number[] = [];
    let zoom = ZOOM_DEFAULT;
    for (let i = 0; i < 5; i += 1) {
      zoom = zoomStep(zoom, 'down');
      seen.push(zoom);
    }
    expect(seen).toEqual([0.6, 0.5, 0.4, 0.3, 0.2]);
    expect(zoom).toBe(0.2);
    expect(zoom).toBe(ZOOM_MIN);
    expect(zoomStep(zoom, 'down')).toBe(0.2);
  });

  it('clamps at 150% going up', () => {
    let zoom = ZOOM_DEFAULT;
    for (let i = 0; i < 12; i += 1) zoom = zoomStep(zoom, 'up');
    expect(zoom).toBe(1.5);
    expect(zoomStep(ZOOM_MAX, 'up')).toBe(ZOOM_MAX);
    expect(zoomStep(1.4, 'up')).toBe(1.5);
  });
});
