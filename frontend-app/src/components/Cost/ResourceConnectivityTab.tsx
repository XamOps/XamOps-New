import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Icon } from '@iconify/react';
import type { GraphElement } from '../../types/cloudmap';
import {
  buildConnectivityFlow,
  buildConnectivityIndex,
  compactConnectivityFlow,
  detailOf,
  fieldOf,
  labelOf,
  locate,
  type PeerFlowNode,
} from '../CloudMap/Connectivity/connectivityFlowModel';
import {
  EdgeLegend,
  FlowEdge,
  MoreButton,
  StateChip,
  TypeIcon,
  hoverText,
  nodeClass,
  peerCardClass,
} from '../CloudMap/Connectivity/connectivityView';
import { fetchCloudMapGraph } from '../../api/cloudMapGraphApi';
import {
  fetchResourceCosts,
  fetchResourceIdentities,
  trimCostIds,
  type CostBasis,
  type ResourceChange,
  type ResourceCostsResponse,
  type ResourceIdentities,
} from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { useMoneyIn } from './costCurrency';
import { shortenId } from '../../utils/shortenId';

/**
 * What a resource is attached to, and what each of those things costs in the same month.
 *
 * <h2>Why this tab exists</h2>
 * The drawer's other three tabs answer whether a resource moved, which line moved and when. They
 * cannot answer the question a reader always has next: what is this thing attached to, and what is
 * the rest of that attachment costing me? A NAT gateway at $344 is meaningless until you know which
 * five instances egress through it; an EBS volume at $180 is meaningless until you know it is
 * attached to an instance that was stopped three weeks ago.
 *
 * <h2>Where the two halves come from</h2>
 * The topology is the Cloud Map's graph endpoint, consumed through the same
 * {@link connectivityFlowModel} the Cloud Map's own connectivity view uses — adjacency, peer
 * ordering, the location wording and the `external` flag all come from there rather than being
 * rebuilt, because a second peer-assembly path is the thing that drifts. The money is the CUR,
 * through one batch query per focus.
 *
 * <p>The join between them is the hard part and it lives on the server: ids in the graph and ids in
 * the bill are different vocabularies, and the inventory table is the only thing holding both forms
 * of one resource on one row. This component sends ids exactly as it received them and looks each
 * row's cost up under the exact string it sent — no transform anywhere on the pricing path, because
 * the server matches on byte equality and any client-side rewriting can only lose a match or
 * manufacture a wrong one.
 */

/** Rendered peers before the reader asks for the rest. Also what bounds the priced set. */
const VISIBLE_PEERS = 25;

/**
 * One flow card: wider and taller than the Cloud Map's 264 × 52 peer card, on purpose.
 *
 * <p>Width is the budgeted dimension. Two columns at 300 plus the model's own 84px `COL_GAP` is
 * 684px, inside a `sm:max-w-[960px]` drawer whose `p-5` body leaves up to 920px — so every tab
 * keeps the same width and the other three are untouched. Height is free, because the drawer body
 * already scrolls vertically, and 84px is what four lines need: relation, label, meta and money.
 * "cost not attributable to this resource alone" is ~210px at 11px and does not fit a right-hand
 * slot in a 264px card, which is why the money is a line of its own rather than a column.
 */
const CARD_W = 300;
const CARD_H = 84;

/**
 * How long a topology read stays fresh.
 *
 * <p>Declared rather than inherited: the app default is five minutes, and a cold
 * `/cloudmap/graph?vpcId=ALL` can take a minute or more because it triggers the account scan. A
 * reader opening six resources in half an hour should pay for that once.
 */
const GRAPH_STALE_MS = 30 * 60 * 1000;

/** Just under the server's own 120 s async request timeout, so a cold-scan failure is ours. */
const GRAPH_TIMEOUT_MS = 110_000;

/**
 * Where the Connectivity tab reads from.
 *
 * <p>One bundle rather than three nullable fetchers, deliberately: the tab needs all three answers
 * to say anything. A provider holding the graph but not the costs would draw boxes with no money in
 * them, which is not the question the tab is named after.
 */
export interface ConnectivityFetchers {
  /** The account topology. Resolves `[]` only for a genuinely empty payload; throws otherwise. */
  graph: (accountId: string, region?: string) => Promise<GraphElement[]>;
  /**
   * Resolves or throws — never null. The tab decides "not in the topology" from an empty candidate
   * match, so a fetcher that answered null on a 502 would turn a backend blip into that benign,
   * retry-less state.
   */
  identities: (accountId: string, ids: string[]) => Promise<ResourceIdentities>;
  costs: (
    accountId: string,
    ids: string[],
    period?: string,
    basis?: CostBasis,
  ) => Promise<ResourceCostsResponse | null>;
}

