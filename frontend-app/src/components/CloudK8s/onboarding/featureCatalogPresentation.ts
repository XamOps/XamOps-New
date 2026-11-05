/**
 * Connect/enable feature list — presentation of the server's onboarding feature catalog
 * (`GET /api/xamops/k8s/onboarding/permissions` → `features[]`). v3 house style: a dot + word,
 * no pills, em-dash for unavailable.
 *
 * OQ-1 (tiering) is undecided, so by default NO tier name, price, or free/paid badge is shown.
 * The server already omits the `tier` field while `xamops.onboarding.show-tiers` is false, so a
 * row simply has no tier to show. This module is the single display gate: it surfaces a tier badge
 * ONLY when the server sent a `tier`. A feature's selectability is driven by `available`/`reason`,
 * never by the tier — the tier is display-only.
 *
 * To turn tiers back on later: set `xamops.onboarding.show-tiers=true` with real tier data in the
 * backend catalog; each row then arrives with a `tier` and `tierBadge` becomes non-null here.
 */

/** One feature row as the server serves it. `tier` is absent while OQ-1 is undecided. */
export interface OnboardingFeatureWire {
  id: string;
  label: string;
  phase: number;
  available: boolean;
  reason: string | null;
  selected: boolean;
  /** OQ-1 placeholder tier name; present only when the server's show-tiers flag is on. */
  tier?: string | null;
}

/** What a feature row renders: its name, what it does, and (only if served) a tier badge. */
export interface OnboardingFeatureRow {
  id: string;
  label: string;
  phase: number;
  available: boolean;
  reason: string | null;
  selected: boolean;
  /**
   * The commercial badge text, or null to render nothing. Null whenever the server did not send a
   * tier (the default while OQ-1 is undecided), so the row shows only its name + what it does.
   */
  tierBadge: string | null;
}

const TIER_LABELS: Record<string, string> = {
  FREE_READ_ONLY: 'Free',
  PAID_PLAN_TBD: 'Paid',
};

/** Map a wire tier to its display label, or null if there is nothing (or nothing known) to show. */
export function tierBadge(tier: string | null | undefined): string | null {
  if (!tier) {
    return null;
  }
  return TIER_LABELS[tier] ?? null;
}

/** Project a server feature row onto its presentation. No badge unless the server sent a tier. */
export function presentFeature(feature: OnboardingFeatureWire): OnboardingFeatureRow {
  return {
    id: feature.id,
    label: feature.label,
    phase: feature.phase,
    available: feature.available,
    reason: feature.reason,
    selected: feature.selected,
    tierBadge: tierBadge(feature.tier),
  };
}

export function presentFeatureList(features: readonly OnboardingFeatureWire[]): OnboardingFeatureRow[] {
  return features.map(presentFeature);
}
