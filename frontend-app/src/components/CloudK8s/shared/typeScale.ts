/**
 * The CloudK8s v3 type scale (DESIGN.md §7).
 *
 * One big-number role with exactly two sizes (hero / KPI) plus three ranked
 * supporting levels. Every panel imports these so there is a single definition
 * and no two peers compete at the same size for the same meaning.
 *
 * Neutral `rgba` only (equal channels) so the `designRules` rgb-colour guard
 * stays green; status/series colour never enters through these tokens.
 */

// ONE big-number role, two sizes only:
export const HERO_NUMBER = 'text-[52px] font-semibold leading-none tabular-nums'; // tier-1 hero
export const KPI_NUMBER = 'text-[22px] font-semibold leading-tight tabular-nums'; // demoted strip / KpiCard / counts

// three ranked supporting levels:
export const EYEBROW = 'text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] dark:text-[rgba(255,255,255,0.5)]';
export const TITLE = 'text-[14px] font-semibold text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]';
export const BODY = 'text-[13px] text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]';
export const SECONDARY = 'text-[12px] text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]';
