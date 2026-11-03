import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
// @ts-ignore — cytoscape-dagre ships no types (repo convention, see pages/CloudMapPage.tsx)
import cytoscapeDagre from 'cytoscape-dagre';
import { fetchConsoleAccessGraph, type AccessGraphDocDto } from '../../../hooks/useDbOpsData';
import { buildAccessGraphElements, accessHiddenLabel } from './accessGraphElements';

// Register dagre once at module load (as pages/CloudMapPage.tsx does). This module is imported
// lazily (React.lazy) by the drawer, so the cytoscape chunk stays off the main DbOps route.
cytoscape.use(cytoscapeDagre);

interface SelectedNode { label: string; kind: string; data: Record<string, unknown>; }

/**
 * Pillar B — the access graph tab. Left-to-right dagre layout, Datastore → Privilege set → Role →
 * Principal. Colour only marks over-privileged / PUBLIC severity; the rest is grey chrome.
 */
export default function AccessGraph({ accountId }: { accountId: string }) {
  const [doc, setDoc] = useState<AccessGraphDocDto | null>(null);
  const [status, setStatus] = useState<'loading' | 'ok' | 'no-access' | 'error'>('loading');
  const [selected, setSelected] = useState<SelectedNode | null>(null);
  const [search, setSearch] = useState('');
  const [zoomPct, setZoomPct] = useState(100);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);

  const load = useCallback(async () => {
    setStatus('loading');
    const res = await fetchConsoleAccessGraph(accountId);
    if (!res.ok) { setStatus(res.status === 403 ? 'no-access' : 'error'); return; }
    setDoc(res.data);
    setStatus('ok');
  }, [accountId]);

  useEffect(() => { load(); }, [load]);

  const elements = useMemo(() => (doc ? buildAccessGraphElements(doc) : []), [doc]);

  useEffect(() => {
    if (status !== 'ok' || !containerRef.current || elements.length === 0) return;
    const cy = cytoscape({
      container: containerRef.current,
      elements: elements as cytoscape.ElementDefinition[],
      style: ACCESS_STYLE as any, // cytoscape style typing is strict; repo casts (CloudMapPage.tsx)
      layout: { name: 'dagre', rankDir: 'LR', nodeSep: 24, rankSep: 90 } as unknown as cytoscape.LayoutOptions,
      wheelSensitivity: 0.2,
    });
    cyRef.current = cy;
    cy.on('tap', 'node', (e) => {
      const d = e.target.data() as Record<string, unknown>;
      setSelected({ label: String(d.label), kind: String(d.kind), data: d });
      cy.elements().removeClass('dim');
      const nbr = e.target.closedNeighborhood();
      cy.elements().not(nbr).addClass('dim');
    });
    cy.on('tap', (e) => { if (e.target === cy) { cy.elements().removeClass('dim'); setSelected(null); } });
    cy.on('zoom', () => setZoomPct(Math.round(cy.zoom() * 100)));
    setZoomPct(Math.round(cy.zoom() * 100));
    return () => { cy.destroy(); cyRef.current = null; };
  }, [status, elements]);

  // Search: dim non-matching principal/role nodes and their neighbourhoods.
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.elements().removeClass('dim');
    const q = search.trim().toLowerCase();
    if (!q) return;
    const matches = cy.nodes().filter((n) => String(n.data('label') ?? '').toLowerCase().includes(q));
    if (matches.length === 0) return;
    const keep = matches.closedNeighborhood();
    cy.elements().not(keep).addClass('dim');
  }, [search]);

  const fit = () => cyRef.current?.fit(undefined, 30);
  const zoomBy = (f: number) => { const cy = cyRef.current; if (cy) cy.zoom({ level: cy.zoom() * f, renderedPosition: { x: (cy.width() || 0) / 2, y: (cy.height() || 0) / 2 } }); };

  if (status === 'loading') return <Centered>Loading access graph…</Centered>;
  if (status === 'no-access') return <Centered>You don’t have access to this database’s access graph.</Centered>;
  if (status === 'error' || !doc) return <ErrorState onRetry={load} />;
  if (doc.nodes.length === 0) return <Centered>No grants collected yet for this datastore.</Centered>;

  const hidden = accessHiddenLabel(doc.meta);

  return (
    <div className="flex flex-col gap-2">
      <Toolbar search={search} setSearch={setSearch} zoomPct={zoomPct} onFit={fit} onZoomIn={() => zoomBy(1.2)} onZoomOut={() => zoomBy(0.83)} placeholder="Search roles / principals…" />
      {hidden && <Banner>{hidden}</Banner>}
      <div className="relative border border-[rgba(0,0,0,0.08)] rounded-md" style={{ height: 460 }}>
        <div ref={containerRef} className="absolute inset-0" />
        {selected && <DetailPanel node={selected} onClose={() => setSelected(null)} />}
      </div>
    </div>
  );
}

