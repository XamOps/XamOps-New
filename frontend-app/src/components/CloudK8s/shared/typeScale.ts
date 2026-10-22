/**
 * The CloudK8s v3 type scale (FEAT-001; docs/cloudk8s/ui-revamp-design.md §7).
 *
 * Seven ranked sizes, no more. The owner's converge visual review reversed the earlier "hero and
 * strip share one figure size" decision: a page now has a dominant HERO_FIGURE (28px) that
 * out-weighs the subordinate strip FIGURE (20px), plus five supporting levels. Hierarchy rests on
 * weight AND placement — the lead number is bigger, not just first. The visual gate's font-size
 * budget (metricGate.test.tsx) is seven to match.
 *
 * Every token embeds its own chrome colour through the `--chrome-*` tokens
 * (components/CloudK8s/shared/styles/k8sChromeTokens.css) — primary ink or the muted grey — so a
 * caller never re-declares a text colour and status / series colour never enters through these
 * tokens. The `--chrome-*` names (not `--k8s-…`) keep the design-rule `scope-raw-var` guard green.
 */

/**
 * 28px bold: the dominant HERO figure — the one lead number a page leads with (MetricStrip's
 * `heroLead` cell). The owner's visual review (converge round) reversed the earlier "hero and strip
 * share one size" decision: the hero must out-weigh its neighbours, so it is its own rank above the
 * strip figure below.
 */
export const HERO_FIGURE = 'text-[28px] font-bold tabular-nums leading-none text-[color:var(--chrome-ink)]';
/** 20px bold: the strip-cell figure. Clearly subordinate to the 28px hero; peers share this size. */
export const FIGURE = 'text-[20px] font-bold tabular-nums leading-none text-[color:var(--chrome-ink)]';
/** 16px/600: a section heading. */
export const SECTION = 'text-[16px] font-semibold leading-tight text-[color:var(--chrome-ink)]';
/** 14px/600: a card / group title. */
export const TITLE = 'text-[14px] font-semibold text-[color:var(--chrome-ink)]';
/** 13px/400: body copy. */
export const BODY = 'text-[13px] font-normal text-[color:var(--chrome-ink)]';
/** 12px/400: secondary / supporting copy, in the muted grey. */
export const SECONDARY = 'text-[12px] font-normal text-[color:var(--chrome-muted)]';
/** 11px/600 uppercase: a caption / eyebrow label, in the muted grey. */
export const LABEL = 'text-[11px] font-semibold uppercase tracking-[0.08em] text-[color:var(--chrome-muted)]';

// ─── Deprecated aliases ─────────────────────────────────────────────────────
// Kept at their original values so EksCostTab.tsx, WorkloadsPanel.tsx, SummaryPanel.tsx,
// status/KpiCard.tsx and SummaryPanel.test.tsx (which asserts the 52px hero) still compile and
// render unchanged. New code uses FIGURE / SECTION / TITLE / BODY / SECONDARY / LABEL above.

/** @deprecated Use `FIGURE`. The old 52px tier-1 hero number. */
export const HERO_NUMBER = 'text-[52px] font-semibold leading-none tabular-nums';
/** @deprecated Use `FIGURE`. The old 22px demoted strip / KpiCard / counts number. */
export const KPI_NUMBER = 'text-[22px] font-semibold leading-tight tabular-nums';
/** @deprecated Use `LABEL`. The old eyebrow. */
export const EYEBROW = 'text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] dark:text-[rgba(255,255,255,0.5)]';
