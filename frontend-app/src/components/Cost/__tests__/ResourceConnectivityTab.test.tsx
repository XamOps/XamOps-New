import { describe, it, expect, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GraphElement } from '../../../types/cloudmap';
import {
  MAX_COST_IDS,
  type ResourceCostsResponse,
  type ResourceIdentities,
} from '../../../api/curInsightsApi';
import ResourceCostDrawer from '../ResourceCostDrawer';
import type { ConnectivityFetchers } from '../ResourceConnectivityTab';
import { renderWithQuery } from './renderWithQuery';

/**
 * The Connectivity tab's two obligations: fetch nothing until it is asked, and never state
 * something it does not know.
 *
 * <p>Several of these assertions are about silence — a query that has never fetched is neither
 * loading nor empty, and `data ?? []` makes the second indistinguishable from the first. Those
 * tests enumerate the sentences that must be absent by name rather than asserting an empty DOM,
 * because the failure they guard renders a confident claim about the reader's infrastructure in a
 * tab they have not opened.
 *
 * <p>Driven through `ResourceCostDrawer` rather than the tab in isolation, because the mount rule,
 * the `active` gate and the tab strip are the drawer's and are half of what is being tested. The
 * drawer renders through a portal, so queries go through `screen`.
 */

const ACCOUNT = '605134457560';
const FOCUS = 'vol-0aa1122334455667';

/** prod-web-01-data, attached to prod-web-01, which also fronts a load balancer. */
const GRAPH: GraphElement[] = [
  { group: 'nodes', data: { id: 'vpc-001', label: 'prod-vpc', type: 'VPC' } },
  { group: 'nodes', data: { id: 'subnet-001', label: 'private-1a', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: FOCUS, label: 'prod-web-01-data', type: 'EBS Volume', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'i-0abc1001', label: 'prod-web-01', type: 'EC2 Instance', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'alb-prod-web-001', label: 'prod-web-alb', type: 'Load Balancer', parent: 'subnet-001' } },
  { group: 'edges', data: { id: 'e1', source: FOCUS, target: 'i-0abc1001', label: 'attached to' } },
  { group: 'edges', data: { id: 'e2', source: 'alb-prod-web-001', target: 'i-0abc1001', label: ':80' } },
];

/**
 * An RDS instance, whose CUR identifier is its ARN while the topology names it by its short id.
 *
 * <p>The Group C shape, and the only one on which the focus-id rule is observable: on the
 * inventory-plane graph path a node is named by `resource_id`, so the drawer's own `resourceId` —
 * read out of `line_item_resource_id` — and the node id the flow roots on are different strings.
 */
const RDS_CUR_ID = 'arn:aws:rds:ap-south-1:605134457560:db:xammer-prod';
const RDS_NODE = 'xammer-prod';

const RDS_GRAPH: GraphElement[] = [
  { group: 'nodes', data: { id: 'vpc-001', label: 'prod-vpc', type: 'VPC' } },
  { group: 'nodes', data: { id: 'subnet-001', label: 'private-1a', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: RDS_NODE, label: 'xammer-prod', type: 'RDS Instance', parent: 'subnet-001' } },
  { group: 'nodes', data: { id: 'i-0abc1001', label: 'prod-web-01', type: 'EC2 Instance', parent: 'subnet-001' } },
  { group: 'edges', data: { id: 'e1', source: 'i-0abc1001', target: RDS_NODE, label: 'accesses' } },
];

const RDS_IDENTITIES: ResourceIdentities = {
  identities: [{
    requestedId: RDS_CUR_ID,
    nodeIdCandidates: [RDS_NODE, RDS_CUR_ID],
    resourceId: RDS_NODE,
    resourceType: 'RDS Instance',
    resourceName: 'xammer-prod',
    region: 'ap-south-1',
  }],
  requested: 1,
  resolved: 1,
};

const IDENTITIES: ResourceIdentities = {
  identities: [{
    requestedId: FOCUS,
    nodeIdCandidates: [FOCUS, 'arn:aws:ec2:ap-south-1:605134457560:volume/vol-0aa1122334455667'],
    resourceId: FOCUS,
    resourceType: 'EBS Volume',
    resourceName: 'prod-web-01-data',
    region: 'ap-south-1',
  }],
  requested: 1,
  resolved: 1,
};

