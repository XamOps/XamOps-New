/**
 * `Surface` — the single source of card chrome for the CloudK8s v3 relayout
 * (DESIGN.md §2, §3). Everything on these pages collapses into exactly three
 * tiers; `tier` is the only knob that changes weight, so callers never
 * re-declare radius / border / shadow / padding again.
 *
 *   hero    — no card; sits directly on the page background.
 *   grouped — one container (radius + border + bg), NO shadow.
 *   table   — the same container as grouped (no shadow; v3 removed the elevation step).
 *
 * Colour safety: this component paints only the `--chrome-*` tokens
 * (components/CloudK8s/shared/styles/k8sChromeTokens.css) — the card surface and the single
 * hairline — and carries no text-status, text-series, text-feature or var(--k8s-...) classes, so the
 * token-scope guard stays green. Status/series colour enters a Surface only through the children the
 * caller passes. The `--chrome-*` names keep the `scope-raw-var` guard (which matches `var(--k8s-`)
 * from flagging it.
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
const RADIUS = 'rounded-[8px]';
const BORDER = 'border border-[color:var(--chrome-line)]';
const BG = 'bg-[color:var(--chrome-card)]';
const DIVIDE_C = 'divide-[color:var(--chrome-line)]';

const CHROME: Record<SurfaceTier, string> = {
  hero: 'px-0 py-0',
  grouped: `${RADIUS} ${BORDER} ${BG} p-5`,
  // v3 removed the elevation step: table reads as the same container as grouped, no shadow.
  table: `${RADIUS} ${BORDER} ${BG}`,
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
