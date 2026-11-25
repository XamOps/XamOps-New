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

/** The first literal words of a line, quoted, to filter by "this kind of line". */
export function lineToPatternQuery(body: string): string {
  const first = body.split('\n')[0];
  return templateToQuery(first.replace(/\d+/g, '<N>'));
}

export const PatternList: React.FC<{ patterns: XpLogPattern[]; onPick: (p: XpLogPattern) => void; onSample?: (p: XpLogPattern) => void }> = ({ patterns, onPick, onSample }) => {
  if (!patterns.length) return <EmptyState title="No patterns yet" body="Patterns group similar log lines so a flood becomes a small set of stories." />;
  return <div className="xp-table-wrap"><table className="xp-table">
    <caption className="xp-sr">Log patterns, most frequent first</caption>
    <thead><tr><th scope="col">Pattern</th><th scope="col" className="xp-num">Count</th><th scope="col" className="xp-num">Change</th><th scope="col">Trend</th><th scope="col">First seen</th><th scope="col"><span className="xp-sr">Actions</span></th></tr></thead>
    <tbody>{patterns.map((pattern) => <tr key={pattern.patternId}>
      <td style={{ maxWidth: 520, whiteSpace: 'normal' }}><SeverityBadge severity={pattern.severity} /> <span className="xp-mono">{pattern.template}</span> {pattern.isNew && <span className="xp-pill xp-pill--info">New</span>}</td>
      <td className="xp-num xp-mono">{formatCount(pattern.count)}</td>
      <td className="xp-num xp-mono" data-status={pattern.isNew || (pattern.deltaPct ?? 0) > 50 ? 'warning' : undefined}>{pattern.isNew ? 'new' : pattern.deltaPct == null ? '—' : `${pattern.deltaPct > 0 ? '+' : ''}${pattern.deltaPct}%`}</td>
      <td><Sparkline values={pattern.trend} label={`${pattern.template} occurrence trend`} /></td><td className="xp-mono">{formatAgo(pattern.firstSeen)}</td>
      <td><div className="xp-row"><button type="button" className="xp-btn xp-btn--sm" onClick={() => onPick(pattern)}>Filter lines</button>{onSample && <button type="button" className="xp-btn xp-btn--ghost xp-btn--sm" onClick={() => onSample(pattern)}>Open sample</button>}</div></td>
    </tr>)}</tbody>
  </table></div>;
};
