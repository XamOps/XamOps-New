import { describe, it, expect } from 'vitest';
import {
  presentFeatureList,
  tierBadge,
  type OnboardingFeatureWire,
} from './featureCatalogPresentation';

// The three catalog features as the server serves them while OQ-1 is undecided: NO `tier` field,
// because xamops.onboarding.show-tiers defaults false.
const WITHOUT_TIERS: OnboardingFeatureWire[] = [
  { id: 'COST_INSIGHTS', label: 'Cost & insights', phase: 1, available: true, reason: null, selected: true },
  { id: 'NODE_CONSOLIDATION', label: 'Node consolidation', phase: 2, available: true, reason: null, selected: false },
  { id: 'WORKLOAD_AUTOSCALING', label: 'Workload autoscaling', phase: 2, available: false, reason: 'READ_ONLY_CONNECTION', selected: false },
];

describe('onboarding feature catalog presentation (OQ-1 tiering hidden by default)', () => {
  it('renders feature names with NO tier/price/free-paid badge when the flag is off (default)', () => {
    const rows = presentFeatureList(WITHOUT_TIERS);

    // Names + what-they-do survive; no commercial label is produced.
    expect(rows.map(r => r.label)).toEqual(['Cost & insights', 'Node consolidation', 'Workload autoscaling']);
    expect(rows.every(r => r.tierBadge === null)).toBe(true);

    // And nothing anywhere in the projected rows reads as a placeholder price or tier.
    const serialized = JSON.stringify(rows);
    expect(serialized).not.toMatch(/plan TBD/i);
    expect(serialized).not.toMatch(/\bPaid\b/);
    expect(serialized).not.toMatch(/\bFree\b/);
    expect(serialized).not.toMatch(/read-only\)/i);
    expect(serialized).not.toMatch(/\$|USD|\/mo/);
  });

  it('selectability still comes from availability, never from the (absent) tier', () => {
    const rows = presentFeatureList(WITHOUT_TIERS);
    const workload = rows.find(r => r.id === 'WORKLOAD_AUTOSCALING')!;
    expect(workload.available).toBe(false);
    expect(workload.reason).toBe('READ_ONLY_CONNECTION');
  });

  it('renders a tier badge only when the server sends a tier (flag on, real data)', () => {
    const withTiers: OnboardingFeatureWire[] = WITHOUT_TIERS.map((f, i) => ({
      ...f,
      tier: i === 0 ? 'FREE_READ_ONLY' : 'PAID_PLAN_TBD',
    }));
    const rows = presentFeatureList(withTiers);
    expect(rows.map(r => r.tierBadge)).toEqual(['Free', 'Paid', 'Paid']);
  });

  it('tierBadge is null for an absent or unknown tier', () => {
    expect(tierBadge(undefined)).toBeNull();
    expect(tierBadge(null)).toBeNull();
    expect(tierBadge('SOMETHING_NEW')).toBeNull();
  });
});
