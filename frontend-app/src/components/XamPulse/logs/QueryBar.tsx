import React, { useEffect, useMemo, useState } from 'react';
import { MessageSquareText, Search, X } from 'lucide-react';
import { DSL_FIELDS, parseFilter } from '../../../utils/xampulse/filterDsl';
import { translate } from '../../../utils/xampulse/plainEnglish';
import type { RangePreset } from '../../../utils/xampulse/format';

const RECENT_KEY = 'xp:recent-queries:v1';
const RECENT_MAX = 8;

export function readRecent(): string[] {
  try {
    const v: unknown = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}
export function pushRecent(q: string): void {
  const t = q.trim();
  if (!t) return;
  try {
    window.localStorage.setItem(RECENT_KEY, JSON.stringify([t, ...readRecent().filter((x) => x !== t)].slice(0, RECENT_MAX)));
  } catch {
    /* storage unavailable */
  }
}

/** Splits a query into removable chips, keeping quoted phrases and field:(a OR b) lists whole. */
export function splitTokens(query: string): string[] {
  return query.match(/-?[\w.]+:(?:"[^"]*"|\([^)]*\)|\S+)|"[^"]*"|\S+/g) ?? [];
}

export const FilterChips: React.FC<{ query: string; onChange: (q: string) => void }> = ({ query, onChange }) => {
  const tokens = splitTokens(query);
  if (tokens.length === 0) return null;
  return (
    <div className="xp-row" aria-label="Active filters" role="group">
      {tokens.map((t, i) => (
        <span key={`${t}-${i}`} className="xp-chip">{t}<button type="button" aria-label={`Remove filter ${t}`} onClick={() => onChange(tokens.filter((_, j) => j !== i).join(' '))}><X size={11} strokeWidth={1.75} /></button></span>
      ))}
      <button type="button" className="xp-btn xp-btn--ghost xp-btn--sm" onClick={() => onChange('')}>Clear all</button>
    </div>
  );
};

/**
 * Query input with live DSL validation, field helpers and recent queries. `/` focuses it from anywhere. In plain-English
 * mode the words are turned into a query in the browser (nothing is sent anywhere until Search) and shown before it
 * runs; a time range in the words ("last 2 hours") sets the page's range through {@code onRange}.
 */
export const QueryBar: React.FC<{ value: string; onSubmit: (q: string) => void; serverError?: string | null; onRange?: (r: RangePreset) => void }> = ({ value, onSubmit, serverError, onRange }) => {
  const [draft, setDraft] = useState(value);
  const [english, setEnglish] = useState(false);
  const [words, setWords] = useState('');
  const [recent, setRecent] = useState<string[]>([]);
  useEffect(() => { setDraft(value); }, [value]);
  useEffect(() => { setRecent(readRecent()); }, [value]);
  const t = useMemo(() => (english ? translate(words) : null), [english, words]);
  const query = english ? t?.query ?? '' : draft;
  const parsed = useMemo(() => parseFilter(query), [query]);
  const error = !parsed.ok ? parsed.error : serverError ?? null;
  const submit = () => {
    if (!parsed.ok) return;
    pushRecent(query);
    if (english && t?.range && onRange) onRange(t.range);
    onSubmit(query.trim());
    if (english) { setDraft(query); }
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <form className="xp-row" role="search" style={{ position: 'relative', flexWrap: 'nowrap' }} onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <label className="xp-sr" htmlFor="xp-query">{english ? 'Describe the logs you want' : 'Log query'}</label>
        <Search size={15} strokeWidth={1.5} aria-hidden="true" style={{ position: 'absolute', left: 14, color: 'var(--xp-muted)', pointerEvents: 'none' }} />
        {english ? (
          <input id="xp-query" data-xp-query="" className="xp-input" style={{ flex: 1, minWidth: 260, height: 38, paddingLeft: 36, borderRadius: 999, background: 'var(--xp-surface)' }} value={words} onChange={(e) => setWords(e.target.value)}
            placeholder="e.g. errors from api in the last hour containing connection reset" aria-describedby="xp-query-reads" autoComplete="off" />
        ) : (
          <input id="xp-query" data-xp-query="" className="xp-input xp-input--mono" style={{ flex: 1, minWidth: 260, height: 38, paddingLeft: 36, borderRadius: 999, background: 'var(--xp-surface)' }} value={draft} onChange={(e) => setDraft(e.target.value)}
            placeholder='Search logs, e.g. service:nginx severity:>=warn "connection reset"' aria-invalid={error ? true : undefined} aria-describedby={error ? 'xp-query-err' : undefined} spellCheck={false} autoComplete="off" />
        )}
        <button type="button" className={`xp-btn${english ? ' xp-btn--primary' : ''}`} style={{ height: 38, gap: 6 }} aria-pressed={english}
          onClick={() => { if (!english) setWords(''); setEnglish((x) => !x); }} title="Describe the logs in plain English; the query is built in your browser">
          <MessageSquareText size={14} aria-hidden="true" /> Plain English
        </button>
        <button type="submit" className="xp-btn xp-btn--primary" style={{ height: 38, padding: '0 20px' }} disabled={!parsed.ok || (english && !query)}>Search</button>
      </form>
      {english && (
        <div id="xp-query-reads" className="xp-muted" style={{ fontSize: 12, paddingLeft: 6 }} aria-live="polite">
          {query
            ? <>Will search: <code className="xp-mono" style={{ color: 'var(--xp-text)' }}>{query}</code>{t?.range ? ` \u00b7 last ${t.range}` : ''} <span>(read as: {t?.understood.join(', ')})</span>
              {' '}<button type="button" className="xp-btn xp-btn--ghost xp-btn--sm" onClick={() => { setDraft(query); setEnglish(false); }}>Edit as query</button></>
            : 'Describe what you are looking for: a severity (errors, warnings), a service ("from api"), an instance id, words or a "quoted phrase", and a time ("last 2 hours").'}
        </div>
      )}
      {error && <div id="xp-query-err" className="xp-err" role="alert">{error}</div>}
      <div className="xp-row" style={{ fontSize: 12, gap: 4, paddingLeft: 6 }}>
        <span className="xp-eyebrow" style={{ marginRight: 4 }}>Fields</span>
        {DSL_FIELDS.map((f) => <button key={f} type="button" className="xp-btn xp-btn--ghost" style={{ height: 24, padding: '0 7px', fontFamily: 'var(--xp-mono)', fontSize: 11.5 }} onClick={() => setDraft((d) => `${d}${d && !d.endsWith(' ') ? ' ' : ''}${f}:`)}>{f}:</button>)}
        {recent.length > 0 && (
          <>
            <span className="xp-eyebrow" style={{ margin: '0 4px 0 10px' }}>Recent</span>
            <label className="xp-sr" htmlFor="xp-recent">Recent queries</label>
            <select id="xp-recent" className="xp-select" style={{ height: 26, maxWidth: 260, fontSize: 12, borderRadius: 999 }} value="" onChange={(e) => { if (e.target.value) { setDraft(e.target.value); onSubmit(e.target.value); } }}>
              <option value="">Choose</option>
              {recent.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </>
        )}
      </div>
    </div>
  );
};
