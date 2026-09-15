import { describe, it, expect } from 'vitest';
import { groupOrphanResources, GLOBAL_SCOPE_ID } from './canvasTheme';

/**
 * Regression tests for the live-mode element preparation.
 *
 * The all-VPCs view is the case that broke in production and the one my earlier
 * testing never covered: it merges a synthetic `<vpc>::vpc-root` node per VPC,
 * carrying `type: 'VPC'` with no parent and no isContainer flag. On an account
 * whose VPCs return no subnets or resources, nothing references those roots as a
 * parent either — so every VPC looked like a loose resource and 30 of them were
 * grouped into a box labelled "not in a VPC".
 */

type El = { group?: string; data: Record<string, unknown> };

const node = (id: string, type: string, extra: Record<string, unknown> = {}): El =>
  ({ group: 'nodes', data: { id, label: id, type, ...extra } });

const inGlobalBox = (out: El[]) =>
  out.filter((e) => e.data.parent === GLOBAL_SCOPE_ID).map((e) => e.data.id);

describe('groupOrphanResources', () => {
  it('never groups VPCs, even when they have no children', () => {
    // Exactly the shape loadAllGraphs produces for an account with empty VPCs.
    const elements = Array.from({ length: 30 }, (_, i) =>
      node(`vpc-${i}::vpc-root`, 'VPC', { label: `vpc-${i} (us-east-1)` }));

    const out = groupOrphanResources(elements);

    expect(inGlobalBox(out)).toEqual([]);
    expect(out.find((e) => e.data.id === GLOBAL_SCOPE_ID)).toBeUndefined();
    expect(out).toHaveLength(30);
  });

  it('never groups other scope-defining types', () => {
    const elements = [
      node('s1', 'Subnet'),
      node('az1', 'Availability Zone'),
      node('r1', 'Region'),
    ];
    expect(inGlobalBox(groupOrphanResources(elements))).toEqual([]);
  });

  it('still groups genuinely VPC-less resources', () => {
    const elements = [
      node('vpc-1', 'VPC'),
      node('sub-1', 'Subnet', { parent: 'vpc-1' }),
      node('bucket', 'S3 Bucket'),
      node('queue', 'SQS Queue'),
      node('table', 'DynamoDB Table'),
    ];

    const out = groupOrphanResources(elements);
    const grouped = inGlobalBox(out);

    expect(grouped.sort()).toEqual(['bucket', 'queue', 'table']);
    // The VPC and its subnet keep their own relationship.
    expect(out.find((e) => e.data.id === 'vpc-1')!.data.parent).toBeUndefined();
    expect(out.find((e) => e.data.id === 'sub-1')!.data.parent).toBe('vpc-1');
  });

  it('leaves a single loose resource alone rather than boxing it', () => {
    const elements = [node('vpc-1', 'VPC'), node('bucket', 'S3 Bucket')];
    const out = groupOrphanResources(elements);
    expect(out.find((e) => e.data.id === GLOBAL_SCOPE_ID)).toBeUndefined();
  });

  it('never groups explicit containers or edges', () => {
    const elements: El[] = [
      node('group', 'Scope', { isContainer: true }),
      node('bucket', 'S3 Bucket'),
      node('queue', 'SQS Queue'),
      { group: 'edges', data: { id: 'e1', source: 'bucket', target: 'queue' } },
    ];

    const out = groupOrphanResources(elements);
    expect(inGlobalBox(out).sort()).toEqual(['bucket', 'queue']);
    expect(out.find((e) => e.data.id === 'e1')!.data.parent).toBeUndefined();
    expect(out.find((e) => e.data.id === 'group')!.data.parent).toBeUndefined();
  });

  it('is idempotent, so a re-render cannot nest global boxes', () => {
    const elements = [node('bucket', 'S3 Bucket'), node('queue', 'SQS Queue')];
    const once = groupOrphanResources(elements);
    const twice = groupOrphanResources(once);
    expect(twice.filter((e) => e.data.id === GLOBAL_SCOPE_ID)).toHaveLength(1);
  });
});
