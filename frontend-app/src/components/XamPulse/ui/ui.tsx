import React, { useEffect, useRef } from 'react';
import { XpApiError } from '../../../api/xampulseErrors';
import type { XpHealth, XpSeverity } from '../../../types/xampulse';

/** Small shared UI pieces. Status is always icon + text + colour, never colour alone. */

export const HEALTH_LABEL: Record<XpHealth, string> = { OK: 'Healthy', WARN: 'Warning', CRIT: 'Critical', NO_DATA: 'No data' };
const HEALTH_ICON: Record<XpHealth, string> = { OK: '\u2713', WARN: '\u25B2', CRIT: '\u2716', NO_DATA: '\u25CC' };
const HEALTH_CLASS: Record<XpHealth, string> = { OK: 'ok', WARN: 'warn', CRIT: 'crit', NO_DATA: 'nodata' };

export const StatusPill: React.FC<{ health: XpHealth; label?: string }> = ({ health, label }) => (
  <span className={`xp-pill xp-pill--${HEALTH_CLASS[health]}`}>
    <span aria-hidden="true">{HEALTH_ICON[health]}</span>
    {label ?? HEALTH_LABEL[health]}
  </span>
);

export const SeverityBadge: React.FC<{ severity: XpSeverity }> = ({ severity }) => (
  <span className={`xp-sev--${severity}`} style={{ fontWeight: 700, fontSize: 11, minWidth: 44, display: 'inline-block' }}>{severity === 'WARN' ? 'WARN' : severity}</span>
);

export const DemoPill: React.FC = () => <span className="xp-pill xp-pill--demo" title="Generated sample data, not from your AWS account">Demo</span>;

export const Card: React.FC<{ title?: React.ReactNode; actions?: React.ReactNode; children: React.ReactNode; className?: string; flat?: boolean; id?: string }> = ({ title, actions, children, className, flat, id }) => (
  <section className={`xp-card${flat ? ' xp-card--flat' : ''}${className ? ` ${className}` : ''}`} aria-labelledby={title && id ? `${id}-t` : undefined}>
    {(title || actions) && (
      <div className="xp-row" style={{ marginBottom: 8 }}>
        {title && <h2 id={id ? `${id}-t` : undefined}>{title}</h2>}
        <span className="xp-spacer" />
        {actions}
      </div>
    )}
    {children}
  </section>
);

export const KpiTile: React.FC<{ label: string; value: React.ReactNode; hint?: React.ReactNode; children?: React.ReactNode }> = ({ label, value, hint, children }) => (
  <div className="xp-card xp-kpi">
    <span className="xp-kpi__l">{label}</span>
    <span className="xp-kpi__v">{value}</span>
    {hint && <span className="xp-muted" style={{ fontSize: 12 }}>{hint}</span>}
    {children}
  </div>
);

export const SkeletonBlock: React.FC<{ height?: number; width?: number | string; label?: string }> = ({ height = 80, width = '100%', label = 'Loading' }) => (
  <div className="xp-skeleton" style={{ height, width }} role="status" aria-label={label} />
);

export const LoadingGrid: React.FC<{ count?: number; height?: number }> = ({ count = 4, height = 96 }) => (
  <div className="xp-grid xp-grid--kpi" aria-busy="true">
    {Array.from({ length: count }, (_, i) => <SkeletonBlock key={i} height={height} />)}
  </div>
);

export const EmptyState: React.FC<{ title: string; body?: React.ReactNode; steps?: string[]; action?: React.ReactNode }> = ({ title, body, steps, action }) => (
  <div className="xp-empty">
    <h3 style={{ margin: 0, fontSize: 18 }}>{title}</h3>
    {body && <p className="xp-muted" style={{ margin: 0, maxWidth: 520 }}>{body}</p>}
    {steps && steps.length > 0 && (
      <ol className="xp-muted" style={{ textAlign: 'left', margin: 0, paddingLeft: 20 }}>
        {steps.map((s) => <li key={s}>{s}</li>)}
      </ol>
    )}
    {action}
  </div>
);

