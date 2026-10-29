/**
 * The one-line status strip above the cluster's sections (docs/cloudk8s/ui-revamp-design.md §4.2,
 * §10.1, D10): overall health, nodes ready, pods running, node automation and access.
 *
 * Health is rule-based (`healthSignals`, `overallHealth`), never a score. Nodes and pods are counted
 * from the same lists the Summary counts, so "running" can't exceed the total. Access reads the
 * capabilities connection, like the fleet chip, and says "Unknown" when capabilities failed. A
 * cluster that isn't monitored shows no counts at all, rather than zeros.
 */
import { useMemo, type ReactNode } from 'react';
import { useK8sCapabilities, type K8sCapabilitiesValue } from '../../hooks/useK8sCapabilities';
import type { EksDashboard } from '../../types/eksTypes';
import type { CapabilitiesCluster, NodeAutomationState } from '../../types/k8sAutomation';
import {
  healthSignals, monitoringState, overallHealth, type HealthSignal, type HealthSignalId, type OverallHealthKind,
} from '../../utils/k8sDerivations';
import { DASH } from '../../utils/k8sQuantities';
import { StatusMark, type StatusState } from '../CloudK8s/shared/StatusMark';

export const STRIP_LABEL = 'Cluster status';
export const DATA_UNAVAILABLE = 'Data unavailable';

export const HEALTH_MARK: Record<OverallHealthKind, StatusState> = {
  healthy: 'healthy',
  attention: 'attention',
  stale: 'attention',
  'not-monitored': 'not-set-up',
  unknown: 'unknown',
};

/** The fleet's "Nodes" line labels (§8.1, N0-N3). */
export const NODE_AUTOMATION_LABELS: Record<NodeAutomationState, string> = {
  NOT_INSTALLED: 'Not installed',
  OFF: 'Off',
  ANALYSIS: 'Analysis',
  AUTOMATED: 'Automated',
  UNKNOWN: 'Unknown',
};

interface Mark {
  state: StatusState;
  label: string;
  detail?: string | null;
}

/** Node automation from capabilities: the derived state, with "agent stale" when it is (N3). */
export function nodeAutomationMark(status: K8sCapabilitiesValue['status'], cluster: CapabilitiesCluster | null): Mark {
  if (status === 'loading') return { state: 'unknown', label: 'Checking…' };
  if (status === 'error') return { state: 'unknown', label: 'Unknown' };
  if (!cluster || !cluster.registered) return { state: 'not-set-up', label: NODE_AUTOMATION_LABELS.NOT_INSTALLED };
  const derived = cluster.nodeAutomation.derivedState;
  const stale = cluster.automationAgent.status === 'STALE' && derived !== 'NOT_INSTALLED';
  const state: StatusState = derived === 'NOT_INSTALLED' || derived === 'OFF' ? 'not-set-up'
    : derived === 'UNKNOWN' ? 'unknown'
      : stale ? 'attention' : 'healthy';
  return { state, label: NODE_AUTOMATION_LABELS[derived] ?? NODE_AUTOMATION_LABELS.UNKNOWN, detail: stale ? 'agent stale' : null };
}

/** Access from capabilities: the cluster's own connection, else the account's. */
export function accessMark(caps: Pick<K8sCapabilitiesValue, 'status' | 'data' | 'noAccess'>, cluster: CapabilitiesCluster | null): Mark {
  if (caps.status === 'loading') return { state: 'unknown', label: 'Checking…' };
  if (caps.noAccess) return { state: 'not-set-up', label: 'No access' };
  if (caps.status === 'error' || !caps.data) return { state: 'unknown', label: 'Unknown' };
  const connection = cluster?.connection ?? caps.data.connection;
  // A malformed / empty / partial payload can leave `connection` undefined (BUG-2): degrade the strip
  // to the honest Unknown mark instead of dereferencing `.accessLevel` and crashing the page.
  if (!connection) return { state: 'unknown', label: 'Unknown' };
  return connection.accessLevel === 'READ_WRITE'
    ? { state: 'healthy', label: 'Read-write' }
    : { state: 'not-set-up', label: 'Read-only' };
}

const signal = (signals: readonly HealthSignal[], id: HealthSignalId) => signals.find((s) => s.id === id) ?? null;

function Item({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 items-baseline gap-2" data-strip-item={term}>
      <dt className="text-[12px] font-medium text-[rgba(0,0,0,0.6)]">{term}</dt>
      <dd className="min-w-0 text-[13px] text-[rgba(0,0,0,0.88)]">{children}</dd>
    </div>
  );
}

function Count({ item, hidden }: { item: HealthSignal | null; hidden: boolean }) {
  if (hidden || !item || !item.known) return <span className="tabular-nums">{DASH}</span>;
  return (
    <span className="tabular-nums">
      <span className={item.attention ? 'font-semibold' : undefined}>{item.value}</span>
      {item.detail && <span className="text-[12px] text-[rgba(0,0,0,0.6)]"> · {item.detail}</span>}
    </span>
  );
}

export interface ClusterStatusStripProps {
  clusterName: string;
  dashboard: EksDashboard | null;
  /** The dashboard failed to load: health says "Data unavailable" and no counts are shown. */
  dashboardError: boolean;
}

export function ClusterStatusStrip({ clusterName, dashboard, dashboardError }: ClusterStatusStripProps) {
  const caps = useK8sCapabilities();
  const cluster = caps.forCluster(clusterName);
  const signals = useMemo(() => healthSignals(dashboard), [dashboard]);
  const monitoring = monitoringState({ capabilitiesStatus: caps.status, cluster, dashboard });
  const health = overallHealth(monitoring.state, signals);
  const notMonitored = health.kind === 'not-monitored';
  const healthMark: Mark = dashboardError
    ? { state: 'unknown', label: DATA_UNAVAILABLE }
    : { state: HEALTH_MARK[health.kind], label: health.label };
  const automation = nodeAutomationMark(caps.status, cluster);
  const access = accessMark(caps, cluster);

  return (
    <section aria-label={STRIP_LABEL} data-health={dashboardError ? 'unavailable' : health.kind}
      className="rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2.5">
      <dl className="flex flex-wrap items-center gap-x-6 gap-y-2">
        <Item term="Health">
          <StatusMark state={healthMark.state} label={healthMark.label} />
        </Item>
        <Item term="Nodes ready"><Count item={signal(signals, 'nodes-ready')} hidden={notMonitored || dashboardError} /></Item>
        <Item term="Pods running"><Count item={signal(signals, 'pods')} hidden={notMonitored || dashboardError} /></Item>
        <Item term="Node automation">
          <StatusMark state={automation.state} label={automation.label} detail={automation.detail} />
        </Item>
        <Item term="Access">
          <StatusMark state={access.state} label={access.label} />
        </Item>
      </dl>
    </section>
  );
}

export default ClusterStatusStrip;
