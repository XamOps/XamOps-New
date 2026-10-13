/**
 * TypeScript types for BillOps Admin Trial Management
 *
 * Backs: GET  /api/billops/admin/trials?includeConverted=true
 *        POST /api/billops/admin/trials/{clientId}/extend
 *        POST /api/billops/admin/trials/{clientId}/convert
 *
 * Every field mirrors what TrialAdminController puts on the wire — nothing is derived
 * server-side, so the UI has to reproduce TrialService.effectiveStatus itself (see
 * src/utils/trialVerdict.ts).
 *
 * For: ROLE_BILLOPS_ADMIN / ROLE_SUPER_ADMIN
 */

export interface TrialAdminRow {
  clientId: number;
  /** Client.name — the tenant, not a user */
  companyName: string | null;
  /** Billing email on the client record */
  email: string | null;
  /** Raw client.plan_type, e.g. 'TRIAL' | 'PAID'. Anything but 'TRIAL' is not trial-gated. */
  planType: string | null;
  /** Raw client.trial_status, e.g. 'ACTIVE' | 'EXPIRED' | 'CONVERTED' */
  trialStatus: string | null;
  /** LocalDateTime.toString() — ISO-8601 local date-time with NO zone offset */
  trialEndAt: string | null;
  /** Server-side figure, floored and clamped at 0; computed without consulting planType */
  daysRemaining: number;
}

/** Response of POST /{clientId}/extend */
export interface TrialExtendResult {
  clientId: number;
  trialStatus: string;
  trialEndAt: string;
  daysRemaining: number;
}

/** Response of POST /{clientId}/convert */
export interface TrialConvertResult {
  clientId: number;
  planType: string;
  trialStatus: string;
}

/** Either mutation's response, kept so the success message can quote the server's own figures. */
export type TrialMutationResult =
  | ({ kind: 'extend' } & TrialExtendResult)
  | ({ kind: 'convert' } & TrialConvertResult);

/** What enforcement would do with this tenant right now. */
export type TrialEnforcementState = 'ACTIVE' | 'EXPIRED' | 'NONE';

export interface TrialVerdict {
  state: TrialEnforcementState;
  /** True when xamops-service would answer 402 for this tenant. */
  blocked: boolean;
  /** True for a tenant that reached PAID through a trial conversion. */
  converted: boolean;
  /** Human-readable verdict; always rendered as text so status is never colour-only. */
  label: string;
}

export interface UseBillOpsAdminTrialsReturn {
  rows: TrialAdminRow[];
  loading: boolean;
  error: string | null;
  /** The client whose mutation is in flight, or null. */
  mutatingClientId: number | null;
  mutationError: string | null;
  /** The last successful mutation response. */
  lastResult: TrialMutationResult | null;

  // Actions
  refresh: () => Promise<void>;
  extendTrial: (clientId: number, days: number) => Promise<boolean>;
  convertToPaid: (clientId: number) => Promise<boolean>;
  clearMutationState: () => void;
}