/** Maps an API error to a friendly one-liner. */
export function friendlyError(e: unknown): string {
  if (e instanceof XpApiError) {
    switch (e.code) {
      case 'XP_QUERY_TOO_BROAD': return 'That time range is too wide for an interactive search. Pick 30 days or less.';
      case 'XP_QUERY_TIMEOUT': return 'The query took too long. Try a shorter range or a narrower filter.';
      case 'XP_STORE_UNAVAILABLE': return 'The XamPulse data store is not reachable right now.';
      case 'XP_FORBIDDEN': return 'You do not have permission to do this.';
      case 'XP_RATE_LIMITED': return 'Too many requests. Wait a moment and retry.';
      default: return e.message;
    }
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}

export const ErrorState: React.FC<{ error: unknown; onRetry?: () => void; compact?: boolean }> = ({ error, onRetry, compact }) => {
  const requestId = error instanceof XpApiError ? error.requestId : '';
  return (
    <div className={`xp-banner xp-banner--crit${compact ? '' : ' xp-empty'}`} role="alert">
      <strong>Could not load this.</strong>{' '}
      <span>{friendlyError(error)}</span>
      {requestId && <div className="xp-muted xp-mono" style={{ fontSize: 12 }}>Request id: {requestId}</div>}
      {onRetry && <div style={{ marginTop: 8 }}><button type="button" className="xp-btn" onClick={onRetry}>Retry</button></div>}
    </div>
  );
};

/** Right-hand drawer with focus trap, Esc to close and focus return. */
export const Drawer: React.FC<{ title: React.ReactNode; onClose: () => void; children: React.ReactNode; label: string }> = ({ title, onClose, children, label }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const node = ref.current;
    const focusables = (): HTMLElement[] => (node ? Array.from(node.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])')) : []);
    (focusables()[0] ?? node)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); onClose(); return; }
      if (e.key !== 'Tab') return;
      const f = focusables();
      if (f.length === 0) return;
      const first = f[0];
      const last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); previous?.focus?.(); };
  }, [onClose]);
  return (
    <>
      <div className="xp-scrim" onClick={onClose} aria-hidden="true" />
      <div className="xp-drawer" role="dialog" aria-modal="true" aria-label={label} ref={ref} tabIndex={-1}>
        <div className="xp-drawer__head">
          <div style={{ flex: 1, minWidth: 0 }}>{title}</div>
          <button type="button" className="xp-btn" onClick={onClose} aria-label="Close panel">Close <span className="xp-kbd">Esc</span></button>
        </div>
        <div className="xp-drawer__body">{children}</div>
      </div>
    </>
  );
};

/** Centered modal with the same focus handling. */
export const Modal: React.FC<{ title: string; onClose: () => void; children: React.ReactNode }> = ({ title, onClose, children }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); previous?.focus?.(); };
  }, [onClose]);
  return (
    <>
      <div className="xp-scrim" onClick={onClose} aria-hidden="true" />
      <div className="xp-modal" role="dialog" aria-modal="true" aria-label={title} ref={ref} tabIndex={-1}>
        <h2 style={{ marginTop: 0 }}>{title}</h2>
        {children}
      </div>
    </>
  );
};

export const BarMeter: React.FC<{ pct: number | null; label: string }> = ({ pct, label }) => {
  if (pct == null) return <span className="xp-muted">{'\u2014'}</span>;
  const level = pct >= 90 ? 'crit' : pct >= 75 ? 'warn' : 'ok';
  return (
    <span>
      <span className="xp-bar" data-level={level} role="img" aria-label={`${label} ${pct.toFixed(0)} percent`}><i style={{ width: `${Math.min(100, pct)}%` }} /></span>
      {pct.toFixed(0)}%
    </span>
  );
};
