import React from 'react';
import type { XpLogPattern } from '../../../types/xampulse';
import { formatAgo, formatCount } from '../../../utils/xampulse/format';
import { EmptyState, SeverityBadge } from '../ui/ui';
import { Sparkline } from '../charts/Visuals';

/** Turns a pattern template into a query that finds its lines: the longest run of literal words, quoted. */
export function templateToQuery(template: string): string {
  const parts = template.split(/<\w+>|\d+/).map((s) => s.trim()).filter((s) => s.length > 2);
  const longest = parts.sort((a, b) => b.length - a.length)[0] ?? template;
  return `"${longest.replace(/"/g, '')}"`;
}

export const PatternList: React.FC<{ patterns: XpLogPattern[]; onPick: (p: XpLogPattern) => void }> = ({ patterns, onPick }) => {
  if (patterns.length === 0) return <EmptyState title="No patterns yet" body="Patterns group similar log lines so a flood of 10,000 lines reads as 3 stories." />;
  return (
    <div className="xp-table-wrap"><table className="xp-table">
      <caption className="xp-sr">Log patterns, most frequent first</caption>
      <thead><tr><th scope="col">Pattern</th><th scope="col" className="xp-num">Count</th><th scope="col" className="xp-num">Change</th><th scope="col">Trend</th><th scope="col">First seen</th></tr></thead>
      <tbody>{patterns.map((p) => (
        <tr key={p.patternId} data-clickable="true" tabIndex={0} onClick={() => onPick(p)} onKeyDown={(e) => { if (e.key === 'Enter') onPick(p); }} aria-label={`Pattern ${p.template}. Filter logs to this pattern.`}>
          <td style={{ maxWidth: 520, overflow: 'hidden', textOverflow: 'ellipsis' }}><SeverityBadge severity={p.severity} /> <span className="xp-mono">{p.template}</span> {p.isNew && <span className="xp-pill xp-pill--info">New</span>}</td>
          <td className="xp-num">{formatCount(p.count)}</td>
          <td className="xp-num">{p.isNew ? 'new' : p.deltaPct == null ? '\u2014' : `${p.deltaPct > 0 ? '+' : ''}${p.deltaPct}%`}</td>
          <td><Sparkline values={p.trend} label="Pattern trend" /></td>
          <td>{formatAgo(p.firstSeen)}</td>
        </tr>
      ))}</tbody>
    </table></div>
  );
};
