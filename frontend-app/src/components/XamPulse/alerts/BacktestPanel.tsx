import React, { useMemo, useState } from 'react';
import type { XpAlertPreview, XpFiring } from '../../../types/xampulse';
import { TimeSeriesChart, type ChartMarker, type ChartSeries } from '../charts/TimeSeriesChart';
import { formatDateTime } from '../../../utils/xampulse/format';

const groupName = (f: XpFiring): string => Object.values(f.group).join(' ') || 'all hosts';
const num = (v: number | null | undefined): string => (v === null || v === undefined ? 'no data' : Math.abs(v) >= 100 ? Math.round(v).toLocaleString() : String(Number(v.toFixed(2))));
const plural = (n: number, one: string, many = `${one}s`): string => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** The one-line verdict: what the rule would have done, in words. */
export function backtestVerdict(p: XpAlertPreview): string {
  if (p.firings.length === 0) {
    return p.evaluatedPoints === 0 ? 'Nothing to replay: no data matched this rule in the last 24 hours.' : 'This rule would not have fired in the last 24 hours.';
  }
  const hosts = new Set(p.firings.map(groupName)).size;
  return `This rule would have fired ${plural(p.firings.length, 'time')} on ${plural(hosts, 'host')} and sent ${plural(p.wouldNotify, 'notification')}.`;
}

/** Threshold line and one marker per firing (at the moment it would have fired). */
export function backtestChart(p: XpAlertPreview, group: string | null): { series: ChartSeries[]; markers: ChartMarker[] } {
  const shown = (p.series ?? []).filter((s) => group === null || s.group === group);
  const series: ChartSeries[] = shown.map((s) => ({ key: s.group, name: s.group, points: s.points }));
  const ts = shown.flatMap((s) => s.points.map((x) => x[0]));
  if (p.threshold !== undefined && ts.length > 1) {
    series.push({ key: '__threshold', name: `Threshold ${p.op ?? ''} ${num(p.threshold)}`, points: [[Math.min(...ts), p.threshold], [Math.max(...ts), p.threshold]], dashed: true, color: 'var(--xp-crit)' });
  }
  const markers = p.firings.filter((f) => group === null || groupName(f) === group)
    .map((f) => ({ t: Date.parse(f.firedAt ?? f.from), label: `Fired on ${groupName(f)}${f.notified === false ? ' (not sent)' : ''}` }));
  return { series, markers };
}

/**
 * Backtest result: the value the rule compared with its threshold, minute by minute, where it would have fired, and
 * a firing list. Counts follow live alerting exactly ("for", flap guard, missing-data policy, repeat window).
 */
export const BacktestPanel: React.FC<{ p: XpAlertPreview }> = ({ p }) => {
  const [group, setGroup] = useState<string | null>(null);
  const chart = useMemo(() => backtestChart(p, group), [p, group]);
  const fromMs = p.from ? Date.parse(p.from) : undefined;
  const toMs = p.to ? Date.parse(p.to) : undefined;
  const groups = (p.series ?? []).map((s) => s.group);
  return (
    <div className="xp-backtest" role="region" aria-label="Backtest result">
      <p className="xp-backtest__verdict" role="status"><strong>{backtestVerdict(p)}</strong></p>
      <dl className="xp-backtest__counts">
        <div><dt>Firings</dt><dd>{p.firings.length.toLocaleString()}</dd></div>
        <div><dt>Notifications</dt><dd>{p.wouldNotify.toLocaleString()}</dd></div>
        {p.resolved !== undefined && <div><dt>Recoveries</dt><dd>{p.resolved.toLocaleString()}</dd></div>}
        {(p.suppressedRepeats ?? 0) > 0 && <div><dt title="Fired again inside the rule's repeat window: not sent">Repeats not sent</dt><dd>{p.suppressedRepeats}</dd></div>}
        {(p.quietReopens ?? 0) > 0 && <div><dt title="Breached again within 5 minutes of recovering: reopened without a notification">Quick re-opens</dt><dd>{p.quietReopens}</dd></div>}
        <div><dt>Evaluations</dt><dd>{p.evaluatedPoints.toLocaleString()}</dd></div>
      </dl>
      {chart.series.length > 0 && (
        <>
          {groups.length > 1 && (
            <div className="xp-seg" role="group" aria-label="Show host" style={{ marginBottom: 6 }}>
              <button type="button" aria-pressed={group === null} onClick={() => setGroup(null)}>All shown</button>
              {groups.map((g) => <button key={g} type="button" aria-pressed={group === g} onClick={() => setGroup(g)}>{g}</button>)}
            </div>
          )}
          <TimeSeriesChart showLegend series={chart.series} markers={chart.markers} height={170} ariaLabel="Backtest: value the rule compared, minute by minute"
            fromMs={fromMs} toMs={toMs} format={num} gapMs={180_000} />
        </>
      )}
      {p.firings.length > 0 && (
        <div className="xp-table-wrap" style={{ marginTop: 10 }}><table className="xp-table">
          <caption className="xp-sr">Firings in the backtest</caption>
          <thead><tr><th scope="col">Host</th><th scope="col">Breach from</th><th scope="col">Would fire</th><th scope="col">Recovered</th><th scope="col" className="xp-num">Worst</th><th scope="col">Sent</th></tr></thead>
          <tbody>{p.firings.slice(0, 50).map((f) => (
            <tr key={`${groupName(f)}-${f.firedAt ?? f.from}`}>
              <td>{groupName(f)}</td><td>{formatDateTime(f.from)}</td><td>{formatDateTime(f.firedAt ?? f.from)}</td>
              <td>{f.ongoing ? 'still firing' : formatDateTime(f.to)}</td><td className="xp-num">{num(f.peak)}</td>
              <td>{f.notified === false ? 'No (repeat or quick re-open)' : 'Yes'}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {p.firings.length > 50 && <p className="xp-muted" style={{ fontSize: 12 }}>First 50 of {p.firings.length.toLocaleString()} firings shown.</p>}
      {(p.notes ?? []).length > 0 && <ul className="xp-backtest__notes">{(p.notes ?? []).map((n) => <li key={n}>{n}</li>)}</ul>}
    </div>
  );
};