const COSTS: ResourceCostsResponse = {
  costs: [
    { resourceId: FOCUS, cost: 484.74 },
    { resourceId: 'i-0abc1001', cost: 1621.35 },
  ],
  ambiguous: [],
  requestedCount: 2,
  pricedCount: 2,
  totalCost: 2106.09,
  groupCount: 2,
  basis: 'unblended',
};

const fetchers = (over: Partial<ConnectivityFetchers> = {}) => ({
  graph: vi.fn().mockResolvedValue(GRAPH),
  identities: vi.fn().mockResolvedValue(IDENTITIES),
  costs: vi.fn().mockResolvedValue(COSTS),
  ...over,
}) as ConnectivityFetchers & {
  graph: ReturnType<typeof vi.fn>;
  identities: ReturnType<typeof vi.fn>;
  costs: ReturnType<typeof vi.fn>;
};

const drawer = (
  connectivity: ConnectivityFetchers | null,
  over: { accountId?: string | null; resourceId?: string } = {},
) => (
  <ResourceCostDrawer
    resourceId={over.resourceId ?? FOCUS}
    resourceName="prod-web-01-data"
    accountId={over.accountId === undefined ? ACCOUNT : over.accountId}
    period="2026-10"
    onClose={() => undefined}
    // No resource-change payload: the other three tabs cannot answer, which is the normal state in
    // demo mode and must not hide a tab that works.
    fetcher={() => Promise.resolve(null)}
    insightName="resource-change"
    scope={{ service: 'Amazon Elastic Compute Cloud', region: 'ap-south-1' }}
    connectivity={connectivity}
    // These tests isolate the Connectivity tab's placement and absence; the Security & Alerts
    // tab is covered by its own specs, so omit it here to keep the asserted tab list focused.
    security={null}
  />
);

const openTab = async () => {
  await userEvent.click(await screen.findByRole('tab', { name: 'Connectivity' }));
};

/** The sentences that must not appear before the tab has read anything. */
const SILENT_UNLESS_READ = [
  /No topology came back for this account/,
  /could not be read/,
  /not in the account topology/,
  /Nothing else in this account references/,
  /no connections between them/,
  /Reading this account/,
];

/*
 * 20 s rather than the 5 s default, for the whole suite.
 *
 * Measured, not guessed: the slowest case here is 646 ms in isolation (150 peers, expanded, with a
 * click in the middle), and most are under 60 ms. Under `vitest run` on the full tree the same
 * cases take ~20x longer — every one of these renders a framer-motion drawer through a portal, and
 * three workers doing that at once on a loaded machine is what turns 646 ms into a timeout. The
 * headroom costs nothing when the suite passes and stops a contended machine reporting a failure
 * that is not one.
 */