function DetailPanel({ node, onClose }: { node: SelectedNode; onClose: () => void }) {
  const d = node.data;
  return (
    <div className="absolute top-2 right-2 w-64 max-h-[92%] overflow-y-auto bg-white border border-[rgba(0,0,0,0.12)] rounded-md shadow-sm p-3 text-xs">
      <div className="flex items-center justify-between mb-2">
        <span className="font-semibold text-[rgba(0,0,0,0.85)] truncate">{node.label}</span>
        <button onClick={onClose} className="text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]">✕</button>
      </div>
      <div className="text-[rgba(0,0,0,0.5)] uppercase tracking-wide text-[10px] mb-2">{node.kind}</div>
      <dl className="space-y-1">
        {detailRows(d).map(([k, v]) => (
          <div key={k} className="flex justify-between gap-2">
            <dt className="text-[rgba(0,0,0,0.45)]">{k}</dt>
            <dd className="text-[rgba(0,0,0,0.8)] text-right break-words">{v}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function detailRows(d: Record<string, unknown>): Array<[string, string]> {
  const rows: Array<[string, string]> = [];
  const push = (k: string, v: unknown) => { if (v !== undefined && v !== null && v !== '' && !(Array.isArray(v) && v.length === 0)) rows.push([k, Array.isArray(v) ? v.join(', ') : String(v)]); };
  push('Host', d.dbHost); push('Roles', d.roleCount); push('Principals', d.principalCount);
  push('Class', d.privilegeClass); push('Objects', d.objectCount); push('PUBLIC', d.hasPublic);
  push('Group', d.isGroup); push('Member of', d.memberOf); push('Grants', d.grantsClasses);
  push('Can login', d.canLogin); push('Superuser', d.isSuperuser); push('Bypass RLS', d.bypassRls);
  push('Conn limit', d.connLimit); push('Inherited from', d.inheritedFrom);
  push('Effective', d.effectivePrivilegeClasses); push('Flags', d.flags);
  return rows;
}

function Toolbar(props: { search: string; setSearch: (s: string) => void; zoomPct: number; onFit: () => void; onZoomIn: () => void; onZoomOut: () => void; placeholder: string }) {
  return (
    <div className="flex items-center gap-2 flex-wrap">
      <input
        value={props.search}
        onChange={(e) => props.setSearch(e.target.value)}
        placeholder={props.placeholder}
        className="flex-1 min-w-[160px] border border-[rgba(0,0,0,0.12)] rounded-md px-2 py-1 text-xs"
      />
      <div className="flex items-center gap-1">
        <IconBtn onClick={props.onZoomOut} label="−" />
        <span className="text-[11px] text-[rgba(0,0,0,0.5)] tabular-nums w-10 text-center">{props.zoomPct}%</span>
        <IconBtn onClick={props.onZoomIn} label="+" />
        <button onClick={props.onFit} className="ml-1 px-2 py-1 rounded-md border border-[rgba(0,0,0,0.12)] text-[11px] hover:bg-[rgba(0,0,0,0.03)]">Fit</button>
      </div>
    </div>
  );
}
function IconBtn({ onClick, label }: { onClick: () => void; label: string }) {
  return <button onClick={onClick} className="w-6 h-6 rounded-md border border-[rgba(0,0,0,0.12)] text-xs leading-none hover:bg-[rgba(0,0,0,0.03)]">{label}</button>;
}
function Banner({ children }: { children: React.ReactNode }) {
  return <div className="text-[11px] text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.08)] rounded-md px-2.5 py-1.5">{children}</div>;
}
function Centered({ children }: { children: React.ReactNode }) {
  return <div className="flex items-center justify-center py-16 text-sm text-[rgba(0,0,0,0.5)]">{children}</div>;
}
function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 text-sm text-[rgba(0,0,0,0.55)]">
      <span>Couldn’t load the access graph.</span>
      <button onClick={onRetry} className="px-3 py-1.5 rounded-md border border-[rgba(0,0,0,0.12)] text-xs hover:bg-[rgba(0,0,0,0.03)]">Retry</button>
    </div>
  );
}

// Grey chrome; colour only for over-privileged (critical=red) / PUBLIC-or-bypass (warning=amber).
const ACCESS_STYLE = [
  { selector: 'node', style: {
      'background-color': '#f4f4f5', 'border-color': 'rgba(0,0,0,0.25)', 'border-width': 1,
      label: 'data(label)', 'font-size': 10, color: 'rgba(0,0,0,0.75)',
      'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'wrap', 'text-max-width': '110px',
      width: 'label', height: 24, padding: '6px', shape: 'round-rectangle',
  } },
  { selector: 'node.datastore', style: { 'background-color': '#18181b', color: '#ffffff', 'border-width': 0, 'font-weight': 'bold' } },
  { selector: 'node.privilege', style: { 'background-color': '#e4e4e7', shape: 'round-rectangle' } },
  { selector: 'node.principal', style: { 'background-color': '#ffffff' } },
  { selector: 'node.warning', style: { 'border-color': '#f59e0b', 'border-width': 2 } },
  { selector: 'node.critical', style: { 'border-color': '#ef4444', 'border-width': 2 } },
  { selector: 'edge', style: {
      width: 1, 'line-color': 'rgba(0,0,0,0.2)', 'target-arrow-color': 'rgba(0,0,0,0.3)',
      'target-arrow-shape': 'triangle', 'curve-style': 'bezier', 'arrow-scale': 0.7,
  } },
  { selector: 'edge.membership', style: { 'line-style': 'dashed', 'line-color': 'rgba(0,0,0,0.3)' } },
  { selector: '.dim', style: { opacity: 0.15 } },
];
