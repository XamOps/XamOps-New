import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
// @ts-ignore — cytoscape-fcose ships no types (repo convention, see pages/CloudMapPage.tsx)
import cytoscapeFcose from 'cytoscape-fcose';
// @ts-ignore — cytoscape-dagre ships no types
import cytoscapeDagre from 'cytoscape-dagre';
import { fetchConsoleTableGraph, type FkGraphDocDto, type FkNode } from '../../../hooks/useDbOpsData';
import { buildFkGraphElements, fkSparseMessage, fkHiddenLabel } from './fkGraphElements';

// Register once at module load; this module is lazy-imported so the chunk stays off the main route.
cytoscape.use(cytoscapeFcose);
cytoscape.use(cytoscapeDagre);

/**
 * Pillar C — the FK table-graph tab. Nodes sized by catalog sizeBytes, activityScore as a
 * border-weight badge, directed child→parent edges. fcose (organic) by default with a dagre TB
 * option. Honest sparse/empty message and a counted "N relationships hidden" banner.
 */
export default function FkGraph({ accountId }: { accountId: string }) {
  const [doc, setDoc] = useState<FkGraphDocDto | null>(null);
  const [status, setStatus] = useState<'loading' | 'ok' | 'no-access' | 'error'>('loading');
  const [selected, setSelected] = useState<FkNode | null>(null);
  const [search, setSearch] = useState('');
  const [layout, setLayout] = useState<'fcose' | 'dagre'>('fcose');
  const [zoomPct, setZoomPct] = useState(100);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);

  const load = useCallback(async () => {
    setStatus('loading');
    const res = await fetchConsoleTableGraph(accountId);
    if (!res.ok) { setStatus(res.status === 403 ? 'no-access' : 'error'); return; }
    setDoc(res.data);
    setStatus('ok');
  }, [accountId]);

  useEffect(() => { load(); }, [load]);

  const elements = useMemo(() => {
    if (!doc) return [];
    return buildFkGraphElements(doc).map((el) => {
      if (el.data.source) return el; // edge
      const size = Number(el.data.sizeBytes ?? 0);
      const act = el.data.activityScore as number | null;
      return {
        ...el,
        data: { ...el.data, diam: 24 + Math.log10(Math.max(size, 1)) * 6, border: act != null ? 1 + act * 4 : 1 },
      };
    });
  }, [doc]);

  useEffect(() => {
    if (status !== 'ok' || !containerRef.current || elements.length === 0) return;
    const cy = cytoscape({
      container: containerRef.current,
      elements: elements as cytoscape.ElementDefinition[],
      style: FK_STYLE as any, // cytoscape style typing is strict; repo casts (CloudMapPage.tsx)
      layout: fkLayout(layout),
      wheelSensitivity: 0.2,
    });
    cyRef.current = cy;
    cy.on('tap', 'node', (e) => {
      const d = e.target.data() as Record<string, unknown>;
      setSelected({
        id: String(d.id), schema: String(d.schema), table: String(d.table),
        sizeBytes: Number(d.sizeBytes ?? 0), rowEstimate: Number(d.rowEstimate ?? 0),
        activityScore: (d.activityScore as number | null) ?? null, boundary: Boolean(d.boundary),
      });
      cy.elements().removeClass('dim');
      cy.elements().not(e.target.closedNeighborhood()).addClass('dim'); // neighbourhood focus
    });
    cy.on('tap', (e) => { if (e.target === cy) { cy.elements().removeClass('dim'); setSelected(null); } });
    cy.on('zoom', () => setZoomPct(Math.round(cy.zoom() * 100)));
    setZoomPct(Math.round(cy.zoom() * 100));
    return () => { cy.destroy(); cyRef.current = null; };
  }, [status, elements, layout]);

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.elements().removeClass('dim');
    const q = search.trim().toLowerCase();
    if (!q) return;
    const matches = cy.nodes().filter((n) => String(n.data('table') ?? '').toLowerCase().includes(q));
    if (matches.length === 0) return;
    cy.elements().not(matches.closedNeighborhood()).addClass('dim');
  }, [search]);

  const fit = () => cyRef.current?.fit(undefined, 30);
  const zoomBy = (f: number) => { const cy = cyRef.current; if (cy) cy.zoom({ level: cy.zoom() * f, renderedPosition: { x: (cy.width() || 0) / 2, y: (cy.height() || 0) / 2 } }); };

  if (status === 'loading') return <Centered>Loading table graph…</Centered>;
  if (status === 'no-access') return <Centered>You don’t have access to this database’s table graph.</Centered>;
  if (status === 'error' || !doc) return <ErrorState onRetry={load} />;

  const sparse = fkSparseMessage(doc);
  const hidden = fkHiddenLabel(doc.meta);

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2 flex-wrap">
        <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search tables…"
          className="flex-1 min-w-[160px] border border-[rgba(0,0,0,0.12)] rounded-md px-2 py-1 text-xs" />
        <select value={layout} onChange={(e) => setLayout(e.target.value as 'fcose' | 'dagre')}
          className="border border-[rgba(0,0,0,0.12)] rounded-md px-2 py-1 text-xs bg-white">
          <option value="fcose">Organic</option>
          <option value="dagre">Hierarchy</option>
        </select>
        <div className="flex items-center gap-1">
          <IconBtn onClick={() => zoomBy(0.83)} label="−" />
          <span className="text-[11px] text-[rgba(0,0,0,0.5)] tabular-nums w-10 text-center">{zoomPct}%</span>
          <IconBtn onClick={() => zoomBy(1.2)} label="+" />
          <button onClick={fit} className="ml-1 px-2 py-1 rounded-md border border-[rgba(0,0,0,0.12)] text-[11px] hover:bg-[rgba(0,0,0,0.03)]">Fit</button>
        </div>
      </div>
      {hidden && <Banner>{hidden}</Banner>}
      {sparse && <Banner>{sparse}</Banner>}
      <button disabled className="self-start text-[11px] text-[rgba(0,0,0,0.35)] border border-dashed border-[rgba(0,0,0,0.12)] rounded-md px-2 py-1 cursor-not-allowed">
        Add query-usage edges (coming later)
      </button>
      <div className="relative border border-[rgba(0,0,0,0.08)] rounded-md" style={{ height: 460 }}>
        <div ref={containerRef} className="absolute inset-0" />
        {selected && <DetailPanel node={selected} doc={doc} onClose={() => setSelected(null)} />}
      </div>
    </div>
  );
}

