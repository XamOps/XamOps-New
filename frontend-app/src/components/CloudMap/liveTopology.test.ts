import { describe, it, expect } from 'vitest';
import { shapeLiveTopology, DEFAULT_LIVE_LAYERS, INTERNET_NODE_ID } from './liveTopology';

/**
 * The live view's shaping step, against the element shape CloudMapService returns:
 * subnets carrying "Availability Zone" and "Tier", resources carrying "VPC ID", and edges
 * labelled the way createAllEdges and addRouteTableNodes label them.
 */

type El = { group?: string; data: Record<string, any> };
const n = (id: string, type: string, extra: Record<string, any> = {}): El =>
  ({ group: 'nodes', data: { id, label: id, type, ...extra } });
const e = (source: string, target: string, label: string): El =>
  ({ group: 'edges', data: { id: `${source}_${target}_${label}`, source, target, label } });

const VPC = { 'VPC ID': 'vpc' };

const threeTier = (): El[] => [
  n('vpc', 'VPC'),
  n('pub-a', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-a', Tier: 'Public', CIDR: '10.0.0.0/24' }),
  n('app-a', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-a', Tier: 'Private' }),
  n('app-b', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-b', Tier: 'Private' }),
  n('igw', 'Internet Gateway', { parent: 'vpc', ...VPC }),
  n('alb', 'Load Balancer', { parent: 'pub-a', ...VPC }),
  n('tg', 'Target Group', { parent: 'vpc', ...VPC }),
  n('web', 'EC2 Instance', { parent: 'app-a', ...VPC }),
  n('db', 'RDS Instance', { parent: 'app-b', ...VPC }),
  n('sg-alb', 'Security Group', { parent: 'vpc', ...VPC }),
  n('sg-web', 'Security Group', { parent: 'vpc', ...VPC }),
  n('sg-db', 'Security Group', { parent: 'vpc', ...VPC }),
  n('vol', 'EBS Volume', { parent: 'vpc', ...VPC }),
  e('alb', 'tg', 'routes to'),
  e('tg', 'web', 'targets'),
  e('alb', 'sg-alb', 'uses SG'),
  e('web', 'sg-web', 'uses SG'),
  e('db', 'sg-db', 'uses SG'),
  e('sg-web', 'sg-alb', 'admits'),
  e('sg-db', 'sg-web', 'admits'),
  e('vol', 'web', 'attaches to'),
  e('web', 'vol', 'mounts'),
  e('alb', 'pub-a', 'attached to'),
  e('igw', 'pub-a', 'egress for'),
];

const ids = (els: El[]) => els.filter((x) => !x.data.source).map((x) => x.data.id);
const edges = (els: El[]) => els.filter((x) => x.data.source).map((x) => `${x.data.source}>${x.data.target}:${x.data.label}`);

describe('shapeLiveTopology', () => {
  it('shows security groups and scaling resources by default, hides storage', () => {
    const { elements, hidden } = shapeLiveTopology(threeTier());
    const shown = ids(elements);
    for (const id of ['sg-alb', 'sg-web', 'sg-db', 'tg']) expect(shown).toContain(id);
    expect(shown).not.toContain('vol');
    expect(hidden).toEqual({ security: 0, routing: 0, storage: 1, scaling: 0, interfaces: 0 });
  });

  it('marks membership lines so they draw only when traced', () => {
    const { elements } = shapeLiveTopology(threeTier());
    const uses = elements.find((x) => x.data.source === 'web' && x.data.target === 'sg-web');
    expect(uses?.data.membership).toBe(true);
    const fwd = elements.find((x) => x.data.source === 'tg' && x.data.target === 'web');
    expect(fwd?.data.membership).toBeUndefined();
  });

  it('keeps the request path when target groups are hidden', () => {
    const out = edges(shapeLiveTopology(threeTier(), { ...DEFAULT_LIVE_LAYERS, scaling: false }).elements);
    expect(out).toContain('alb>web:forwards to');
  });

  it('turns security group rules into "can reach" between the protected resources', () => {
    const out = edges(shapeLiveTopology(threeTier()).elements);
    expect(out).toContain('web>db:can reach');
  });

  it('does not repeat a request path as a "can reach" line', () => {
    const out = edges(shapeLiveTopology(threeTier(), { ...DEFAULT_LIVE_LAYERS, scaling: false }).elements);
    // sg-web admits sg-alb, but alb already forwards to web.
    expect(out).not.toContain('alb>web:can reach');
  });

  it('keeps "can reach" lines whether or not security groups are shown', () => {
    for (const security of [true, false]) {
      const out = edges(shapeLiveTopology(threeTier(), { ...DEFAULT_LIVE_LAYERS, security }).elements);
      expect(out).toContain('web>db:can reach');
    }
  });

  it('groups subnets into availability zone columns inside their VPC', () => {
    const { elements } = shapeLiveTopology(threeTier());
    const byId = new Map(elements.map((x) => [x.data.id, x.data]));
    expect(byId.get('pub-a')!.parent).toBe('vpc::az::az-a');
    expect(byId.get('app-b')!.parent).toBe('vpc::az::az-b');
    expect(byId.get('vpc::az::az-a')!.parent).toBe('vpc');
    expect(byId.get('pub-a')!.displayLabel).toBe('pub-a  ·  Public  ·  10.0.0.0/24');
  });

  it('adds one Internet node wired to every internet gateway', () => {
    const out = shapeLiveTopology(threeTier()).elements;
    expect(ids(out)).toContain(`${INTERNET_NODE_ID}::vpc`);
    expect(edges(out)).toContain(`${INTERNET_NODE_ID}::vpc>igw:ingress`);
  });

  it('adds no Internet node to a VPC with no internet gateway', () => {
    const out = shapeLiveTopology([n('vpc', 'VPC'), n('i', 'EC2 Instance', { parent: 'vpc', ...VPC })]).elements;
    expect(ids(out).some((id) => id.startsWith(INTERNET_NODE_ID))).toBe(false);
  });

  it('drops edges that only restate containment', () => {
    const { elements } = shapeLiveTopology(threeTier(), { ...DEFAULT_LIVE_LAYERS, routing: true });
    expect(edges(elements)).not.toContain('alb>pub-a:attached to');
  });

  it('collapses a reciprocal storage pair to one line', () => {
    const { elements } = shapeLiveTopology(threeTier(), { ...DEFAULT_LIVE_LAYERS, storage: true });
    const storage = edges(elements).filter((x) => x.includes('vol'));
    expect(storage).toHaveLength(1);
  });

  it('lifts a VPC-less neighbour out of the VPC that asked for it', () => {
    // loadAllGraphs parents parentless neighbours to the requesting VPC.
    const out = shapeLiveTopology([n('vpc', 'VPC'), n('bucket', 'S3 Bucket', { parent: 'vpc' })]).elements;
    expect(out.find((x) => x.data.id === 'bucket')!.data.parent).toBeUndefined();
  });

  it('keeps a resource that genuinely belongs to the VPC inside it', () => {
    const out = shapeLiveTopology([n('vpc', 'VPC'), n('sgx', 'EC2 Instance', { parent: 'vpc', ...VPC })]).elements;
    expect(out.find((x) => x.data.id === 'sgx')!.data.parent).toBe('vpc');
  });

  it('does not mutate its input', () => {
    const input = threeTier();
    const before = JSON.stringify(input);
    shapeLiveTopology(input);
    expect(JSON.stringify(input)).toBe(before);
  });

  it('draws a gateway the scan missed when a route table targets it', () => {
    // UAT shape: route tables name igw-/nat- ids that have no node of their own.
    const out = shapeLiveTopology([
      n('vpc', 'VPC'),
      n('pub', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-a', Tier: 'Public' }),
      n('rtb', 'Route Table', { parent: 'vpc', ...VPC }),
      n('web', 'EC2 Instance', { parent: 'pub', ...VPC }),
      e('rtb', 'igw-123', 'default via'),
      e('igw-123', 'pub', 'egress for'),
    ]).elements;
    const igw = out.find((x) => x.data.id === 'igw-123');
    expect(igw?.data.type).toBe('Internet Gateway');
    expect(igw?.data.parent).toBe('vpc');
    expect(igw?.data.inferred).toBe(true);
    expect(edges(out)).toContain('pub>igw-123:egress via');
    expect(edges(out)).toContain(`${INTERNET_NODE_ID}::vpc>igw-123:ingress`);
  });

  it('never invents a gateway from an id it cannot classify', () => {
    const out = shapeLiveTopology([
      n('vpc', 'VPC'),
      n('rtb', 'Route Table', { parent: 'vpc', ...VPC }),
      e('rtb', 'pcx-9', 'default via'),
    ]).elements;
    expect(ids(out)).not.toContain('pcx-9');
  });

  it('hides network interfaces by default and links them to their instance when shown', () => {
    const input = [
      n('vpc', 'VPC'),
      n('i-1', 'EC2 Instance', { parent: 'vpc', ...VPC }),
      n('eni-1', 'Network Interface', { parent: 'vpc', ...VPC, 'Attached to': 'i-1' }),
      n('eni-2', 'Network Interface', { parent: 'vpc', ...VPC }),
    ];
    const off = shapeLiveTopology(input);
    expect(ids(off.elements)).not.toContain('eni-1');
    expect(off.hidden.interfaces).toBe(2);
    const on = shapeLiveTopology(input, { ...DEFAULT_LIVE_LAYERS, interfaces: true });
    expect(edges(on.elements)).toContain('eni-1>i-1:attached to');
  });

  it('gives each VPC with a gateway its own Internet entry', () => {
    const out = shapeLiveTopology([
      n('a', 'VPC'), n('b', 'VPC'),
      n('igw-a', 'Internet Gateway', { parent: 'a', 'VPC ID': 'a' }),
      n('igw-b', 'Internet Gateway', { parent: 'b', 'VPC ID': 'b' }),
      n('i-a', 'EC2 Instance', { parent: 'a', 'VPC ID': 'a' }),
      n('i-b', 'EC2 Instance', { parent: 'b', 'VPC ID': 'b' }),
    ]).elements;
    expect(edges(out)).toContain(`${INTERNET_NODE_ID}::a>igw-a:ingress`);
    expect(edges(out)).toContain(`${INTERNET_NODE_ID}::b>igw-b:ingress`);
  });

  it('collapses a VPC that holds only subnets and route tables', () => {
    const out = shapeLiveTopology([
      n('vpc', 'VPC'),
      n('s1', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-a' }),
      n('s2', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-b' }),
      n('rtb', 'Route Table', { parent: 'vpc', ...VPC }),
    ]).elements;
    const vpc = out.find((x) => x.data.id === 'vpc')!;
    expect(vpc.data.collapsed).toBe(true);
    expect(vpc.data.displayLabel).toContain('2 subnets');
    expect(ids(out)).not.toContain('s1');
    expect(ids(out).some((id) => id.includes('::az::'))).toBe(false);
  });

  it('keeps a VPC open when its only workload is on a hidden layer', () => {
    const out = shapeLiveTopology([
      n('vpc', 'VPC'),
      n('s1', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-a' }),
      n('eni', 'Network Interface', { parent: 's1', ...VPC }),
    ]).elements;
    expect(out.find((x) => x.data.id === 'vpc')!.data.collapsed).toBeUndefined();
  });

  it('treats the describe scan\'s ENI type and attachment key the same way', () => {
    const input = [
      n('vpc', 'VPC'),
      n('i-1', 'EC2 Instance', { parent: 'vpc', ...VPC }),
      n('eni-9', 'Network Interface (ENI)', { parent: 'vpc', ...VPC, 'Attached To': 'i-1' }),
    ];
    expect(ids(shapeLiveTopology(input).elements)).not.toContain('eni-9');
    const on = shapeLiveTopology(input, { ...DEFAULT_LIVE_LAYERS, interfaces: true });
    expect(edges(on.elements)).toContain('eni-9>i-1:attached to');
  });

  it('runs the request path internet -> gateway -> internet-facing load balancer', () => {
    const out = shapeLiveTopology([
      n('vpc', 'VPC'),
      n('pub', 'Subnet', { parent: 'vpc', 'Availability Zone': 'az-a', Tier: 'Public' }),
      n('igw', 'Internet Gateway', { parent: 'vpc', ...VPC }),
      n('alb', 'Load Balancer', { parent: 'vpc', ...VPC, Scheme: 'internet-facing', Type: 'application' }),
      n('int', 'Load Balancer', { parent: 'vpc', ...VPC, Scheme: 'internal', Type: 'network' }),
      n('web', 'EC2 Instance', { parent: 'pub', ...VPC }),
      e('alb', 'web', 'forwards to'),
      e('igw', 'pub', 'egress for'),
    ]).elements;
    const es = edges(out);
    expect(es).toContain('igw>alb:ingress');
    expect(es).not.toContain('igw>int:ingress');
    expect(es).toContain('alb>web:forwards to');
    // Outbound points out of the subnet, and to an internet gateway only on hover.
    const egress = out.find((x) => x.data.label === 'egress via')!;
    expect(`${egress.data.source}>${egress.data.target}`).toBe('pub>igw');
    expect(egress.data.membership).toBe(true);
    expect(out.find((x) => x.data.id === 'alb')!.data.caption).toBe('ALB · internet-facing');
  });

  it('folds security groups nothing uses into a count on the VPC', () => {
    const out = shapeLiveTopology([
      n('vpc', 'VPC'),
      n('web', 'EC2 Instance', { parent: 'vpc', ...VPC }),
      n('sg-used', 'Security Group', { parent: 'vpc', ...VPC }),
      n('sg-rule', 'Security Group', { parent: 'vpc', ...VPC }),
      n('sg-idle-1', 'Security Group', { parent: 'vpc', ...VPC }),
      n('sg-idle-2', 'Security Group', { parent: 'vpc', ...VPC }),
      e('web', 'sg-used', 'uses SG'),
      e('sg-used', 'sg-rule', 'admits'),
    ]).elements;
    const shown = ids(out);
    expect(shown).toContain('sg-used');
    expect(shown).toContain('sg-rule');
    expect(shown).not.toContain('sg-idle-1');
    expect(out.find((x) => x.data.id === 'vpc')!.data.displayLabel).toContain('2 unused security groups hidden');
  });
});
