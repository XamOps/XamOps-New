import React, { useEffect, useMemo, useState } from 'react';
import { ScrollText } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { XpApiError } from '../../api/xampulseErrors';
import { xampulseApi, isXpDemo } from '../../api/xampulseApi';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useLogFacets, useLogHistogram, useLogPatterns, useLogSearch } from '../../hooks/xampulse/useXamPulseQueries';
import { useLogTail } from '../../hooks/xampulse/useLogTail';
import { setPrefs, usePrefs } from '../../hooks/xampulse/prefs';
import { addFacetTerm } from '../../utils/xampulse/filterDsl';
import { formatBytes, formatCount } from '../../utils/xampulse/format';
import { Card, EmptyState, ErrorState, SkeletonBlock, PageHead } from '../../components/XamPulse/ui/ui';
import { LogRibbon } from '../../components/XamPulse/charts/Visuals';
import { VirtualLogList } from '../../components/XamPulse/tables/VirtualLogList';
import { FilterChips, QueryBar } from '../../components/XamPulse/logs/QueryBar';
import { FacetSidebar } from '../../components/XamPulse/logs/FacetSidebar';
import { LogDetailDrawer } from '../../components/XamPulse/logs/LogDetailDrawer';
import { PatternList, lineToPatternQuery, templateToQuery } from '../../components/XamPulse/logs/PatternList';
import { ArchiveEstimatePanel } from '../../components/XamPulse/logs/ArchiveEstimatePanel';
import type { XpLogRow, XpSavedView } from '../../types/xampulse';

type View = 'explorer' | 'tail' | 'patterns' | 'archive';
const VIEWS: [View, string][] = [['explorer', 'Explorer'], ['tail', 'Live tail'], ['patterns', 'Patterns'], ['archive', 'Archive']];

export { lineToPatternQuery };

const SavedViews: React.FC<{ accountId: number | undefined; q: string; onApply: (q: string) => void }> = ({ accountId, q, onApply }) => {
  const client = useQueryClient();
  const [name, setName] = useState('');
  const views = useQuery({ queryKey: ['xp', isXpDemo() ? 'demo' : 'live', 'views', accountId], enabled: accountId !== undefined, queryFn: () => xampulseApi.views(accountId as number), retry: false });
  const save = useMutation({
    mutationFn: (v: XpSavedView) => xampulseApi.saveView(accountId as number, v),
    onSuccess: () => { setName(''); void client.invalidateQueries({ queryKey: ['xp', isXpDemo() ? 'demo' : 'live', 'views'] }); },
  });
  const logViews = (views.data ?? []).filter((v) => v.kind === 'LOGS');
  return (
    <div className="xp-row" aria-label="Saved views" role="group">
      {logViews.length > 0 && (
        <>
          <label className="xp-sr" htmlFor="xp-saved">Saved views</label>
          <select id="xp-saved" className="xp-select" value="" onChange={(e) => { const v = logViews.find((x) => x.name === e.target.value); if (v) onApply(String(v.state.q ?? '')); }}>
            <option value="">Saved views</option>
            {logViews.map((v) => <option key={v.name} value={v.name}>{v.name}</option>)}
          </select>
        </>
      )}
      <label className="xp-sr" htmlFor="xp-save-name">Name for this view</label>
      <input id="xp-save-name" className="xp-input" placeholder="Name this search" value={name} onChange={(e) => setName(e.target.value)} style={{ width: 170, borderRadius: 999, paddingLeft: 14 }} />
      <button type="button" className="xp-btn" disabled={!name.trim() || save.isPending || accountId === undefined} onClick={() => save.mutate({ name: name.trim(), kind: 'LOGS', shared: false, state: { q } })}>Save</button>
      {save.error && <span className="xp-err">Could not save.</span>}
    </div>
  );
};

