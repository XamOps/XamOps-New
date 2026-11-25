import React, { useEffect, useRef, useState } from 'react';
import { X, type LucideIcon } from 'lucide-react';
import { XpApiError } from '../../../api/xampulseErrors';
import type { XpHealth, XpSeverity } from '../../../types/xampulse';

/** Small shared UI pieces. Status is always a word plus colour, never colour alone. */

export const HEALTH_LABEL: Record<XpHealth, string> = { OK: 'Healthy', WARN: 'Warning', CRIT: 'Critical', NO_DATA: 'No data' };
const HEALTH_CLASS: Record<XpHealth, string> = { OK: 'ok', WARN: 'warn', CRIT: 'crit', NO_DATA: 'nodata' };
export const HEALTH_VAR: Record<XpHealth, string> = { OK: 'var(--xp-ok)', WARN: 'var(--xp-warn)', CRIT: 'var(--xp-crit)', NO_DATA: 'var(--xp-nodata)' };

/** Status badge: coloured dot + uppercase label on a barely tinted ground. Critical states pulse gently. */
export const StatusPill: React.FC<{ health: XpHealth; label?: string }> = ({ health, label }) => (
  <span className={`xp-pill xp-pill--${HEALTH_CLASS[health]}`}>
    <span className={`xp-dot${health === 'CRIT' ? ' xp-live-dot' : ''}`} aria-hidden="true" />
    {label ?? HEALTH_LABEL[health]}
  </span>
);

/** A severity-tinted pill for any of the four semantic tones. */
export const TonePill: React.FC<{ tone: 'ok' | 'warn' | 'crit' | 'info' | 'nodata'; children: React.ReactNode; live?: boolean }> = ({ tone, children, live }) => (
  <span className={`xp-pill xp-pill--${tone}`}><span className={`xp-dot${live ? ' xp-live-dot' : ''}`} aria-hidden="true" />{children}</span>
);

export const SeverityBadge: React.FC<{ severity: XpSeverity }> = ({ severity }) => (
  <span className={`xp-sev xp-sev--${severity}`}>{severity}</span>
);

export const DemoPill: React.FC = () => <span className="xp-pill xp-pill--demo" title="Generated sample data, not from your AWS account">Demo</span>;

/** Page title row: muted contextual icon, light title, one terse context line, actions on the right. */
export const PageHead: React.FC<{ icon?: LucideIcon; title: React.ReactNode; sub?: React.ReactNode; actions?: React.ReactNode; extra?: React.ReactNode }> = ({ icon: Icon, title, sub, actions, extra }) => (
  <div className="xp-pagehead">
    <div className="xp-pagehead__l">
      <div className="xp-row" style={{ gap: 14 }}>
        <h1 className="xp-title">{Icon && <Icon size={18} strokeWidth={1.5} aria-hidden="true" />}{title}</h1>
        {extra}
      </div>
      {sub && <p className="xp-sub">{sub}</p>}
    </div>
    {actions && <div className="xp-pagehead__r">{actions}</div>}
  </div>
);

export const Card: React.FC<{ title?: React.ReactNode; actions?: React.ReactNode; children: React.ReactNode; className?: string; flat?: boolean; id?: string }> = ({ title, actions, children, className, flat, id }) => (
  <section className={`xp-card${flat ? ' xp-card--flat' : ''}${className ? ` ${className}` : ''}`} aria-labelledby={title && id ? `${id}-t` : undefined}>
    {(title || actions) && (
      <div className="xp-card__head">
        {title && <h2 id={id ? `${id}-t` : undefined}>{title}</h2>}
        <span className="xp-spacer" />
        {actions}
      </div>
    )}
    {children}
  </section>
);

/** Answer-first summary for a page or tab: the status, why, what to do next, and the facts behind it. */
export interface Verdict { level: XpHealth; title: string; cause: string; next: string; facts: { label: string; value: string }[] }
export const VerdictCard: React.FC<{ v: Verdict; label: string; actions?: React.ReactNode }> = ({ v, label, actions }) => (
  <section className="xp-card" aria-label={label}>
    <div className="xp-verdict">
      <div className="xp-verdict__title"><StatusPill health={v.level} />{v.title}<span className="xp-spacer" />{actions}</div>
      <p className="xp-verdict__line"><strong>Why: </strong>{v.cause}</p>
      <p className="xp-verdict__line"><strong>Next: </strong>{v.next}</p>
      {v.facts.length > 0 && <div className="xp-verdict__facts">{v.facts.map((f) => <span key={f.label} className="xp-verdict__fact"><span>{f.label}</span><span>{f.value}</span></span>)}</div>}
    </div>
  </section>
);

