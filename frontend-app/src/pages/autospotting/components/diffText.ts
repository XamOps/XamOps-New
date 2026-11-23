/**
 * Plain-words labels and values for the "What changes" table (design-ux-ops.md 3.5). Fields the table has no special
 * wording for are shown as the plan sends them.
 */
export const MAINTENANCE_POLICY_FIELD = 'instanceMaintenancePolicy';

export const POLICY_UNCHANGED = 'unchanged';
export const MAINTENANCE_POLICY_LABEL = 'Replacement safety (instance maintenance policy)';

/** The line under the table that explains the policy row in everyday words. */
export const MAINTENANCE_POLICY_HELP =
  'While AWS swaps instances, this keeps all your current instances running until each new one is ready. ' +
  'The group can briefly run up to twice its size. XamOps removes this setting when you revert.';

export function diffFieldLabel(field: string): string {
  return field === MAINTENANCE_POLICY_FIELD ? MAINTENANCE_POLICY_LABEL : field;
}

/** "min 100 / max 200" becomes "Min 100% / Max 200%"; "none" becomes "Not set". */
export function policyValue(value: string | null): string {
  if (value === null || value === 'none') return 'Not set';
  const m = /^min (\d+|unset) \/ max (\d+|unset)$/.exec(value);
  if (!m) return value;
  const part = (label: string, v: string) => (v === 'unset' ? `${label} not set` : `${label} ${v}%`);
  return `${part('Min', m[1])} / ${part('Max', m[2])}`;
}

/** The text of one cell of the diff table. `side` marks the column; XamOps-added values say so. */
export function diffValue(field: string, value: string | null, side: 'from' | 'to'): string {
  if (field !== MAINTENANCE_POLICY_FIELD) return value ?? '--';
  // A customer's own policy is never rewritten: the plan sends "unchanged" as the target (design-ux-ops.md 3.5).
  if (side === 'to' && value === POLICY_UNCHANGED) return 'Unchanged';
  const text = policyValue(value);
  return side === 'to' && value !== null && value !== 'none' ? `${text}, added by XamOps` : text;
}
