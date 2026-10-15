import { describe, it, expect } from 'vitest';
import type { GraphElement } from '../../../types/cloudmap';
import {
  CFLOW,
  buildConnectivityFlow,
  buildConnectivityIndex,
  compactConnectivityFlow,
  locate,
} from '../Connectivity/connectivityFlowModel';

/**
 * The shared connectivity model, scoped to what two views now depend on it for.
 *
 * <p>It had no test. That was tolerable while one pan/zoom canvas consumed it; it is not now that
 * the cost drawer's Connectivity tab reads the same peer list, ordering, `derived` and `external`
 * flags and location wording — the whole point of consuming the model rather than reimplementing
 * adjacency being that the two views cannot disagree about one account.
 */

const elements: GraphElement[] = [
  { group: 'nodes', data: { id: 'vpc-001', label: 'prod-vpc', type: 'VPC' } },
  { group: 'nodes', data: { id: 'vpc-002', label: 'dr-vpc', type: 'VPC', region: 'us-east-1' } },
  { group: 'nodes', data: { id: 'subnet-001', label: 'private-1a', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: 'subnet-002', label: 'private-1b', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: 'i-web', label: 'prod-web-01', type: 'EC2 Instance', parent: 'subnet-001', 'Security Groups': 'sg-web' } },
  { group: 'nodes', data: { id: 'vol-web', label: 'prod-web-01-data', type: 'EBS Volume', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'i-api', label: 'prod-api-01', type: 'EC2 Instance', parent: 'subnet-002' } },
  { group: 'nodes', data: { id: 'sg-web', label: 'sg-web', type: 'Security Group', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: 'bucket', label: 'xammer-prod-assets', type: 'S3 Bucket' } },
  { group: 'edges', data: { id: 'e-vol', source: 'vol-web', target: 'i-web', label: 'attached to' } },
  { group: 'edges', data: { id: 'e-api', source: 'i-api', target: 'i-web', label: 'calls' } },
  { group: 'edges', data: { id: 'e-s3', source: 'i-web', target: 'bucket', label: 'reads' } },
  // An edge pointing at an id with no node — an IAM role the map does not draw.
  { group: 'edges', data: { id: 'e-role', source: 'i-web', target: 'arn:aws:iam::605134457560:role/backup', label: 'assumes' } },
];

const index = buildConnectivityIndex(elements);

const flowFor = (nodeId: string) => {
  const at = locate(index, nodeId);
  if (!at) throw new Error(`locate returned null for ${nodeId}`);
  return buildConnectivityFlow({
    index,
    accountId: '605134457560',
    accountName: '',
    selectedRegion: at.region,
    selectedVpc: at.vpc,
    selectedSubnet: at.subnet,
    selectedService: at.service,
    selectedResource: at.resource,
    search: '',
    showAllRegions: true,
    showAllVpcs: true,
    showAllSubnets: true,
    showAllServices: true,
    showAllResources: true,
  });
};

describe('locate', () => {
  it('places a resource on the branch that contains it', () => {
    expect(locate(index, 'i-web')).toEqual(expect.objectContaining({
      vpc: 'vpc-001',
      subnet: 'subnet-001',
      service: 'EC2 Instance',
      resource: 'i-web',
    }));
  });

  it('refuses a container, which is what makes a container row unfollowable', () => {
    // The cost drawer renders a row as a button only when locate would succeed, so a subnet that
    // is a peer rather than a parent reads as a connection without pretending to be a hop.
    expect(locate(index, 'subnet-001')).toBeNull();
    expect(locate(index, 'vpc-001')).toBeNull();
  });

  it('returns null for an id with no node at all', () => {
    expect(locate(index, 'arn:aws:iam::605134457560:role/backup')).toBeNull();
  });
});

