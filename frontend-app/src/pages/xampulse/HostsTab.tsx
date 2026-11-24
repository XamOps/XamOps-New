import React, { useEffect, useState } from 'react';
import { ChevronDown, ChevronUp, Download, Server } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useHosts, useXpAccounts } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { BarMeter, Card, DemoPill, EmptyState, PageHead, SkeletonBlock, StatusPill, TonePill } from '../../components/XamPulse/ui/ui';
import { FleetHoneycomb, Sparkline } from '../../components/XamPulse/charts/Visuals';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatRate } from '../../utils/xampulse/format';
import type { XpHealth, XpHostRow } from '../../types/xampulse';

type SortKey = 'name' | 'health' | 'cpuPct' | 'memPct' | 'lastSeenAt';
type ColKey = 'disk' | 'net' | 'load' | 'type';
const SORT_API: Record<SortKey, string> = { name: 'name', health: 'health', cpuPct: 'cpu', memPct: 'mem', lastSeenAt: 'lastSeen' };
const HEALTH_RANK = { CRIT: 0, WARN: 1, NO_DATA: 2, OK: 3 } as const;

/** Up to three customer tags under the host id (Name is already the title); the full set in the tooltip. */
function tagLine(tags: Record<string, string> | undefined): React.ReactNode {
  const entries = Object.entries(tags ?? {}).filter(([k]) => k !== 'Name');
  if (!entries.length) return null;
  const all = entries.map(([k, v]) => (v ? `${k}=${v}` : k));
  const shown = all.slice(0, 3).join(' · ');
  return <span className="xp-cell-sub xp-cell-sub--clip" title={all.join('\n')}>{shown}{all.length > 3 ? ` · +${all.length - 3} more` : ''}</span>;
}

export function sortHosts(rows: XpHostRow[], key: SortKey, dir: 1 | -1): XpHostRow[] {
  const val = (row: XpHostRow): number | string => key === 'health' ? HEALTH_RANK[row.health] : key === 'name' ? row.name.toLowerCase() : key === 'lastSeenAt' ? Date.parse(row.lastSeenAt ?? '') || 0 : row[key] ?? -1;
  return rows.slice().sort((a, b) => {
    const x = val(a); const y = val(b);
    return (typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y))) * dir;
  });
}

export function hostsToCsv(rows: XpHostRow[]): string {
  const esc = (value: unknown): string => `"${String(value ?? '').replace(/"/g, '""')}"`;
  const head = ['account_id', 'name', 'instance_id', 'region', 'platform', 'health', 'cpu_pct', 'mem_pct', 'disk_pct', 'agent', 'collection', 'last_seen', 'demo'];
  return [head.join(','), ...rows.map((row) => [row.accountId, row.name, row.instanceId, row.region, row.platform, row.health, row.cpuPct, row.memPct, row.diskPct, row.agentStatus, row.collectionLevel, row.lastSeenAt, row.instanceId.startsWith('i-demo') ? 'DEMO' : ''].map(esc).join(','))].join('\n');
}

