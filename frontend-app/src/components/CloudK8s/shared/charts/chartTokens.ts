/**
 * The one SVG file that names chart-series tokens (design-delta §2.2, §2.5, §2.6 rule 5). Every
 * chart — the recharts area/bar components and the plain-SVG primitives — reads its colour, line
 * style and fill opacity from here, so no component ever reaches for a recharts default
 * (#8884d8 / #82ca9d / #ffc658) or writes a raw hue.
 *
 * The design-rule guard allows raw `var(--k8s-…)` ONLY in this file and the status components, so
 * this is where the series tokens turn into the actual colour strings recharts needs as explicit
 * `stroke`/`fill` props. The plain-SVG primitives instead take the Tailwind class names below
 * (`stroke-series-used`), which the guard allows anywhere under charts/.
 *
 * The tokens hold bare "R G B" channel triples (so Tailwind's opacity modifiers work), which are not
 * colours on their own: `fill="var(--k8s-…)"` paints black in a browser. Every colour string here is
 * therefore `rgb(var(--k8s-…))`, the same form tailwind.config.js uses, and still follows the theme
 * (light under :root, dark under .dark).
 *
 * One meaning per colour, one line style per series (§2.1 rule 1):
 *   requested — solid 1.8 px, 16% fill;
 *   used      — solid 2.2 px, 26% fill;
 *   suggested — dashed 7 4, 10% fill (XamOps); the Kubernetes VPA suggestion is the same hue,
 *               hatched (same meaning, different source);
 *   limit     — dotted 1.5 3.5;
 *   capacity  — solid, 30% fill for "not requested".
 */

/** The chart series kinds (design-delta §2.2). */
export type SeriesKind = 'capacity' | 'requested' | 'used' | 'suggested' | 'limit';

/** The money kinds that draw as a band or a bar (§2.2). */
export type MoneyKind = 'saved' | 'extra';

export interface SeriesStyle {
  /** The CSS colour string for a recharts `stroke`/`fill` prop (raw var: allowed here only). */
  color: string;
  /** The Tailwind stroke class for a plain-SVG primitive. */
  strokeClass: string;
  /** The Tailwind fill class for a plain-SVG primitive. */
  fillClass: string;
  /** Line width in px (§2.2). */
  strokeWidth: number;
  /** SVG dash array, or undefined for a solid line (§2.2). */
  dash?: string;
  /** Area fill opacity, light theme (§2.2 "Opacity rules"). */
  fillOpacity: number;
  /** Area fill opacity, dark theme (§2.2 "Opacity rules"). */
  fillOpacityDark: number;
}

/**
 * The series table. `color` is `rgb(var(--k8s-series-…))` so a recharts element inherits the
 * theme's value (light under :root, dark under .dark) without a component ever naming the hue.
 */
export const SERIES: Record<SeriesKind, SeriesStyle> = {
  capacity: {
    color: 'rgb(var(--k8s-series-capacity))',
    strokeClass: 'stroke-series-capacity',
    fillClass: 'fill-series-capacity',
    strokeWidth: 1.5,
    fillOpacity: 0.3,
    fillOpacityDark: 0.3,
  },
  requested: {
    color: 'rgb(var(--k8s-series-requested))',
    strokeClass: 'stroke-series-requested',
    fillClass: 'fill-series-requested',
    strokeWidth: 1.8,
    fillOpacity: 0.16,
    fillOpacityDark: 0.22,
  },
  used: {
    color: 'rgb(var(--k8s-series-used))',
    strokeClass: 'stroke-series-used',
    fillClass: 'fill-series-used',
    strokeWidth: 2.2,
    fillOpacity: 0.26,
    fillOpacityDark: 0.3,
  },
  suggested: {
    color: 'rgb(var(--k8s-series-suggested))',
    strokeClass: 'stroke-series-suggested',
    fillClass: 'fill-series-suggested',
    strokeWidth: 1.8,
    dash: '7 4',
    fillOpacity: 0.1,
    fillOpacityDark: 0.14,
  },
  limit: {
    color: 'rgb(var(--k8s-series-limit))',
    strokeClass: 'stroke-series-limit',
    fillClass: 'fill-series-limit',
    strokeWidth: 1.5,
    dash: '1.5 3.5',
    fillOpacity: 0,
    fillOpacityDark: 0,
  },
};

