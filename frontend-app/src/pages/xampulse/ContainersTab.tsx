import React, { useEffect, useMemo, useState } from 'react';
import { Boxes, ChevronDown, ChevronUp, Search } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import '../../styles/xampulse-containers.css';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useContainers, useXpAccounts } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { BarMeter, EmptyState, PageHead, StatusPill } from '../../components/XamPulse/ui/ui';
import { xamPulseContainerPath, xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatBytes } from '../../utils/xampulse/format';
import { containerRowHealth, healthRank, needsAttention, type ContainerRowHealth } from '../../utils/xampulse/containerHealth';
import { formatCorePct } from '../../utils/xampulse/containerVerdict';
import type { XpContainerRow, XpPage } from '../../types/xampulse';

type SortKey = 'name' | 'cpuPct' | 'memBytes' | 'restarts' | 'lastSeenAt';
type Filter = 'all' | 'attention' | 'healthy';
interface Row { c: XpContainerRow; h: ContainerRowHealth }

const plural = (n: number, one: string, many = `${one}s`): string => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** Memory against a real limit as a bar; with no limit set, the bytes and the host's memory it can grow into. */
export function memoryCell(c: XpContainerRow): React.ReactNode {
  if (c.memBytes == null) return <span className="xp-ct-n" data-zero="true">—</span>;
  if (c.memLimitIsHost === true || !c.memLimitBytes || c.memLimitBytes <= 0) {
    return <><span className="xp-ct-n">{formatBytes(c.memBytes)}</span><span className="xp-ct-sub" title="Docker reports the host's memory as the limit when none is set">No limit{c.memLimitIsHost && c.memLimitBytes ? ` · host ${formatBytes(c.memLimitBytes)}` : ''}</span></>;
  }
  return <><BarMeter pct={(c.memBytes / c.memLimitBytes) * 100} label="Memory of its limit" /><span className="xp-ct-sub">{formatBytes(c.memBytes)} / {formatBytes(c.memLimitBytes)}</span></>;
}

/** The header line: how many, where, and the one container to look at first, in words. */
function summary(page: XpPage<XpContainerRow>, rows: Row[]): string {
  const hosts = new Set(rows.map((r) => r.c.instanceId)).size;
  const where = page.total > rows.length
    ? `${plural(page.total, 'container')}; ${rows.length} on this page`
    : `${plural(page.total, 'container')} on ${plural(hosts, 'host')}`;
  const bad = rows.filter((r) => needsAttention(r.h)).sort((a, b) => healthRank(a.h) - healthRank(b.h));
  if (!bad.length) return `${where}. None need attention.`;
  const w = bad[0];
  const lead = bad.length === 1 ? '1 needs attention:' : `${bad.length} need attention; worst is`;
  return `${where}. ${lead} ${w.c.name} on ${w.c.instanceId} (${w.h.reasons.join(', ')}).`;
}

const ListSkeleton: React.FC = () => (
  <div className="xp-ct-skel" role="status" aria-label="Loading containers">
    {Array.from({ length: 5 }, (_, i) => <div key={i}>{Array.from({ length: 8 }, (__, j) => <span key={j} />)}</div>)}
  </div>
);

