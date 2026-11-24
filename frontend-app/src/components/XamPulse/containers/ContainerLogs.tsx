import React, { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { useLogHistogram, useLogSearch } from '../../../hooks/xampulse/useXamPulseQueries';
import { useLogTail } from '../../../hooks/xampulse/useLogTail';
import { setPrefs, usePrefs } from '../../../hooks/xampulse/prefs';
import { Card, EmptyState, ErrorState, SkeletonBlock } from '../ui/ui';
import { LogRibbon } from '../charts/Visuals';
import { VirtualLogList } from '../tables/VirtualLogList';
import { LogDetailDrawer } from '../logs/LogDetailDrawer';
import { lineToPatternQuery } from '../logs/PatternList';
import { addFacetTerm } from '../../../utils/xampulse/filterDsl';
import { containerLogQuery, type SeverityFilter, type SeverityTotals } from '../../../utils/xampulse/containerLogs';
import { formatCount } from '../../../utils/xampulse/format';
import type { XpLogRow, XpScope } from '../../../types/xampulse';

const SEVERITIES: [SeverityFilter, string][] = [['all', 'All'], ['warn', 'Warn+'], ['error', 'Error+']];

/** The loaded lines' own height (VirtualLogList rows are 30px, 56px wrapped), at least six rows, at most 420px; more lines scroll. */
export const listHeight = (lines: number, wrap: boolean): number => Math.min(420, Math.max(6, lines) * (wrap ? 56 : 30) + 2);

interface Props {
  scope: XpScope;
  instanceId: string;
  /** Every container id this name had in the range (redeploys), oldest first. */
  containerIds: string[];
  severity: SeverityFilter;
  onSeverity: (s: SeverityFilter) => void;
  /** Counts for the unfiltered container logs, for the severity buttons; null while unknown. */
  totals: SeverityTotals | null;
}

/**
 * The container's own logs on its page, so "why is it failing" is answered without leaving: severity filter with
 * counts, phrase search, volume by severity over time (drag to zoom), the lines newest first with paging, and live tail.
 */
export const ContainerLogs: React.FC<Props> = ({ scope, instanceId, containerIds, severity, onSeverity, totals }) => {
  const ctx = useViewContext();
  const navigate = useNavigate();
  const prefs = usePrefs();
  const [draft, setDraft] = useState('');
  const [text, setText] = useState('');
  const [live, setLive] = useState(false);
  const [selected, setSelected] = useState<XpLogRow | null>(null);
  const query = containerLogQuery(instanceId, containerIds, severity, text);
  const search = useLogSearch(scope, query, !live);
  const hist = useLogHistogram(scope, query, true);
  const tail = useLogTail(scope, query, live);
  const rows = useMemo(() => {
    const seen = new Set<string>();
    return (search.data?.pages ?? []).flatMap((p) => p.rows).filter((r) => !seen.has(r.id) && !!seen.add(r.id));
  }, [search.data]);
  const toLogs = (q: string) => navigate(ctx.link('/xampulse/logs', { q }));
  const brush = (a: number, b: number) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() });
  const counts: Record<SeverityFilter, number | null> = totals && !text
    ? { all: totals.total, warn: totals.warnings + totals.errors, error: totals.errors }
    : { all: null, warn: null, error: null };
  const filtered = severity !== 'all' || !!text;

  return (
    <Card title="Logs" id="xp-clogs" actions={<>
      <div className="xp-seg" role="group" aria-label="Minimum severity">
        {SEVERITIES.map(([id, label]) => (
          <button key={id} type="button" aria-pressed={severity === id} onClick={() => onSeverity(id)}>
            {label}{counts[id] != null && <span className="xp-seg__count"> {formatCount(counts[id])}</span>}
          </button>
        ))}
      </div>
      <form className="xp-row" role="search" onSubmit={(e) => { e.preventDefault(); setText(draft.trim()); }}>
        <label className="xp-sr" htmlFor="xp-clog-q">Search this container's logs</label>
        <input id="xp-clog-q" className="xp-input" placeholder="Search text, Enter" value={draft} onChange={(e) => { setDraft(e.target.value); if (!e.target.value) setText(''); }} style={{ width: 200, height: 28, fontSize: 12.5 }} />
        <button type="submit" className="xp-btn xp-btn--sm xp-btn--icon" aria-label="Search" style={{ width: 28 }}><Search size={13} aria-hidden="true" /></button>
      </form>
      <button type="button" className="xp-btn xp-btn--sm" aria-pressed={live} onClick={() => setLive((v) => !v)}>
        <span className={`xp-dot${live && tail.status === 'live' ? ' xp-live-dot' : ''}`} aria-hidden="true" />{live ? 'Live on' : 'Live'}
      </button>
      <button type="button" className="xp-btn xp-btn--sm" aria-pressed={prefs.logWrap} onClick={() => setPrefs({ logWrap: !prefs.logWrap })}>Wrap</button>
      <Link className="xp-btn xp-btn--sm" to={ctx.link('/xampulse/logs', { q: query })}>Open in Logs</Link>
    </>}>
      <div style={{ marginBottom: 10 }}>
        {hist.isLoading ? <SkeletonBlock height={64} label="Loading log volume" />
          : hist.error ? <ErrorState error={hist.error} compact />
          : hist.data && hist.data.buckets.some((b) => Object.values(b.counts).some((n) => (n ?? 0) > 0)) ? <LogRibbon data={hist.data} height={64} onBrush={brush} />
          : null}
      </div>

      {live ? (
        <>
          <div className="xp-status-line" style={{ marginBottom: 8 }} role="status" aria-live="polite">
            {tail.status === 'live' ? 'Streaming new lines' : tail.status === 'paused' ? 'Paused' : tail.status === 'ended' ? `Stream ended${tail.endReason ? ` (${tail.endReason.toLowerCase().replace('_', ' ')})` : ''}` : tail.status === 'error' ? 'Disconnected' : 'Connecting'}
            {' · '}{formatCount(tail.matched)} matched{tail.dropped ? ` · ${formatCount(tail.dropped)} dropped` : ''} · {tail.rate}/s
            {(tail.status === 'live' || tail.status === 'paused') && <> · <button type="button" className="xp-linkbtn" onClick={() => (tail.status === 'paused' ? tail.resume() : tail.pause())}>{tail.status === 'paused' ? 'Resume' : 'Pause'}</button></>}
          </div>
          {tail.notice && <div className="xp-banner xp-banner--warn" style={{ marginBottom: 8 }}>{tail.notice}</div>}
          <VirtualLogList rows={tail.rows} selectedId={selected?.id} onSelect={setSelected} wrap={prefs.logWrap} height={420} followTop label="Live container log lines" emptyText="Waiting for new lines from this container." />
        </>
      ) : (
        <>
          {search.isLoading && <SkeletonBlock height={300} label="Loading container logs" />}
          {search.error && <ErrorState error={search.error} onRetry={() => { void search.refetch(); }} />}
          {search.data && (rows.length === 0
            ? <EmptyState title={filtered ? 'No lines match this filter' : 'No logs from this container in this range'}
                body={filtered ? 'Choose All, clear the search, or widen the time range.' : 'Widen the time range, or check that log collection is on for this host (Setup).'} />
            : <>
                <div className="xp-status-line" style={{ marginBottom: 8 }} role="status" aria-live="polite">
                  {formatCount(rows.length)} lines loaded, newest first{search.isFetchingNextPage ? ' · loading more…' : search.hasNextPage ? ' · scroll for more' : ' · all lines in range'}
                </div>
                <VirtualLogList rows={rows} selectedId={selected?.id} onSelect={setSelected} wrap={prefs.logWrap} height={listHeight(rows.length, prefs.logWrap)} label="Container log lines"
                  onNearEnd={() => { if (search.hasNextPage && !search.isFetchingNextPage) void search.fetchNextPage(); }} />
                {search.hasNextPage && (
                  <div className="xp-row" style={{ justifyContent: 'center', marginTop: 8 }}>
                    <button type="button" className="xp-btn" disabled={search.isFetchingNextPage} onClick={() => { void search.fetchNextPage(); }}>{search.isFetchingNextPage ? 'Loading…' : 'Load more'}</button>
                  </div>
                )}
              </>)}
        </>
      )}

      {selected && (
        <LogDetailDrawer row={selected} onClose={() => setSelected(null)} permalink={`${window.location.origin}${ctx.link('/xampulse/logs', { q: query })}`}
          onFilter={(f, v) => { setSelected(null); toLogs(addFacetTerm(query, f, v, false)); }}
          onShowPattern={(r) => { setSelected(null); toLogs(`${query} ${lineToPatternQuery(r.body)}`); }} />
      )}
    </Card>
  );
};