/**
 * The AWS default, and the only place `vpcId` and the timeout live.
 *
 * <p>`graph` is an adapter rather than `fetchCloudMapGraph` itself: the bundle's signature is
 * `(accountId, region)` because that is all the tab knows. `vpcId: 'ALL'` is what returns the whole
 * estate's nodes and the full edge pass in one request, and the tab has no business overriding it.
 * `forceRefresh` is never set — a cost drawer must not be able to trigger an account rescan.
 */
export const awsConnectivity: ConnectivityFetchers = {
  graph: (accountId, region) =>
    fetchCloudMapGraph({ accountId, vpcId: 'ALL', region, timeoutMs: GRAPH_TIMEOUT_MS }),
  identities: fetchResourceIdentities,
  costs: fetchResourceCosts,
};

interface ResourceConnectivityTabProps {
  /**
   * Whether this tab is the selected one.
   *
   * <p>Passed in rather than derived, because the body is mounted while hidden so the focus trail
   * survives a glance at another tab — and a body that inferred "I am mounted, therefore I am
   * visible" would fetch while hidden.
   */
  active: boolean;
  accountId: string | null;
  /** The drawer's own resource, as a CUR identifier. */
  resourceId: string;
  resourceName?: string;
  /** Billing month, `YYYY-MM`. The peers are priced for the same one. */
  period: string;
  /** The basis the card above is on, threaded so every figure in the tab is comparable. */
  basis?: CostBasis;
  /** The row's region, omitted when the report gave none. Scopes the topology scaffolding. */
  region?: string;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName: string;
  currency?: string;
  connectivity: ConnectivityFetchers;
  /** The Change tab's payload, so the two figures' difference can be named when there is one. */
  change?: ResourceChange | null;
}

/**
 * A node id without its `vpc-xxx::` scope prefix, when it really has one.
 *
 * <p>NOT the model's `bareId`, which is `id.slice(id.lastIndexOf('::') + 2)` with no test on the
 * prefix — so it truncates any ARN with an empty region or account segment, and on a genuinely
 * scoped payload it mangles `vpc-abc::arn:aws:s3:::bucket` down to `bucket`. Inside that module the
 * ids are known to be one of two shapes and it is safe; here they are not.
 *
 * <p>This is the only place in the tab where a node id is transformed at all, it is used for a
 * comparison and never for a request, and it is dead code against the `vpcId=ALL` payload this tab
 * fetches — which returns unscoped ids. It exists so that repointing the fetcher at a scoped
 * payload later degrades into a miss rather than a wrong match.
 */
const unscope = (id: string): string => {
  const at = id.lastIndexOf('::');
  if (at < 0) return id;
  return /^vpc-[0-9a-f]+$/.test(id.slice(0, at)) ? id.slice(at + 2) : id;
};

