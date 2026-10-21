/**
 * `Surface` — the single source of card chrome for the CloudK8s v3 relayout
 * (DESIGN.md §2, §3). Everything on these pages collapses into exactly three
 * tiers; `tier` is the only knob that changes weight, so callers never
 * re-declare radius / border / shadow / padding again.
 *
 *   hero    — no card; sits directly on the page background.
 *   grouped — one container (radius + border + bg), NO shadow.
 *   table   — one container + the single allowed elevation step.
 *
 * Colour safety: this component uses ONLY neutral rgba(0,0,0,x) /
 * rgba(255,255,255,x), bg-white and #141414 (all equal-channel), and carries
 * no text-status, text-series, text-feature or var(--k8s-...) classes — those
 * are token-scope-restricted to the status/chart components. Status/series
 * colour enters a Surface only through the children the caller passes.
 */
import type { ReactNode } from 'react';
import { EYEBROW, TITLE } from './typeScale';

export type SurfaceTier = 'hero' | 'grouped' | 'table';

export interface SurfaceProps {
  tier: SurfaceTier;
  /** Optional eyebrow/title row rendered inside the surface header. */
  title?: ReactNode;
  /** Right-aligned header content (SourceLabel, a link, a count tag). */
  aside?: ReactNode;
  /** Adds the single hairline divider between direct children (one level only). */
  divide?: 'x' | 'y' | 'none'; // default 'none'
  as?: 'section' | 'div'; // default 'section'
  'aria-label'?: string;
  'aria-labelledby'?: string;
  className?: string;
  children: ReactNode;
}

// The tokens the component holds internally (do NOT re-declare these per page).
const RADIUS = 'rounded-[10px]';
const BORDER = 'border border-[rgba(0,0,0,0.07)] dark:border-[rgba(255,255,255,0.09)]';
const BG = 'bg-white dark:bg-[#141414]';
const DIVIDE_C = 'divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]';
const SHADOW = 'shadow-[0_1px_2px_rgba(0,0,0,0.04)]';

const CHROME: Record<SurfaceTier, string> = {
  hero: 'px-0 py-0',
  grouped: `${RADIUS} ${BORDER} ${BG} p-5`,
  table: `${RADIUS} ${BORDER} ${BG} ${SHADOW}`,
};

const DIVIDE: Record<NonNullable<SurfaceProps['divide']>, string> = {
  none: '',
  x: `divide-x ${DIVIDE_C}`,
  y: `divide-y ${DIVIDE_C}`,
};

export function Surface({
  tier,
  title,
  aside,
  divide = 'none',
  as = 'section',
  className = '',
  children,
  'aria-label': ariaLabel,
  'aria-labelledby': ariaLabelledby,
}: SurfaceProps) {
  const Root = as;
  const header = (title !== undefined && title !== null) || (aside !== undefined && aside !== null);
  // tier-3 (table) headers get their own padding; grouped already has p-5 on the root.
  const headerPad = tier === 'table' ? 'px-5 py-3.5' : '';
  const bodyWrap = `${DIVIDE[divide]}`.trim();

  return (
    <Root
      data-k8s-kit=""
      data-surface-tier={tier}
      aria-label={ariaLabel}
      aria-labelledby={ariaLabelledby}
      className={`${CHROME[tier]} ${className}`.trim()}
    >
      {header && (
        <div className={`flex items-start justify-between gap-3 ${headerPad}`.trim()}>
          {title !== undefined && title !== null && (
            <div className="min-w-0">
              {typeof title === 'string' ? <span className={TITLE}>{title}</span> : title}
            </div>
          )}
          {aside !== undefined && aside !== null && <div className={`min-w-0 ${EYEBROW}`}>{aside}</div>}
        </div>
      )}
      {bodyWrap ? <div className={bodyWrap}>{children}</div> : children}
    </Root>
  );
}

export default Surface;
