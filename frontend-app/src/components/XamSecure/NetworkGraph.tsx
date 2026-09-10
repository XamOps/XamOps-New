import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type {
  AssetRelationships, ConfigGraph, TopologyEdge, TopologyNode,
} from '../../types/xamsecure';

/**
 * Network configuration, grouped VPC -> subnet -> resource, with search, filtering and a
 * click-through detail panel.
 *
 * Deliberately NOT a force-directed node-link diagram. Measured on the real estate this carries
 * 1,384 nodes and 1,853 edges across two accounts, one VPC alone holding 446 children; as a spring
 * layout that is an unreadable hairball. Every edge here is containment (in-vpc, in-subnet,
 * attached-to), so nesting shows the same structure and stays legible at that size.
 */

const TYPE_META: Record<string, { label: string; icon: string }> = {
  aws_vpc: { label: 'VPC', icon: 'mdi:cloud-outline' },
  aws_subnet: { label: 'Subnet', icon: 'mdi:vector-square' },
  aws_security_group: { label: 'Security Group', icon: 'mdi:shield-outline' },
  aws_elastic_network_interface: { label: 'Network Interface', icon: 'mdi:ethernet' },
  aws_ec2_instance: { label: 'EC2 Instance', icon: 'mdi:server' },
  aws_application_load_balancer: { label: 'Load Balancer', icon: 'mdi:scale-balance' },
  aws_ec2_classic_load_balancer: { label: 'Classic LB', icon: 'mdi:scale-balance' },
  aws_nat_gateway: { label: 'NAT Gateway', icon: 'mdi:transit-connection-variant' },
  aws_ec2_elastic_ip: { label: 'Elastic IP', icon: 'mdi:ip-outline' },
};
const typeLabel = (t: string | null | undefined) =>
  (t && TYPE_META[t]?.label) ?? (t ?? 'Resource').replace(/^aws_/, '').replace(/_/g, ' ');
const typeIcon = (t: string | null | undefined) =>
  (t && TYPE_META[t]?.icon) ?? 'mdi:cube-outline';

const EDGE_LABEL: Record<TopologyEdge['kind'], string> = {
  'in-vpc': 'In VPC',
  'in-subnet': 'In subnet',
  'attached-to': 'Attached to',
};

interface Props {
  graph: ConfigGraph;
  /**
   * Provider-recorded references for one asset, loaded on demand. Keyed by the Upwind asset id,
   * which every non-synthetic node carries — unlike the cloud resource id, which the graph holds
   * in a canonical short form that will not match the catalog's ARN.
   */
  loadRelationships?: (assetId: string) => Promise<AssetRelationships | null>;
}

/** How many VPCs start expanded. The rest collapse so a large estate stays scannable. */
const INITIAL_EXPANDED = 2;