describe('buildConnectivityFlow peers', () => {
  it('lists every relation the focus takes part in, inbound and outbound', () => {
    const peers = flowFor('i-web').peers;

    expect(peers.map((peer) => peer.id).sort()).toEqual([
      'arn:aws:iam::605134457560:role/backup', 'bucket', 'i-api', 'sg-web', 'vol-web',
    ]);
  });

  it('orders stated relations before inferred ones', () => {
    const peers = flowFor('i-web').peers;
    const firstDerived = peers.findIndex((peer) => peer.derived);

    // The cost drawer's trim drops from the end of this order, so what survives is the half the
    // model already considers more informative.
    expect(firstDerived).toBeGreaterThan(0);
    expect(peers.slice(0, firstDerived).every((peer) => !peer.derived)).toBe(true);
    expect(peers.slice(firstDerived).every((peer) => peer.derived)).toBe(true);
  });

  it('marks a security group read out of a detail field as inferred', () => {
    const sg = flowFor('i-web').peers.find((peer) => peer.id === 'sg-web');

    expect(sg?.derived).toBe(true);
    expect(sg?.external).toBe(false);
  });

  it('marks an edge pointing at a missing node as external', () => {
    const role = flowFor('i-web').peers
      .find((peer) => peer.id === 'arn:aws:iam::605134457560:role/backup');

    // There is no node, so there is no id to price — which is a different fact from a resource
    // whose cost cannot be attributed, and the two render differently.
    expect(role?.external).toBe(true);
    expect(role?.type).toBe('Not on the map');
  });

  it('says where each peer sits relative to the focus', () => {
    const peers = flowFor('i-web').peers;
    const location = (id: string) => peers.find((peer) => peer.id === id)?.location;

    expect(location('vol-web')).toBe('Same subnet');
    expect(location('i-api')).toBe('Another subnet');
    expect(location('bucket')).toBe('Outside any VPC');
    expect(location('arn:aws:iam::605134457560:role/backup')).toBe('');
  });

  it('counts each peer\'s own connections, so a reader can see what is worth following', () => {
    const peers = flowFor('vol-web').peers;

    // The volume, the API instance, the bucket, the IAM role and the inferred security group.
    expect(peers.find((peer) => peer.id === 'i-web')?.peerCount).toBe(5);
  });

  it('marks the focus as selected and leaves it out of its own peer list', () => {
    const flow = flowFor('i-web');

    expect(flow.selectedResource).toBe('i-web');
    expect(flow.resources.find((resource) => resource.selected)?.label).toBe('prod-web-01');
    expect(flow.peers.some((peer) => peer.id === 'i-web')).toBe(false);
  });
});

/**
 * The compact placement the cost drawer draws from.
 *
 * <p>`buildConnectivityFlow` centres a six-column canvas, so on the measured instance in this
 * account (18 connections, 16 of them SSM session rows) the focus card sits ~560px down an
 * ~1,120px peer stack. The drawer draws a subset of those peers in two columns, so the rows have
 * to close up — and because that is arithmetic rather than styling, it is checked here.
 */
