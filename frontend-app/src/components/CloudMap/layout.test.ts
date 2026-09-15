import { describe, it, expect } from 'vitest';
import cytoscape from 'cytoscape';
import { layoutHierarchicalGrid, buildCytoscapeStyle, CARD_W, CARD_H } from './canvasTheme';

/**
 * Reproduces the single-VPC live view, which reportedly renders as an empty dotted
 * box with only the VPC name — no subnets, no resources visible inside.
 *
 * Uses a headless Cytoscape so the real layout code runs against the element shape
 * CloudMapService actually returns: a VPC node with no parent, subnets parented to
 * it, and resources parented to a subnet.
 */

const mk = (elements: any[]) =>
  cytoscape({
    headless: true,
    elements,
    styleEnabled: true,
    // The real live-mode stylesheet: card sizing and :parent padding both come
    // from here, so a test without it cannot tell whether a container collapsed.
    style: buildCytoscapeStyle('light', 'live') as any,
  });

const node = (id: string, type: string, parent?: string) =>
  ({ group: 'nodes', data: { id, label: id, type, ...(parent ? { parent } : {}) } });

describe('layoutHierarchicalGrid', () => {
  it('places subnets inside a VPC at distinct positions', () => {
    const cy = mk([
      node('vpc-1', 'VPC'),
      node('subnet-a', 'Subnet', 'vpc-1'),
      node('subnet-b', 'Subnet', 'vpc-1'),
    ]);

    layoutHierarchicalGrid(cy);

    const a = cy.getElementById('subnet-a').position();
    const b = cy.getElementById('subnet-b').position();

    for (const [id, p] of [['subnet-a', a], ['subnet-b', b]] as const) {
      expect(Number.isFinite(p.x), `${id}.x = ${p.x}`).toBe(true);
      expect(Number.isFinite(p.y), `${id}.y = ${p.y}`).toBe(true);
    }
    // Two cards on top of each other would look like one empty container.
    expect(a.x !== b.x || a.y !== b.y).toBe(true);
  });

  it('places resources nested two levels deep', () => {
    const cy = mk([
      node('vpc-1', 'VPC'),
      node('subnet-a', 'Subnet', 'vpc-1'),
      node('i-1', 'EC2 Instance', 'subnet-a'),
      node('i-2', 'EC2 Instance', 'subnet-a'),
    ]);

    layoutHierarchicalGrid(cy);

    const p1 = cy.getElementById('i-1').position();
    const p2 = cy.getElementById('i-2').position();
    expect(Number.isFinite(p1.x) && Number.isFinite(p1.y)).toBe(true);
    expect(Number.isFinite(p2.x) && Number.isFinite(p2.y)).toBe(true);
    expect(p1.x !== p2.x || p1.y !== p2.y).toBe(true);

    // The instances must sit inside their subnet's box.
    const subnet = cy.getElementById('subnet-a').boundingBox();
    for (const p of [p1, p2]) {
      expect(p.x).toBeGreaterThanOrEqual(subnet.x1 - 1);
      expect(p.x).toBeLessThanOrEqual(subnet.x2 + 1);
    }
  });

  it('gives every leaf a finite position in a full three-tier graph', () => {
    const cy = mk([
      node('vpc-1', 'VPC'),
      node('pub', 'Subnet', 'vpc-1'),
      node('priv', 'Subnet', 'vpc-1'),
      node('igw', 'Internet Gateway', 'vpc-1'),
      node('sg', 'Security Group', 'vpc-1'),
      node('alb', 'Load Balancer', 'pub'),
      node('i-1', 'EC2 Instance', 'priv'),
      node('db', 'RDS Instance', 'priv'),
    ]);

    layoutHierarchicalGrid(cy);

    const leaves = cy.nodes().filter((n: any) => !n.isParent());
    expect(leaves.length).toBe(5);
    leaves.forEach((n: any) => {
      const p = n.position();
      expect(Number.isFinite(p.x), `${n.id()}.x`).toBe(true);
      expect(Number.isFinite(p.y), `${n.id()}.y`).toBe(true);
    });

    // No two leaves may occupy the same point.
    const seen = new Set(leaves.map((n: any) => `${Math.round(n.position('x'))},${Math.round(n.position('y'))}`));
    expect(seen.size).toBe(5);
  });

  it('sizes a VPC container large enough to hold its children', () => {
    const cy = mk([
      node('vpc-1', 'VPC'),
      node('subnet-a', 'Subnet', 'vpc-1'),
      node('subnet-b', 'Subnet', 'vpc-1'),
    ]);

    layoutHierarchicalGrid(cy);

    const vpc = cy.getElementById('vpc-1').boundingBox();
    // An empty-looking dotted box is the symptom of a container that collapsed or
    // whose children landed outside it.
    expect(vpc.w).toBeGreaterThan(CARD_W);
    expect(vpc.h).toBeGreaterThan(CARD_H);

    for (const id of ['subnet-a', 'subnet-b']) {
      const p = cy.getElementById(id).position();
      expect(p.x).toBeGreaterThan(vpc.x1);
      expect(p.x).toBeLessThan(vpc.x2);
      expect(p.y).toBeGreaterThan(vpc.y1);
      expect(p.y).toBeLessThan(vpc.y2);
    }
  });

  it('handles a VPC with no children at all', () => {
    const cy = mk([node('vpc-1', 'VPC')]);
    layoutHierarchicalGrid(cy);
    const p = cy.getElementById('vpc-1').position();
    expect(Number.isFinite(p.x) && Number.isFinite(p.y)).toBe(true);
  });
});