function DetailPanel({ node, doc, onClose }: { node: FkNode; doc: FkGraphDocDto; onClose: () => void }) {
  const outbound = doc.edges.filter((e) => e.source === node.id);
  const inbound = doc.edges.filter((e) => e.target === node.id);
  return (
    <div className="absolute top-2 right-2 w-64 max-h-[92%] overflow-y-auto bg-white border border-[rgba(0,0,0,0.12)] rounded-md shadow-sm p-3 text-xs">
      <div className="flex items-center justify-between mb-2">
        <span className="font-semibold text-[rgba(0,0,0,0.85)] truncate">{node.schema}.{node.table}</span>
        <button onClick={onClose} className="text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]">✕</button>
      </div>
      {node.boundary && <div className="text-[10px] text-amber-700 mb-2">Boundary — outside the current selection</div>}
      <dl className="space-y-1">
        <Row k="Size" v={humanBytes(node.sizeBytes)} />
        <Row k="Rows (est.)" v={node.rowEstimate.toLocaleString()} />
        <Row k="Activity" v={node.activityScore != null ? `${Math.round(node.activityScore * 100)}%` : '—'} />
        <Row k="References" v={String(outbound.length)} />
        <Row k="Referenced by" v={String(inbound.length)} />
      </dl>
      {outbound.length > 0 && (
        <div className="mt-2">
          <div className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-1">References</div>
          {outbound.map((e) => (
            <div key={e.id} className="text-[11px] text-[rgba(0,0,0,0.65)] break-words">
              → {e.target.split('.')[1]} <span className="text-[rgba(0,0,0,0.4)]">({e.columns.join(', ')}{e.onDelete ? `, ON DELETE ${e.onDelete}` : ''})</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between gap-2">
      <dt className="text-[rgba(0,0,0,0.45)]">{k}</dt>
      <dd className="text-[rgba(0,0,0,0.8)] text-right break-words">{v}</dd>
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
      <span>Couldn’t load the table graph.</span>
      <button onClick={onRetry} className="px-3 py-1.5 rounded-md border border-[rgba(0,0,0,0.12)] text-xs hover:bg-[rgba(0,0,0,0.03)]">Retry</button>
    </div>
  );
}

function humanBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = bytes / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(1)} ${units[i]}`;
}

function fkLayout(kind: 'fcose' | 'dagre'): cytoscape.LayoutOptions {
  if (kind === 'dagre') return { name: 'dagre', rankDir: 'TB', nodeSep: 20, rankSep: 70 } as unknown as cytoscape.LayoutOptions;
  return { name: 'fcose', quality: 'default', animate: false, nodeSeparation: 90 } as unknown as cytoscape.LayoutOptions;
}

const FK_STYLE = [
  { selector: 'node', style: {
      'background-color': '#e4e4e7', 'border-color': 'rgba(0,0,0,0.35)', 'border-width': 'data(border)',
      label: 'data(label)', 'font-size': 9, color: 'rgba(0,0,0,0.7)',
      'text-valign': 'bottom', 'text-halign': 'center', 'text-margin-y': 2,
      width: 'data(diam)', height: 'data(diam)', shape: 'ellipse',
  } },
  { selector: 'node.boundary', style: { 'background-color': '#fafafa', 'border-style': 'dashed', color: 'rgba(0,0,0,0.4)' } },
  { selector: 'edge', style: {
      width: 1, 'line-color': 'rgba(0,0,0,0.22)', 'target-arrow-color': 'rgba(0,0,0,0.35)',
      'target-arrow-shape': 'triangle', 'curve-style': 'bezier', 'arrow-scale': 0.7,
  } },
  { selector: '.dim', style: { opacity: 0.12 } },
];
