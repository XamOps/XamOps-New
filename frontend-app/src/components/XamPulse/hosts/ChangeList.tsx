import React from 'react';
import { Link } from 'react-router-dom';
import { Activity, CircleSlash, Cpu, Package, Play, Power, RefreshCcw, Rocket, RotateCcw, Server, Settings2, Square, type LucideIcon } from 'lucide-react';
import type { XpChange, XpChangeEffect } from '../../../types/xampulse';
import { formatAgo, formatBytes, formatClock } from '../../../utils/xampulse/format';
import { useViewContext } from '../../../hooks/xampulse/useViewContext';
import { TonePill } from '../ui/ui';

const KIND_ICON: Record<string, LucideIcon> = {
  DEPLOY: Rocket, RECREATE: RefreshCcw, CONTAINER_START: Play, CONTAINER_STOP: Square, RESTART: RotateCcw, REBOOT: Power,
  DATA_GAP: CircleSlash, PACKAGE: Package, CONFIG: Settings2, EC2_STATE: Server, AGENT: Cpu,
};

const VERDICT: Record<string, { label: string; tone: 'info' | 'ok' | 'nodata' }> = {
  FOLLOWED_BY_CHANGE: { label: 'Followed by a change in the measurements', tone: 'info' },
  NO_CLEAR_EFFECT: { label: 'No clear effect after it', tone: 'ok' },
  TOO_RECENT: { label: 'Too recent to judge', tone: 'nodata' },
  NOT_ENOUGH_DATA: { label: 'Not enough data around it', tone: 'nodata' },
};

/** A before/after value in the signal's own unit. */
export function formatEffectValue(unit: string, v: number): string {
  if (unit === 'bytes') return formatBytes(v);
  if (unit === '/min') return `${v < 10 ? v.toFixed(1) : Math.round(v)}/min`;
  if (unit.startsWith('%')) return `${v < 10 ? v.toFixed(1) : Math.round(v)}%`;
  return v.toFixed(1);
}

export function effectText(e: XpChangeEffect): string {
  const pct = Math.round(e.relative * 100);
  return `${e.label} ${formatEffectValue(e.unit, e.before)} → ${formatEffectValue(e.unit, e.after)} (${pct > 0 ? '+' : ''}${pct}%, ${e.strength.toLowerCase()})`;
}

/** Marker for a chart: when it happened and what it was. */
export const changeMarkers = (changes: XpChange[] | undefined): { t: number; label: string }[] =>
  (changes ?? []).map((c) => ({ t: Date.parse(c.ts), label: c.title }));

/**
 * Changes, newest first, each with what the measurements did right after it. Effects describe what followed, never a
 * cause; a change within 10 minutes of another on the same host is marked as not separable.
 */
export const ChangeList: React.FC<{ changes: XpChange[]; limit?: number; containerLink?: (name: string) => string }> = ({ changes, limit, containerLink }) => {
  const ctx = useViewContext();
  const shown = limit ? changes.slice(0, limit) : changes;
  if (shown.length === 0) return <p className="xp-muted" style={{ margin: 0 }}>No changes seen in this range.</p>;
  return (
    <ol className="xp-changes" aria-label="Changes, newest first">
      {shown.map((c) => {
        const Icon = KIND_ICON[c.kind] ?? Activity;
        const v = c.verdict ? VERDICT[c.verdict] : undefined;
        return (
          <li key={`${c.ts}-${c.kind}-${c.title}`} className="xp-change">
            <span className="xp-change__ic" aria-hidden="true"><Icon size={13} strokeWidth={1.75} /></span>
            <div className="xp-change__body">
              <div className="xp-row" style={{ gap: 8, flexWrap: 'wrap' }}>
                <strong style={{ fontWeight: 600 }}>{c.title}</strong>
                {c.subject && containerLink && <Link className="xp-muted" style={{ fontSize: 12 }} to={ctx.link(containerLink(c.subject))}>open container</Link>}
                <span className="xp-spacer" />
                <time className="xp-muted xp-mono" style={{ fontSize: 11.5 }} dateTime={c.ts} title={c.ts}>{formatClock(c.ts).slice(0, 5)} · {formatAgo(c.ts)}</time>
              </div>
              {c.detail && <div className="xp-muted" style={{ fontSize: 12.5, marginTop: 2 }}>{c.detail}</div>}
              <div className="xp-location">Source {c.source} · host <span className="xp-mono">{c.instanceId}</span></div>
              {(v || c.effects.length > 0 || c.overlapping) && (
                <div className="xp-row" style={{ gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
                  {v && <TonePill tone={v.tone}>{v.label}</TonePill>}
                  {c.effects.map((e) => <span key={e.signal + e.label} className="xp-chip xp-mono xp-change__effect" data-strength={e.strength}>{effectText(e)}</span>)}
                  {c.overlapping && <span className="xp-muted" style={{ fontSize: 12 }}>Another change happened within 10 minutes: their effects cannot be told apart.</span>}
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
};
