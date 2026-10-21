/**
 * Light design tokens for the XamSecure Overview redesign.
 *
 * One source of truth for the soft tinted shadow system, surface gradients and accent spine so
 * the SurfaceCard primitive and every panel share the same depth language. The theme is strictly
 * LIGHT: depth comes from soft slate/indigo-tinted drop-shadows and faint white gradients, never
 * from dark glow or additive bloom.
 */
import type React from 'react';

/** Soft tinted shadow system (3 levels) — slate/indigo tint, never flat black. */
export const SHADOW = {
  /** Default card at rest. */
  rest: '0 1px 2px rgba(15,23,42,0.04), 0 1px 3px rgba(15,23,42,0.06)',
  /** Hero band, KPI tiles, hover of interactive cards. */
  raised: '0 4px 12px -2px rgba(15,23,42,0.08), 0 2px 6px -2px rgba(15,23,42,0.05)',
  /** Hover lift on genuinely clickable cards — the only place a faint blue tint shows. */
  lifted: '0 12px 28px -6px rgba(37,99,235,0.14), 0 6px 12px -6px rgba(15,23,42,0.08)',
} as const;

/** Faint surface gradients so card edges catch light while still reading as clean white. */
export const SURFACE = {
  card: 'linear-gradient(180deg, #ffffff 0%, #fbfcfe 100%)',
  hero: 'linear-gradient(135deg, #ffffff 0%, #f6f8ff 55%, #f3f9ff 100%)',
  /** Round accent icon badge behind KPI icons — a soft tinted chip, not a glowing orb. */
  kpiBadge: 'linear-gradient(135deg, rgba(37,99,235,0.12), rgba(124,58,237,0.10))',
} as const;

/** Hairline borders. */
export const BORDER = {
  card: '1px solid rgba(15,23,42,0.06)',
  hero: '1px solid rgba(37,99,235,0.10)',
} as const;

/**
 * Left→right white overlay that lets the topology accent bleed under the hero without ever
 * dropping the KPI text below AA — the floor stays at 0.85 white even where a tile underlaps.
 */
export const HERO_ACCENT_OVERLAY =
  'linear-gradient(90deg, #ffffff 0%, #ffffff 60%, rgba(255,255,255,0.85) 100%)';

/** Non-severity brand spine — used for decoration only, never to signal severity. */
export const ACCENT = {
  blue: '#2563eb',
  violet: '#7c3aed',
  cyan: '#0891b2',
  green: '#059669',
  amber: '#d97706',
} as const;

/** Convenience style objects for the shared SurfaceCard primitive. */
export const cardStyle: React.CSSProperties = {
  background: SURFACE.card,
  border: BORDER.card,
  boxShadow: SHADOW.rest,
};

export const heroStyle: React.CSSProperties = {
  background: SURFACE.hero,
  border: BORDER.hero,
  boxShadow: SHADOW.raised,
};