const ResourceConnectivityTab: React.FC<ResourceConnectivityTabProps> = ({
  active,
  accountId,
  resourceId,
  resourceName,
  period,
  basis,
  region,
  insightName,
  currency = 'USD',
  connectivity,
  change,
}) => {
  const format = useMoneyIn(currency);
  const [trail, setTrail] = useState<string[]>([]);
  /**
   * Whether "Show all N connections" has been used for the CURRENT focus.
   *
   * Reset on every hop rather than carried: expansion is a judgement about one resource's peer
   * list, and inheriting it would make one click on a 25-peer resource silently request 300 ids on
   * the next.
   */
  const [expanded, setExpanded] = useState(false);
  /**
   * Whether the inferred tail has been unfolded for the CURRENT focus.
   *
   * Reset on every hop for the same reason `expanded` is: the measured
   * `xamops-prod-db-redis` has 18 connections of which 16 are SSM session rows, so carrying the
   * reveal across a hop would open another resource's tail unasked and push its two paying peers
   * off the top of the panel. One-way per focus, mirroring the Cloud Map's own `showAll*`.
   */
  const [showInferred, setShowInferred] = useState(false);

  /*
   * A namespace of its own, never ['cloudmap','graph',…].
   *
   * That key belongs to the Cloud Map page, which uses react-query as a plain store and holds an
   * all-VPCs payload merged from one request per VPC with every node id rewritten to
   * `vpcId::nodeId`. Reading it would hand this tab scoped ids; writing this tab's unscoped payload
   * into it would leave that page's canvas with parent references it no longer satisfies.
   *
   * Region is in the key because the backend's VPC and subnet scaffolding is region-scoped, so two
   * regions are two different payloads and one key would serve the wrong breadcrumb.
   */
  const graphQuery = useQuery({
    queryKey: ['cost-connectivity', 'graph', accountId ?? '', region ?? ''],
    queryFn: () => connectivity.graph(accountId as string, region),
    enabled: active && Boolean(accountId),
    staleTime: GRAPH_STALE_MS,
    // A cold read can take 110 s; retrying one is two minutes of spinner for the same answer.
    retry: false,
  });

  const identityQuery = useCurInsight<ResourceIdentities>(
    `${insightName}:identities:${resourceId}`,
    // Null while the tab is unselected, which is what stops it fetching. The mechanism
    // ServiceResourcesDrawer uses while closed.
    active ? accountId : null,
    // No period: which resource an id names does not depend on a billing month.
    undefined,
    (account) => connectivity.identities(account, [resourceId]),
  );

  const elements = graphQuery.data ?? [];
  const index = useMemo(() => buildConnectivityIndex(elements), [elements]);

  /*
   * Selected by `requestedId`, never as identities[0]. The endpoint's contract is one entry per
   * requested id in request order, and this request has exactly one — but a positional read is how
   * a contract change becomes a silent mislabel, and this entry carries the candidate list the
   * whole tab is rooted on.
   */
  const identity = identityQuery.data?.identities
    .find((entry) => entry.requestedId === resourceId) ?? null;

  /**
   * Which node in this graph is the resource the drawer was opened on.
   *
   * Tries each candidate the backend returned, in order, by exact equality. Deliberately not
   * `identity.resourceId` alone: that is the inventory's short id, always the ARN tail, and for a
   * load balancer the describe-scan graph names the node `my-lb` while the tail is
   * `50dc6c495c0c9188` — so a single-field read reports every load balancer as missing from the
   * topology.
   */
  const rootNodeId = useMemo(() => {
    const candidates = (identity?.nodeIdCandidates ?? [])
      .filter((candidate) => typeof candidate === 'string' && candidate.length > 0);
    // First pass: byte-exact against the ids as they arrived. On this payload that is the pass
    // that hits.
    for (const candidate of candidates) {
      if (index.byId.has(candidate)) return candidate;
    }
    // Second pass, for a scoped payload only: unscope the node id and compare exactly again. It
    // cannot relax the match — both sides are still whole strings the inventory holds.
    for (const candidate of candidates) {
      for (const nodeId of index.byId.keys()) {
        if (unscope(nodeId) === candidate) return nodeId;
      }
    }
    return null;
  }, [index, identity]);

  const focusId = trail.length > 0 ? trail[trail.length - 1] : rootNodeId;
  const at = useMemo(() => (focusId ? locate(index, focusId) : null), [index, focusId]);
  const flow = useMemo(
    () => (at && accountId
      ? buildConnectivityFlow({
        index,
        accountId,
        // The account node is not rendered here and the model falls back to the id, so there is
        // nothing to pass and nothing to invent.
        accountName: '',
        selectedRegion: at.region,
        selectedVpc: at.vpc,
        selectedSubnet: at.subnet,
        selectedService: at.service,
        selectedResource: at.resource,
        search: '',
        // Every cap lifted: the focus may sit past the fourteenth row of its own column, and the
        // model folds an unselected row away.
        showAllRegions: true,
        showAllVpcs: true,
        showAllSubnets: true,
        showAllServices: true,
        showAllResources: true,
      })
      : null),
    [index, accountId, at],
  );

  const focus = flow?.resources.find((resource) => resource.selected) ?? null;
  // The model's order: declared relations before inferred ones, then by relation — not by label.
  // The trim below drops from the end of exactly this order.
  const peers = flow?.peers ?? [];
  const shown = expanded ? peers : peers.slice(0, VISIBLE_PEERS);

  /*
   * The fold: stated connections are drawn, the inferred tail stands behind one row until asked
   * for. Both halves come from the model's own `derived` flag, and the split is over `shown` so
   * the fold can never reveal a row the cap already dropped.
   */
  const stated = shown.filter((peer) => !peer.derived);
  const inferred = shown.filter((peer) => peer.derived);
  const drawn = showInferred ? shown : stated;
  const hiddenInferred = showInferred ? 0 : inferred.length;
  /*
   * Re-packed rather than placed by `buildConnectivityFlow`: that function centres a six-column
   * canvas, so on the real 18-peer focus the resource card sits ~560px down an ~1,120px stack.
   * This view draws a subset — the inferred tail folded away — whose rows have to close up.
   */
  const placed = useMemo(
    () => compactConnectivityFlow({
      peers: drawn,
      hidden: hiddenInferred,
      focusW: CARD_W,
      focusH: CARD_H,
      peerW: CARD_W,
      peerH: CARD_H,
    }),
    // Keyed on what is drawn rather than on the array, which is a new one every render.
    [drawn.map((peer) => `${peer.relation}\u0000${peer.id}`).join(','), hiddenInferred],
  );

  /*
   * While the trail is empty the focus is the drawer's own resource, and the drawer's resourceId is
   * a CUR id by construction — the resource table read it out of line_item_resource_id. Sending
   * that rather than rootNodeId is what lets a load balancer price its own focus card on an account
   * whose graph names it something the CUR never saw. After a hop the focus is a peer and the graph
   * id is all there is.
   */
  const focusCostId = trail.length === 0 ? resourceId : (focusId ?? resourceId);
  /*
   * Byte-identical. No bareId, no unscope, no transform of any kind: a peer id's only correct form
   * on this path is the form the graph gave it.
   *
   * Derived from `shown`, never from `drawn`: the inferred fold is presentation, so it must not
   * change which ids are priced, the cost cache key, or what the trim banner counts — and
   * unfolding then shows figures that were already fetched rather than issuing a second Athena
   * scan for rows the reader was looking at the count of a moment ago.
   */
  const peerCostIds = shown.filter((peer) => !peer.external).map((peer) => peer.id);
  const { sent, sentPeerCount, totalPeerCount } = useMemo(
    () => trimCostIds(focusCostId, peerCostIds),
    [focusCostId, peerCostIds.join(',')],
  );
  const sortedIds = useMemo(
    // Sorted and deduplicated so two readers opening the same resource produce the same list and
    // hit the same server-side cache entry. Sorting does not change the joined length the trim
    // measured.
    () => Array.from(new Set(sent)).sort((a, b) => a.localeCompare(b)),
    [sent],
  );

  /*
   * Gated on the flow being settled, not merely on the tab being open.
   *
   * Without this the first open issues two Athena scans — one for the focus alone while the graph
   * is still in flight, then one for the real peer set.
   */
  const settled = graphQuery.isSuccess && identityQuery.isSuccess;
  const costsReady = active && settled && focusId !== null && flow !== null;
  const costsQuery = useCurInsight<ResourceCostsResponse>(
    `${insightName}:costs:${sortedIds.join(',')}${basis === 'amortized' ? ':amortized' : ''}`,
    costsReady ? accountId : null,
    period,
    (account, month) => connectivity.costs(account, sortedIds, month, basis),
  );

  /*
   * Each flag repeats its query's own enabled condition rather than reading isPending alone.
   * react-query reports `status: 'pending'` for a query that has never fetched, including a
   * disabled one, so isPending alone is true forever while the tab is closed — a reading sentence
   * that never resolves. ChargeTypeReconciliation documents the same fix.
   */
  const graphLoading = active && Boolean(accountId) && graphQuery.isPending;
  const identityLoading = active && Boolean(accountId) && identityQuery.isPending;
  // No peer-count clause: a peerless focus card would otherwise read "no cost attributable" while
  // its own figure was still in flight.
  const costsLoading = costsReady && costsQuery.isPending;

  const costs = costsQuery.data ?? null;
  const costsFailed = costsReady && !costsQuery.isPending && costs === null;
  const costOf = (id: string): number | null => {
    const hit = costs?.costs.find((entry) => entry.resourceId === id);
    return hit ? hit.cost : null;
  };
  const isAmbiguous = (id: string): boolean => Boolean(costs?.ambiguous.includes(id));

  const monthLabel = useMemo(() => {
    const [year, month] = period.split('-').map(Number);
    if (!year || !month) return period;
    return new Date(year, month - 1, 1)
      .toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  }, [period]);

  const followPeer = (peer: PeerFlowNode) => {
    if (peer.external || !locate(index, peer.id)) return;
    setTrail((walked) => [...walked, peer.id]);
    setExpanded(false);
    setShowInferred(false);
  };

  const rootLabel = rootNodeId
    ? labelOf(index.byId.get(rootNodeId) ?? { id: rootNodeId, label: '', type: '' })
    : (resourceName || resourceId);
  const focusLabel = focus?.label ?? (focusId ? focusId : rootLabel);

  /*
   * The unavailable states, in this exact order, which is the design and has two jobs.
   *
   * Failure before fact: every rung above state 2 is a failure rung, and state 2 is the only one
   * that makes a claim about the reader's infrastructure with no way to retry it.
   *
   * Silence before either: a query that has never fetched is neither loading nor empty — it is
   * silent, and silence licences no sentence at all. With `elements = data ?? []` the empty rung
   * would otherwise be true while the tab is closed or the account is null, so a body mounted and
   * hidden would carry "No topology came back for this account" in the DOM before the reader had
   * ever opened it. `settled` is checked after isError, because a failed query is neither pending
   * nor successful and testing it first would swallow every error into silence.
   */
  if (!active) return null;
  if (graphLoading || identityLoading) {
    return (
      <Status live>
        Reading this account&apos;s topology — the first read after a while can take up to a minute.
      </Status>
    );
  }
  if (graphQuery.isError || identityQuery.isError) {
    return (
      <Unavailable
        title="The account topology could not be read"
        detail="So this resource's connections are unknown. The figures on the other tabs are unaffected."
        action={{ label: 'Try again', onClick: () => {
          void graphQuery.refetch();
          void identityQuery.refetch();
        } }}
      />
    );
  }
  if (!identity && identityQuery.isSuccess) {
    // The endpoint guarantees an entry per requested id, so its absence is a malformed answer
    // rather than a fact about the account — state 1a with a retry, never state 2.
    return (
      <Unavailable
        title="The account topology could not be read"
        detail="The identity lookup answered without this resource in it, which should not happen."
        action={{ label: 'Try again', onClick: () => { void identityQuery.refetch(); } }}
      />
    );
  }
  if (!settled) return null;
  if (elements.length === 0 || index.resourceCount === 0) {
    /*
     * Two conditions, not one. CloudMapService creates nodes inside a per-resource try/catch and
     * builds the VPC and subnet scaffolding first, so a payload can come back non-empty while every
     * resource node threw — leaving only containers, which carry no resourceCount. Without the
     * second half the reader would be told this resource is not in the topology because our own
     * node build failed.
     */
    return (
      <Unavailable
        title="No topology came back for this account"
        detail="If the Cloud Map shows resources, reload the topology."
        action={{ label: 'Reload topology', onClick: () => { void graphQuery.refetch(); } }}
      />
    );
  }
  if (rootNodeId === null || at === null || flow?.selectedResource !== focusId) {
    return (
      <Unavailable
        title="This resource is not in the account topology"
        detail={'Not everything on a bill is: XamOps indexes infrastructure, while a bill also'
          + ' carries Marketplace subscriptions, support, tax and resource types the topology does'
          + ' not draw.'}
      />
    );
  }
  if (peers.length === 0 && index.linkCount === 0 && index.resourceCount > 1) {
    // A graph with many resources and not one edge anywhere is far more likely to be a
    // relationship scan that did not finish than an account where nothing touches anything — and
    // claiming the latter would be an unfounded statement about the infrastructure.
    return (
      <Unavailable
        title="This account's topology came back with resources but no connections between them"
        detail={'That usually means the relationship scan did not finish. Reload the topology, or'
          + ' open the Cloud Map to check.'}
        action={{ label: 'Reload topology', onClick: () => { void graphQuery.refetch(); } }}
      />
    );
  }

  const focusCost = costOf(focusCostId);
  /*
   * The breakdown beside the count, over every peer rather than the rendered ones: "18" and
   * "2 stated · 16 inferred" have to add up, and on a 150-peer security group the rendered
   * slice would make them disagree.
   */
  const statedCount = peers.filter((peer) => !peer.derived).length;
  const inferredCount = peers.length - statedCount;
  /** What the focus card puts beside its own name. The state is a chip, so it is not in here. */
  const focusMeta = [focus?.type, ...(focus?.facts ?? [])].filter(Boolean).join(' · ');
  /*
   * Only when there is a difference to explain, and only against a figure that exists. The Change
   * tab compares metered usage alone, because a unit rate needs a quantity; this figure is every
   * charge type. On a commitment-covered instance the gap is most of the cost, and two numbers for
   * one resource on adjacent tabs with nothing accounting for the difference reads as the product
   * being wrong even when both are right.
   */
  const differsFromChangeTab = focusCost !== null && change
    && Math.abs(focusCost - change.currentCost) >= 0.01;

  return (
    <div className="space-y-4">
      {trail.length > 0 && (
        <div
          aria-label={`Showing ${focusLabel}, reached from ${rootLabel}`}
          className="flex items-start justify-between gap-3 px-3.5 py-2.5 rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(66,133,244,0.04)]"
        >
          <p className="text-[12.5px] text-black min-w-0">
            <span className="font-semibold">{focusLabel}</span>
            <span className="text-[rgba(0,0,0,0.45)]"> · reached from {rootLabel}</span>
          </p>
          <div className="flex items-center gap-1.5 flex-shrink-0">
            <button
              type="button"
              onClick={() => {
                setTrail((walked) => walked.slice(0, -1));
                setExpanded(false);
                setShowInferred(false);
              }}
              className="px-2 py-1 text-[11.5px] font-medium rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
            >
              Back
            </button>
            {trail.length > 1 && (
              <button
                type="button"
                onClick={() => { setTrail([]); setExpanded(false); setShowInferred(false); }}
                className="px-2 py-1 text-[11.5px] font-medium rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              >
                Back to {rootLabel}
              </button>
            )}
          </div>
        </div>
      )}

      {/* Where the focus sits, from the model's own location wording. */}
      <p className="text-[11px] text-[rgba(0,0,0,0.45)] flex flex-wrap items-center gap-1.5">
        {breadcrumb(flow).map((part, i) => (
          <React.Fragment key={`${part}-${i}`}>
            {i > 0 && <span className="text-[rgba(0,0,0,0.2)]">›</span>}
            <span>{part}</span>
          </React.Fragment>
        ))}
      </p>

      {/* Above the flow, because the sentence points downward at the figures it is about. */}
      {costsFailed && (
        <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] px-3.5 py-2.5 rounded-xl border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)]">
          The costs for {monthLabel} could not be read, so no figures are shown below. The
          connections themselves are unaffected.
        </p>
      )}

      {/*
        The flow: the focus at the left, what it connects to in a column at the right, in the
        Cloud Map's own visual language and from its own geometry. 684px of it inside a body up to
        920px wide — which is why there is no pan/zoom here. The Cloud Map needs one for six
        columns across a full page; two columns in a dialog that already scrolls vertically would
        only fight the reader.
      */}
      <div>
        {peers.length > 0 && (
          <div className="flex items-baseline justify-between gap-3">
            <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium">
              Connected to ({peers.length})
              {inferredCount > 0 && (
                <span className="ml-1.5 normal-case tracking-normal text-[rgba(0,0,0,0.3)]">
                  {statedCount} stated · {inferredCount} inferred
                </span>
              )}
            </p>
            {peers.length > VISIBLE_PEERS && (
              <button
                type="button"
                onClick={() => setExpanded((open) => !open)}
                aria-expanded={expanded}
                className="text-[11.5px] font-medium text-[#4285F4] hover:underline decoration-[rgba(66,133,244,0.4)] underline-offset-2"
              >
                {expanded ? `Show the first ${VISIBLE_PEERS}` : `Show all ${peers.length} connections`}
              </button>
            )}
          </div>
        )}

        {peers.length > 0 && sentPeerCount < totalPeerCount && (
          <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1">
            Costs shown for the first {sentPeerCount} of {totalPeerCount} connections — more than
            that does not fit one request.
          </p>
        )}

        {/* Native horizontal scroll, for the `w-full` phone case alone: at 684px the second
            column is past the right edge of a phone-width drawer, and the fix is a scrollbar
            rather than a wider drawer that would move the other three tabs. */}
        <div className="mt-2 overflow-x-auto">
          <div
            role="group"
            aria-label={`What ${focusLabel} connects to`}
            className="relative"
            style={{ width: placed.width, height: placed.height }}
          >
            {/* Edges under the cards, so a card's shadow sits on top of its own connector. */}
            <svg
              width={placed.width}
              height={placed.height}
              viewBox={`0 0 ${placed.width} ${placed.height}`}
              className="absolute inset-0 pointer-events-none"
              aria-hidden="true"
            >
              {placed.peers.map((peer) => (
                <FlowEdge
                  key={`e-${peer.relation}-${peer.id}`}
                  x1={placed.focus.x + placed.focus.w}
                  y1={placed.focus.y + placed.focus.h / 2}
                  x2={peer.x}
                  y2={peer.y + peer.h / 2}
                  trunkX={placed.trunkX}
                  kind="connects"
                  dashed={peer.derived || peer.external}
                />
              ))}
              {placed.more && (
                <FlowEdge
                  x1={placed.focus.x + placed.focus.w}
                  y1={placed.focus.y + placed.focus.h / 2}
                  x2={placed.more.x}
                  y2={placed.more.y + placed.more.h / 2}
                  trunkX={placed.trunkX}
                  kind="contains"
                  dashed
                />
              )}
            </svg>

            {/* The focus. `aria-current` rather than a colour alone: which resource the rest of
                the panel is about is the one thing a reader must not have to infer. */}
            <div
              aria-current="true"
              aria-label={`Showing ${focusLabel}`}
              className={nodeClass(true)}
              style={{
                left: placed.focus.x,
                top: placed.focus.y,
                width: placed.focus.w,
                height: placed.focus.h,
              }}
            >
              <TypeIcon type={focus?.type ?? 'Resource'} />
              <div className="min-w-0 flex-1">
                <p className="text-[12.5px] font-semibold text-black truncate leading-tight"
                  title={hoverText(focusLabel, focusMeta, focusCostId)}>
                  {focusLabel}
                </p>
                <div className="flex items-center gap-1.5 min-w-0">
                  {focus?.state && <StateChip state={focus.state} />}
                  <p className="text-[10px] text-[rgba(0,0,0,0.45)] truncate">{focusMeta}</p>
                </div>
                <p className="text-[9.5px] text-[rgba(0,0,0,0.3)] font-mono truncate"
                  title={focusCostId}>
                  {shortenId(focusCostId, 40)}
                </p>
                <div className="mt-0.5">
                  <MoneySlot
                    cost={focusCost}
                    loading={costsLoading}
                    failed={costsFailed}
                    ambiguous={isAmbiguous(focusCostId)}
                    format={format}
                  />
                </div>
              </div>
            </div>

            {placed.peers.map((peer) => (
              <PeerCard
                key={`${peer.relation}-${peer.id}`}
                peer={peer}
                index={index}
                costOf={costOf}
                isAmbiguous={isAmbiguous}
                costsLoading={costsLoading}
                costsFailed={costsFailed}
                format={format}
                onFollow={followPeer}
              />
            ))}

            {placed.more && (
              <MoreButton
                row={placed.more}
                onClick={() => setShowInferred(true)}
                noun="inferred connection"
                expanded={showInferred}
              />
            )}
          </div>
        </div>

        {peers.length > 0 && (
          <EdgeLegend className="flex items-center gap-3 text-[10px] text-[rgba(0,0,0,0.45)] mt-2" />
        )}
      </div>

      {/* The focus card's prose, below the geometry rather than inside it: an 84px card cannot
          hold a three-line sentence, and the sentence is the honest part. */}
      {focus?.warnings?.map((warning) => (
        <p key={warning} className="text-[11px] text-[#B45309]">{warning}</p>
      ))}
      {differsFromChangeTab && change && (
        <p className="text-[11px] text-[rgba(0,0,0,0.5)] leading-relaxed">
          {format(focusCost as number)} for {monthLabel}, every charge type — the{' '}
          <span className="font-medium">Change</span> tab compares metered usage only
          ({format(change.currentCost)}), which is why the two differ.
        </p>
      )}

      {peers.length === 0 && (
        /*
         * State 3a — in the topology, nothing references it, in a graph that does have edges
         * elsewhere. Rendered beside the flow rather than as an early return because where the
         * resource sits is still an answer, and its own card is still worth showing.
         */
        <p className="text-[12px] text-[rgba(0,0,0,0.55)] leading-relaxed">
          <span className="font-medium text-black">
            Nothing else in this account references {focusLabel}.
          </span>{' '}
          The topology is built from relationships AWS states and from each resource&apos;s own
          details, so a genuinely isolated resource has none — an unattached volume and a bucket
          nothing names are the common cases.
        </p>
      )}

      <p className="text-[11px] text-[rgba(0,0,0,0.4)] leading-relaxed">
        {costs && (
          <>
            Priced {costs.pricedCount} of the {costs.requestedCount} resources asked about, for{' '}
            {monthLabel}. That total is the sum of what could be priced, not a slice of the bill —
            the resources in a flow belong to many services and some carry no cost at all.{' '}
          </>
        )}
        A resource can be real and carry no charges (a security group is not billed); charges and
        credits that cancel within a cent are dropped rather than reported as zero; and a resource
        whose billing identifier the inventory cannot vouch for is left blank on purpose. An
        inferred connection was read out of a resource&apos;s own details rather than stated by AWS.
      </p>
    </div>
  );
};

