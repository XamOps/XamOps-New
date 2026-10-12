/**
 * Publishes the cluster page's automation access for the sidebar
 * (implementation plan, Addendum 1, J-5 and decision A4; design §6.3, §6.4).
 *
 * "View only" means no §6.3 action is allowed on this cluster for this user, whatever the reason
 * (read-only connection, no permission, demo, controllers not ready, …), and the hosted Workload
 * autoscaler can't apply either. The explanation is the reason copy of the primary gate on turning
 * node automation on, the first control a user who can change automation would reach. Nothing is
 * published while capabilities load.
 */
import { useEffect, useMemo } from 'react';
import { ACTION_IDS, isVerticalAction, type ActionId, type CapabilitiesDto, type Capability } from '../types/k8sAutomation';
import { alsoNeedsLine, reasonCopy } from '../components/CloudK8s/shared/reasonCopy';
import { clearClusterNavAccess, publishClusterNavAccess } from '../utils/k8sNavAccess';
import { findCluster, useK8sCapabilities, type CapabilitiesStatus } from './useK8sCapabilities';

/** The action whose primary reason explains "View only". */
export const VIEW_ONLY_EXPLAINED_BY: ActionId = 'automation.enable';

export interface NavAccess {
  viewOnly: boolean;
  reason: string | null;
}

/**
 * Whether the hosted Workload autoscaler (Automation › Workload autoscaler) may apply on this
 * cluster, by the gates it shares with the server: the engine is on and serves the cluster,
 * `vertical-rightsizing` is ready, and no write gate applies (read-only connection, no permission,
 * demo). Until FEAT-005 unifies the two vertical clients, that tab runs on the engine's own gates,
 * not on the D16 contract block the capability model puts on every `vertical.*` action, so the
 * sidebar mustn't call the section "View only" while the tab can apply (demo-cut review S2).
 */
export function hostedWorkloadAutoscalerMayApply(
  data: CapabilitiesDto | null,
  clusterName: string,
  verticalEngineEnabled: boolean,
  demo: boolean,
): boolean {
  const cluster = findCluster(data, clusterName);
  if (!data || demo || !verticalEngineEnabled || !cluster?.registered) return false;
  const writeGate = cluster.writeGate ?? data.writeGate;
  if ((writeGate?.reasons ?? []).length > 0 || (data.user?.reasons ?? []).length > 0) return false;
  return data.readiness?.['vertical-rightsizing']?.ready === true;
}

/**
 * Pure: the sidebar's access for one cluster, or null while capabilities load. `vertical.*` actions
 * are left out of the "any change allowed" test; `hostedVertical` stands in for them (review S2).
 */
export function navAccessFor(
  status: CapabilitiesStatus,
  can: (actionId: ActionId, ctx?: { cluster?: string | null }) => Capability,
  clusterName: string,
  hostedVertical = false,
): NavAccess | null {
  if (status === 'loading') return null;
  if (hostedVertical || ACTION_IDS.some((id) => !isVerticalAction(id) && can(id, { cluster: clusterName }).allowed)) {
    return { viewOnly: false, reason: null };
  }
  const capability = can(VIEW_ONLY_EXPLAINED_BY, { cluster: clusterName });
  const text = capability.gate ? reasonCopy(capability.gate).text : null;
  const reason = [text, alsoNeedsLine(capability.also)].filter(Boolean).join(' ');
  return { viewOnly: true, reason: reason || null };
}

/** Call inside the page's K8sCapabilitiesProvider. Clears the value when the page leaves. */
export function usePublishClusterNavAccess(clusterName: string, accountId: string): void {
  const { status, can, data, verticalEngineEnabled, demo } = useK8sCapabilities();
  const access = useMemo(() => {
    const hosted = status === 'ready' && hostedWorkloadAutoscalerMayApply(data, clusterName, verticalEngineEnabled, demo);
    return navAccessFor(status, can, clusterName, hosted);
  }, [status, can, clusterName, data, verticalEngineEnabled, demo]);

  useEffect(() => {
    publishClusterNavAccess(access ? { clusterName, accountId, ...access } : null);
  }, [access, clusterName, accountId]);

  useEffect(() => () => clearClusterNavAccess(clusterName, accountId), [clusterName, accountId]);
}
