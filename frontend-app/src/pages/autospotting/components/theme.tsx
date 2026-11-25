/**
 * The app's house style (indigo accent, "style A") for the AutoSpotting shell, as the CloudSitter,
 * Rightsizing and Reservation pages use it. One place for the class strings so every screen matches.
 * Colour is never the only signal: every notice and badge also states its meaning in text.
 */
import React from 'react';
import { Icon } from '@iconify/react';

/** Primary text, secondary text (4.7:1 on white) and dividers. */
export const TEXT = 'text-[rgba(0,0,0,0.88)]';
export const MUTED = 'text-[rgba(0,0,0,0.55)]';

const FOCUS = 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 focus-visible:ring-offset-1';

export const BTN_PRIMARY =
  `inline-flex items-center justify-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-[12px] font-semibold text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-indigo-600 ${FOCUS}`;
export const BTN_DANGER =
  `inline-flex items-center justify-center gap-1.5 rounded-lg bg-red-600 px-3 py-1.5 text-[12px] font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-red-600 ${FOCUS}`;
export const BTN_SECONDARY =
  `inline-flex items-center justify-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] transition-colors hover:bg-[rgba(0,0,0,0.02)] hover:text-[rgba(0,0,0,0.88)] disabled:cursor-not-allowed disabled:opacity-40 ${FOCUS}`;
/** A small text-weight button (Copy, Close) for dense rows. */
export const BTN_SMALL =
  `inline-flex items-center gap-1 rounded-md border border-[rgba(0,0,0,0.12)] bg-white px-2 py-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.65)] hover:text-[rgba(0,0,0,0.88)] ${FOCUS}`;
export const LINK = `font-semibold text-indigo-700 underline-offset-2 hover:underline ${FOCUS} rounded`;

export const CARD = 'rounded-xl border border-[rgba(0,0,0,0.12)] bg-white shadow-sm';
export const CARD_TITLE = `text-[13px] font-semibold ${TEXT}`;

/** Form controls keep a boundary of at least gray-400 contrast. */
export const INPUT =
  `rounded-lg border border-[rgba(0,0,0,0.38)] bg-white px-2.5 py-1.5 text-[12px] ${TEXT} focus:border-indigo-400 focus:outline-none focus:ring-2 focus:ring-indigo-300`;
export const FIELD_LABEL = `text-[11px] font-semibold uppercase tracking-wide ${MUTED}`;
export const CHECKBOX = 'mt-0.5 h-4 w-4 shrink-0 accent-indigo-600';

/** Table-card pieces (CloudSitter list table). */
export const TABLE = `w-full text-left text-[13px] ${TEXT}`;
export const THEAD_ROW = 'border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]';
export const TH = `whitespace-nowrap px-5 py-2 text-[11px] font-semibold uppercase tracking-wide ${MUTED}`;
export const TR = 'border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.01)]';
export const TD = 'whitespace-nowrap px-5 py-3';
/** Compact variants for tables inside the detail panel and dialogs. */
export const TH_SM = `px-3 py-2 text-[11px] font-semibold uppercase tracking-wide ${MUTED}`;
export const TD_SM = 'px-3 py-2';

/** A neutral rounded chip, e.g. a money tag or an outcome code. */
export const CHIP = `inline-block rounded border border-[rgba(0,0,0,0.12)] bg-white px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${MUTED} align-middle`;

export type Tone = 'warning' | 'danger' | 'success' | 'info' | 'neutral';

const TONES: Record<Tone, { box: string; icon: string; iconClass: string }> = {
  warning: { box: 'border-amber-200 bg-amber-50 text-amber-900', icon: 'mdi:alert-outline', iconClass: 'text-amber-700' },
  danger: { box: 'border-red-200 bg-red-50 text-red-900', icon: 'mdi:alert-circle-outline', iconClass: 'text-red-700' },
  success: { box: 'border-emerald-200 bg-emerald-50 text-emerald-900', icon: 'mdi:check-circle-outline', iconClass: 'text-emerald-700' },
  info: { box: 'border-indigo-200 bg-indigo-50 text-indigo-900', icon: 'mdi:information-outline', iconClass: 'text-indigo-700' },
  neutral: { box: `border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] ${TEXT}`, icon: 'mdi:information-outline', iconClass: 'text-[rgba(0,0,0,0.55)]' },
};

type NoticeProps = {
  tone: Tone;
  icon?: string;
  className?: string;
  children: React.ReactNode;
} & Pick<React.HTMLAttributes<HTMLDivElement>, 'role' | 'aria-label'>;

/** An inline notice card with a leading icon (Beta notice, warnings, errors). */
export const Notice: React.FC<NoticeProps> = ({ tone, icon, className = '', children, ...rest }) => {
  const t = TONES[tone];
  return (
    <div {...rest} className={`flex items-start gap-2.5 rounded-xl border px-4 py-3 text-[13px] ${t.box} ${className}`}>
      <Icon icon={icon ?? t.icon} width={16} aria-hidden="true" className={`mt-0.5 shrink-0 ${t.iconClass}`} />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
};

/** The empty-state card: round icon holder, 14px title, 13px hint. */
export const EmptyState: React.FC<{
  icon: string;
  title: React.ReactNode;
  children?: React.ReactNode;
  role?: string;
  iconClass?: string;
}> = ({ icon, title, children, role, iconClass = 'text-[rgba(0,0,0,0.45)]' }) => (
  <div role={role} className={`${CARD} p-10 text-center`}>
    <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-[rgba(0,0,0,0.04)]">
      <Icon icon={icon} width={22} aria-hidden="true" className={iconClass} />
    </div>
    <p className={`text-[14px] font-semibold ${TEXT}`}>{title}</p>
    {children && <div className={`mt-1 text-[13px] ${MUTED}`}>{children}</div>}
  </div>
);

/** Segmented range buttons for the Analytics and Activity views (aria-pressed toggles). */
export const rangeButtonClass = (active: boolean): string =>
  `rounded-lg border px-3 py-1.5 text-[12px] font-semibold transition-colors ${FOCUS} ${
    active
      ? 'border-indigo-600 bg-indigo-600 text-white'
      : 'border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.65)] hover:text-[rgba(0,0,0,0.88)]'
  }`;