/** The containment path, read off the flow the model already built. */
const breadcrumb = (flow: ReturnType<typeof buildConnectivityFlow> | null): string[] => {
  if (!flow) return [];
  const parts: string[] = [];
  const region = flow.regions.find((entry) => entry.selected);
  if (region) parts.push(region.label || region.region);
  const vpc = flow.vpcs.find((entry) => entry.selected);
  if (vpc) parts.push(vpc.label);
  const subnet = flow.subnets.find((entry) => entry.selected);
  if (subnet) parts.push(subnet.label);
  if (flow.selectedService) parts.push(flow.selectedService);
  return parts;
};

/**
 * One connection, placed where the model put it, with the model's own wording for the
 * relationship and this tab's money on the bottom line.
 *
 * <p>Followable peers are real `<button>`s and everything else is a plain `<div>` — never a
 * disabled button. A disabled button still answers `getByRole('button', { name })` and still
 * reads as a control to a screen reader, and "this peer is not something you can open" is the
 * fact being reported.
 */
const PeerCard: React.FC<{
  peer: PeerFlowNode;
  index: ReturnType<typeof buildConnectivityIndex>;
  costOf: (id: string) => number | null;
  isAmbiguous: (id: string) => boolean;
  costsLoading: boolean;
  costsFailed: boolean;
  format: (v: number) => string;
  onFollow: (peer: PeerFlowNode) => void;
}> = ({ peer, index, costOf, isAmbiguous, costsLoading, costsFailed, format, onFollow }) => {
  // A card is a button only when following it would land somewhere: a container — including a
  // load balancer's non-parent subnet — and an id with no node both locate to null.
  const followable = !peer.external && Boolean(locate(index, peer.id));
  const relationLine = `${peer.outbound ? '' : '← '}${peer.relation}`;
  const metaLine = [
    peer.type,
    peer.location,
    // Over one, not over zero: the connection the reader is looking at is one of them, and
    // "1 connections" is both wrong and the count of nothing new.
    peer.peerCount > 1 && `${peer.peerCount} connections`,
    peer.derived && 'inferred',
  ].filter(Boolean).join(' · ');
  /*
   * A peer's state is not on `PeerFlowNode` — it is on the graph node, read through the model's
   * own field and detail readers so the chip here says exactly what the Cloud Map's chip says.
   * An external peer has no node, so it has no chip, which is correct: there is nothing to report.
   */
  const node = index.byId.get(peer.id);
  const state = node ? (fieldOf(node, 'state') || detailOf(node, 'State', 'Status')) : '';
  // The tooltip carries the lines the 300px card truncates, plus the id it has no room to show.
  const title = hoverText(relationLine, peer.label, metaLine, peer.id);
  const className = [
    'absolute flex items-center gap-2.5 px-3 py-2 text-left transition-all',
    peerCardClass({ dashed: peer.derived || peer.external }),
    followable
      ? 'hover:bg-[#f5f3ff] outline-none focus-visible:ring-2 focus-visible:ring-[#8b5cf6]'
      : '',
  ].join(' ');
  const style = { left: peer.x, top: peer.y, width: peer.w, height: peer.h };

  const body = (
    <>
      <TypeIcon type={peer.type} />
      <div className="min-w-0 flex-1">
        {/* The relation sits immediately before the label with nothing between them, so the
            accessible name reads "attached to prod-web-01". A chip or a badge in that gap
            renames the card without changing a word of what it says. */}
        <span className="block text-[9px] font-bold uppercase tracking-wide text-[#7c3aed] truncate">
          {relationLine}
        </span>
        <p className="text-[11.5px] font-semibold text-black truncate leading-tight">
          {peer.label}
        </p>
        <div className="flex items-center gap-1 min-w-0">
          {state && <StateChip state={state} />}
          <p className="text-[9.5px] text-[rgba(0,0,0,0.4)] truncate">{metaLine}</p>
        </div>
        {/* An external peer has no node and therefore no id to price, which is a different fact
            from a resource whose cost cannot be attributed — so it gets no money slot at all
            rather than an empty one. */}
        {peer.external ? (
          <span className="text-[10px] text-[rgba(0,0,0,0.35)] whitespace-nowrap">
            Not drawn on the map
          </span>
        ) : (
          <MoneySlot
            cost={costOf(peer.id)}
            loading={costsLoading}
            failed={costsFailed}
            ambiguous={isAmbiguous(peer.id)}
            format={format}
          />
        )}
      </div>
    </>
  );

  return followable ? (
    <button type="button" onClick={() => onFollow(peer)} title={title}
      className={className} style={style}>
      {body}
    </button>
  ) : (
    <div title={title} className={className} style={style}>{body}</div>
  );
};

