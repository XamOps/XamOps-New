import React, { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { XpApiError } from '../../api/xampulseErrors';
import { xampulseApi, isXpDemo } from '../../api/xampulseApi';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useLogFacets, useLogHistogram, useLogPatterns, useLogSearch } from '../../hooks/xampulse/useXamPulseQueries';
import { useLogTail } from '../../hooks/xampulse/useLogTail';
import { setPrefs, usePrefs } from '../../hooks/xampulse/prefs';
import { addFacetTerm } from '../../utils/xampulse/filterDsl';
import { formatBytes, formatCount } from '../../utils/xampulse/format';
import { Card, EmptyState, ErrorState, SkeletonBlock } from '../../components/XamPulse/ui/ui';
import { LogRibbon } from '../../components/XamPulse/charts/Visuals';
import { VirtualLogList } from '../../components/XamPulse/tables/VirtualLogList';
import { FilterChips, QueryBar } from '../../components/XamPulse/logs/QueryBar';
import { FacetSidebar } from '../../components/XamPulse/logs/FacetSidebar';
import { LogDetailDrawer } from '../../components/XamPulse/logs/LogDetailDrawer';
import { PatternList, templateToQuery } from '../../components/XamPulse/logs/PatternList';
import { ArchiveEstimatePanel } from '../../components/XamPulse/logs/ArchiveEstimatePanel';
import type { XpLogRow, XpSavedView } from '../../types/xampulse';

type View = 'explorer' | 'tail' | 'patterns' | 'archive';
const VIEWS: [View, string][] = [['explorer', 'Explorer'], ['tail', 'Live tail'], ['patterns', 'Patterns'], ['archive', 'Archive']];

/** The first literal words of a line, quoted, to filter by "this kind of line". */
export function lineToPatternQuery(body: string): string {
  const first = body.split('\n')[0];
  return templateToQuery(first.replace(/\d+/g, '<N>'));
}

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
      <input id="xp-save-name" className="xp-input" placeholder="Name this search" value={name} onChange={(e) => setName(e.target.value)} style={{ width: 160 }} />
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
  const explorer = view === 'explorer';
  const search = useLogSearch(ctx.scope, ctx.q, explorer);
  const hist = useLogHistogram(ctx.scope, ctx.q, explorer);
  const facets = useLogFacets(ctx.scope, ctx.q, explorer);
  const patterns = useLogPatterns(ctx.scope, ctx.q, view === 'patterns');
  const tail = useLogTail(ctx.scope, ctx.q, view === 'tail');

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
      <div className="xp-row">
        <h1 className="xp-h1" style={{ fontSize: 22 }}>Logs</h1>
        <div className="xp-seg" role="group" aria-label="Logs view">
          {VIEWS.map(([id, label]) => <button key={id} type="button" aria-pressed={view === id} onClick={() => choose(id)}>{label}</button>)}
        </div>
        <span className="xp-spacer" />
        <button type="button" className="xp-btn" aria-pressed={prefs.logWrap} onClick={() => setPrefs({ logWrap: !prefs.logWrap })}>Wrap lines</button>
        <SavedViews accountId={ctx.effectiveAccountIds[0]} q={ctx.q} onApply={setQ} />
      </div>
      <QueryBar value={ctx.q} onSubmit={setQ} serverError={badFilter} />
      <FilterChips query={ctx.q} onChange={setQ} />

      {view === 'explorer' && (
        <>
          <Card flat>
            {hist.isLoading ? <SkeletonBlock height={84} label="Loading volume" /> : hist.data ? <LogRibbon data={hist.data} onBrush={brush} /> : hist.error ? <ErrorState error={hist.error} compact /> : null}
            <p className="xp-muted" style={{ margin: '6px 0 0', fontSize: 12 }}>Drag across the bars to zoom into a time window.</p>
          </Card>
          <div className="xp-logs">
            <FacetSidebar data={facets.data} loading={facets.isLoading} onInclude={(f, v) => filter(f, v)} onExclude={(f, v) => filter(f, v, true)} />
            <div>
              {search.isLoading && <SkeletonBlock height={560} label="Searching logs" />}
              {search.error && !badFilter && <ErrorState error={search.error} onRetry={() => { void search.refetch(); }} />}
              {search.data && (
                <>
                  <div className="xp-muted" style={{ fontSize: 12, marginBottom: 6 }} role="status" aria-live="polite">
                    {formatCount(search.data.rows.length)} lines shown · scanned {formatCount(search.data.scanned.rows)} rows ({formatBytes(search.data.scanned.bytes)}) in {search.data.tookMs} ms{search.data.truncated ? ' · results truncated, narrow your filter' : ''}
                  </div>
                  {search.data.rows.length === 0
                    ? <Card><EmptyState title="No log lines match" body={ctx.q ? 'Loosen the filter or widen the time range.' : 'No logs arrived in this range. Check your log sources in Setup.'} /></Card>
                    : <VirtualLogList rows={search.data.rows} selectedId={selected?.id} onSelect={setSelected} wrap={prefs.logWrap} label="Log lines" />}
                </>
              )}
            </div>
          </div>
        </>
      )}

      {view === 'tail' && (
        <Card title="Live tail" id="xp-tail" actions={
          <div className="xp-row">
            <span className={tail.status === 'live' ? 'xp-pill xp-pill--ok' : 'xp-pill xp-pill--nodata'}><span className="xp-live-dot" aria-hidden="true">{'\u25CF'}</span>{tail.status === 'live' ? 'Live' : tail.status === 'paused' ? 'Paused' : tail.status === 'connecting' || tail.status === 'reconnecting' ? 'Connecting' : tail.status === 'ended' ? 'Ended' : tail.status === 'error' ? 'Disconnected' : 'Idle'}</span>
            <button type="button" className="xp-btn" onClick={() => { if (tail.status === 'paused') tail.resume(); else tail.pause(); }} disabled={tail.status !== 'live' && tail.status !== 'paused'}>{tail.status === 'paused' ? 'Resume' : 'Pause'} <span className="xp-kbd">p</span></button>
            <button type="button" className="xp-btn" onClick={tail.clear}>Clear</button>
          </div>}>
          <div className="xp-muted" style={{ fontSize: 12, marginBottom: 6 }} role="status" aria-live="polite">
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
        <Card title="Patterns" id="xp-pat" actions={<span className="xp-muted" style={{ fontSize: 12 }}>Compared with the previous period</span>}>
          {patterns.isLoading && <SkeletonBlock height={240} label="Clustering log lines" />}
          {patterns.error && <ErrorState error={patterns.error} onRetry={() => { void patterns.refetch(); }} />}
          {patterns.data && <PatternList patterns={patterns.data.patterns} onPick={(p) => choose('explorer', `${ctx.q} ${templateToQuery(p.template)}`.trim())} />}
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