const HostsTab: React.FC = () => {
  const ctx = useViewContext();
  const accounts = useXpAccounts();
  const [term, setTerm] = useState('');
  const [health, setHealth] = useState<XpHealth | ''>('');
  const [page, setPage] = useState(0);
  const [view, setView] = useState<'table' | 'map'>('table');
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'health', dir: 1 });
  const [cols, setCols] = useState<Record<ColKey, boolean>>({ disk: true, net: false, load: false, type: true });
  const [colsOpen, setColsOpen] = useState(false);
  const [available, setAvailable] = useState({ net: false, load: false });
  useEffect(() => setPage(0), [term, health, ctx.effectiveAccountIds]);
  const apiSort = `${sort.dir === -1 ? '-' : ''}${SORT_API[sort.key]}`;
  const query = useHosts(ctx.effectiveAccountIds, { q: term, state: health || undefined, page, size: 50, sort: apiSort });
  useEffect(() => {
    if (!query.data) return;
    setAvailable((value) => ({
      net: value.net || query.data.items.some((row) => row.netInBps != null || row.netOutBps != null),
      load: value.load || query.data.items.some((row) => row.load1 != null),
    }));
  }, [query.data]);
  const accountName = (id: number) => accounts.data?.find((account) => account.cloudAccountId === id)?.name ?? `Account ${id}`;
  const exportCsv = (rows: XpHostRow[]) => {
    const blob = new Blob([hostsToCsv(rows)], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = `xampulse-hosts${rows.some((row) => row.instanceId.startsWith('i-demo')) ? '-DEMO' : ''}.csv`; a.click();
    URL.revokeObjectURL(url);
  };
  const header = (key: SortKey, label: string, numeric = false) => <th scope="col" className={numeric ? 'xp-num' : undefined} aria-sort={sort.key === key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}><button type="button" onClick={() => { setPage(0); setSort((value) => ({ key, dir: value.key === key ? (value.dir === 1 ? -1 : 1) : key === 'health' ? 1 : -1 })); }}>{label}{sort.key === key ? sort.dir === 1 ? <ChevronUp size={11} aria-hidden="true" /> : <ChevronDown size={11} aria-hidden="true" /> : null}</button></th>;

  return <>
    <PageHead icon={Server} title="Hosts" sub="Reporting EC2 fleet with health, location, live 15-minute signals and collection freshness." actions={<>
      <label className="xp-sr" htmlFor="xp-host-q">Search hosts</label><input id="xp-host-q" className="xp-input" placeholder="Search name or instance ID" value={term} onChange={(event) => setTerm(event.target.value)} />
      <label className="xp-sr" htmlFor="xp-host-health">Health</label><select id="xp-host-health" className="xp-select" value={health} onChange={(event) => setHealth(event.target.value as XpHealth | '')}><option value="">All health</option><option value="CRIT">Critical</option><option value="WARN">Warning</option><option value="NO_DATA">No data</option><option value="OK">Healthy</option></select>
      <div className="xp-seg" role="group" aria-label="Hosts view"><button type="button" aria-pressed={view === 'table'} onClick={() => setView('table')}>Table</button><button type="button" aria-pressed={view === 'map'} onClick={() => setView('map')}>Map</button></div>
      <div style={{ position: 'relative' }}>
        <button type="button" className="xp-btn" aria-expanded={colsOpen} aria-controls="xp-host-columns" onClick={() => setColsOpen((open) => !open)}>Columns</button>
        {colsOpen && <div id="xp-host-columns" className="xp-menu" role="group" aria-label="Choose host columns">
          {(Object.keys(cols) as ColKey[]).map((key) => {
            const unavailable = (key === 'net' || key === 'load') && !available[key];
            const label = key === 'disk' ? 'Disk' : key === 'net' ? 'Network in / out' : key === 'load' ? 'Load 1m' : 'Instance type';
            return <label key={key}><input type="checkbox" checked={cols[key]} disabled={unavailable && !cols[key]} onChange={(event) => setCols({ ...cols, [key]: event.target.checked })} />{label}{unavailable && <span className="xp-muted">No data</span>}</label>;
          })}
        </div>}
      </div>
      {query.data && <button type="button" className="xp-btn" onClick={() => exportCsv(query.data!.items)}><Download size={14} aria-hidden="true" />Export page</button>}
    </>} />
    <QueryBoundary query={query} skeleton={<SkeletonBlock height={360} label="Loading hosts" />}>
      {(result) => {
        const start = result.total === 0 ? 0 : result.page * result.size + 1;
        const end = Math.min(result.total, (result.page + 1) * result.size);
        if (!result.items.length) return <Card><EmptyState title={term || health ? 'No hosts match these filters' : 'No hosts are reporting yet'} body={term || health ? 'Clear a filter or try another search.' : 'Check the enrollment and reporting funnel in Setup.'} /></Card>;
        const pager = <nav className="xp-pagination" aria-label="Host pages"><button type="button" className="xp-btn" disabled={result.page === 0} onClick={() => setPage(result.page - 1)}>Previous</button><span>Page {result.page + 1} of {Math.max(1, Math.ceil(result.total / result.size))}</span><button type="button" className="xp-btn" disabled={end >= result.total} onClick={() => setPage(result.page + 1)}>Next</button></nav>;
        return <>
          <div className="xp-status-line" role="status">Showing {start}–{end} of {result.total.toLocaleString()} hosts · metrics reflect the latest 15-minute host window</div>
          {view === 'map' ? <Card title="Fleet map" id="xp-hmap" actions={<span className="xp-hint">Current result page</span>}><FleetHoneycomb tiles={result.items.map((row) => ({ accountId: row.accountId, instanceId: row.instanceId, name: row.name, health: row.health, score: row.score, cpuPct: row.cpuPct, memPct: row.memPct }))} hrefFor={(tile) => ctx.link(xamPulseHostPath(tile.instanceId, tile.accountId))} /></Card> :
            <div className="xp-table-wrap"><table className="xp-table">
              <caption className="xp-sr">Hosts, with account, location, health, live utilization and collection freshness</caption>
              <thead><tr>{header('name', 'Host')}{header('health', 'Health')}<th scope="col">Location</th>{header('cpuPct', 'CPU', true)}{header('memPct', 'Memory', true)}{cols.disk && <th scope="col" className="xp-num">Disk</th>}{cols.net && <th scope="col" className="xp-num">Network in / out</th>}{cols.load && <th scope="col" className="xp-num">Load 1m</th>}<th scope="col">CPU 1h</th><th scope="col">Agent</th>{header('lastSeenAt', 'Last seen')}{cols.type && <th scope="col">Type</th>}</tr></thead>
              <tbody>{result.items.map((row) => <tr key={`${row.accountId}-${row.instanceId}`}>
                <td><Link className="xp-cell-name" to={ctx.link(xamPulseHostPath(row.instanceId, row.accountId))}>{row.name}</Link>{row.instanceId.startsWith('i-demo') && <> <DemoPill /></>}{row.state && row.state !== 'running' && <> <TonePill tone={row.state === 'stopped' || row.state === 'terminated' ? 'crit' : 'warn'}>{row.state}</TonePill></>}<span className="xp-cell-sub">{row.instanceId}</span>{tagLine(row.tags)}</td>
                <td><StatusPill health={row.health} />{row.reason && <span className="xp-cell-sub xp-cell-sub--wrap">{row.reason}</span>}</td>
                <td>{row.region}<span className="xp-cell-sub">{accountName(row.accountId)} · {row.platform}</span></td>
                <td className="xp-num"><BarMeter pct={row.cpuPct} label="CPU" /></td><td className="xp-num"><BarMeter pct={row.memPct} label="Memory" /></td>{cols.disk && <td className="xp-num"><BarMeter pct={row.diskPct} label="Disk" /></td>}
                {cols.net && <td className="xp-num xp-mono">{formatRate(row.netInBps)} / {formatRate(row.netOutBps)}</td>}{cols.load && <td className="xp-num xp-mono">{row.load1 ?? '—'}</td>}
                <td><Sparkline values={row.cpuSpark} label={`${row.name} CPU last hour`} /></td>
                <td><StatusPill health={row.agentStatus === 'ACTIVE' ? 'OK' : row.agentStatus === 'STALE' ? 'WARN' : row.agentStatus === 'REVOKED' ? 'CRIT' : 'NO_DATA'} label={row.agentStatus.toLowerCase()} /><span className="xp-cell-sub">Collection {row.collectionLevel}</span></td>
                <td className="xp-mono">{formatAgo(row.lastSeenAt)}</td>{cols.type && <td>{row.instanceType ?? '—'}</td>}
              </tr>)}</tbody>
            </table></div>}
          {pager}
        </>;
      }}
    </QueryBoundary>
  </>;
};

export default HostsTab;