const NetworkGraph: React.FC<Props> = ({ graph, loadRelationships }) => {
  const [query, setQuery] = useState('');
  const [exposedOnly, setExposedOnly] = useState(false);
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const typeCounts = useMemo(() => {
    const m = new Map<string, number>();
    graph.nodes.forEach((n) => {
      if (!n.type || n.type === 'aws_vpc' || n.type === 'aws_subnet') return;
      m.set(n.type, (m.get(n.type) ?? 0) + 1);
    });
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }, [graph.nodes]);

  // Filtering keeps containers whose children still match, so a VPC does not vanish while the
  // instance you searched for is inside it.
  const filtered = useMemo(
    () => filterGraph(graph, query.trim().toLowerCase(), exposedOnly, typeFilter),
    [graph, query, exposedOnly, typeFilter]);

  const model = useMemo(() => buildHierarchy(filtered), [filtered]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  // Re-seed which VPCs are open when the filtered set changes: after a search the matching VPCs
  // should be open, otherwise the user searches and sees only collapsed headers.
  useEffect(() => {
    const narrowed = query.trim() !== '' || exposedOnly || typeFilter !== null;
    setExpanded(new Set(model.vpcs
      .slice(0, narrowed ? model.vpcs.length : INITIAL_EXPANDED)
      .map((v) => v.node.id)));
  }, [query, exposedOnly, typeFilter, model.vpcs]);

  const selected = useMemo(
    () => graph.nodes.find((n) => n.id === selectedId) ?? null, [graph.nodes, selectedId]);

  const toggle = (id: string) => setExpanded((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const clearFilters = () => { setQuery(''); setExposedOnly(false); setTypeFilter(null); };
  const isFiltered = query.trim() !== '' || exposedOnly || typeFilter !== null;

  if (graph.nodes.length === 0) {
    return (
      <p className="py-12 text-center text-xs text-gray-400">
        No network assets for the selected account.
      </p>
    );
  }

  return (
    <div className="space-y-4">
      {graph.truncated && (
        <div className="flex items-start gap-2 px-3 py-2 bg-amber-50 border border-amber-200 rounded-lg">
          <Icon icon="mdi:alert-outline" width={16} className="text-amber-500 mt-0.5 shrink-0" />
          <p className="text-[11px] text-amber-700 leading-relaxed">
            This view is still building. Counts below are a lower bound — reload in a few minutes
            for the full picture.
          </p>
        </div>
      )}

      {/* Controls */}
      <div className="flex flex-wrap items-center gap-2">
        <label className="relative flex-1 min-w-[14rem]">
          <Icon icon="mdi:magnify" width={15}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by name, type or resource id"
            aria-label="Search network resources"
            className="w-full rounded-lg border border-gray-200 py-1.5 pl-8 pr-3 text-xs
                       placeholder:text-gray-400 focus:border-gray-400 focus:outline-none"
          />
        </label>
        <button
          type="button"
          onClick={() => setExposedOnly((v) => !v)}
          aria-pressed={exposedOnly}
          className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px]
                      font-medium transition-colors ${
            exposedOnly
              ? 'border-red-200 bg-red-50 text-red-700'
              : 'border-gray-200 text-gray-600 hover:bg-gray-50'}`}
        >
          <Icon icon="mdi:earth" width={13} />
          Internet exposed
          {model.exposedCount > 0 && (
            <span className="font-bold">{model.exposedCount}</span>
          )}
        </button>
        {isFiltered && (
          <button type="button" onClick={clearFilters}
                  className="text-[11px] font-medium text-gray-500 hover:text-gray-800">
            Clear
          </button>
        )}
      </div>

      {/* Type filter */}
      <div className="flex flex-wrap gap-1.5">
        {typeCounts.map(([t, count]) => (
          <button
            key={t}
            type="button"
            onClick={() => setTypeFilter(typeFilter === t ? null : t)}
            aria-pressed={typeFilter === t}
            className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1
                        text-[10px] transition-colors ${
              typeFilter === t
                ? 'border-gray-900 bg-gray-900 text-white'
                : 'border-gray-200 text-gray-600 hover:bg-gray-50'}`}
          >
            <Icon icon={typeIcon(t)} width={11} />
            {typeLabel(t)}
            <span className="font-semibold opacity-70">{count}</span>
          </button>
        ))}
      </div>

      <div className="flex flex-wrap gap-2">
        <Stat label="VPCs" value={model.vpcs.length} />
        <Stat label="Subnets" value={model.subnetCount} />
        <Stat label="Resources" value={model.resourceCount} />
        <Stat label="Connections" value={filtered.edges.length} />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        {/* Tree */}
        <div className={`space-y-3 ${selected ? 'xl:col-span-2' : 'xl:col-span-3'}`}>
          {model.vpcs.length === 0 && model.unplaced.length === 0 ? (
            <p className="py-10 text-center text-xs text-gray-400">
              Nothing matches those filters.
            </p>
          ) : model.vpcs.map((vpc) => {
            const open = expanded.has(vpc.node.id);
            return (
              <div key={vpc.node.id} className="border border-gray-200 rounded-xl overflow-hidden">
                <button
                  type="button"
                  onClick={() => toggle(vpc.node.id)}
                  aria-expanded={open}
                  className="w-full flex items-center gap-3 px-4 py-3 bg-gray-50 hover:bg-gray-100
                             transition-colors text-left"
                >
                  <Icon icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={18}
                        className="text-gray-400 shrink-0" />
                  <Icon icon={typeIcon(vpc.node.type)} width={18} className="text-gray-500 shrink-0" />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-gray-800 truncate">
                        {vpc.node.name}
                      </span>
                      {vpc.node.synthetic && <InferredBadge />}
                    </span>
                    <span className="flex items-center gap-2 text-[10px] text-gray-500 mt-0.5">
                      {vpc.node.cidrBlock && <span className="font-mono">{vpc.node.cidrBlock}</span>}
                      {vpc.node.region && <span>{vpc.node.region}</span>}
                      <span>{vpc.subnets.length} subnet(s)</span>
                      <span>{vpc.total} resource(s)</span>
                    </span>
                  </span>
                  {vpc.exposed > 0 && (
                    <span className="shrink-0 rounded-full bg-red-50 px-2 py-0.5 text-[10px]
                                     font-semibold text-red-600">
                      {vpc.exposed} exposed
                    </span>
                  )}
                </button>

                {open && (
                  <div className="p-4 space-y-4">
                    {vpc.subnets.map((sub) => (
                      <div key={sub.node.id} className="border-l-2 border-gray-100 pl-3">
                        <p className="flex items-center gap-2 mb-2">
                          <Icon icon={typeIcon('aws_subnet')} width={14} className="text-gray-400" />
                          <span className="font-mono text-xs font-medium text-gray-600">
                            {sub.node.name}
                          </span>
                          {sub.node.synthetic && <InferredBadge />}
                          <span className="text-[10px] text-gray-400">
                            {sub.members.length} resource(s)
                          </span>
                        </p>
                        <NodeChips nodes={sub.members} selectedId={selectedId}
                                   onSelect={setSelectedId} />
                      </div>
                    ))}

                    {vpc.direct.length > 0 && (
                      <div>
                        <p className="mb-2 text-[10px] font-semibold uppercase tracking-wide text-gray-400">
                          In this VPC, no subnet recorded
                        </p>
                        <NodeChips nodes={vpc.direct} selectedId={selectedId}
                                   onSelect={setSelectedId} />
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}

          {model.unplaced.length > 0 && (
            <div className="rounded-xl border border-dashed border-gray-300 p-4">
              <p className="mb-1 flex items-center gap-2">
                <Icon icon="mdi:help-circle-outline" width={15} className="text-gray-400" />
                <span className="text-xs font-semibold text-gray-600">
                  Not placed ({model.unplaced.length})
                </span>
              </p>
              <p className="mb-3 text-[10px] leading-relaxed text-gray-400">
                These resources have no recorded network placement, so they are listed separately
                rather than hidden.
              </p>
              <NodeChips nodes={model.unplaced} selectedId={selectedId} onSelect={setSelectedId} />
            </div>
          )}
        </div>

        {/* Detail */}
        {selected && (
          <NodeDetail
            node={selected}
            graph={graph}
            onClose={() => setSelectedId(null)}
            onSelect={setSelectedId}
            loadRelationships={loadRelationships}
          />
        )}
      </div>
    </div>
  );
};

/** Side panel for one node: its own attributes, its graph neighbours, its provider references. */
const NodeDetail: React.FC<{
  node: TopologyNode;
  graph: ConfigGraph;
  onClose: () => void;
  onSelect: (id: string) => void;
  loadRelationships?: (assetId: string) => Promise<AssetRelationships | null>;
}> = ({ node, graph, onClose, onSelect, loadRelationships }) => {
  const [rels, setRels] = useState<AssetRelationships | null>(null);
  const [relState, setRelState] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');

  const byId = useMemo(
    () => new Map(graph.nodes.map((n) => [n.id, n])), [graph.nodes]);

  // Both directions: what this node sits inside, and what sits inside/attaches to it.
  const outgoing = graph.edges.filter((e) => e.source === node.id);
  const incoming = graph.edges.filter((e) => e.target === node.id);

  // Synthetic nodes (subnets, out-of-scope VPCs) have no Upwind asset id, so there is nothing to
  // fetch references for.
  const canLoadRels = Boolean(loadRelationships) && !node.synthetic && node.id.startsWith('uwr-');

  useEffect(() => { setRels(null); setRelState('idle'); }, [node.id]);

  const fetchRels = async () => {
    if (!loadRelationships) return;
    setRelState('loading');
    try {
      setRels(await loadRelationships(node.id));
      setRelState('done');
    } catch {
      setRelState('error');
    }
  };

  return (
    <aside className="xl:col-span-1 h-fit rounded-xl border border-gray-200 bg-white">
      <div className="flex items-start justify-between gap-2 border-b border-gray-100 px-4 py-3">
        <span className="min-w-0">
          <span className="flex items-center gap-2">
            <Icon icon={typeIcon(node.type)} width={16} className="shrink-0 text-gray-500" />
            <span className="truncate text-sm font-semibold text-gray-900">{node.name}</span>
          </span>
          <span className="mt-0.5 block text-[10px] text-gray-500">{typeLabel(node.type)}</span>
        </span>
        <button type="button" onClick={onClose} aria-label="Close details"
                className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-700">
          <Icon icon="mdi:close" width={15} />
        </button>
      </div>

      <div className="space-y-3 px-4 py-3">
        {node.exposure && (
          <p className="flex items-center gap-1.5 rounded-lg bg-red-50 px-2.5 py-1.5
                        text-[11px] font-semibold text-red-700">
            <Icon icon="mdi:earth" width={13} />
            Internet exposure: {node.exposure}
          </p>
        )}
        {node.synthetic && (
          <p className="rounded-lg bg-gray-50 px-2.5 py-1.5 text-[10px] leading-relaxed text-gray-500">
            Inferred from a reference on another resource rather than inventoried directly.
          </p>
        )}

        <dl className="space-y-0">
          <DetailRow label="Resource id" value={node.resourceId} mono />
          <DetailRow label="Region" value={node.region} />
          <DetailRow label="Account" value={node.cloudAccountId} mono />
          <DetailRow label="CIDR" value={node.cidrBlock} mono />
          <DetailRow label="Category" value={node.subCategory?.replace(/_/g, ' ')} />
        </dl>

        <NeighbourList title="Sits inside" edges={outgoing} byId={byId} pick="target"
                       onSelect={onSelect} />
        <NeighbourList title="Contains / attached" edges={incoming} byId={byId} pick="source"
                       onSelect={onSelect} />

        {canLoadRels && (
          <div className="border-t border-gray-100 pt-3">
            {relState === 'idle' && (
              <button type="button" onClick={fetchRels}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-gray-200
                                 px-2.5 py-1.5 text-[11px] font-medium text-gray-700 hover:bg-gray-50">
                <Icon icon="mdi:graph-outline" width={13} />
                Load provider references
              </button>
            )}
            {relState === 'loading' && (
              <p className="text-[11px] text-gray-400">Loading references…</p>
            )}
            {relState === 'error' && (
              <p className="text-[11px] text-gray-500">References could not be loaded.</p>
            )}
            {relState === 'done' && (
              (rels?.relationships.length ?? 0) === 0 ? (
                <p className="text-[11px] text-gray-500">
                  This resource references no others.
                </p>
              ) : (
                <div className="space-y-2">
                  {rels!.relationships.map((r) => (
                    <div key={r.field}>
                      <p className="text-[10px] font-semibold uppercase tracking-wide text-gray-400">
                        {r.label} · {r.count}
                        {r.relatedType && (
                          <span className="ml-1 font-normal normal-case text-gray-400">
                            → {typeLabel(r.relatedType)}
                          </span>
                        )}
                      </p>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {r.references.slice(0, 8).map((ref) => (
                          <span key={ref}
                                className="rounded border border-gray-200 px-1.5 py-0.5
                                           font-mono text-[9px] text-gray-600">
                            {ref}
                          </span>
                        ))}
                        {r.references.length > 8 && (
                          <span className="text-[9px] text-gray-400">
                            +{r.references.length - 8}
                          </span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )
            )}
          </div>
        )}
      </div>
    </aside>
  );
};

const DetailRow: React.FC<{ label: string; value?: string | null; mono?: boolean }> =
  ({ label, value, mono }) => {
    if (!value) return null;
    return (
      <div className="flex items-start gap-2 border-b border-gray-50 py-1.5 last:border-0">
        <dt className="w-24 shrink-0 text-[10px] text-gray-500">{label}</dt>
        <dd className={`min-w-0 flex-1 break-words text-[11px] text-gray-800 ${
          mono ? 'font-mono text-[10px]' : ''}`}>{value}</dd>
      </div>
    );
  };

const NeighbourList: React.FC<{
  title: string;
  edges: TopologyEdge[];
  byId: Map<string, TopologyNode>;
  pick: 'source' | 'target';
  onSelect: (id: string) => void;
}> = ({ title, edges, byId, pick, onSelect }) => {
  const [showAll, setShowAll] = useState(false);
  if (edges.length === 0) return null;
  const LIMIT = 8;
  const shown = showAll ? edges : edges.slice(0, LIMIT);
  return (
    <div>
      <p className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-gray-400">
        {title} ({edges.length})
      </p>
      <div className="space-y-1">
        {shown.map((e) => {
          const other = byId.get(e[pick]);
          if (!other) return null;
          return (
            <button
              key={`${e.source}-${e.target}-${e.kind}`}
              type="button"
              onClick={() => onSelect(other.id)}
              className="flex w-full items-center gap-1.5 rounded px-1.5 py-1 text-left
                         hover:bg-gray-50"
            >
              <Icon icon={typeIcon(other.type)} width={11}
                    className="shrink-0 text-gray-400" />
              <span className="min-w-0 flex-1 truncate text-[10px] text-gray-700">
                {other.name}
              </span>
              <span className="shrink-0 text-[9px] text-gray-400">{EDGE_LABEL[e.kind]}</span>
            </button>
          );
        })}
      </div>
      {edges.length > LIMIT && (
        <button type="button" onClick={() => setShowAll((s) => !s)}
                className="mt-1 text-[10px] font-medium text-blue-600 hover:text-blue-700">
          {showAll ? 'Show fewer' : `Show all ${edges.length}`}
        </button>
      )}
    </div>
  );
};

const InferredBadge: React.FC = () => (
  <span title="Inferred from a reference on another resource, not inventoried directly"
        className="shrink-0 rounded bg-gray-100 px-1.5 py-px text-[9px] font-medium text-gray-500">
    inferred
  </span>
);

const Stat: React.FC<{ label: string; value: number }> = ({ label, value }) => (
  <span className="rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-[11px] text-gray-600">
    <span className="font-bold">{value}</span> {label}
  </span>
);

const NodeChips: React.FC<{
  nodes: TopologyNode[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}> = ({ nodes, selectedId, onSelect }) => {
  const [showAll, setShowAll] = useState(false);
  // A subnet measured 75 members; rendering every one by default buries the rest of the page.
  const LIMIT = 24;
  const shown = showAll ? nodes : nodes.slice(0, LIMIT);
  if (nodes.length === 0) {
    return <p className="text-[10px] italic text-gray-400">Nothing attached.</p>;
  }
  return (
    <>
      <div className="flex flex-wrap gap-1.5">
        {shown.map((n) => {
          const active = n.id === selectedId;
          return (
            <button
              key={n.id}
              type="button"
              onClick={() => onSelect(n.id)}
              aria-pressed={active}
              title={`${typeLabel(n.type)}${n.resourceId ? ` · ${n.resourceId}` : ''}`}
              className={`inline-flex max-w-[15rem] items-center gap-1.5 rounded-md border
                          px-2 py-1 text-[10px] transition-colors ${
                active
                  ? 'border-gray-900 bg-gray-900 text-white'
                  : n.exposure
                    ? 'border-red-200 bg-red-50 text-red-700 hover:border-red-300'
                    : 'border-gray-200 bg-white text-gray-600 hover:border-gray-400'}`}
            >
              <Icon icon={typeIcon(n.type)} width={12} className="shrink-0 opacity-70" />
              <span className="truncate">{n.name}</span>
              {n.exposure && (
                <span className="inline-flex shrink-0" title={`Internet exposure: ${n.exposure}`}>
                  <Icon icon="mdi:earth" width={11} />
                </span>
              )}
            </button>
          );
        })}
      </div>
      {nodes.length > LIMIT && (
        <button type="button" onClick={() => setShowAll((s) => !s)}
                className="mt-2 text-[10px] font-medium text-blue-600 hover:text-blue-700">
          {showAll ? 'Show fewer' : `Show all ${nodes.length}`}
        </button>
      )}
    </>
  );
};

/**
 * Narrows the graph to nodes matching the search and filters, then keeps the containers those
 * nodes live in.
 *
 * Dropping unmatched containers would hide the answer: searching for an instance would remove the
 * VPC and subnet that give it context, leaving a chip with nowhere to sit.
 */
function filterGraph(
  graph: ConfigGraph, query: string, exposedOnly: boolean, typeFilter: string | null,
): ConfigGraph {
  if (!query && !exposedOnly && !typeFilter) return graph;

  const isContainer = (n: TopologyNode) => n.type === 'aws_vpc' || n.type === 'aws_subnet';
  const matches = (n: TopologyNode) => {
    if (exposedOnly && !n.exposure) return false;
    if (typeFilter && n.type !== typeFilter) return false;
    if (!query) return true;
    return [n.name, n.type, n.resourceId, n.region, n.subCategory]
      .some((v) => v?.toLowerCase().includes(query));
  };

  const keep = new Set<string>();
  graph.nodes.forEach((n) => { if (!isContainer(n) && matches(n)) keep.add(n.id); });

  // Pull in the containers of everything kept, following containment upwards.
  const parents = new Map<string, string[]>();
  graph.edges.forEach((e) => {
    if (e.kind === 'in-vpc' || e.kind === 'in-subnet' || e.kind === 'attached-to') {
      parents.set(e.source, [...(parents.get(e.source) ?? []), e.target]);
    }
  });
  const walk = (id: string, depth = 0) => {
    if (depth > 6) return;
    (parents.get(id) ?? []).forEach((p) => {
      if (!keep.has(p)) { keep.add(p); walk(p, depth + 1); }
    });
  };
  [...keep].forEach((id) => walk(id));

  // A container matching the query on its own name keeps its children, so searching a VPC name
  // shows that VPC's contents rather than an empty shell.
  const containerHits = graph.nodes.filter((n) => isContainer(n) && matches(n)).map((n) => n.id);
  if (containerHits.length > 0) {
    const hits = new Set(containerHits);
    hits.forEach((h) => keep.add(h));
    graph.edges.forEach((e) => { if (hits.has(e.target)) keep.add(e.source); });
  }

  return {
    ...graph,
    nodes: graph.nodes.filter((n) => keep.has(n.id)),
    edges: graph.edges.filter((e) => keep.has(e.source) && keep.has(e.target)),
  };
}

interface SubnetGroup { node: TopologyNode; members: TopologyNode[] }
interface VpcGroup {
  node: TopologyNode;
  subnets: SubnetGroup[];
  /** In the VPC but with no subnet recorded — security groups, mostly. */
  direct: TopologyNode[];
  total: number;
  exposed: number;
}

/**
 * Folds the flat node/edge lists into VPC -> subnet -> resource.
 *
 * Resources reach a subnet in two hops: an instance attaches to a network interface
 * (`attached-to`) and the interface carries the subnet (`in-subnet`). So an asset resolves to its
 * own subnet first, then to the subnet of whatever it is attached to.
 */
function buildHierarchy(graph: ConfigGraph) {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const vpcOf = new Map<string, string>();
  const subnetOf = new Map<string, string>();
  const attachedTo = new Map<string, string>();

  for (const e of graph.edges) {
    if (e.kind === 'in-vpc') vpcOf.set(e.source, e.target);
    else if (e.kind === 'in-subnet') subnetOf.set(e.source, e.target);
    else if (e.kind === 'attached-to') attachedTo.set(e.source, e.target);
  }

  const resolveSubnet = (id: string): string | undefined =>
    subnetOf.get(id) ?? (attachedTo.get(id) ? subnetOf.get(attachedTo.get(id)!) : undefined);
  const resolveVpc = (id: string): string | undefined =>
    vpcOf.get(id) ?? (attachedTo.get(id) ? vpcOf.get(attachedTo.get(id)!) : undefined)
    ?? (() => { const s = resolveSubnet(id); return s ? vpcOf.get(s) : undefined; })();

  const vpcs = new Map<string, VpcGroup>();
  const ensureVpc = (id: string): VpcGroup | null => {
    const node = byId.get(id);
    if (!node) return null;
    let g = vpcs.get(id);
    if (!g) { g = { node, subnets: [], direct: [], total: 0, exposed: 0 }; vpcs.set(id, g); }
    return g;
  };
  for (const n of graph.nodes) if (n.type === 'aws_vpc') ensureVpc(n.id);

  const subnetGroups = new Map<string, SubnetGroup>();
  const unplaced: TopologyNode[] = [];
  let subnetCount = 0;
  let resourceCount = 0;
  let exposedCount = 0;

  for (const n of graph.nodes) {
    if (n.exposure) exposedCount++;
    if (n.type === 'aws_vpc' || n.type === 'aws_subnet') continue;

    resourceCount++;
    const subnetId = resolveSubnet(n.id);
    const vpcId = resolveVpc(n.id);

    if (subnetId && byId.has(subnetId)) {
      let sg = subnetGroups.get(subnetId);
      if (!sg) {
        sg = { node: byId.get(subnetId)!, members: [] };
        subnetGroups.set(subnetId, sg);
        subnetCount++;
        const parent = vpcOf.get(subnetId);
        if (parent) ensureVpc(parent)?.subnets.push(sg);
      }
      sg.members.push(n);
      const owner = vpcId ?? vpcOf.get(subnetId);
      if (owner) {
        const g = ensureVpc(owner);
        if (g) { g.total++; if (n.exposure) g.exposed++; }
      }
      continue;
    }

    if (vpcId && byId.has(vpcId)) {
      const g = ensureVpc(vpcId);
      if (g) { g.direct.push(n); g.total++; if (n.exposure) g.exposed++; }
      continue;
    }
    unplaced.push(n);
  }

  // Subnets holding nothing still belong to their VPC; surface them so the count is truthful.
  for (const n of graph.nodes) {
    if (n.type !== 'aws_subnet' || subnetGroups.has(n.id)) continue;
    const parent = vpcOf.get(n.id);
    const g = parent ? ensureVpc(parent) : null;
    if (g) { g.subnets.push({ node: n, members: [] }); subnetCount++; }
  }

  const ordered = [...vpcs.values()]
    .filter((g) => g.total > 0 || g.subnets.length > 0)
    .sort((a, b) => b.total - a.total || a.node.name.localeCompare(b.node.name));
  ordered.forEach((g) => g.subnets.sort(
    (a, b) => b.members.length - a.members.length || a.node.name.localeCompare(b.node.name)));

  return { vpcs: ordered, unplaced, subnetCount, resourceCount, exposedCount };
}

export default NetworkGraph;