/**
 * The money column — a figure, or the reason there is not one.
 *
 * <p>Never `$0.00` for an absence. Four different facts, four different renderings: in flight, the
 * read failed, the cost cannot be attributed to this resource alone, and there is nothing to
 * attribute. A blank sentence is only shown once the query has settled, because "we could not ask"
 * and "there is nothing to attribute" are different claims and the second would be a lie about the
 * first.
 */
const MoneySlot: React.FC<{
  cost: number | null;
  loading: boolean;
  failed: boolean;
  ambiguous: boolean;
  format: (v: number) => string;
}> = ({ cost, loading, failed, ambiguous, format }) => {
  if (loading) {
    return (
      <span className="text-[11px] text-[rgba(0,0,0,0.35)] whitespace-nowrap">
        pricing these resources…
      </span>
    );
  }
  // "We could not ask" is not "there is nothing to attribute", so a failed read shows neither a
  // figure nor a blank sentence — the banner above the list carries that fact once.
  if (failed) return null;
  if (cost !== null) {
    return (
      <span className="text-[12.5px] font-semibold tabular-nums text-black whitespace-nowrap">
        {format(cost)}
      </span>
    );
  }
  if (ambiguous) {
    return (
      <span className="text-[11px] text-[rgba(0,0,0,0.4)] whitespace-nowrap">
        cost not attributable to this resource alone
      </span>
    );
  }
  return (
    <span className="text-[11px] text-[rgba(0,0,0,0.4)] whitespace-nowrap">
      no cost attributable
    </span>
  );
};

