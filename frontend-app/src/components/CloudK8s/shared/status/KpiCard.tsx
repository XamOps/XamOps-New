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
import { HERO_NUMBER, KPI_NUMBER } from '../typeScale';

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
  /**
   * The one hero per panel (DESIGN §4.1/§7): the number steps up to `HERO_NUMBER` (52 px) and the
   * card sits on the page with no card chrome. Every other prop — label, icon, tone accent, sub-lines
   * and the `data-kpi-tone`/`data-k8s-kit` hooks — is unchanged, so the card still reads as the same
   * KPI and the same queries find it. The default (KPI) card is the demoted-strip member.
   */
  hero?: boolean;
  className?: string;
}

export function KpiCard({ tone, icon, label, value, children, hero = false, className = '' }: KpiCardProps) {
  const t = toneClasses(tone);
  // The hero drops the card chrome (tier-1 sits on the page) and keeps only the status accent bar;
  // the demoted card keeps the bordered chrome it has always had.
  const chrome = hero
    ? 'relative pl-4 pr-4 py-1'
    : 'relative overflow-hidden rounded-lg border border-[rgba(0,0,0,0.08)] bg-white pl-4 pr-4 py-3 dark:border-[rgba(255,255,255,0.1)] dark:bg-[#141414]';
  return (
    <div
      className={`${chrome} ${className}`.trim()}
      data-kpi-tone={tone}
      data-kpi-hero={hero ? '' : undefined}
      data-k8s-kit=""
    >
      <span className={`absolute inset-y-0 left-0 w-1 ${t.border} border-l-4`} aria-hidden="true" />
      <div className={`flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide ${t.text}`}>
        <Icon icon={icon} width={13} aria-hidden="true" className="shrink-0" />
        <span>{label}</span>
      </div>
      <div className={`mt-1 text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)] ${hero ? HERO_NUMBER : KPI_NUMBER}`}>
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
