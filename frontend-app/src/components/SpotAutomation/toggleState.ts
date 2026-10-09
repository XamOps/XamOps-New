/**
 * Decides how an AutoSpotting toggle renders for one group.
 *
 * - 'switch': the normal toggle. Groups already on Spot always get it, so Disable stays
 *   available while conversions are paused.
 * - 'disabled': shown but not clickable (new conversions are paused).
 * - 'locked': replaced by a lock chip (the group is managed by EKS or GKE).
 */
export type ToggleMode = 'switch' | 'disabled' | 'locked';

export interface ToggleState {
  mode: ToggleMode;
  reason?: string;
}

export const PAUSED_TOOLTIP = 'New Spot conversions are paused while AutoSpotting is being upgraded.';

export const GKE_LOCK_REASON =
  'This instance group belongs to a GKE node pool. GKE manages its VMs. To use Spot, create a Spot node pool in GKE.';

export function awsToggleState(
  asg: { isEnabled: boolean; eksManaged: boolean },
  paused: boolean,
): ToggleState {
  if (asg.eksManaged) return { mode: 'locked' };
  if (!asg.isEnabled && paused) return { mode: 'disabled', reason: PAUSED_TOOLTIP };
  return { mode: 'switch' };
}

export function gcpToggleState(
  mig: { isEnabled: boolean; gkeManagedPool?: boolean; xamopsManagedTemplate?: boolean },
  paused: boolean,
): ToggleState {
  // A GKE pool that XamOps itself converted can still be switched back to On-Demand.
  if (mig.gkeManagedPool && !(mig.isEnabled && mig.xamopsManagedTemplate)) {
    return { mode: 'locked', reason: GKE_LOCK_REASON };
  }
  if (!mig.isEnabled && paused) return { mode: 'disabled', reason: PAUSED_TOOLTIP };
  return { mode: 'switch' };
}
