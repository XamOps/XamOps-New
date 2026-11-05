import React, { useEffect, useMemo, useState } from 'react';
import { DSL_FIELDS, parseFilter } from '../../../utils/xampulse/filterDsl';

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
        <span key={`${t}-${i}`} className="xp-chip">{t}<button type="button" aria-label={`Remove filter ${t}`} onClick={() => onChange(tokens.filter((_, j) => j !== i).join(' '))}>{'\u2715'}</button></span>
      ))}
      <button type="button" className="xp-btn xp-btn--ghost" onClick={() => onChange('')}>Clear all</button>
    </div>
  );
};

/** Query input with live DSL validation, field helpers and recent queries. `/` focuses it from anywhere. */
export const QueryBar: React.FC<{ value: string; onSubmit: (q: string) => void; serverError?: string | null }> = ({ value, onSubmit, serverError }) => {
  const [draft, setDraft] = useState(value);
  const [recent, setRecent] = useState<string[]>([]);
  useEffect(() => { setDraft(value); }, [value]);
  useEffect(() => { setRecent(readRecent()); }, [value]);
  const parsed = useMemo(() => parseFilter(draft), [draft]);
  const error = !parsed.ok ? parsed.error : serverError ?? null;
  const submit = () => { if (parsed.ok) { pushRecent(draft); onSubmit(draft.trim()); } };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <form className="xp-row" role="search" onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <label className="xp-sr" htmlFor="xp-query">Log query</label>
        <input id="xp-query" data-xp-query="" className="xp-input xp-input--mono" style={{ flex: 1, minWidth: 260 }} value={draft} onChange={(e) => setDraft(e.target.value)}
          placeholder='Search logs, e.g. service:nginx severity:>=warn "connection reset"' aria-invalid={error ? true : undefined} aria-describedby={error ? 'xp-query-err' : undefined} spellCheck={false} autoComplete="off" />
        <button type="submit" className="xp-btn xp-btn--primary" disabled={!parsed.ok}>Search</button>
        <span className="xp-kbd" aria-hidden="true">/</span>
      </form>
      {error && <div id="xp-query-err" className="xp-err" role="alert">{error}</div>}
      <div className="xp-row" style={{ fontSize: 12 }}>
        <span className="xp-muted">Fields:</span>
        {DSL_FIELDS.map((f) => <button key={f} type="button" className="xp-btn xp-btn--ghost" style={{ padding: '1px 6px', fontFamily: 'var(--xp-mono)' }} onClick={() => setDraft((d) => `${d}${d && !d.endsWith(' ') ? ' ' : ''}${f}:`)}>{f}:</button>)}
        {recent.length > 0 && (
          <>
            <span className="xp-muted">Recent:</span>
            <label className="xp-sr" htmlFor="xp-recent">Recent queries</label>
            <select id="xp-recent" className="xp-select" style={{ padding: '2px 6px', maxWidth: 260 }} value="" onChange={(e) => { if (e.target.value) { setDraft(e.target.value); onSubmit(e.target.value); } }}>
              <option value="">Choose</option>
              {recent.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </>
        )}
      </div>
    </div>
  );
};