const LogsTab: React.FC = () => {
  const ctx = useViewContext();
  const prefs = usePrefs();
  const [view, setView] = useState<View>(ctx.live ? 'tail' : 'explorer');
  const [selected, setSelected] = useState<XpLogRow | null>(null);
  const [patternSampleError, setPatternSampleError] = useState<string | null>(null);
  const explorer = view === 'explorer';
  const search = useLogSearch(ctx.scope, ctx.q, explorer);
  const hist = useLogHistogram(ctx.scope, ctx.q, explorer);
  const facets = useLogFacets(ctx.scope, ctx.q, explorer);
  const patterns = useLogPatterns(ctx.scope, ctx.q, view === 'patterns');
  const tail = useLogTail(ctx.scope, ctx.q, view === 'tail');
  const searchRows = useMemo(() => {
    const seen = new Set<string>();
    return (search.data?.pages ?? []).flatMap((page) => page.rows).filter((row) => !seen.has(`${row.accountId}:${row.id}`) && !!seen.add(`${row.accountId}:${row.id}`));
  }, [search.data]);
  const searchMeta = useMemo(() => {
    const pages = search.data?.pages ?? [];
    if (!pages.length) return undefined;
    const last = pages[pages.length - 1];
    return {
      scanned: {
        rows: pages.reduce((total, current) => total + current.scanned.rows, 0),
        bytes: pages.reduce((total, current) => total + current.scanned.bytes, 0),
      },
      tookMs: pages.reduce((total, current) => total + current.tookMs, 0),
      truncated: pages.some((current) => current.truncated),
      parsed: last.parsed,
      pageCount: pages.length,
    };
  }, [search.data]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT')) return;
      if (e.key === 'p' && view === 'tail' && !e.metaKey && !e.ctrlKey) { e.preventDefault(); if (tail.status === 'paused') tail.resume(); else tail.pause(); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [view, tail]);

  const setQ = (q: string) => ctx.update({ q });
  const filter = (field: string, value: string, exclude = false) => setQ(addFacetTerm(ctx.q, field, value, exclude));
  const choose = (v: View, q?: string) => { setView(v); ctx.update(q === undefined ? { live: v === 'tail' } : { live: v === 'tail', q }); };
  const brush = (a: number, b: number) => ctx.update({ range: null, absFrom: new Date(a).toISOString(), absTo: new Date(b).toISOString() });
  const badFilter = search.error instanceof XpApiError && search.error.code === 'XP_BAD_FILTER' ? search.error.message : null;
  const permalink = (): string => `${window.location.origin}${ctx.link('/xampulse/logs', { q: ctx.q })}`;

  return (
    <>
      <PageHead icon={ScrollText} title="Logs"
        extra={<div className="xp-seg" role="group" aria-label="Logs view">
          {VIEWS.map(([id, label]) => <button key={id} type="button" aria-pressed={view === id} onClick={() => choose(id)}>{label}</button>)}
        </div>}
        actions={<>
          <button type="button" className="xp-btn" aria-pressed={prefs.logWrap} onClick={() => setPrefs({ logWrap: !prefs.logWrap })}>Wrap lines</button>
          <SavedViews accountId={ctx.effectiveAccountIds[0]} q={ctx.q} onApply={setQ} />
        </>} />
      <QueryBar value={ctx.q} onSubmit={setQ} serverError={badFilter} onRange={(r) => ctx.update({ range: r, absFrom: null, absTo: null })} />
      <FilterChips query={ctx.q} onChange={setQ} />

      {view === 'explorer' && (
        <>
          <Card title="Volume" actions={<span className="xp-hint" style={{ margin: 0 }}>Drag across the bars to zoom into a time window</span>}>
            {hist.isLoading ? <SkeletonBlock height={84} label="Loading volume" /> : hist.data ? <LogRibbon data={hist.data} onBrush={brush} /> : hist.error ? <ErrorState error={hist.error} compact /> : null}
          </Card>
          <div className="xp-logs">
            <FacetSidebar data={facets.data} loading={facets.isLoading} onInclude={(f, v) => filter(f, v)} onExclude={(f, v) => filter(f, v, true)} />
            <div>
              {search.isLoading && <SkeletonBlock height={560} label="Searching logs" />}
              {search.error && !badFilter && <ErrorState error={search.error} onRetry={() => { void search.refetch(); }} />}
              {search.data && searchMeta && (
                <>
                  <div className="xp-status-line" style={{ marginBottom: 8 }} role="status" aria-live="polite">
                    {formatCount(searchRows.length)} lines loaded · scanned {formatCount(searchMeta.scanned.rows)} rows ({formatBytes(searchMeta.scanned.bytes)}) across {searchMeta.pageCount} {searchMeta.pageCount === 1 ? 'page' : 'pages'} · requests answered in {searchMeta.tookMs} ms total{searchMeta.truncated ? ' · one or more pages truncated, narrow your filter' : ''}{searchMeta.parsed?.normalised && searchMeta.parsed.normalised !== ctx.q.trim() ? ` · interpreted as ${searchMeta.parsed.normalised}` : ''}{search.isFetchingNextPage ? ' · loading more…' : search.hasNextPage ? ' · more available' : ' · end of results'}
                  </div>
                  {searchRows.length === 0
                    ? <Card><EmptyState title="No log lines match" body={ctx.q ? 'Loosen the filter or widen the time range.' : 'No logs arrived in this range. Check your log sources in Setup.'} /></Card>
                    : <VirtualLogList rows={searchRows} selectedId={selected?.id} onSelect={setSelected} wrap={prefs.logWrap} label="Log lines" onNearEnd={() => { if (search.hasNextPage && !search.isFetchingNextPage) void search.fetchNextPage(); }} />}
                </>
              )}
            </div>
          </div>
        </>
      )}

      {view === 'tail' && (
        <Card title="Live tail" id="xp-tail" actions={
          <div className="xp-row">
            <span className={tail.status === 'live' ? 'xp-pill xp-pill--ok' : 'xp-pill xp-pill--nodata'}><span className={`xp-dot${tail.status === 'live' ? ' xp-live-dot' : ''}`} aria-hidden="true" />{tail.status === 'live' ? 'Live' : tail.status === 'paused' ? 'Paused' : tail.status === 'connecting' || tail.status === 'reconnecting' ? 'Connecting' : tail.status === 'ended' ? 'Ended' : tail.status === 'error' ? 'Disconnected' : 'Idle'}</span>
            <button type="button" className="xp-btn" onClick={() => { if (tail.status === 'paused') tail.resume(); else tail.pause(); }} disabled={tail.status !== 'live' && tail.status !== 'paused'}>{tail.status === 'paused' ? 'Resume' : 'Pause'} <span className="xp-kbd">p</span></button>
            <button type="button" className="xp-btn" onClick={tail.clear}>Clear</button>
          </div>}>
          <div className="xp-status-line" style={{ marginBottom: 8 }} role="status" aria-live="polite">
            {formatCount(tail.matched)} matched · {formatCount(tail.dropped)} dropped · {tail.rate}/s
            {tail.endReason ? ` · stream ended (${tail.endReason.toLowerCase().replace('_', ' ')})` : ''}
          </div>
          {tail.notice && <div className="xp-banner xp-banner--warn" style={{ marginBottom: 8 }}>{tail.notice}</div>}
          {tail.status === 'error' && <ErrorState error={new XpApiError('XP_STORE_UNAVAILABLE', 'The live connection dropped. Check your network and reopen Live tail.', 0)} compact />}
          {tail.status === 'connecting' && tail.rows.length === 0 ? <SkeletonBlock height={200} label="Connecting to live tail" /> : (
            <VirtualLogList rows={tail.rows} selectedId={selected?.id} onSelect={setSelected} wrap={prefs.logWrap} followTop label="Live log lines" emptyText="Waiting for new log lines that match your filter." />
          )}
        </Card>
      )}

      {view === 'patterns' && (
        <Card title="Patterns" id="xp-pat" actions={<span className="xp-eyebrow">Compared with the previous period</span>}>
          {patterns.isLoading && <SkeletonBlock height={240} label="Clustering log lines" />}
          {patterns.error && <ErrorState error={patterns.error} onRetry={() => { void patterns.refetch(); }} />}
          {patternSampleError && <div className="xp-banner xp-banner--warn" role="alert">{patternSampleError}</div>}
          {patterns.data && <PatternList patterns={patterns.data.patterns} onPick={(p) => choose('explorer', `${ctx.q} ${templateToQuery(p.template)}`.trim())} onSample={(p) => {
            setPatternSampleError(null);
            void xampulseApi.logContext(p.sampleAccountId, p.sampleId, 20, 20)
              .then((result) => setSelected(result.anchor))
              .catch(() => setPatternSampleError('The sample context could not be loaded. It may have expired or become unavailable.'));
          }} />}
        </Card>
      )}

      {view === 'archive' && <ArchiveEstimatePanel scope={ctx.scope} query={ctx.q} />}

      {selected && (
        <LogDetailDrawer row={selected} onClose={() => setSelected(null)} permalink={permalink()}
          onFilter={(f, v) => { filter(f, v); setSelected(null); }}
          onShowPattern={(r) => { setSelected(null); choose('explorer', `${ctx.q} ${lineToPatternQuery(r.body)}`.trim()); }} />
      )}
    </>
  );
};

export default LogsTab;
