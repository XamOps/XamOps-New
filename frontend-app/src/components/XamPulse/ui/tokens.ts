import { useEffect, useState } from 'react';

/**
 * JS mirror of the --xp-* colour tokens in styles/xampulse.css. Charts draw SVG with CSS variables, so this
 * map exists for the contrast test and for places that need a literal colour. Keep both in sync.
 */
export type XpTheme = 'light' | 'dark';

export interface XpPalette {
  bg: string; surface: string; surface2: string; border: string;
  text: string; textMuted: string; accent: string; accentText: string; onAccent: string;
  ok: string; warn: string; crit: string; info: string; noData: string;
}

export const XP_PALETTE: Record<XpTheme, XpPalette> = {
  dark: {
    bg: '#0a1020', surface: '#111a2e', surface2: '#18243d', border: '#5d709a',
    text: '#e8eefc', textMuted: '#a3b1cf', accent: '#3dd6c3', accentText: '#5ee6d4', onAccent: '#04201c',
    ok: '#4ade80', warn: '#fbbf24', crit: '#ff7b7b', info: '#7cb7ff', noData: '#94a3b8',
  },
  light: {
    bg: '#faf8f5', surface: '#ffffff', surface2: '#f3efe9', border: '#8a8378',
    text: '#14171f', textMuted: '#4b5262', accent: '#0f766e', accentText: '#0f766e', onAccent: '#ffffff',
    ok: '#166534', warn: '#92590a', crit: '#b91c1c', info: '#1d4ed8', noData: '#5b6474',
  },
};

export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState<boolean>(() =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const on = () => setReduced(mq.matches);
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return reduced;
}
