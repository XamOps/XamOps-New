import React, { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useHosts } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { BarMeter, Card, DemoPill, EmptyState, SkeletonBlock, StatusPill } from '../../components/XamPulse/ui/ui';
import { FleetHoneycomb, Sparkline } from '../../components/XamPulse/charts/Visuals';
import { Resource360Drawer } from '../../components/XamPulse/hosts/Resource360Drawer';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatRate } from '../../utils/xampulse/format';
import type { XpHostRow } from '../../types/xampulse';

type SortKey = 'name' | 'health' | 'cpuPct' | 'memPct' | 'diskPct' | 'lastSeenAt';
type ColKey = 'disk' | 'net' | 'load' | 'type';
const HEALTH_RANK = { CRIT: 0, WARN: 1, NO_DATA: 2, OK: 3 } as const;

export function sortHosts(rows: XpHostRow[], key: SortKey, dir: 1 | -1): XpHostRow[] {
  const val = (r: XpHostRow): number | string => (key === 'health' ? HEALTH_RANK[r.health] : key === 'name' ? r.name.toLowerCase() : key === 'lastSeenAt' ? Date.parse(r.lastSeenAt ?? '') || 0 : r[key] ?? -1);
  return rows.slice().sort((a, b) => {
    const x = val(a);
    const y = val(b);
    const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y));
    return c * dir;
  });
}

/** CSV with a DEMO marker column when the rows are generated sample data. */
export function hostsToCsv(rows: XpHostRow[]): string {
  const esc = (v: unknown): string => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const head = ['name', 'instance_id', 'region', 'health', 'cpu_pct', 'mem_pct', 'disk_pct', 'agent', 'last_seen', 'demo'];
  const lines = rows.map((r) => [r.name, r.instanceId, r.region, r.health, r.cpuPct, r.memPct, r.diskPct, r.agentStatus, r.lastSeenAt, r.instanceId.startsWith('i-demo') ? 'DEMO' : ''].map(esc).join(','));
  return [head.join(','), ...lines].join('\n');
}

