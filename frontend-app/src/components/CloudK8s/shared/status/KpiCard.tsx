/**
 * A KPI card (design-delta §1 "KPI cards", §2.2; glossary §10): a left status-accent bar, an
 * uppercase label tinted in the tone with its icon, and the number in ink — never in colour. The
 * sub-lines sit in grey under the number. The label and icon are required, so a KPI's colour always
 * reaches the page with a word and an icon attached.
 */
import type { ReactNode } from 'react';
import { Icon } from '@iconify/react';
import type { Tone } from './statusTones';
import { toneClasses } from './toneClasses';

export interface KpiCardProps {
  tone: Tone;
  /** A bundled `mdi:*` id for the label. */
  icon: string;
  /** The label text; shown uppercase. Required. */
  label: string;
  /** The headline number, in ink. */
  value: ReactNode;
  /** Optional sub-lines under the number, in grey. */
  children?: ReactNode;
  className?: string;
}

export function KpiCard({ tone, icon, label, value, children, className = '' }: KpiCardProps) {
  const t = toneClasses(tone);
  return (
    <div
      className={`relative overflow-hidden rounded-lg border border-[rgba(0,0,0,0.08)] bg-white pl-4 pr-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414] ${className}`}
      data-kpi-tone={tone}
      data-k8s-kit=""
    >
      <span className={`absolute inset-y-0 left-0 w-1 ${t.border} border-l-4`} aria-hidden="true" />
      <div className={`flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide ${t.text}`}>
        <Icon icon={icon} width={13} aria-hidden="true" className="shrink-0" />
        <span>{label}</span>
      </div>
      <div className="mt-1 text-[22px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]">
        {value}
      </div>
      {children && (
        <div className="mt-1 space-y-0.5 text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]">
          {children}
        </div>
      )}
    </div>
  );
}

export default KpiCard;
