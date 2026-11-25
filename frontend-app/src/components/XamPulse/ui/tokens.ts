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
    text: '#e8eefc', textMuted: '#a3b1cf', accent: '#1be68b', accentText: '#1be68b', onAccent: '#04231a',
    ok: '#4ade80', warn: '#fbbf24', crit: '#ff7b7b', info: '#7cb7ff', noData: '#94a3b8',
  },
  light: {
    bg: '#ffffff', surface: '#ffffff', surface2: '#f1f5f9', border: '#6b7280',
    text: '#172033', textMuted: '#566176', accent: '#1be68b', accentText: '#087a4c', onAccent: '#04231a',
    ok: '#15803d', warn: '#b45309', crit: '#d11f2f', info: '#2563eb', noData: '#586274',
  },
};

/** JS mirror of the chart-graphic tokens (--xp-series-*, --xp-fill-*): marks only, never text. */
export interface XpChartPalette { series1: string; series2: string; fillWarn: string; fillCrit: string; fillVolume: string; fillVolumeQuiet: string }
export const XP_CHART: Record<XpTheme, XpChartPalette> = {
  light: { series1: '#3b82f6', series2: '#ec4899', fillWarn: '#f59e0b', fillCrit: '#e11d48', fillVolume: '#cbd5e1', fillVolumeQuiet: '#e2e8f0' },
  dark: { series1: '#3b82f6', series2: '#ec4899', fillWarn: '#f59e0b', fillCrit: '#f43f5e', fillVolume: '#475569', fillVolumeQuiet: '#334155' },
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
