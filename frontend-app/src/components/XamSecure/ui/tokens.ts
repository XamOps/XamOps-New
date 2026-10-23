import { useEffect, useState } from 'react';

/**
 * Token helper for the XamSecure Overview design system.
 *
 * `tokens.css` defines the CSS custom properties on `:root` (light-only). Components read them via
 * `var(--xs-*)`; this module exposes the names as typed constants so a component never hard-codes a
 * token string, plus `sevVar()` to resolve a severity to its single-source hue/tint/badge tokens,
 * and `usePrefersReducedMotion()` to gate JS-driven animation.
 */

/** The four first-class severities, in the page's display order. */
export type SevKey = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

/** CSS `var(--xs-*)` references, grouped by role. */
export const XS = {
  bgPrimary: 'var(--xs-bg-primary)',
  bgSecondary: 'var(--xs-bg-secondary)',
  bgTertiary: 'var(--xs-bg-tertiary)',
  borderSubtle: 'var(--xs-border-subtle)',
  borderPrimary: 'var(--xs-border-primary)',
  textPrimary: 'var(--xs-text-primary)',
  textSecondary: 'var(--xs-text-secondary)',
  textTertiary: 'var(--xs-text-tertiary)',
  interactiveHover: 'var(--xs-interactive-hover)',
  radiusCard: 'var(--xs-radius-card)',
  radiusChip: 'var(--xs-radius-chip)',
  radiusPill: 'var(--xs-radius-pill)',
  cardPad: 'var(--xs-card-pad)',
  cardHeight: 'var(--xs-card-height)',
  gridGap: 'var(--xs-grid-gap)',
  menuShadow: 'var(--xs-menu-shadow)',
  legendInactive: 'var(--xs-legend-inactive-text)',
  metal: 'var(--xs-metal-02)',
  blue01: 'var(--xs-blue-01)',
  blue02: 'var(--xs-blue-02)',
  blue06: 'var(--xs-blue-06)',
  purple01: 'var(--xs-purple-01)',
  purple02: 'var(--xs-purple-02)',
  purple06: 'var(--xs-purple-06)',
  pink00: 'var(--xs-pink-00)',
  pink06: 'var(--xs-pink-06)',
  red03: 'var(--xs-red-03)',
  red06: 'var(--xs-red-06)',
  green00: 'var(--xs-green-00)',
  green06: 'var(--xs-green-06)',
} as const;

/** Severity → the design tokens that carry it everywhere (single source of truth). */
export const SEV_TOKENS: Record<SevKey, { color: string; tint: string; badge: string; ring: string; letter: string }> = {
  CRITICAL: { color: 'var(--xs-sev-critical)', tint: 'var(--xs-sev-critical-tint)', badge: 'var(--xs-badge-c)', ring: 'var(--xs-badge-ring-c)', letter: 'C' },
  HIGH: { color: 'var(--xs-sev-high)', tint: 'var(--xs-sev-high-tint)', badge: 'var(--xs-badge-h)', ring: 'var(--xs-badge-ring-h)', letter: 'H' },
  MEDIUM: { color: 'var(--xs-sev-medium)', tint: 'var(--xs-sev-medium-tint)', badge: 'var(--xs-badge-m)', ring: 'var(--xs-badge-ring-m)', letter: 'M' },
  LOW: { color: 'var(--xs-sev-low)', tint: 'var(--xs-sev-low-tint)', badge: 'var(--xs-badge-l)', ring: 'var(--xs-badge-ring-l)', letter: 'L' },
};

/** The "Other" bucket (anything that is not one of the four first-class severities). */
export const SEV_OTHER = {
  color: 'var(--xs-sev-other)',
  tint: 'var(--xs-sev-other-tint)',
  badge: 'var(--xs-badge-o)',
  ring: 'var(--xs-badge-ring-o)',
  letter: 'O',
} as const;

/**
 * Resolve any severity string to its tokens. Unknown / null → the "Other" bucket, so color is never
 * picked ad-hoc and the letter badge always has a glyph.
 */
export function sevTokens(severity: string | null | undefined): { color: string; tint: string; badge: string; ring: string; letter: string } {
  const key = (severity ?? '').toUpperCase();
  if (key === 'CRITICAL' || key === 'HIGH' || key === 'MEDIUM' || key === 'LOW') {
    return SEV_TOKENS[key as SevKey];
  }
  return SEV_OTHER;
}

/**
 * Tracks `prefers-reduced-motion: reduce`.
 *
 * Returns the current value and updates on change. All JS-driven motion (count-up, chart draw-in,
 * bar grow-in) reads this and renders the final static state when it is true. A matching
 * `@media (prefers-reduced-motion: reduce)` block in tokens.css zeroes the CSS animations.
 */
export function usePrefersReducedMotion(): boolean {
  const query = '(prefers-reduced-motion: reduce)';
  const [reduced, setReduced] = useState<boolean>(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(query);
    const onChange = (e: MediaQueryListEvent) => setReduced(e.matches);
    setReduced(mql.matches);
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    }
    // Older Safari.
    mql.addListener(onChange);
    return () => mql.removeListener(onChange);
  }, []);

  return reduced;
}

/**
 * Count-up from 0 to `value` over `duration` ms (ease-out). When reduced-motion is set, returns
 * `value` immediately and never animates.
 */
export function useCountUp(value: number, duration = 800): number {
  const reduced = usePrefersReducedMotion();
  const [display, setDisplay] = useState<number>(reduced ? value : 0);

  useEffect(() => {
    if (reduced) { setDisplay(value); return; }
    if (value === 0) { setDisplay(0); return; }
    let raf = 0;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      const eased = 1 - (1 - t) ** 3; // ease-out cubic
      setDisplay(Math.round(eased * value));
      if (t < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value, duration, reduced]);

  return display;
}
