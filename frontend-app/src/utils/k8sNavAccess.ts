/**
 * What the sidebar shows about automation access for the cluster in context
 * (implementation plan, Addendum 1, J-5 and decision A4).
 *
 * The cluster page owns the capabilities request (K8sCapabilitiesProvider); the sidebar sits
 * outside it. Rather than send a second request, the page publishes the one fact the sidebar needs
 * here, and the sidebar reads it with `useSyncExternalStore`, so the two can never disagree. A value
 * is tied to its cluster and account and is cleared when the page leaves, so a sidebar entry never
 * shows another cluster's access. Strings only: this module is in every page's bundle.
 */
import { useSyncExternalStore } from 'react';

export interface ClusterNavAccess {
  clusterName: string;
  accountId: string;
  /** No automation change is allowed on this cluster for this user, whatever the reason. */
  viewOnly: boolean;
  /** Why, from the reason copy of the primary gate; null when changes are allowed. */
  reason: string | null;
}

export const VIEW_ONLY_TEXT = 'View only';

let current: ClusterNavAccess | null = null;
const listeners = new Set<() => void>();

function same(a: ClusterNavAccess | null, b: ClusterNavAccess | null): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return a.clusterName === b.clusterName && a.accountId === b.accountId && a.viewOnly === b.viewOnly && a.reason === b.reason;
}

/** Sets the access for the page's cluster (null while capabilities load). */
export function publishClusterNavAccess(next: ClusterNavAccess | null): void {
  if (same(current, next)) return;
  current = next;
  listeners.forEach((listener) => listener());
}

/** Forgets the value, but only if it still belongs to this cluster and account. */
export function clearClusterNavAccess(clusterName: string, accountId: string): void {
  if (current && current.clusterName === clusterName && current.accountId === accountId) {
    publishClusterNavAccess(null);
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const snapshot = () => current;

/** The published access, if it is for this cluster and account. */
export function useClusterNavAccess(clusterName: string | null, accountId: string | null): ClusterNavAccess | null {
  const access = useSyncExternalStore(subscribe, snapshot, snapshot);
  return access && access.clusterName === clusterName && access.accountId === accountId ? access : null;
}