const ContainersTab: React.FC = () => {
  const ctx = useViewContext();
  const navigate = useNavigate();
  const accounts = useXpAccounts();
  const [term, setTerm] = useState('');
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<Filter>('all');
  const [sort, setSort] = useState<{ key: SortKey; direction: 1 | -1 }>({ key: 'restarts', direction: -1 });
  useEffect(() => setPage(0), [term, ctx.effectiveAccountIds]);
  const sortParam = `${sort.direction === -1 ? '-' : ''}${sort.key}`;
  const query = useContainers(ctx.effectiveAccountIds, { q: term, page, size: 50, sort: sortParam });
  const multiAccount = ctx.effectiveAccountIds.length > 1;
  const accountName = (id: number) => accounts.data?.find((account) => account.cloudAccountId === id)?.name ?? `Account ${id}`;

  // "Not reporting" is judged against the end of the range: now for a relative range, the chosen end for an absolute one.
  const now = ctx.absTo ? ctx.toMs : Date.now();
  const rows = useMemo<Row[]>(() => (query.data?.items ?? []).map((c) => ({ c, h: containerRowHealth(c, now) })), [query.data, now]);
  const counts: Record<Filter, number> = {
    all: rows.length,
    attention: rows.filter((r) => needsAttention(r.h)).length,
    healthy: rows.filter((r) => r.h.state === 'OK').length,
  };
  const shown = filter === 'all' ? rows : rows.filter((r) => (filter === 'attention' ? needsAttention(r.h) : r.h.state === 'OK'));
  const href = (c: XpContainerRow) => ctx.link(xamPulseContainerPath(c.instanceId, c.name, c.accountId));

  const header = (key: SortKey, label: string, numeric = false, title?: string) => (
    <th scope="col" className={numeric ? 'xp-num' : undefined} title={title} aria-sort={sort.key === key ? (sort.direction === 1 ? 'ascending' : 'descending') : 'none'}>
      <button type="button" onClick={() => { setPage(0); setSort((current) => ({ key, direction: current.key === key ? (current.direction === 1 ? -1 : 1) : -1 })); }}>
        {label}{sort.key === key ? sort.direction === 1 ? <ChevronUp size={11} aria-hidden="true" /> : <ChevronDown size={11} aria-hidden="true" /> : null}
      </button>
    </th>
  );

  return (
    <div className="xp-ct">
      <PageHead icon={Boxes} title="Containers"
        sub={query.data ? summary(query.data, rows) : 'Docker containers on your hosts: CPU, memory against its limit, restarts and errors, newest data first.'} />

      <section className="xp-ct-panel" aria-label="Containers">
        <div className="xp-ct-toolbar">
          <div className="xp-ct-search">
            <Search size={14} strokeWidth={1.75} aria-hidden="true" />
            <label className="xp-sr" htmlFor="xp-ct-q">Search containers</label>
            <input id="xp-ct-q" className="xp-input" placeholder="Filter by name or image…" value={term} onChange={(event) => setTerm(event.target.value)} />
          </div>
          <div className="xp-seg" role="group" aria-label="Show containers">
            {([['all', 'All'], ['attention', 'Needs attention'], ['healthy', 'Healthy']] as [Filter, string][]).map(([id, label]) => (
              <button key={id} type="button" aria-pressed={filter === id} onClick={() => setFilter(id)} disabled={!query.data}>
                {label}{query.data && <span className="xp-seg__count">{counts[id]}</span>}
              </button>
            ))}
          </div>
        </div>

        <QueryBoundary query={query} skeleton={<ListSkeleton />}>
          {(result) => {
            if (!result.items.length) {
              return <EmptyState icon={Boxes} title={term ? 'No containers match that search' : 'No containers reported'}
                body={term ? 'Clear the search or try another name or image.' : 'Turn on Docker collection for a host in Setup and its containers appear here within a minute.'}
                action={term ? <button type="button" className="xp-btn" onClick={() => setTerm('')}>Clear search</button> : <Link className="xp-btn" to={ctx.link('/xampulse/setup')}>Open Setup</Link>} />;
            }
            const start = result.page * result.size + 1;
            const end = Math.min(result.total, (result.page + 1) * result.size);
            const pages = Math.max(1, Math.ceil(result.total / result.size));
            return <>
              <div className="xp-ct-tablewrap">
                <table className="xp-ct-table">
                  <caption className="xp-sr">Containers with status, host, CPU as percent of one core, memory against its limit, restart count, errors in the last 15 minutes and last telemetry time</caption>
                  <thead><tr>
                    {header('name', 'Container')}
                    <th scope="col">Status</th>
                    <th scope="col">Host</th>
                    {header('cpuPct', 'CPU', true, 'Percent of one CPU core, as docker stats shows it: 200% is two full cores')}
                    {header('memBytes', 'Memory', true, 'Memory against the container’s own limit')}
                    {header('restarts', 'Restarts', true)}
                    <th scope="col" className="xp-num">Errors 15m</th>
                    {header('lastSeenAt', 'Last seen', true)}
                  </tr></thead>
                  <tbody>
                    {shown.map(({ c, h }) => (
                      <tr key={`${c.accountId}-${c.instanceId}-${c.containerId}`} data-clickable="true"
                        onClick={(event) => { if (!(event.target as HTMLElement).closest('a, button')) navigate(href(c)); }}>
                        <td>
                          <Link className="xp-ct-name" to={href(c)}>{c.name}</Link>
                          <span className="xp-ct-sub" title={c.image ?? undefined}>{c.image ?? 'image not reported'} · {c.containerId.slice(0, 12)}</span>
                        </td>
                        <td title={h.reasons.length ? h.reasons.join(', ') : undefined}>
                          <StatusPill health={h.health} label={h.label} />
                          {h.reasons.length > 0 && <span className="xp-sr">: {h.reasons.join(', ')}</span>}
                        </td>
                        <td>
                          <Link className="xp-ct-host" to={ctx.link(xamPulseHostPath(c.instanceId, c.accountId))}>{c.instanceId}</Link>
                          {multiAccount && <span className="xp-ct-sub xp-ct-sub--sans">{accountName(c.accountId)}</span>}
                        </td>
                        <td className="xp-num">
                          {c.cpuPct == null ? <span className="xp-ct-n" data-zero="true">—</span> : <>
                            <span className="xp-ct-n">{formatCorePct(c.cpuPct)}</span>
                            {c.cpuPct >= 100 && <span className="xp-ct-sub">{(c.cpuPct / 100).toFixed(1)} cores</span>}
                          </>}
                        </td>
                        <td className="xp-num">{memoryCell(c)}</td>
                        <td className="xp-num">
                          <span className="xp-ct-n" data-zero={c.restarts === 0 || c.restarts == null} data-tone={(c.restarts ?? 0) > 0 ? 'warn' : undefined}>{c.restarts ?? '—'}</span>
                        </td>
                        <td className="xp-num">
                          {c.errors == null ? <span className="xp-ct-n" data-zero="true">—</span>
                            : c.errors > 0 ? <Link className="xp-ct-n" data-tone="crit" to={`${href(c)}#errors`} title="Open this container's errors">{c.errors.toLocaleString()}</Link>
                            : <span className="xp-ct-n" data-zero="true">0</span>}
                        </td>
                        <td className="xp-num"><span className="xp-ct-n" data-zero="true">{formatAgo(c.lastSeenAt)}</span></td>
                      </tr>
                    ))}
                    {shown.length === 0 && (
                      <tr className="xp-ct-empty-row"><td colSpan={8}>
                        {filter === 'attention' ? 'Nothing needs attention on this page.' : 'No healthy containers on this page.'}{' '}
                        <button type="button" className="xp-linkbtn" onClick={() => setFilter('all')}>Show all</button>
                      </td></tr>
                    )}
                  </tbody>
                </table>
              </div>
              <div className="xp-ct-foot">
                <span role="status">
                  {filter === 'all' ? `Showing ${start}–${end} of ${result.total.toLocaleString()}` : `Showing ${shown.length} of ${rows.length} on this page`}
                  {' · sorted by '}{({ name: 'name', cpuPct: 'CPU', memBytes: 'memory', restarts: 'restarts', lastSeenAt: 'last seen' } as Record<SortKey, string>)[sort.key]}
                </span>
                {pages > 1 && (
                  <nav aria-label="Container pages">
                    <button type="button" className="xp-btn xp-btn--sm" disabled={result.page === 0} onClick={() => setPage(result.page - 1)}>Previous</button>
                    <span>Page {result.page + 1} of {pages}</span>
                    <button type="button" className="xp-btn xp-btn--sm" disabled={end >= result.total} onClick={() => setPage(result.page + 1)}>Next</button>
                  </nav>
                )}
              </div>
            </>;
          }}
        </QueryBoundary>
      </section>
    </div>
  );
};
export default ContainersTab;
