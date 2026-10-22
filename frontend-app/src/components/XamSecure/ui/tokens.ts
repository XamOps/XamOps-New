/**
 * Light design tokens for the XamSecure Overview — Upwind-matched re-skin.
 *
 * The theme is strictly LIGHT. Depth comes from a soft-gray panel fill (#F7F8FA) against the white
 * page and from hairline dividers, never from heavy borders, dark glow or additive bloom. This file
 * is the single source of truth for the Overview page palette, shadows and convenience style
 * objects; every color value the page renders flows from here.
 *
 * Severity is rendered as light dots + count pills (see SEVERITY), never as chunky chips. Chart
 * colors live in their own CHART.areaStroke map, deliberately independent of the severity-dot
 * palette so the two can never drift into each other.
 */
import type React from 'react';

/**
 * Shadow policy — mostly none / very soft. The three-level tinted shadow system is collapsed to
 * two, both barely visible. Depth for soft-gray panels comes from fill contrast, not shadow.
 */
export const SHADOW = {
  /** Default for soft-gray panels and tiles. */
  none: 'none',
  /** ONLY on genuinely clickable cards (active-story buttons) on hover/focus. Neutral slate tint. */
  hover: '0 4px 16px -6px rgba(15,23,42,0.10)',
} as const;

/** Grouping surfaces — soft-gray panels instead of bordered white cards. */
export const PANEL = {
  /** The Upwind grouping surface: a rounded fill, no border, no shadow. */
  soft: '#F7F8FA',
  /** A hair darker, for a nested well (e.g. the focal-hero inner panel) against a soft panel. */
  softAlt: '#F3F5F8',
  /** Empty-track fill for the mini severity bar-strip (reads against white AND the soft panel). */
  track: '#EEF1F5',
} as const;

/** Reserved for the few places a literal white tile still helps. */
export const SURFACE = {
  card: '#FFFFFF',
} as const;

/** Text ramp — all AA on the surfaces each tone lands on (see the design review Finding 1). */
export const TEXT = {
  /** slate-900 — focal numbers, headings. */
  strong: '#0F172A',
  /** slate-700 — row labels, values. ~9.8:1 on #F7F8FA. */
  body: '#334155',
  /** darkened slate — captions, metadata. ≥4.5:1 AA on white, #F7F8FA, #F3F5F8 and #EEF1F5. */
  muted: '#576578',
  /** slate-400 — NON-ESSENTIAL decoration ONLY (axis ticks, decorative eyebrow glyphs). */
  faint: '#94A3B8',
} as const;

/** Hairline dividers — the primary separator, replacing boxed card borders. */
export const HAIRLINE = 'rgba(15,23,42,0.08)';
/** Even softer, for inside soft-gray panels where 0.08 reads too hard. */
export const HAIRLINE_SOFT = 'rgba(15,23,42,0.05)';

/** The one calm accent that carries emphasis. */
export const ACCENT = {
  green: '#16A34A',
  greenSoft: '#DCFCE7',
  greenText: '#15803D',
} as const;

/**
 * Chart palette — low chroma, thin strokes. The area-stroke map is the area chart's OWN 3-color
 * set for the vuln series (CRITICAL / HIGH / OTHER), independent of the SEVERITY dot palette.
 * `OTHER` is the pale muted-blue line color #60A5FA, which is unrelated to the #94A3B8 UNKNOWN dot.
 */
export const CHART = {
  areaStroke: { CRITICAL: '#DC2626', HIGH: '#EA580C', OTHER: '#60A5FA' } as Record<string, string>,
  /** Findings-over-time trend bars — pale blue, vertical gradient, rounded tops. */
  trendBarTop: '#93C5FD',
  trendBarBottom: '#BFDBFE',
  /** Pale coral/salmon funnel wedge. */
  funnelStart: '#FDA4AF',
  funnelMid: '#FCA5A5',
  funnelEnd: '#FBBFA8',
} as const;

/**
 * Severity semantic palette — rendered as LIGHT dots + pills, never chunky. Each entry pairs a dot
 * color with a pill background and an AA-passing pill text color. UNKNOWN is the fallback for null
 * or unrecognized severities (matches SeverityChip's behavior). This palette is for severity pills
 * ONLY; the area chart's OTHER series uses CHART.areaStroke, not the UNKNOWN dot color.
 */
export const SEVERITY: Record<string, { dot: string; pillBg: string; pillText: string }> = {
  CRITICAL: { dot: '#DC2626', pillBg: '#FEE2E2', pillText: '#991B1B' },
  HIGH: { dot: '#EA580C', pillBg: '#FFEDD5', pillText: '#9A3412' },
  // MEDIUM dot #CA8A04 is decorative (aria-hidden); #CA8A04 on the pill bg is only 2.74:1 and must
  // NOT be used as text. Pill text #854D0E measures 6.38:1 on #FEF9C3 — passes AA.
  MEDIUM: { dot: '#CA8A04', pillBg: '#FEF9C3', pillText: '#854D0E' },
  LOW: { dot: '#2563EB', pillBg: '#DBEAFE', pillText: '#1E40AF' },
  UNKNOWN: { dot: '#94A3B8', pillBg: '#F1F5F9', pillText: '#475569' },
};

/** Panel radii, in case a component wants the token rather than the Tailwind class. */
export const RADIUS = {
  panel: 16,
  pill: 9999,
} as const;

/** Convenience style objects — a soft-gray panel (no border, no shadow). */
export const panelStyle: React.CSSProperties = {
  background: PANEL.soft,
  boxShadow: SHADOW.none,
};

/** The nested focal-hero well — a hair darker so it separates from the outer soft panel. */
export const focalPanelStyle: React.CSSProperties = {
  background: PANEL.softAlt,
  boxShadow: SHADOW.none,
};
