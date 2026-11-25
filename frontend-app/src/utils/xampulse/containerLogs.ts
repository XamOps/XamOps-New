/**
 * Log queries for one container (all its versions in the range) on one host. Everything goes in the query text, not
 * only the scope, because live tail applies the query but not the scope's resource filters.
 */
import type { XpLogHistogramResponse } from '../../types/xampulse';

export type SeverityFilter = 'all' | 'warn' | 'error';

/** The parser allows 2000 characters and 40 terms; 12 full container ids keep well inside both. */
export const MAX_CONTAINER_IDS = 12;

/** A quoted phrase in the filter DSL (backslash and double quote escaped, as the backend's parser expects). */
export function quotePhrase(text: string): string {
  return `"${text.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** Container ids newest last (as the summary lists versions); the most recent ones are kept when there are too many. */
export function containerLogQuery(instanceId: string, containerIds: string[], severity: SeverityFilter, text: string): string {
  const ids = Array.from(new Set(containerIds.filter(Boolean))).slice(-MAX_CONTAINER_IDS);
  const parts = [`instance:${instanceId}`];
  if (ids.length === 1) parts.push(`container:${ids[0]}`);
  else if (ids.length > 1) parts.push(`container:(${ids.join(' OR ')})`);
  if (severity !== 'all') parts.push(`severity:>=${severity}`);
  const t = text.trim();
  if (t) parts.push(quotePhrase(t.slice(0, 200)));
  return parts.join(' ');
}

export interface SeverityTotals { total: number; errors: number; warnings: number }

/** Totals from a severity-stacked histogram: errors = ERROR + FATAL, warnings = WARN. */
export function severityTotals(h: XpLogHistogramResponse | undefined): SeverityTotals | null {
  if (!h) return null;
  return h.buckets.reduce<SeverityTotals>((acc, b) => {
    const c = b.counts;
    const all = Object.values(c).reduce<number>((s, v) => s + (v ?? 0), 0);
    return { total: acc.total + all, errors: acc.errors + (c.ERROR ?? 0) + (c.FATAL ?? 0), warnings: acc.warnings + (c.WARN ?? 0) };
  }, { total: 0, errors: 0, warnings: 0 });
}
