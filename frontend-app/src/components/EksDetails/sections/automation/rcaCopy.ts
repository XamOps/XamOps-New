/**
 * Plain-language copy for the RCA RootCausePanel (FEAT-002).
 *
 * Sibling of {@link ./verticalCopy.ts} / {@link ./hpaCopy.ts}. The remediation sentence itself
 * arrives from the server (the analyzer builds it from structured evidence), so this file carries
 * only the UI-side labels: the per-kind title and tone, the evidence-strength wording, the honest
 * empty state, and the dependencies note. Codes never appear as visible prose.
 */

import type { Tone } from '../../../CloudK8s/shared/status/statusTones';

/** The five emitted RcaPatternKind names, plus the two declared-but-unemitted dependencies. */
export const RCA_KIND_LABELS: Record<string, string> = {
  REPEATED_FAILED: 'Repeated apply failure',
  REPEATED_REFUSED: 'Repeatedly refused',
  PARTIAL_APPLY_CHURN: 'Partial-apply churn',
  EXPIRY_CHURN: 'Applies expiring',
  RESIZE_FLIP_FLOP: 'Recommendation oscillating',
  OOM_EVICTION: 'OOM / eviction',
  HPA_FLAP: 'HPA replica flapping',
};

/** A kind's tone, from the existing status kit — no new colours. */
export const RCA_KIND_TONES: Record<string, Tone> = {
  REPEATED_FAILED: 'bad',
  REPEATED_REFUSED: 'bad',
  PARTIAL_APPLY_CHURN: 'warn',
  EXPIRY_CHURN: 'warn',
  RESIZE_FLIP_FLOP: 'warn',
};

/** A kind's bundled `mdi:*` icon, so the chip is never colour alone. */
export const RCA_KIND_ICONS: Record<string, string> = {
  REPEATED_FAILED: 'mdi:alert-octagon-outline',
  REPEATED_REFUSED: 'mdi:cancel',
  PARTIAL_APPLY_CHURN: 'mdi:progress-alert',
  EXPIRY_CHURN: 'mdi:timer-off-outline',
  RESIZE_FLIP_FLOP: 'mdi:swap-vertical',
};

export const rcaKindLabel = (kind: string): string => RCA_KIND_LABELS[kind] ?? kind;
export const rcaKindTone = (kind: string): Tone => RCA_KIND_TONES[kind] ?? 'info';
export const rcaKindIcon = (kind: string): string =>
  RCA_KIND_ICONS[kind] ?? 'mdi:help-circle-outline';

/** The compact confidence word that rides beside the number: `0.85 · strong evidence`. */
export const rcaEvidenceStrength = (confidence: number | null): string => {
  if (confidence === null) return 'evidence strength unknown';
  if (confidence >= 0.8) return 'strong evidence';
  if (confidence >= 0.5) return 'moderate evidence';
  return 'weak evidence';
};

/** `0.85` to two places, or a dash when the score was not measured. */
export const rcaScoreLabel = (confidence: number | null): string =>
  confidence === null ? '—' : confidence.toFixed(2);

/** `0.85 · strong evidence`, for the chip's second line. */
export const rcaConfidenceLine = (confidence: number | null): string =>
  `${rcaScoreLabel(confidence)} · ${rcaEvidenceStrength(confidence)}`;

export const RCA_PANEL_TITLE = 'Probable root causes';

export const RCA_PANEL_SOURCE =
  "Source: XamOps's own recorded apply, refusal and expiry history for this cluster. Read-only — RCA applies nothing.";

/** The honest empty state when there are no causes. */
export const RCA_EMPTY_STATE =
  'Not enough recorded history yet to find a root cause. Causes appear once XamOps has recorded repeated outcomes on this cluster.';

/** The muted note under the panel naming the two dependency causes. */
export const RCA_DEPENDENCIES_NOTE =
  'OOM/eviction and HPA-flap causes arrive when XamOps persists cluster events and HPA history.';