describe('compactConnectivityFlow', () => {
  const CARD_W = 300;
  const CARD_H = 84;
  const peersOf = (id: string) => flowFor(id).peers;

  const compact = (count: number, hidden = 0) => compactConnectivityFlow({
    peers: peersOf('i-web').slice(0, count),
    hidden,
    focusW: CARD_W,
    focusH: CARD_H,
    peerW: CARD_W,
    peerH: CARD_H,
  });

  it('puts the focus at the origin column and the peers one gap to its right', () => {
    const placed = compact(3);

    expect(placed.focus.x).toBe(0);
    expect(placed.peers.every((peer) => peer.x === CARD_W + CFLOW.COL_GAP)).toBe(true);
    expect(placed.trunkX).toBe(CARD_W + CFLOW.TRUNK_OFFSET);
  });

  it('closes the peers up to one row pitch, in the model\'s own order', () => {
    const source = peersOf('i-web').slice(0, 4);
    const placed = compactConnectivityFlow({
      peers: source, hidden: 0, focusW: CARD_W, focusH: CARD_H, peerW: CARD_W, peerH: CARD_H,
    });

    expect(placed.peers.map((peer) => peer.id)).toEqual(source.map((peer) => peer.id));
    expect(placed.peers.map((peer) => peer.y))
      .toEqual([0, CARD_H + CFLOW.ROW_GAP, 2 * (CARD_H + CFLOW.ROW_GAP), 3 * (CARD_H + CFLOW.ROW_GAP)]);
    expect(placed.peers.every((peer) => peer.w === CARD_W && peer.h === CARD_H)).toBe(true);
    // Fields the drawer renders survive the re-stack; it reads relation and derived off these.
    expect(placed.peers.every((peer) => typeof peer.relation === 'string')).toBe(true);
  });

  it('adds the folded row last in the column, and only when something is folded', () => {
    expect(compact(2).more).toBeNull();

    const placed = compact(2, 16);
    expect(placed.more).toEqual({
      hidden: 16,
      x: CARD_W + CFLOW.COL_GAP,
      y: 2 * (CARD_H + CFLOW.ROW_GAP),
      w: CARD_W,
      h: CFLOW.MORE_H,
    });
  });

  it('centres the focus on the stack it is pointing at', () => {
    const placed = compact(3);
    const stackBottom = 2 * (CARD_H + CFLOW.ROW_GAP) + CARD_H;

    expect(placed.focus.y).toBeCloseTo((stackBottom - CARD_H) / 2);
    expect(placed.focus.y + placed.focus.h / 2).toBeCloseTo(stackBottom / 2);
  });

  it('shifts the stack down rather than placing the focus above the origin', () => {
    // One short peer against a tall focus: centring alone would put the focus at a negative y and
    // the drawer would clip the card that says which resource this is.
    const placed = compactConnectivityFlow({
      peers: peersOf('i-web').slice(0, 1),
      hidden: 0,
      focusW: CARD_W, focusH: 120, peerW: CARD_W, peerH: 40,
    });

    expect(placed.focus.y).toBe(0);
    expect(placed.peers[0].y).toBe((120 - 40) / 2);
    expect(placed.peers.every((peer) => peer.y >= 0)).toBe(true);
    expect(placed.height).toBe(120);
  });

  it('claims exactly the box it draws', () => {
    const placed = compact(2, 4);

    expect(placed.width).toBe(CARD_W + CFLOW.COL_GAP + CARD_W);
    expect(placed.height).toBe(2 * (CARD_H + CFLOW.ROW_GAP) + CFLOW.MORE_H);
  });

  it('claims no second column when there is nothing to put in it', () => {
    const placed = compactConnectivityFlow({
      peers: [], hidden: 0, focusW: CARD_W, focusH: CARD_H, peerW: CARD_W, peerH: CARD_H,
    });

    expect(placed.peers).toEqual([]);
    expect(placed.more).toBeNull();
    expect(placed.width).toBe(CARD_W);
    expect(placed.height).toBe(CARD_H);
    expect(placed.focus).toEqual({ x: 0, y: 0, w: CARD_W, h: CARD_H });
  });

  it('defaults to the canvas\'s own card sizes', () => {
    const placed = compactConnectivityFlow({ peers: peersOf('i-web').slice(0, 2), hidden: 0 });

    expect(placed.focus.w).toBe(CFLOW.RESOURCE_W);
    expect(placed.focus.h).toBe(CFLOW.RESOURCE_H);
    expect(placed.peers[0].w).toBe(CFLOW.PEER_W);
    expect(placed.peers[1].y).toBe(CFLOW.PEER_H + CFLOW.ROW_GAP);
    expect(placed.width).toBe(CFLOW.RESOURCE_W + CFLOW.COL_GAP + CFLOW.PEER_W);
  });
});
