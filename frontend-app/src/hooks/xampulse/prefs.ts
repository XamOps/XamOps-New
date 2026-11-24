import { useSyncExternalStore } from 'react';

/** Per-user XamPulse display preferences, persisted in localStorage under xp:prefs:v1. */
export type XpThemePref = 'system' | 'light' | 'dark';
export type XpDensity = 'comfortable' | 'compact';
export interface XpPrefs { theme: XpThemePref; density: XpDensity; logWrap: boolean }

export const PREFS_KEY = 'xp:prefs:v1';
export const DEFAULT_PREFS: XpPrefs = { theme: 'light', density: 'comfortable', logWrap: false };

/** Parses stored JSON, ignoring anything that is not a known value. */
export function parsePrefs(raw: string | null): XpPrefs {
  if (!raw) return DEFAULT_PREFS;
  try {
    const v: unknown = JSON.parse(raw);
    if (typeof v !== 'object' || v === null) return DEFAULT_PREFS;
    const o = v as Record<string, unknown>;
    return {
      theme: o.theme === 'light' || o.theme === 'dark' || o.theme === 'system' ? o.theme : DEFAULT_PREFS.theme,
      density: o.density === 'compact' || o.density === 'comfortable' ? o.density : DEFAULT_PREFS.density,
      logWrap: typeof o.logWrap === 'boolean' ? o.logWrap : DEFAULT_PREFS.logWrap,
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

let current: XpPrefs | null = null;
const listeners = new Set<() => void>();

function read(): XpPrefs {
  if (current) return current;
  try {
    current = parsePrefs(window.localStorage.getItem(PREFS_KEY));
  } catch {
    current = DEFAULT_PREFS;
  }
  return current;
}

export function setPrefs(patch: Partial<XpPrefs>): void {
  current = { ...read(), ...patch };
  try {
    window.localStorage.setItem(PREFS_KEY, JSON.stringify(current));
  } catch {
    /* storage unavailable: keep in memory */
  }
  listeners.forEach((l) => l());
}

/** Test helper: drop the cached copy so the next read hits storage again. */
export function resetPrefsCache(): void { current = null; }

const subscribe = (cb: () => void): (() => void) => { listeners.add(cb); return () => { listeners.delete(cb); }; };

export function usePrefs(): XpPrefs {
  return useSyncExternalStore(subscribe, read, () => DEFAULT_PREFS);
}

export function resolveTheme(pref: XpThemePref): 'light' | 'dark' {
  if (pref !== 'system') return pref;
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}
