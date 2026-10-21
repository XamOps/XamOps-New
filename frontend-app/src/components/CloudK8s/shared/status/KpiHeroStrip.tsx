/**
 * The hero + demoted-strip layout for a KPI band (DESIGN §4.1, §4.3, §5). The one chosen `KpiCard`
 * (rendered with `hero`) sits in tier 1 on the page; the remaining `KpiCard`s become a `divide-x`
 * strip of equal cells below it. Nothing about the cards themselves changes — the caller still passes
 * ordinary `KpiCard` elements, so every `data-kpi-tone` query, label and sub-line is preserved; this
 * component only arranges them. Neutral dividers only (no colour enters here).
 */
import type { ReactNode } from 'react';

export interface KpiHeroStripProps {
  /** The single tier-1 hero card (a `<KpiCard hero … />`). */
  hero: ReactNode;
  /** The demoted cards, laid out as a `divide-x` strip under the hero. */
  strip: ReactNode[];
  /** Extra attributes spread onto the band root (e.g. `data-overview-kpis`). */
  rootProps?: Record<string, string>;
}

// One divider colour, one radius-free strip: neutral greys only (the §5 strip). The columns resolve
// to a static Tailwind class per cell count so JIT keeps them; up to four demoted cells are in scope.
const DIVIDE = 'divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]';
const BORDER_T = 'border-t border-[rgba(0,0,0,0.06)] dark:border-[rgba(255,255,255,0.08)]';
const COLS: Record<number, string> = {
  1: 'sm:grid-cols-1',
  2: 'sm:grid-cols-2',
  3: 'sm:grid-cols-2 xl:grid-cols-3',
  4: 'sm:grid-cols-2 xl:grid-cols-4',
};

export function KpiHeroStrip({ hero, strip, rootProps = {} }: KpiHeroStripProps) {
  const cols = COLS[Math.min(Math.max(strip.length, 1), 4)] ?? COLS[4];
  return (
    // data-k8s-kit: the band carries only neutral dividers with their dark variants, so the legacy
    // dark CSS leaves it alone (darkKitExemption guard).
    <div className="space-y-4" data-k8s-kit="" {...rootProps}>
      {hero}
      <div className={`grid grid-cols-1 ${cols} ${BORDER_T} sm:divide-x ${DIVIDE}`}>
        {strip.map((cell, i) => (
          // eslint-disable-next-line react/no-array-index-key
          <div key={i} className="py-1 sm:px-1 sm:py-0">{cell}</div>
        ))}
      </div>
    </div>
  );
}

export default KpiHeroStrip;
