import React from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { CircleCheck, CircleDashed, TriangleAlert } from 'lucide-react';
import type { XpApiError } from '../../../api/xampulseApi';
import type { XpHostLimits, XpLimitFinding, XpLimitSignal } from '../../../types/xampulse';
import { ErrorState, SkeletonBlock } from '../ui/ui';

const SOURCE_LABEL: Record<XpLimitFinding['source'], string> = { AWS: 'AWS limit', KERNEL: 'Kernel', CONTAINER: 'Container limit' };

/** Evidence values by their name: shares (0..1) as percent, *_pct already percent, rates per second, counts whole. */
export function formatEvidence(key: string, v: number): string {
  if (key.endsWith('_pct')) return `${v.toFixed(v < 10 ? 1 : 0)}%`;
  if (key.endsWith('_share') || key.endsWith('_ratio') || key === 'fill' || key === 'steal_avg') return `${(v * 100).toFixed(v < 0.1 ? 1 : 0)}%`;
  if (key === 'per_second') return `${v < 10 ? v.toFixed(2) : Math.round(v).toLocaleString()}/s`;
  if (key.endsWith('_seconds')) return `${Math.round(v).toLocaleString()} s`;
  return Math.round(v).toLocaleString();
}

export const evidenceLabel = (key: string): string => key.replace(/_pct$/, '').replace(/_/g, ' ');

/** One limit finding: who imposed it, what was measured, and the fix. Also used by Container 360. */
export const LimitFinding: React.FC<{ f: XpLimitFinding }> = ({ f }) => (
  <div className={`xp-banner xp-banner--${f.severity === 'CRITICAL' ? 'crit' : f.severity === 'WARNING' ? 'warn' : 'info'} xp-radar__finding`} role={f.severity === 'CRITICAL' ? 'alert' : undefined}>
    <div className="xp-row" style={{ gap: 8, flexWrap: 'wrap' }}>
      <span className={`xp-pill xp-pill--${f.source === 'AWS' ? 'info' : 'nodata'}`}>{SOURCE_LABEL[f.source]}</span>
      <strong style={{ fontWeight: 600 }}>{f.title}</strong>
    </div>
    <div className="xp-muted" style={{ fontSize: 12.5, marginTop: 4 }}>{f.detail}</div>
    <div style={{ fontSize: 12.5, marginTop: 6 }}><span className="xp-eyebrow" style={{ marginRight: 6 }}>Fix</span>{f.fix}</div>
    {Object.keys(f.evidence).length > 0 && (
      <div className="xp-row" style={{ gap: 6, marginTop: 8, flexWrap: 'wrap' }} aria-label="Measured evidence">
        {Object.entries(f.evidence).map(([k, v]) => (
          <span key={k} className="xp-chip xp-mono" title={`Measured ${evidenceLabel(k)}`}>{evidenceLabel(k)}: {formatEvidence(k, v)}</span>
        ))}
      </div>
    )}
  </div>
);

const SignalChip: React.FC<{ s: XpLimitSignal }> = ({ s }) => {
  const Icon = s.state === 'OK' ? CircleCheck : s.state === 'LIMITED' ? TriangleAlert : CircleDashed;
  const tone = s.state === 'OK' ? 'ok' : s.state === 'LIMITED' ? 'warn' : 'nodata';
  const state = s.state === 'OK' ? 'no limit hit' : s.state === 'LIMITED' ? 'limit hit' : 'not measured here';
  return (
    <li className={`xp-radar__signal xp-radar__signal--${tone}`} title={s.note}>
      <Icon size={13} strokeWidth={1.75} aria-hidden="true" />
      <span>{s.label}</span>
      <span className="xp-sr">: {state}</span>
    </li>
  );
};

/**
 * "Is something capping this host, and who?" Findings first (each with what was measured and the fix), then every
 * signal the radar knows with its state. A signal that could not be measured says so and why: it is never shown as
 * a green "healthy".
 */
export const ThrottleRadar: React.FC<{ q: UseQueryResult<XpHostLimits, XpApiError> }> = ({ q }) => {
  if (q.isLoading) return <SkeletonBlock height={72} label="Checking limits" />;
  if (q.error) return <ErrorState compact error={q.error} onRetry={() => { void q.refetch(); }} />;
  const l = q.data;
  if (!l) return null;
  const measured = l.signals.filter((s) => s.state !== 'NOT_MEASURED').length;
  const missing = l.signals.filter((s) => s.state === 'NOT_MEASURED');
  return (
    <section className="xp-radar" aria-label="Throttle Radar: limits hit in this range">
      <div className="xp-status-line">Evaluated {new Date(l.from).toLocaleString()} to {new Date(l.to).toLocaleString()}</div>
      {l.findings.length > 0
        ? <div className="xp-radar__findings">{l.findings.map((f) => <LimitFinding key={f.code + f.title} f={f} />)}</div>
        : (
          <div className="xp-banner xp-banner--ok">
            <strong style={{ fontWeight: 600 }}>No limits hit in this range.</strong>{' '}
            <span className="xp-muted">{measured} of {l.signals.length} limit checks could be measured on this host.</span>
          </div>
        )}
      <ul className="xp-radar__signals" aria-label="Limit checks">
        {l.signals.map((s) => <SignalChip key={s.code} s={s} />)}
      </ul>
      {missing.length > 0 && (
        <details className="xp-radar__missing">
          <summary>{missing.length} check{missing.length === 1 ? '' : 's'} not measurable here: why</summary>
          <ul>{missing.map((s) => <li key={s.code}><strong style={{ fontWeight: 600 }}>{s.label}.</strong> <span className="xp-muted">{s.note}</span></li>)}</ul>
        </details>
      )}
    </section>
  );
};