/** A sentence about what is happening, announced when it resolves. */
const Status: React.FC<{ children: React.ReactNode; live?: boolean }> = ({ children, live }) => (
  <p
    aria-live={live ? 'polite' : undefined}
    className="text-[12px] text-[rgba(0,0,0,0.5)] leading-relaxed"
  >
    {children}
  </p>
);

/**
 * One of the reasons the tab cannot answer, with its own sentence and its own action.
 *
 * <p>Each caller supplies both. These are different facts and are never collapsed into one
 * apology — and the one state that is a normal condition rather than a fault carries no action at
 * all, because offering a retry for it would present a fact as a failure.
 */
const Unavailable: React.FC<{
  title: string;
  detail: string;
  action?: { label: string; onClick: () => void };
}> = ({ title, detail, action }) => (
  <div className="text-center py-10" aria-live="polite">
    <Icon
      icon="mdi:lan-disconnect"
      width={24}
      className="text-[rgba(0,0,0,0.2)] mx-auto mb-2"
    />
    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">{title}</p>
    <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-[460px] mx-auto leading-relaxed">
      {detail}
    </p>
    {action && (
      <button
        type="button"
        onClick={action.onClick}
        className="mt-3 px-3 py-1.5 text-[11.5px] font-medium rounded-lg border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
      >
        {action.label}
      </button>
    )}
  </div>
);

export default ResourceConnectivityTab;
