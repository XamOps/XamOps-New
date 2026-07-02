/**
 * Canonical ticket severity list — single source of truth shared by every
 * ticket create/edit surface (Technical + Account & Billing).
 *
 * These four tiers map 1:1 to the distinct SLA targets defined in
 * `src/utils/timeUtils.ts`:
 *   Critical → 4h resolution / 1h first-response
 *   High     → 24h / 4h
 *   Normal   → 48h / 8h
 *   Low      → 72h / 24h
 *
 * NOTE: Legacy tickets may still carry older values (Urgent, Medium, Info).
 * The SLA engine and severity-badge helpers continue to understand those, so
 * existing tickets render correctly — only the create/edit dropdowns are
 * unified to this canonical set.
 */

export const TICKET_SEVERITIES = ['Critical', 'High', 'Normal', 'Low'] as const;

export type CanonicalSeverity = (typeof TICKET_SEVERITIES)[number];

/** SLA summary shown as a hint in the severity dropdown. */
export const SEVERITY_SLA_HINT: Record<CanonicalSeverity, string> = {
  Critical: '4h resolution · 1h first response',
  High: '24h resolution · 4h first response',
  Normal: '48h resolution · 8h first response',
  Low: '72h resolution · 24h first response',
};

/** Options ready for a <select>, each with label + SLA hint. */
export const SEVERITY_OPTIONS: { value: CanonicalSeverity; label: string; hint: string }[] =
  TICKET_SEVERITIES.map((value) => ({
    value,
    label: value,
    hint: SEVERITY_SLA_HINT[value],
  }));