describe('the Connectivity tab', () => {
  it('is the fourth tab, after Trend', async () => {
    renderWithQuery(drawer(fetchers()));

    const tabs = await screen.findAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      'Change', 'Breakdown', 'Trend', 'Connectivity',
    ]);
  });

  it('is absent when the fetchers are not supplied', async () => {
    renderWithQuery(drawer(null));

    const tabs = await screen.findAllByRole('tab');
    expect(tabs).toHaveLength(3);
    expect(screen.queryByRole('tab', { name: 'Connectivity' })).not.toBeInTheDocument();
  });

  it('fetches nothing, and says nothing, until it is selected', async () => {
    const read = fetchers();
    renderWithQuery(drawer(read));

    await screen.findByRole('tab', { name: 'Connectivity' });

    expect(read.graph).not.toHaveBeenCalled();
    expect(read.identities).not.toHaveBeenCalled();
    expect(read.costs).not.toHaveBeenCalled();
    // Enumerated rather than asserting an empty DOM: the failure this guards is a claim about the
    // account rendered in a tab nobody has opened, with a retry control that cannot fire.
    for (const sentence of SILENT_UNLESS_READ) {
      expect(screen.queryByText(sentence)).not.toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: 'Reload topology' })).not.toBeInTheDocument();
  });

  it('says nothing when it is selected with no account to ask about', async () => {
    // Reachable: ResourceCostDrawer passes `resourceId && accountId ? accountId : null`. A query
    // that has never fetched licences no statement and no retry.
    const read = fetchers();
    renderWithQuery(drawer(read, { accountId: null }));
    await openTab();

    expect(read.graph).not.toHaveBeenCalled();
    for (const sentence of SILENT_UNLESS_READ) {
      expect(screen.queryByText(sentence)).not.toBeInTheDocument();
    }
  });

  it('reads the topology once, for the account and the row region, and the identity without a region',
    async () => {
      const read = fetchers();
      renderWithQuery(drawer(read));
      await openTab();

      await waitFor(() => expect(read.graph).toHaveBeenCalledTimes(1));
      expect(read.graph).toHaveBeenCalledWith(ACCOUNT, 'ap-south-1');
      expect(read.identities).toHaveBeenCalledTimes(1);
      expect(read.identities).toHaveBeenCalledWith(ACCOUNT, [FOCUS]);
    });

  it('prices the focus and its rendered peers in one request, sorted and deduplicated', async () => {
    const read = fetchers();
    renderWithQuery(drawer(read));
    await openTab();

    await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(1));
    const [account, ids, period, basis] = read.costs.mock.calls[0];
    expect(account).toBe(ACCOUNT);
    expect(ids).toEqual(['i-0abc1001', FOCUS].sort((a, b) => a.localeCompare(b)));
    expect(period).toBe('2026-10');
    expect(basis).toBeUndefined();
  });

  it('prices the focus by the drawer\'s own CUR id, and a hopped-to peer by its graph id',
    async () => {
      // The vocabulary switch, on the one fixture where it is visible: the drawer's resourceId is
      // the ARN the CUR bills under, the node is named by the inventory's short id. Sending the
      // node id for the first focus is what blanks a load balancer's or an RDS instance's own
      // card on an account whose graph never saw the ARN; sending the drawer's id after a hop
      // would price the wrong resource entirely.
      const read = fetchers({
        graph: vi.fn().mockResolvedValue(RDS_GRAPH),
        identities: vi.fn().mockResolvedValue(RDS_IDENTITIES),
        costs: vi.fn().mockResolvedValue({ ...COSTS, costs: [], pricedCount: 0 }),
      });
      renderWithQuery(drawer(read, { resourceId: RDS_CUR_ID }));
      await openTab();

      await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(1));
      expect(read.costs.mock.calls[0][1]).toContain(RDS_CUR_ID);
      expect(read.costs.mock.calls[0][1]).not.toContain(RDS_NODE);

      await userEvent.click(await screen.findByRole('button', { name: /accesses prod-web-01/ }));

      await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(2));
      // The peer travels as the graph gave it, and the resource left behind travels as its node
      // id too — after a hop the graph is the only vocabulary either of them has.
      expect(read.costs.mock.calls[1][1]).toContain('i-0abc1001');
      expect(read.costs.mock.calls[1][1]).toContain(RDS_NODE);
      expect(read.costs.mock.calls[1][1]).not.toContain(RDS_CUR_ID);
    });

  it('issues one cost query when the identity lands before the topology', async () => {
    // The ordering the gate exists for. The cost query waits on both prior queries succeeding,
    // not merely on the tab being open — without that, an identity that resolves first prices the
    // focus alone, and the real peer set arrives as a second Athena scan for the same drawer open.
    let release: (elements: GraphElement[]) => void = () => undefined;
    const read = fetchers({
      graph: vi.fn(() => new Promise<GraphElement[]>((resolve) => { release = resolve; })),
    });
    const { client } = renderWithQuery(drawer(read));
    await openTab();

    // Waits on the identity's cache entry rather than on the spy, because a spy records the call
    // and says nothing about the answer having arrived.
    await waitFor(() => expect(client.getQueryData(
      ['cur-insights', `resource-change:identities:${FOCUS}`, ACCOUNT, 'current'],
    )).toBeDefined());
    expect(read.costs).not.toHaveBeenCalled();

    release(GRAPH);

    // Waiting on a rendered figure rather than on the spy: it proves the response landed and the
    // component re-rendered, which is the point at which a second, wider request would have been
    // issued if the first had been the focus-only one.
    await screen.findByText('$484.74');
    expect(read.costs).toHaveBeenCalledTimes(1);
    expect(read.costs.mock.calls[0][1]).toContain('i-0abc1001');
  });

  it('shows each resource its figure, and never a zero for one it has none for', async () => {
    const read = fetchers({
      costs: vi.fn().mockResolvedValue({
        ...COSTS,
        // The instance priced; the load balancer did not, which is the real Group C gap.
        costs: [{ resourceId: FOCUS, cost: 484.74 }],
        pricedCount: 1,
      }),
    });
    renderWithQuery(drawer(read));
    await openTab();

    await screen.findByText('$484.74');
    expect(await screen.findByText('no cost attributable')).toBeInTheDocument();
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
  });

  it('separates "cannot be attributed to this resource alone" from "no cost attributable"',
    async () => {
      const read = fetchers({
        costs: vi.fn().mockResolvedValue({
          ...COSTS,
          costs: [{ resourceId: FOCUS, cost: 484.74 }],
          ambiguous: ['i-0abc1001'],
          pricedCount: 1,
        }),
      });
      renderWithQuery(drawer(read));
      await openTab();

      expect(await screen.findByText('cost not attributable to this resource alone'))
        .toBeInTheDocument();
      expect(screen.queryByText('no cost attributable')).not.toBeInTheDocument();
    });

  it('says it on the focus card too, and still reports the resource as present', async () => {
    // The drawer's own resource is the likeliest one to be unanswerable — on an account where
    // short ids collide it is every EC2 instance — so the sentence has to work on the card as
    // well as on a row. The second half matters more: an id the inventory cannot vouch for is a
    // cost blank, never the "not in the account topology" state, which has no retry.
    const read = fetchers({
      costs: vi.fn().mockResolvedValue({
        ...COSTS,
        costs: [{ resourceId: 'i-0abc1001', cost: 1621.35 }],
        ambiguous: [FOCUS],
        pricedCount: 1,
      }),
    });
    renderWithQuery(drawer(read));
    await openTab();

    const slot = await screen.findByText('cost not attributable to this resource alone');
    // Bound to the focus card rather than merely present somewhere in the tab: the peer below it
    // prices normally, so "present" would pass on the wrong element.
    expect(slot.closest('div.rounded-xl')).toHaveTextContent('prod-web-01-data');
    expect(await screen.findByText('$1,621.35')).toBeInTheDocument();
    expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
  });

  it('renders the connections and one banner when the costs cannot be read', async () => {
    const read = fetchers({ costs: vi.fn().mockResolvedValue(null) });
    renderWithQuery(drawer(read));
    await openTab();

    expect(await screen.findByText(/The costs for October 2026 could not be read/))
      .toBeInTheDocument();
    // The flow still works, and no row claims there is nothing to attribute — "we could not ask"
    // and "there is nothing to attribute" are different facts.
    expect(screen.getByText('prod-web-01')).toBeInTheDocument();
    expect(screen.queryByText('no cost attributable')).not.toBeInTheDocument();
    expect(screen.queryByText('$484.74')).not.toBeInTheDocument();
  });

  it('does not render the failure sentence while a read is still in flight', async () => {
    // react-query reports data: undefined for the whole fetch, so flattening that into "null"
    // makes the first thing a click produces an apology, withdrawn on arrival.
    const read = fetchers({ graph: vi.fn(() => new Promise<GraphElement[]>(() => undefined)) });
    renderWithQuery(drawer(read));
    await openTab();

    expect(await screen.findByText(/Reading this account's topology/)).toBeInTheDocument();
    expect(screen.queryByText(/could not be read/)).not.toBeInTheDocument();
    expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No topology came back/)).not.toBeInTheDocument();
  });

  it('shows the pricing placeholder, not a blank, while a peerless resource is being priced',
    async () => {
      /*
       * The case a peer-count clause on the loading flag would get wrong. A resource with no
       * connections still has its own figure in flight, and gating the placeholder on there being
       * peers to price would leave the focus card reading "no cost attributable" for the length
       * of the query — a settled claim made before the answer arrived, then withdrawn.
       */
      const read = fetchers({
        graph: vi.fn().mockResolvedValue([
          { group: 'nodes', data: { id: FOCUS, label: 'prod-web-01-data', type: 'EBS Volume' } },
          { group: 'nodes', data: { id: 'i-0abc1001', label: 'prod-web-01', type: 'EC2 Instance' } },
          { group: 'nodes', data: { id: 'alb-prod-web-001', label: 'prod-web-alb', type: 'Load Balancer' } },
          { group: 'edges', data: { id: 'e2', source: 'alb-prod-web-001', target: 'i-0abc1001', label: ':80' } },
        ] as GraphElement[]),
        costs: vi.fn(() => new Promise<ResourceCostsResponse>(() => undefined)),
      });
      renderWithQuery(drawer(read));
      await openTab();

      expect(await screen.findByText('pricing these resources…')).toBeInTheDocument();
      expect(screen.queryByText('no cost attributable')).not.toBeInTheDocument();
      expect(screen.queryByText('cost not attributable to this resource alone'))
        .not.toBeInTheDocument();
      // Where the resource sits is still an answer, so 3a renders beside the pending card rather
      // than replacing it.
      expect(await screen.findByText(/Nothing else in this account references/))
        .toBeInTheDocument();
    });

  it('offers a retry when the topology request fails, and claims nothing about the account',
    async () => {
      const read = fetchers({ graph: vi.fn().mockRejectedValue(new Error('HTTP error! status: 502')) });
      renderWithQuery(drawer(read));
      await openTab();

      await screen.findByText('The account topology could not be read');
      expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
      expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
      expect(screen.queryByText(/No topology came back/)).not.toBeInTheDocument();
      // retry: false, so one call and the Try again control is the only way forward.
      expect(read.graph).toHaveBeenCalledTimes(1);
    });

  it('offers a retry when the identity lookup fails, rather than the benign no-retry state',
    async () => {
      // Through the shared null-returning fetcher contract this would have resolved successfully
      // with an empty candidate list and rendered "not in the account topology" permanently.
      const read = fetchers({
        identities: vi.fn().mockRejectedValue(new Error('resource-identities 502')),
      });
      renderWithQuery(drawer(read));
      await openTab();

      await screen.findByText('The account topology could not be read');
      expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
      expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
    });

  it('distinguishes an empty payload from an account with no topology', async () => {
    const read = fetchers({ graph: vi.fn().mockResolvedValue([]) });
    renderWithQuery(drawer(read));
    await openTab();

    await screen.findByText('No topology came back for this account');
    expect(screen.getByRole('button', { name: 'Reload topology' })).toBeInTheDocument();
    expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
  });

  it('treats a payload of VPC and subnet scaffolding alone as a failed read, not an absence',
    async () => {
      // CloudMapService builds the scaffolding first and creates resource nodes inside a
      // per-resource try/catch, so this payload means our own node build failed.
      const read = fetchers({
        graph: vi.fn().mockResolvedValue([
          { group: 'nodes', data: { id: 'vpc-001', label: 'prod-vpc', type: 'VPC' } },
          { group: 'nodes', data: { id: 'subnet-001', label: 'private-1a', type: 'Subnet', parent: 'vpc-001' } },
        ] as GraphElement[]),
      });
      renderWithQuery(drawer(read));
      await openTab();

      await screen.findByText('No topology came back for this account');
      expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
    });

  it('reports a resource genuinely absent from a usable topology, with no retry', async () => {
    const read = fetchers({
      identities: vi.fn().mockResolvedValue({
        identities: [{
          requestedId: FOCUS,
          nodeIdCandidates: ['snap-0aa1122334455667'],
          resourceId: 'snap-0aa1122334455667',
          resourceType: 'EBS Snapshot',
          resourceName: '',
          region: 'ap-south-1',
        }],
        requested: 1,
        resolved: 1,
      } as ResourceIdentities),
    });
    renderWithQuery(drawer(read));
    await openTab();

    await screen.findByText('This resource is not in the account topology');
    // Nothing to retry, and offering one would present a normal condition as a fault.
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reload topology' })).not.toBeInTheDocument();
  });

  it('roots on the ARN candidate when the short id matches no node', async () => {
    // The load-balancer regression: on a describe-scan account the node id is the ARN while the
    // inventory's short id is the ARN tail, so asserting on resourceId alone reports every load
    // balancer as missing from the topology.
    const read = fetchers({
      identities: vi.fn().mockResolvedValue({
        identities: [{
          requestedId: FOCUS,
          nodeIdCandidates: ['50dc6c495c0c9188', FOCUS],
          resourceId: '50dc6c495c0c9188',
          resourceType: 'EBS Volume',
          resourceName: 'prod-web-01-data',
          region: 'ap-south-1',
        }],
        requested: 1,
        resolved: 1,
      } as ResourceIdentities),
    });
    renderWithQuery(drawer(read));
    await openTab();

    expect(await screen.findByText('prod-web-01')).toBeInTheDocument();
    expect(screen.queryByText(/not in the account topology/)).not.toBeInTheDocument();
  });

  it('roots on the first candidate when the graph holds a node for both', async () => {
    /*
     * Precedence, which is only observable on a payload carrying a node for each candidate form.
     * Contrived — one resource normally appears once — but the backend declares the order
     * (`[resource_id, arn]`) and a reader of the tab cannot see which node it chose unless the two
     * have different neighbours. The first candidate is the inventory's own short id, which is
     * what the inventory-plane graph names nodes by, so trying it first is one lookup rather than
     * a scan for most accounts.
     */
    const read = fetchers({
      graph: vi.fn().mockResolvedValue([
        ...RDS_GRAPH,
        { group: 'nodes', data: { id: RDS_CUR_ID, label: 'xammer-prod', type: 'RDS Instance', parent: 'subnet-001' } },
        { group: 'nodes', data: { id: 'i-0bbb2002', label: 'prod-db-standby', type: 'EC2 Instance', parent: 'subnet-001' } },
        { group: 'edges', data: { id: 'e9', source: 'i-0bbb2002', target: RDS_CUR_ID, label: 'accesses' } },
      ] as GraphElement[]),
      identities: vi.fn().mockResolvedValue(RDS_IDENTITIES),
    });
    renderWithQuery(drawer(read, { resourceId: RDS_CUR_ID }));
    await openTab();

    // nodeIdCandidates[0] is the short id, whose only neighbour is prod-web-01.
    expect(await screen.findByText('prod-web-01')).toBeInTheDocument();
    expect(screen.queryByText('prod-db-standby')).not.toBeInTheDocument();
  });

  it('renders the flow for an identity the inventory could not resolve', async () => {
    // An unresolved id still has itself as a candidate, and a bare-id type matches a node with no
    // inventory row at all — so this is not by itself the benign state.
    const read = fetchers({
      identities: vi.fn().mockResolvedValue({
        identities: [{
          requestedId: FOCUS,
          nodeIdCandidates: [FOCUS],
          resourceId: null,
          resourceType: null,
          resourceName: null,
          region: null,
        }],
        requested: 1,
        resolved: 0,
      } as ResourceIdentities),
    });
    renderWithQuery(drawer(read));
    await openTab();

    expect(await screen.findByText('prod-web-01')).toBeInTheDocument();
  });

  it('reports a graph with resources and no edges anywhere as a scan that did not finish',
    async () => {
      const read = fetchers({
        graph: vi.fn().mockResolvedValue([
          { group: 'nodes', data: { id: FOCUS, label: 'prod-web-01-data', type: 'EBS Volume' } },
          { group: 'nodes', data: { id: 'i-0abc1001', label: 'prod-web-01', type: 'EC2 Instance' } },
        ] as GraphElement[]),
      });
      renderWithQuery(drawer(read));
      await openTab();

      await screen.findByText(/came back with resources but no connections between them/);
      // Not 3a's claim, which would be an unfounded statement about the infrastructure made from a
      // failure in our own scan.
      expect(screen.queryByText(/Nothing else in this account references/)).not.toBeInTheDocument();
    });

  it('names the resource when nothing references it in a graph that has edges elsewhere',
    async () => {
      const read = fetchers({
        graph: vi.fn().mockResolvedValue([
          { group: 'nodes', data: { id: FOCUS, label: 'prod-web-01-data', type: 'EBS Volume' } },
          { group: 'nodes', data: { id: 'i-0abc1001', label: 'prod-web-01', type: 'EC2 Instance' } },
          { group: 'nodes', data: { id: 'alb-prod-web-001', label: 'prod-web-alb', type: 'Load Balancer' } },
          { group: 'edges', data: { id: 'e2', source: 'alb-prod-web-001', target: 'i-0abc1001', label: ':80' } },
        ] as GraphElement[]),
      });
      renderWithQuery(drawer(read));
      await openTab();

      await screen.findByText(/Nothing else in this account references/);
      expect(screen.queryByText(/no connections between them/)).not.toBeInTheDocument();
    });

  it('re-roots on a peer, keeps the drawer header, and prices the peer\'s own set', async () => {
    const read = fetchers();
    renderWithQuery(drawer(read));
    await openTab();

    const header = (await screen.findAllByTitle('prod-web-01-data'))[0];
    await userEvent.click(await screen.findByRole('button', { name: /attached to prod-web-01/ }));

    // The banner names what is being shown and where it was reached from; the drawer's own header
    // is not rewritten, because the other three tabs did not move.
    await screen.findByLabelText('Showing prod-web-01, reached from prod-web-01-data');
    expect(header.textContent).toBe('prod-web-01-data');
    await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(2));
    // The peer's own peers, which here include the load balancer the volume cannot see.
    expect(read.costs.mock.calls[1][1]).toContain('alb-prod-web-001');
    // One identity lookup for the drawer's resource, and none per hop.
    expect(read.identities).toHaveBeenCalledTimes(1);
  });

  it('walks back one hop, and back to the resource the drawer was opened on', async () => {
    renderWithQuery(drawer(fetchers()));
    await openTab();

    await userEvent.click(await screen.findByRole('button', { name: /attached to prod-web-01/ }));
    await screen.findByLabelText('Showing prod-web-01, reached from prod-web-01-data');
    await userEvent.click(screen.getByRole('button', { name: 'Back' }));

    await waitFor(() => expect(
      screen.queryByLabelText('Showing prod-web-01, reached from prod-web-01-data'),
    ).not.toBeInTheDocument());
  });

  it('keeps the trail when the reader glances at another tab', async () => {
    renderWithQuery(drawer(fetchers()));
    await openTab();

    await userEvent.click(await screen.findByRole('button', { name: /attached to prod-web-01/ }));
    await screen.findByLabelText('Showing prod-web-01, reached from prod-web-01-data');
    await userEvent.click(screen.getByRole('tab', { name: 'Trend' }));
    await openTab();

    // Mounted and hidden rather than unmounted, which is what preserves the walk.
    expect(screen.getByLabelText('Showing prod-web-01, reached from prod-web-01-data'))
      .toBeInTheDocument();
  });

  it('does not offer a peer with no node as something to follow, and does not price it', async () => {
    const read = fetchers({
      graph: vi.fn().mockResolvedValue([
        ...GRAPH,
        { group: 'edges', data: { id: 'e3', source: FOCUS, target: 'arn:aws:iam::605134457560:role/backup', label: 'assumes' } },
      ] as GraphElement[]),
    });
    renderWithQuery(drawer(read));
    await openTab();

    await screen.findByText('Not drawn on the map');
    expect(screen.queryByRole('button', { name: /assumes/ })).not.toBeInTheDocument();
    await waitFor(() => expect(read.costs).toHaveBeenCalled());
    expect(read.costs.mock.calls[0][1]).not.toContain('arn:aws:iam::605134457560:role/backup');
  });

  it('sends a peer id byte-identically, however many colons it carries', async () => {
    const waf = 'arn:aws:waf::605134457560:webacl/abc';
    const read = fetchers({
      graph: vi.fn().mockResolvedValue([
        ...GRAPH,
        { group: 'nodes', data: { id: waf, label: 'prod-acl', type: 'WAF Web ACL', parent: 'subnet-001' } },
        { group: 'edges', data: { id: 'e4', source: FOCUS, target: waf, label: 'protected by' } },
      ] as GraphElement[]),
      costs: vi.fn().mockResolvedValue({
        ...COSTS,
        costs: [{ resourceId: waf, cost: 12.5 }],
        pricedCount: 1,
      }),
    });
    renderWithQuery(drawer(read));
    await openTab();

    await waitFor(() => expect(read.costs).toHaveBeenCalled());
    // Untransformed: `bareId` would have sent `605134457560:webacl/abc`, a string in no
    // vocabulary, and the resource would have rendered as unpriced.
    expect(read.costs.mock.calls[0][1]).toContain(waf);
    expect(await screen.findByText('$12.50')).toBeInTheDocument();
  });

  it('caps the rendered peers and re-requests when the reader asks for the rest', async () => {
    const many: GraphElement[] = [
      { group: 'nodes', data: { id: FOCUS, label: 'sg-web', type: 'Security Group' } },
    ];
    for (let i = 0; i < 40; i++) {
      many.push({ group: 'nodes', data: { id: `i-${i}`, label: `peer-${i}`, type: 'EC2 Instance' } });
      many.push({ group: 'edges', data: { id: `e-${i}`, source: `i-${i}`, target: FOCUS, label: 'uses SG' } });
    }
    const read = fetchers({ graph: vi.fn().mockResolvedValue(many) });
    renderWithQuery(drawer(read));
    await openTab();

    // 25 rendered peers plus the focus — pricing a row the reader cannot see buys nothing.
    await waitFor(() => expect(read.costs).toHaveBeenCalled());
    expect(read.costs.mock.calls[0][1]).toHaveLength(26);

    await userEvent.click(screen.getByRole('button', { name: 'Show all 40 connections' }));
    await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(2));
    expect(read.costs.mock.calls[1][1]).toHaveLength(41);
  });

  it('trims an over-cap peer set to the limit, says so, and keys only what it sent', async () => {
    /*
     * A shared security group is the real case: 150 instances reference one, and the reader has
     * asked to see all of them. The request cannot carry 151 ids, so what matters is that the
     * figures shown are the ones asked about, the shortfall is stated in the reader's own counting
     * (connections, not ids), and no cache key claims an id the request never carried — a key
     * built from the untrimmed list would serve one id set's answer under another's name.
     */
    const many: GraphElement[] = [
      { group: 'nodes', data: { id: FOCUS, label: 'sg-web', type: 'Security Group' } },
    ];
    for (let i = 0; i < 150; i++) {
      // Fixed-width ids so sorting is the natural order and a substring assertion on the cache
      // key cannot match a shorter id by accident.
      const id = `i-${String(i).padStart(3, '0')}`;
      many.push({ group: 'nodes', data: { id, label: `peer-${id}`, type: 'EC2 Instance' } });
      many.push({ group: 'edges', data: { id: `e-${id}`, source: id, target: FOCUS, label: 'uses SG' } });
    }
    const read = fetchers({ graph: vi.fn().mockResolvedValue(many) });
    const { client } = renderWithQuery(drawer(read));
    await openTab();

    await waitFor(() => expect(read.costs).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: 'Show all 150 connections' }));
    await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(2));

    const sent = read.costs.mock.calls[1][1] as string[];
    expect(sent).toHaveLength(MAX_COST_IDS);
    // The focus survives every trim: its absence would read as "this resource has no cost".
    expect(sent).toContain(FOCUS);
    // Dropped from the end of the model's order, so the 99 peers kept are the ones it ranks
    // first — 25 rendered rows' worth of them are on screen either way.
    expect(sent).toContain('i-098');
    expect(sent).not.toContain('i-099');
    await screen.findByText(/Costs shown for the first 99 of 150 connections/);

    const costKeys = client.getQueryCache().getAll()
      .map((query) => JSON.stringify(query.queryKey))
      .filter((key) => key.includes(':costs:'));
    expect(costKeys.some((key) => key.includes('i-098'))).toBe(true);
    expect(costKeys.every((key) => !key.includes('i-099'))).toBe(true);
  });

  it('issues no new request when the tab is reopened', async () => {
    const read = fetchers();
    renderWithQuery(drawer(read));
    await openTab();
    await waitFor(() => expect(read.costs).toHaveBeenCalledTimes(1));

    await userEvent.click(screen.getByRole('tab', { name: 'Change' }));
    await openTab();

    expect(read.graph).toHaveBeenCalledTimes(1);
    expect(read.identities).toHaveBeenCalledTimes(1);
    expect(read.costs).toHaveBeenCalledTimes(1);
  });

  it('does not read or write the Cloud Map page\'s own topology entry', async () => {
    const read = fetchers();
    const { client } = renderWithQuery(drawer(read));
    // Seeded, so the test can tell "not written" from "not read" — a shared key would have served
    // this payload to the tab.
    client.setQueryData(['cloudmap', 'graph', ACCOUNT, '__ALL__'], ['sentinel']);
    await openTab();
    await waitFor(() => expect(read.graph).toHaveBeenCalled());

    // The two payloads have different id vocabularies — that page's all-VPCs entry is merged from
    // one request per VPC with every id rewritten to `vpcId::nodeId` — so a shared key would be a
    // cross-feature corruption rather than a cache hit.
    expect(client.getQueryData(['cloudmap', 'graph', ACCOUNT, '__ALL__'])).toEqual(['sentinel']);
    expect(client.getQueryData(['cost-connectivity', 'graph', ACCOUNT, 'ap-south-1'])).toEqual(GRAPH);
  });

  it('starts a different resource with a clean trail and no stale peers', async () => {
    const read = fetchers();
    const view = renderWithQuery(drawer(read));
    await openTab();
    await userEvent.click(await screen.findByRole('button', { name: /attached to prod-web-01/ }));
    await screen.findByLabelText('Showing prod-web-01, reached from prod-web-01-data');

    view.rerender(drawer(read, { resourceId: 'vol-0bb1122334455667' }));

    // Keyed on resourceId, so the trail is discarded in the same commit rather than one render
    // later, and the cost fetcher is never called with the previous focus.
    expect(screen.queryByLabelText('Showing prod-web-01, reached from prod-web-01-data'))
      .not.toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: 'Change' }))
      .toHaveAttribute('aria-selected', 'true');
  });
}, 20_000);
