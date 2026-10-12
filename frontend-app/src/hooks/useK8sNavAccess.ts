/**
 * Publishes the cluster page's automation access for the sidebar
 * (implementation plan, Addendum 1, J-5 and decision A4; design §6.3, §6.4).
 *
 * "View only" means no §6.3 action is allowed on this cluster for this user, whatever the reason
 * (read-only connection, no permission, demo, controllers not ready, …). The explanation is the
 * reason copy of the primary gate on turning node automation on, the first control a user who can
 * change automation would reach. Nothing is published while capabilities load.
 */
import { useEffect, useMemo } from 'react';
import { ACTION_IDS, type ActionId, type Capability } from '../types/k8sAutomation';
import { alsoNeedsLine, reasonCopy } from '../components/CloudK8s/shared/reasonCopy';
import { clearClusterNavAccess, publishClusterNavAccess } from '../utils/k8sNavAccess';
import { useK8sCapabilities, type CapabilitiesStatus } from './useK8sCapabilities';

/** The action whose primary reason explains "View only". */
export const VIEW_ONLY_EXPLAINED_BY: ActionId = 'automation.enable';

export interface NavAccess {
  viewOnly: boolean;
  reason: string | null;
}

/** Pure: the sidebar's access for one cluster, or null while capabilities load. */
export function navAccessFor(
  status: CapabilitiesStatus,
  can: (actionId: ActionId, ctx?: { cluster?: string | null }) => Capability,
  clusterName: string,
): NavAccess | null {
  if (status === 'loading') return null;
  if (ACTION_IDS.some((id) => can(id, { cluster: clusterName }).allowed)) return { viewOnly: false, reason: null };
  const capability = can(VIEW_ONLY_EXPLAINED_BY, { cluster: clusterName });
  const text = capability.gate ? reasonCopy(capability.gate).text : null;
  const reason = [text, alsoNeedsLine(capability.also)].filter(Boolean).join(' ');
  return { viewOnly: true, reason: reason || null };
}

/** Call inside the page's K8sCapabilitiesProvider. Clears the value when the page leaves. */
export function usePublishClusterNavAccess(clusterName: string, accountId: string): void {
  const { status, can } = useK8sCapabilities();
  const access = useMemo(() => navAccessFor(status, can, clusterName), [status, can, clusterName]);

  useEffect(() => {
    publishClusterNavAccess(access ? { clusterName, accountId, ...access } : null);
  }, [access, clusterName, accountId]);

  useEffect(() => () => clearClusterNavAccess(clusterName, accountId), [clusterName, accountId]);
}