const HostsTab: React.FC = () => {
  const ctx = useViewContext();
  const navigate = useNavigate();
  const { instanceId } = useParams<{ instanceId?: string }>();
  const [term, setTerm] = useState('');
  const [view, setView] = useState<'table' | 'map'>('table');
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'health', dir: 1 });
  const [cols, setCols] = useState<Record<ColKey, boolean>>({ disk: true, net: false, load: false, type: true });
  const [colsOpen, setColsOpen] = useState(false);
  const q = useHosts(ctx.effectiveAccountIds, term);
  const open = (id: string) => navigate(ctx.link(xamPulseHostPath(id)));
  const closeDrawer = () => navigate(ctx.link('/xampulse/hosts'));

  const header = (key: SortKey, label: string, num = false) => (
    <th scope="col" className={num ? 'xp-num' : undefined} aria-sort={sort.key === key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}>
      <button type="button" onClick={() => setSort((s) => ({ key, dir: s.key === key ? (s.dir === 1 ? -1 : 1) : 1 }))}>{label}{sort.key === key ? (sort.dir === 1 ? ' \u25B2' : ' \u25BC') : ''}</button>
    </th>
  );

  const exportCsv = (rows: XpHostRow[]) => {
    const demo = rows.some((r) => r.instanceId.startsWith('i-demo'));
    const blob = new Blob([hostsToCsv(rows)], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `xampulse-hosts${demo ? '-DEMO' : ''}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const drawerAccount = useMemo(() => (q.data?.items.find((h) => h.instanceId === instanceId)?.accountId ?? ctx.effectiveAccountIds[0]), [q.data, instanceId, ctx.effectiveAccountIds]);

  return (
    <>
      <div className="xp-row">
        <h1 className="xp-h1" style={{ fontSize: 22 }}>Hosts</h1>
        <span className="xp-spacer" />
        <label className="xp-sr" htmlFor="xp-host-q">Search hosts</label>
        <input id="xp-host-q" className="xp-input" placeholder="Search name or instance id" value={term} onChange={(e) => setTerm(e.target.value)} style={{ minWidth: 240 }} />
        <div className="xp-seg" role="group" aria-label="View">
          <button type="button" aria-pressed={view === 'table'} onClick={() => setView('table')}>Table</button>
          <button type="button" aria-pressed={view === 'map'} onClick={() => setView('map')}>Map</button>
        </div>
        <div style={{ position: 'relative' }}>
          <button type="button" className="xp-btn" aria-expanded={colsOpen} onClick={() => setColsOpen((o) => !o)}>Columns</button>
          {colsOpen && (
            <div className="xp-card" style={{ position: 'absolute', right: 0, top: 40, zIndex: 10, minWidth: 180 }} role="group" aria-label="Choose columns">
              {(Object.keys(cols) as ColKey[]).map((c) => (
                <label key={c} style={{ display: 'flex', gap: 8, padding: '3px 0' }}><input type="checkbox" checked={cols[c]} onChange={(e) => setCols({ ...cols, [c]: e.target.checked })} />{c === 'disk' ? 'Disk' : c === 'net' ? 'Network' : c === 'load' ? 'Load' : 'Type'}</label>
              ))}
            </div>
          )}
        </div>
        {q.data && <button type="button" className="xp-btn" onClick={() => exportCsv(q.data!.items)}>Export CSV</button>}
      </div>
      <QueryBoundary query={q} skeleton={<SkeletonBlock height={320} label="Loading hosts" />}>
        {(page) => {
          if (page.items.length === 0) {
            return <Card><EmptyState title={term ? 'No hosts match that search' : 'No hosts are reporting yet'} body={term ? 'Try a different name or clear the search.' : 'Hosts appear as soon as their agent enrols. Check Setup for coverage.'} /></Card>;
          }
          const rows = sortHosts(page.items, sort.key, sort.dir);
          if (view === 'map') {
            return <Card title="Fleet map" id="xp-hmap"><FleetHoneycomb tiles={rows.map((r) => ({ accountId: r.accountId, instanceId: r.instanceId, name: r.name, health: r.health, score: r.score, cpuPct: r.cpuPct, memPct: r.memPct }))} hrefFor={(t) => ctx.link(xamPulseHostPath(t.instanceId))} /></Card>;
          }
          return (
            <div className="xp-table-wrap">
              <table className="xp-table">
                <caption className="xp-sr">Hosts. Select a row to open Resource 360.</caption>
                <thead><tr>
                  {header('name', 'Host')}{header('health', 'Health')}{header('cpuPct', 'CPU', true)}{header('memPct', 'Memory', true)}
                  {cols.disk && header('diskPct', 'Disk', true)}
                  {cols.net && <th scope="col" className="xp-num">Net in / out</th>}
                  {cols.load && <th scope="col" className="xp-num">Load</th>}
                  <th scope="col">CPU 1h</th>{header('lastSeenAt', 'Last seen')}
                  {cols.type && <th scope="col">Type</th>}
                </tr></thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.instanceId} data-clickable="true" tabIndex={0} onClick={() => open(r.instanceId)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(r.instanceId); } }}
                      aria-label={`${r.name}, ${r.health}. Open Resource 360.`}>
                      <td><strong>{r.name}</strong> {r.instanceId.startsWith('i-demo') && <DemoPill />}<div className="xp-muted xp-mono" style={{ fontSize: 11 }}>{r.instanceId}</div></td>
                      <td><StatusPill health={r.health} />{r.reason && <div className="xp-muted" style={{ fontSize: 11, whiteSpace: 'normal', maxWidth: 260 }}>{r.reason}</div>}</td>
                      <td className="xp-num"><BarMeter pct={r.cpuPct} label="CPU" /></td>
                      <td className="xp-num"><BarMeter pct={r.memPct} label="Memory" /></td>
                      {cols.disk && <td className="xp-num"><BarMeter pct={r.diskPct} label="Disk" /></td>}
                      {cols.net && <td className="xp-num">{formatRate(r.netInBps)} / {formatRate(r.netOutBps)}</td>}
                      {cols.load && <td className="xp-num">{r.load1 ?? '\u2014'}</td>}
                      <td><Sparkline values={r.cpuSpark} label={`${r.name} CPU last hour`} /></td>
                      <td>{formatAgo(r.lastSeenAt)}</td>
                      {cols.type && <td>{r.instanceType ?? '\u2014'}</td>}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }}
      </QueryBoundary>
      {instanceId && drawerAccount !== undefined && <Resource360Drawer accountId={drawerAccount} instanceId={instanceId} onClose={closeDrawer} />}
    </>
  );
};

export default HostsTab;
