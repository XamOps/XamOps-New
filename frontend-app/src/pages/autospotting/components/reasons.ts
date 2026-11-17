import { AutoSpottingApiError, NETWORK_UNREACHABLE } from '../../../api/autospottingApi';

export const PAUSED_REASON = 'New Spot conversions are paused while AutoSpotting is being upgraded.';
export const SWITCH_UNREADABLE_TEXT =
  "AutoSpotting can't read its safety switches right now; revert is held until it can";

/** Plain text for an API error code and optional switch scope (core §11). Never a raw provider message. */
export function reasonForError(e: unknown, provider = 'AWS'): string {
  if (!(e instanceof AutoSpottingApiError)) return 'Request failed. Try again.';
  if (e.code === NETWORK_UNREACHABLE) return e.detail;
  if (e.code === 'AS_SWITCH_UNREADABLE') return SWITCH_UNREADABLE_TEXT;
  if (e.code === 'AUTOSPOTTING_CONVERSIONS_PAUSED') return PAUSED_REASON;
  if (e.code === 'AS_GROUP_BUSY') return 'Another change is running on this group.';
  if (e.code === 'AS_PROVIDER_NOT_READY') return `AutoSpotting changes for ${provider} aren't available yet.`;
  if (e.code === 'AS_FORBIDDEN_ROLE') return 'Your role can view this plan but not confirm it.';
  if (e.code === 'PLAN_KIND_NOT_ALLOWED') return e.detail || 'This change is not available for the group in its current state.';
  if (e.switch) return `AutoSpotting changes are paused for ${e.switch}.`;
  return e.detail || 'Request failed. Try again.';
}

export type ConfirmOutcome =
  | { kind: 'stale'; title: string; message: string }
  | { kind: 'ack'; message: string }
  | { kind: 'busy'; message: string }
  | { kind: 'forbidden'; message: string }
  | { kind: 'message'; message: string };

/** The confirm outcomes of design-ux-ops.md §3.3 (202 is handled by the caller). */
export function classifyConfirmError(e: unknown, provider = 'AWS'): ConfirmOutcome {
  if (e instanceof AutoSpottingApiError) {
    if (e.code === 'PLAN_STALE') {
      return { kind: 'stale', title: 'The group changed', message: 'The group changed after this plan was made.' };
    }
    if (e.code === 'PLAN_EXPIRED') {
      return { kind: 'stale', title: 'The plan expired', message: 'This plan expired before it was confirmed.' };
    }
    if (e.code === 'PLAN_VERSION_MISMATCH') {
      return { kind: 'stale', title: 'The plan changed', message: 'This plan was replaced by a newer version.' };
    }
    if (e.status === 422 && e.code === 'ACK_REQUIRED') {
      return { kind: 'ack', message: 'Tick every acknowledgment before confirming.' };
    }
    if (e.code === 'AS_GROUP_BUSY') return { kind: 'busy', message: reasonForError(e, provider) };
    if (e.status === 403) return { kind: 'forbidden', message: reasonForError(e, provider) };
  }
  return { kind: 'message', message: reasonForError(e, provider) };
}

/** A fallback sentence for a warning id whose text the list did not carry. */
export function warningText(id: string, known?: string): string {
  if (known) return known;
  if (id.includes('REVERT_FROM_FAILED')) {
    return 'I understand this group is in a failed state and the revert may not restore every setting.';
  }
  return `I acknowledge the warning ${id}.`;
}