function reducedMotion(): boolean {
  return typeof window === 'undefined' || typeof window.matchMedia !== 'function' || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Eases a number towards `target` (about 900ms, ease-out cubic). Re-renders never restart it: a new target
 * eases on from the value currently shown. Skipped under reduced motion and where matchMedia is missing.
 */
export function useCountUp(target: number, duration = 900): number {
  const [value, setValue] = useState(() => (reducedMotion() ? target : 0));
  const shown = useRef(reducedMotion() ? target : 0);
  useEffect(() => {
    if (!Number.isFinite(target) || reducedMotion()) { shown.current = target; setValue(target); return undefined; }
    const from = shown.current;
    if (from === target) return undefined;
    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      const v = from + (target - from) * (1 - Math.pow(1 - t, 3));
      shown.current = v;
      setValue(v);
      if (t < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, duration]);
  return value;
}

const NUM_RE = /-?\d[\d,]*(?:\.\d+)?/;

/**
 * Counts up the first number inside a formatted string ("1.68 GB", "6 / 6", "100%"), keeping its decimals,
 * thousands separators and the surrounding text. A trailing unit is set smaller and muted.
 */
export const CountUpText: React.FC<{ text: string }> = ({ text }) => {
  const m = NUM_RE.exec(text);
  const raw = m?.[0] ?? '';
  const target = m ? Number(raw.replace(/,/g, '')) : NaN;
  const decimals = raw.includes('.') ? raw.split('.')[1].length : 0;
  const v = useCountUp(Number.isFinite(target) ? target : 0);
  if (!m || !Number.isFinite(target)) return <>{text}</>;
  const shownNum = raw.includes(',') ? v.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals }) : v.toFixed(decimals);
  const before = text.slice(0, m.index);
  const after = text.slice(m.index + raw.length);
  const unit = /^\s?[A-Za-z%/]+$/.test(after) && after.trim().length <= 4 ? after : null;
  return (
    <>
      <span className="xp-sr">{text}</span>
      <span aria-hidden="true">{before}{shownNum}{unit ? <span className="xp-kpi__unit">{unit.trim()}</span> : after}</span>
    </>
  );
};

/**
 * Summary tile: dot + uppercase label, a light hero number that counts up on load, one muted line.
 * One of the few places a bordered card earns its keep.
 */
/** Icon-tile tints, as on the XamOps AWS cost page (emerald, violet, indigo, amber). */
const KPI_TONE = { green: 'var(--xp-accent-text)', violet: 'var(--xp-violet)', indigo: 'var(--xp-info)', amber: 'var(--xp-warn)', red: 'var(--xp-crit)' } as const;
export type KpiTone = keyof typeof KPI_TONE;

/** One column of a KPI strip (wrap several in `xp-grid xp-grid--kpi`): tinted icon, label, value, context line. */
export const KpiTile: React.FC<{ label: string; value: React.ReactNode; hint?: React.ReactNode; status?: XpHealth; icon?: LucideIcon; tone?: KpiTone; children?: React.ReactNode }> = ({ label, value, hint, status, icon: Icon, tone = 'green', children }) => (
  <div className="xp-card xp-kpi">
    {Icon && <span className="xp-kpi__icon" style={{ color: KPI_TONE[tone] }} aria-hidden="true"><Icon size={18} strokeWidth={1.75} /></span>}
    <div className="xp-kpi__body">
      <span className="xp-kpi__l"><span>{label}</span>{status && <span className="xp-kpi__status" style={{ color: HEALTH_VAR[status] }}><span className="xp-dot" aria-hidden="true" />{HEALTH_LABEL[status]}</span>}</span>
      <span className="xp-kpi__v">{typeof value === 'number' ? <CountUpText text={String(value)} /> : typeof value === 'string' ? <CountUpText text={value} /> : value}</span>
      {hint && <span className="xp-kpi__h">{hint}</span>}
      {children}
    </div>
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

export const EmptyState: React.FC<{ title: string; body?: React.ReactNode; steps?: string[]; action?: React.ReactNode; icon?: LucideIcon }> = ({ title, body, steps, action, icon: Icon }) => (
  <div className="xp-empty">
    {Icon && <Icon size={20} strokeWidth={1.5} className="xp-empty__icon" aria-hidden="true" />}
    <h3>{title}</h3>
    {body && <p className="xp-muted" style={{ margin: 0, maxWidth: 520, fontSize: 12.5 }}>{body}</p>}
    {steps && steps.length > 0 && (
      <ol className="xp-muted" style={{ textAlign: 'left', margin: '4px 0', paddingLeft: 20, fontSize: 12.5, lineHeight: 1.8 }}>
        {steps.map((s) => <li key={s}>{s}</li>)}
      </ol>
    )}
    {action && <div style={{ marginTop: 6 }}>{action}</div>}
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
      <strong style={{ fontWeight: 500, color: 'var(--xp-text)' }}>Could not load this.</strong>{' '}
      <span>{friendlyError(error)}</span>
      {requestId && <div className="xp-muted xp-mono" style={{ fontSize: 11, marginTop: 4 }}>Request id: {requestId}</div>}
      {onRetry && <div style={{ marginTop: 10 }}><button type="button" className="xp-btn xp-btn--sm" onClick={onRetry}>Retry</button></div>}
    </div>
  );
};

/** Right-hand drawer with focus trap, Esc to close and focus return. Slides in like a native sheet. */
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
          <span className="xp-kbd" aria-hidden="true">Esc</span>
          <button type="button" className="xp-btn xp-btn--ghost xp-btn--icon" onClick={onClose} aria-label="Close panel" title="Close"><X size={16} strokeWidth={1.5} /></button>
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
        <h2>{title}</h2>
        {children}
      </div>
    </>
  );
};

/** Linear meter. The bar always wears the accent; the number carries the state (warn at 75, crit at 90). */
export const BarMeter: React.FC<{ pct: number | null; label: string }> = ({ pct, label }) => {
  if (pct == null) return <span className="xp-muted">{'—'}</span>;
  const level = pct >= 90 ? 'crit' : pct >= 75 ? 'warn' : 'ok';
  return (
    <span className="xp-meter" data-level={level}>
      <span className="xp-bar" role="img" aria-label={`${label} ${pct.toFixed(0)} percent`}><i style={{ width: `${Math.min(100, pct)}%` }} /></span>
      <span className="xp-meter__v">{pct.toFixed(0)}%</span>
    </span>
  );
};