export interface MoneyStyle {
  /**
   * The money colour as `rgb(var(--k8s-money-…))`. The design-rule guard only lets the
   * `fill-money-*` / `stroke-money-*` CLASSES live in the status components, so a chart passes this
   * raw var as an inline `fill`/`stroke` instead (raw `var(--k8s-…)` is allowed in this file).
   */
  color: string;
  /** Freed/saved band opacity (§2.2). */
  fillOpacity: number;
  fillOpacityDark: number;
}

/** Money saved is the freed band; money extra is "costs more" (§2.2). */
export const MONEY: Record<MoneyKind, MoneyStyle> = {
  saved: {
    color: 'rgb(var(--k8s-money-saved))',
    fillOpacity: 0.24,
    fillOpacityDark: 0.28,
  },
  extra: {
    color: 'rgb(var(--k8s-money-extra))',
    fillOpacity: 0.16,
    fillOpacityDark: 0.2,
  },
};

/**
 * The hatch patterns a chart declares once in its SVG `<defs>` (design-delta §2.4, §3.3, §2.7). A
 * pattern is referenced as `url(#id)`; the audit checks the id resolves to a `<pattern>` in the same
 * surface. Hatches are drawn in the series/band colour, so they still carry one meaning.
 */
export type HatchKind = 'suggested' | 'noHistory' | 'notInEffect' | 'freed' | 'atMax';

export interface HatchStyle {
  id: string;
  /** The hatch lines' colour, as `rgb(var(--k8s-…))` applied inline (raw var allowed here). */
  stroke: string;
}

export const HATCH: Record<HatchKind, HatchStyle> = {
  // Kubernetes VPA suggestion: the suggested hue, hatched (same meaning, different source).
  suggested: { id: 'k8s-hatch-suggested', stroke: 'rgb(var(--k8s-series-suggested))' },
  // "No history" / "no data before" block: neutral capacity grey.
  noHistory: { id: 'k8s-hatch-no-history', stroke: 'rgb(var(--k8s-series-capacity))' },
  // "Declared, not in effect": the limit hue, hatched.
  notInEffect: { id: 'k8s-hatch-not-in-effect', stroke: 'rgb(var(--k8s-series-limit))' },
  // Freed request band: the money-saved hue, hatched.
  freed: { id: 'k8s-hatch-freed', stroke: 'rgb(var(--k8s-money-saved))' },
  // The zone above an HPA's target while it is held at its maximum (glossary §10): the hue of a
  // declared bound (the limit), hatched.
  atMax: { id: 'k8s-hatch-at-max', stroke: 'rgb(var(--k8s-series-limit))' },
};

/**
 * Status tone colours as `rgb(var(--k8s-status-…))`, for the in-plot places a chart draws a result's
 * status tone: the LanesTimeline outcome dots (design-delta §2.1 "timeline markers", §3.1) and the
 * changes-per-day bars stacked by result (Bars `tone`), each beside the result chips that name them.
 * The design-rule guard only lets the `fill-status-*` CLASSES live in the status components, so the
 * charts read these raw vars from here (raw `var(--k8s-…)` is allowed in this file) and pass them as
 * inline `fill`/`stroke`, keeping charts/ free of status classes.
 */
export type SeriesTone = 'good' | 'warn' | 'bad' | 'info' | 'off';

export const TONE_COLOR: Record<SeriesTone, string> = {
  good: 'rgb(var(--k8s-status-good))',
  warn: 'rgb(var(--k8s-status-warn))',
  bad: 'rgb(var(--k8s-status-bad))',
  info: 'rgb(var(--k8s-status-info))',
  off: 'rgb(var(--k8s-status-off))',
};

/**
 * Feature accent colours as `rgb(var(--k8s-feature-…))`, for the LanesTimeline source dots — the
 * only place a feature accent appears inside a plot area (§2.7 check 4). Read from here so the raw
 * var stays in this one file.
 */
export type FeatureAccent = 'workload' | 'node' | 'hpa' | 'vpa';

export const FEATURE_COLOR: Record<FeatureAccent, string> = {
  workload: 'rgb(var(--k8s-feature-workload))',
  node: 'rgb(var(--k8s-feature-node))',
  hpa: 'rgb(var(--k8s-feature-hpa))',
  vpa: 'rgb(var(--k8s-feature-vpa))',
};

/** Resolve a series style, or throw at build time if an unknown kind is passed. */
export function seriesStyle(kind: SeriesKind): SeriesStyle {
  return SERIES[kind];
}

/** Resolve a money style. */
export function moneyStyle(kind: MoneyKind): MoneyStyle {
  return MONEY[kind];
}
