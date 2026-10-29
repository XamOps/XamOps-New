/**
 * The cluster page header (docs/cloudk8s/ui-revamp-design.md §4.2; plan item 19).
 *
 * In the topbar, through the Topbar context: the cluster name as the title, back to the fleet,
 * version · region, "Synced …", Guide and Sync. In the page: two freshness clocks from
 * capabilities, each a SourceLabel, because they measure different things: the XamOps agent's last
 * data (the snapshot every section shows) and the node automation agent's last check-in (how recently
 * the xamops-autoscaler reconciled its node policy). Then the sync timeout line. The clocks sit under the topbar rather than in
 * it so the header still fits at 1280 px.
 */
import { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { Link } from 'react-router-dom';
import { useTopbarActions } from '../../contexts/TopbarContext';
import { useK8sCapabilities, type K8sCapabilitiesValue } from '../../hooks/useK8sCapabilities';
import type { AgentStatus } from '../../types/k8sAutomation';
import { SourceLabel } from '../CloudK8s/shared/SourceLabel';
import { displayVersion } from '../../utils/k8sVersion';

export const SYNC_TIMEOUT_TEXT = 'Sync is taking longer than expected. The latest data will appear when it finishes.';
export const VERSION_NOT_REPORTED = 'Version not reported';

export interface ClusterHeaderProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** Kubernetes version from describeCluster or the agent; null when neither reported one. */
  version: string | null;
  isSyncing: boolean;
  syncTimedOut: boolean;
  lastUpdateTimestamp: number;
  getTimeSince: (timestamp: number) => string;
  onSync: () => void;
  onOpenGuide: () => void;
}

const focusRing = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

interface ClockState {
  at: string | null;
  verb: string;
  missingText: string;
}

/** What one agent clock shows, from the capabilities state and the agent's status. */
function clockState(
  caps: Pick<K8sCapabilitiesValue, 'status'>,
  agent: { status: AgentStatus; at: string | null } | null,
  verb: string,
): ClockState {
  if (caps.status === 'loading') return { at: null, verb, missingText: 'checking…' };
  if (caps.status === 'error') return { at: null, verb, missingText: 'unknown' };
  if (!agent || agent.status === 'NOT_INSTALLED') return { at: null, verb, missingText: 'not installed' };
  if (agent.status === 'UNKNOWN') return { at: null, verb, missingText: 'unknown' };
  return { at: agent.at, verb: agent.status === 'STALE' ? `stale, ${verb}` : verb, missingText: 'no data yet' };
}

/** "Synced 2m ago", "Synced just now". */
function syncedLabel(since: string): string {
  return `Synced ${since.charAt(0).toLowerCase()}${since.slice(1)}`;
}

export function ClusterHeader({
  clusterName, accountId, region, version, isSyncing, syncTimedOut, lastUpdateTimestamp, getTimeSince, onSync, onOpenGuide,
}: ClusterHeaderProps) {
  const { setTitle, setActions, clearActions } = useTopbarActions();
  const caps = useK8sCapabilities();
  const cluster = caps.forCluster(clusterName);

  useEffect(() => {
    setTitle(clusterName);
    setActions(
      <div className="flex items-center gap-3">
        <Link
          to={`/cloudk8s?accountIds=${encodeURIComponent(accountId)}`}
          aria-label="Back to clusters"
          title="Back to clusters"
          className={`flex h-9 w-9 items-center justify-center rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] transition-colors hover:border-[rgba(0,0,0,0.25)] hover:text-[rgba(0,0,0,0.88)] ${focusRing}`}
        >
          <Icon icon="mdi:arrow-left" width={16} aria-hidden="true" />
        </Link>

        <div className="hidden flex-col items-end leading-tight md:flex">
          <span className="flex items-center gap-1 whitespace-nowrap text-[11px] font-medium text-[rgba(0,0,0,0.6)]">
            <Icon icon="mdi:source-branch" width={12} aria-hidden="true" />
            {version ? <span title={version}>{displayVersion(version)}</span> : <span>{VERSION_NOT_REPORTED}</span>}
            <span aria-hidden="true">·</span>
            <Icon icon="mdi:map-marker-outline" width={12} aria-hidden="true" />
            <span>{region}</span>
          </span>
          <span className="whitespace-nowrap text-[11px] font-semibold text-[rgba(0,0,0,0.6)]">
            {syncedLabel(getTimeSince(lastUpdateTimestamp))}
          </span>
        </div>

        <button
          type="button"
          onClick={onOpenGuide}
          className={`flex items-center gap-2 rounded-xl border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2 text-xs font-bold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.03)] ${focusRing}`}
        >
          <Icon icon="mdi:book-open-variant" width={14} aria-hidden="true" />
          <span>Guide</span>
        </button>

        <button
          type="button"
          onClick={() => { if (!isSyncing) onSync(); }}
          aria-disabled={isSyncing || undefined}
          className={`flex items-center gap-2 rounded-xl bg-[rgba(0,0,0,0.88)] px-4 py-2 text-xs font-bold text-white transition-colors hover:bg-black aria-disabled:cursor-default aria-disabled:opacity-60 ${focusRing}`}
        >
          <Icon icon="mdi:sync" width={14} aria-hidden="true" className={isSyncing ? 'animate-spin' : ''} />
          <span>{isSyncing ? 'Syncing…' : 'Sync data'}</span>
        </button>
      </div>,
    );
    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, clusterName, accountId, version, region, isSyncing, lastUpdateTimestamp, getTimeSince, onSync, onOpenGuide]);

  // A malformed / partial capabilities payload can list a cluster with no agent / automationAgent
  // (BUG-2): fall back to null (an Unknown clock) rather than dereferencing undefined and crashing.
  const snapshot = clockState(caps, cluster?.agent ? { status: cluster.agent.status, at: cluster.agent.lastDataAt } : null, 'data');
  const reconcile = clockState(
    caps,
    cluster?.automationAgent ? { status: cluster.automationAgent.status, at: cluster.automationAgent.lastSeenAt } : null,
    'seen',
  );

  return (
    <div className="space-y-1">
      <div role="group" aria-label="Data freshness" className="flex flex-wrap items-center gap-x-5 gap-y-1">
        <SourceLabel source="XamOps agent" at={snapshot.at} verb={snapshot.verb} missingText={snapshot.missingText} />
        <SourceLabel source="Node automation agent" at={reconcile.at} verb={reconcile.verb} missingText={reconcile.missingText} />
      </div>
      <p role="status" className="text-[12px] text-[rgba(0,0,0,0.6)]">
        {syncTimedOut ? SYNC_TIMEOUT_TEXT : ''}
      </p>
    </div>
  );
}

export default ClusterHeader;
