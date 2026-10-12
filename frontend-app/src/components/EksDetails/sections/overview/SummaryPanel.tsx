/**
 * Overview › Summary (docs/cloudk8s/ui-revamp-design.md §10.1, D9, D10, §14).
 *
 * The cluster now, from the agent's data, every figure with its source: the health signals
 * (rule-based, each with its own count, never a score), KPI tiles, the five most severe findings
 * (the Alerts page's own `extractAlerts`, so the two agree), the data sources capabilities report,
 * namespace utilisation, and the top CPU and memory pods. A cluster XamOps doesn't monitor shows the
 * Not monitored panel instead, never zeros; anything unreported renders "—" with its source.
 *
 * Removed with the old Overview tab: the composite health, efficiency, cost and security score
 * gauges, and the "savings opportunity" computed from node CPU. Neither had a real basis (D10).
 */
import { useMemo } from 'react';
import { Icon } from '@iconify/react';
import { Link, useLocation } from 'react-router-dom';
import { useK8sCapabilities } from '../../../../hooks/useK8sCapabilities';
import { EKS_SECTIONS, LEGACY_TAB_MAP } from '../../../../hooks/useEksSectionRoute';
import { extractAlerts, SEV_ORDER, type EksAlertItem, type EksAlertSeverity } from '../../../../hooks/useEksAlertsData';
import type { EksDashboard, EksSectionId, EksSubtabId } from '../../../../types/eksTypes';
import {
  clusterCostSource, clusterNodes, clusterRequestTotals, healthSignals, monitoringState, podCounts,
  MONTHLY_FROM_DAILY, type HealthSignal,
} from '../../../../utils/k8sDerivations';
import { DASH, formatCpu, formatMemoryMiB, formatPercent, formatUsd, toNumber } from '../../../../utils/k8sQuantities';
import { inPageState } from '../../../../utils/inPageNavigation';
import { K8sDataTable, type ColumnDef } from '../../../CloudK8s/shared/K8sDataTable';
import { MetricTile } from '../../../CloudK8s/shared/MetricTile';
import { NotMonitoredPanel } from '../../../CloudK8s/shared/NotMonitoredPanel';
import { SourceLabel, formatAgo } from '../../../CloudK8s/shared/SourceLabel';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import type { EksSectionProps } from '../sectionTypes';

export const HEALTH_TITLE = 'Health';
export const FINDINGS_TITLE = 'Top findings';
export const VIEW_ALL_ALERTS = 'View all in Alerts';
export const ALERTS_HREF = '/alerts?tab=k8s';
export const NO_FINDINGS = "No findings in the agent's latest data.";
export const PROMETHEUS_SOURCE = 'Prometheus (agent)';
export const RESOURCES_SOURCE = 'XamOps agent · k8s-resources';
export const TRIVY_SOURCE = 'Trivy (agent)';

/** Readable names for the data types capabilities report; anything else shows its own type. */
const DATA_SOURCE_LABELS: Record<string, string> = {
  'k8s-resources': 'Kubernetes resources',
  'prometheus-metrics': 'Prometheus metrics',
  'opencost-cost': 'OpenCost',
  'trivy-vulnerabilities': 'Trivy',
  'kubescape-compliance': 'Kubescape',
  'falco-events': 'Falco',
  'autoscaling-metrics': 'Automation agent',
};

const card = 'rounded-lg border border-[rgba(0,0,0,0.12)] bg-white';
const cardHeader = 'flex flex-wrap items-center justify-between gap-2 border-b border-[rgba(0,0,0,0.08)] px-4 py-3';
const cardTitle = 'text-[14px] font-semibold text-[rgba(0,0,0,0.88)]';
const secondary = 'text-[12px] text-[rgba(0,0,0,0.6)]';
const linkClass = 'inline-flex items-center gap-1 rounded text-[12px] font-semibold text-[rgba(0,0,0,0.88)] underline-offset-2 hover:underline '
  + 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

type AnyRecord = Record<string, any>;
const records = (value: unknown): AnyRecord[] =>
  Array.isArray(value) ? value.filter((v): v is AnyRecord => typeof v === 'object' && v !== null) : [];

/**
 * The cluster's used CPU or memory %, only when the agent's Prometheus metrics reported usage: the
 * backend leaves 0 in place when nothing did, and an unreported value isn't a measured 0 (D10).
 */
export function clusterUsagePct(dashboard: EksDashboard | null | undefined, resource: 'cpu' | 'memory'): number | null {
  const pct = toNumber(resource === 'cpu' ? dashboard?.clusterOverview?.cpuUsagePercent : dashboard?.clusterOverview?.memoryUsagePercent);
  const reported = records(dashboard?.nodeUtilization).length > 0 || (pct ?? 0) > 0;
  return reported ? pct : null;
}

/** Trivy's critical and high counts, or null when the dashboard carries no Trivy data. */
export function trivyCounts(dashboard: EksDashboard | null | undefined): { critical: number; high: number } | null {
  const summary = dashboard?.trivy?.summary;
  if (!summary) return null;
  const critical = toNumber(summary.totalCritical);
  const high = toNumber(summary.totalHigh);
  return critical === null && high === null ? null : { critical: critical ?? 0, high: high ?? 0 };
}

/** The five most severe findings, most severe first, then the most frequent. */
export function topFindings(alerts: readonly EksAlertItem[], limit = 5): EksAlertItem[] {
  return [...alerts]
    .sort((a, b) => SEV_ORDER[a.severity] - SEV_ORDER[b.severity] || (b.count ?? 0) - (a.count ?? 0))
    .slice(0, limit);
}

/** Where a finding's link goes: its legacy tab through the same map the page resolves. */
export function findingTarget(alert: EksAlertItem): { tab: EksSectionId; sub: EksSubtabId; label: string } {
  const target = LEGACY_TAB_MAP[alert.targetTab] ?? LEGACY_TAB_MAP.overview;
  const section = EKS_SECTIONS.find((s) => s.id === target.tab) ?? EKS_SECTIONS[0];
  const sub = section.subs.find((s) => s.id === target.sub) ?? section.subs[0];
  return { tab: section.id, sub: sub.id, label: `${section.label} › ${sub.label}` };
}

const SEVERITY: Record<EksAlertSeverity, { icon: string; label: string; className: string }> = {
  critical: { icon: 'mdi:alert-octagon', label: 'Critical', className: 'rounded border border-black px-1 font-bold text-[rgba(0,0,0,0.88)]' },
  high: { icon: 'mdi:alert-outline', label: 'High', className: 'font-semibold text-[rgba(0,0,0,0.88)]' },
  medium: { icon: 'mdi:alert-outline', label: 'Medium', className: 'font-normal text-[rgba(0,0,0,0.88)]' },
  low: { icon: 'mdi:information-outline', label: 'Low', className: 'font-normal text-[rgba(0,0,0,0.6)]' },
  info: { icon: 'mdi:information-outline', label: 'Info', className: 'font-normal text-[rgba(0,0,0,0.6)]' },
};

function SeverityMark({ severity }: { severity: EksAlertSeverity }) {
  const style = SEVERITY[severity];
  return (
    <span className={`inline-flex shrink-0 items-center gap-1 text-[12px] ${style.className}`} data-severity={severity}>
      <Icon icon={style.icon} width={14} aria-hidden="true" />
      {style.label}
    </span>
  );
}

function SignalRow({ signal }: { signal: HealthSignal }) {
  const state = !signal.known ? 'unknown' : signal.attention ? 'attention' : 'healthy';
  return (
    <li className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 py-2" data-signal={signal.id} data-attention={signal.attention}>
      <span className="w-44 shrink-0"><StatusMark state={state} label={signal.label} /></span>
      <span className="text-[13px] tabular-nums text-[rgba(0,0,0,0.88)]">{signal.value}</span>
      {signal.detail && <span className={secondary}>{signal.detail}</span>}
      {!signal.known && <span className={secondary}>Not reported by the XamOps agent</span>}
    </li>
  );
}

interface TopPod {
  name: string;
  namespace: string;
  value: number;
}

function TopPods({ title, pods, format, at }: { title: string; pods: TopPod[]; format: (v: number) => string; at: string | null }) {
  const max = pods.reduce((m, p) => Math.max(m, p.value), 0) || 1;
  return (
    <section className={card} aria-label={title}>
      <div className={cardHeader}>
        <h3 className={cardTitle}>{title}</h3>
        <SourceLabel source={PROMETHEUS_SOURCE} at={at} verb="data" missingText="no data yet" />
      </div>
      <div className="px-4 py-3">
        {pods.length === 0 ? (
          <p className={secondary}>{`${DASH} Not reported by ${PROMETHEUS_SOURCE}`}</p>
        ) : (
          <ol className="space-y-2.5">
            {pods.map((pod) => (
              <li key={`${pod.namespace}/${pod.name}`} className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1">
                <span className="flex min-w-0 items-baseline gap-2">
                  <span className="truncate font-mono text-[13px] font-semibold text-[rgba(0,0,0,0.88)]" title={pod.name}>{pod.name}</span>
                  <span className={`truncate ${secondary}`}>{pod.namespace}</span>
                </span>
                <span className="text-right text-[12px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]">{format(pod.value)}</span>
                <span className="col-span-2 h-1.5 overflow-hidden rounded-full bg-[rgba(0,0,0,0.06)]" aria-hidden="true">
                  <span className="block h-full rounded-full bg-[rgba(0,0,0,0.88)]" style={{ width: `${Math.min(100, (pod.value / max) * 100)}%` }} />
                </span>
              </li>
            ))}
          </ol>
        )}
        {pods.length > 0 && <p className={`mt-3 ${secondary}`}>Usage is reported for the top 20 pods only.</p>}
      </div>
    </section>
  );
}

interface NamespaceRow {
  namespace: string;
  cpuM: number | null;
  cpuPct: number | null;
  memMi: number | null;
  memPct: number | null;
  pods: number | null;
}

const withPct = (value: string, pct: number | null) => (value === DASH ? DASH : pct === null ? value : `${value} · ${formatPercent(pct)}`);

const NAMESPACE_COLUMNS: ReadonlyArray<ColumnDef<NamespaceRow>> = [
  { id: 'namespace', header: 'Namespace', priority: 'essential', cell: (r) => <span className="font-mono">{r.namespace}</span>, sortValue: (r) => r.namespace },
  { id: 'cpu', header: 'CPU used', align: 'right', cell: (r) => withPct(formatCpu(r.cpuM), r.cpuPct), sortValue: (r) => r.cpuM, csv: (r) => r.cpuM },
  { id: 'memory', header: 'Memory used', align: 'right', cell: (r) => withPct(formatMemoryMiB(r.memMi), r.memPct), sortValue: (r) => r.memMi, csv: (r) => r.memMi },
  { id: 'pods', header: 'Pods', align: 'right', cell: (r) => (r.pods === null ? DASH : r.pods), sortValue: (r) => r.pods },
];

export default function SummaryPanel({ clusterName, accountId, region, eksData, route, onOpenGuide }: EksSectionProps) {
  const location = useLocation();
  const caps = useK8sCapabilities();
  const dashboard = eksData.dashboard;
  const capsCluster = caps.forCluster(clusterName);
  const monitoring = monitoringState({ capabilitiesStatus: caps.status, cluster: capsCluster, dashboard });

  const derived = useMemo(() => {
    const nodes = clusterNodes(dashboard);
    return {
      signals: healthSignals(dashboard),
      nodes: nodes.length > 0 ? { ready: nodes.filter((n) => n.ready === true).length, total: nodes.length } : null,
      pods: podCounts(dashboard),
      requests: clusterRequestTotals(dashboard),
      cpuUsed: clusterUsagePct(dashboard, 'cpu'),
      memUsed: clusterUsagePct(dashboard, 'memory'),
      cost: clusterCostSource(dashboard),
      trivy: trivyCounts(dashboard),
      findings: topFindings(extractAlerts(clusterName, region, accountId, dashboard)),
      topCpu: records(dashboard?.topCpuPods).map((p) => ({ name: String(p.name ?? ''), namespace: String(p.namespace ?? ''), value: toNumber(p.cpuMillicores) }))
        .filter((p): p is TopPod => p.name !== '' && p.value !== null).slice(0, 8),
      topMemory: records(dashboard?.topMemoryPods).map((p) => ({ name: String(p.name ?? ''), namespace: String(p.namespace ?? ''), value: toNumber(p.memoryMB) }))
        .filter((p): p is TopPod => p.name !== '' && p.value !== null).slice(0, 8),
      namespaces: records(dashboard?.namespaceUtilization).map((n): NamespaceRow => ({
        namespace: String(n.namespace ?? ''),
        cpuM: toNumber(n.cpuUsageMillicores),
        cpuPct: toNumber(n.cpuUsagePct),
        memMi: toNumber(n.memoryUsageMB),
        memPct: toNumber(n.memUsagePct),
        pods: toNumber(n.podCount),
      })).filter((n) => n.namespace !== ''),
    };
  }, [dashboard, clusterName, region, accountId]);

  if (monitoring.state === 'M0') return <NotMonitoredPanel clusterName={clusterName} onSetUp={onOpenGuide} />;

  const sources = capsCluster?.dataSources ?? [];
  const sourceAt = (type: string) => sources.find((s) => s.type === type)?.collectedAt ?? null;
  const agentAt = monitoring.lastDataAt ?? sourceAt('k8s-resources');
  const promAt = sourceAt('prometheus-metrics');
  const usedLine = (pct: number | null) => (pct === null ? 'Usage not reported' : `Used ${formatPercent(pct)} · ${PROMETHEUS_SOURCE}`);
  const { requests, cost } = derived;

  return (
    <div className="space-y-4" data-panel="overview-summary">
      {monitoring.state === 'M2' && (
        <p role="status" className={`${card} flex flex-wrap items-center gap-2 px-4 py-2.5 text-[13px] text-[rgba(0,0,0,0.88)]`}>
          <StatusMark state="attention" label="Data stale" detail={formatAgo(agentAt)} />
          <span>The XamOps agent hasn't reported recently. These are the last values it sent.</span>
        </p>
      )}

      <section className={card} aria-label={HEALTH_TITLE}>
        <div className={cardHeader}>
          <h3 className={cardTitle}>{HEALTH_TITLE}</h3>
          <SourceLabel source="XamOps agent" at={agentAt} verb="data" missingText="no data yet" />
        </div>
        <ul className="divide-y divide-[rgba(0,0,0,0.06)] px-4">
          {derived.signals.map((s) => <SignalRow key={s.id} signal={s} />)}
        </ul>
      </section>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <MetricTile
          label="Nodes ready"
          value={derived.nodes?.ready ?? null}
          suffix={derived.nodes ? `/ ${derived.nodes.total}` : undefined}
          source={RESOURCES_SOURCE}
        />
        <MetricTile
          label="Pods running"
          value={derived.pods?.running ?? null}
          suffix={derived.pods ? `/ ${derived.pods.total}` : undefined}
          detail={derived.pods ? `${derived.pods.pending} pending · ${derived.pods.failed} failed` : undefined}
          source={RESOURCES_SOURCE}
        />
        <MetricTile
          label="CPU requested"
          value={requests.cpuRequestedPct}
          format={(v) => formatPercent(v)}
          detail={usedLine(derived.cpuUsed)}
          title={requests.cpuAllocatableM !== null ? `${formatCpu(requests.cpuRequestM)} requested of ${formatCpu(requests.cpuAllocatableM)} allocatable` : undefined}
          source="Pod requests ÷ node allocatable"
          missingText="Node allocatable not reported"
        />
        <MetricTile
          label="Memory requested"
          value={requests.memRequestedPct}
          format={(v) => formatPercent(v)}
          detail={usedLine(derived.memUsed)}
          title={requests.memAllocatableMi !== null ? `${formatMemoryMiB(requests.memRequestMi)} requested of ${formatMemoryMiB(requests.memAllocatableMi)} allocatable` : undefined}
          source="Pod requests ÷ node allocatable"
          missingText="Node allocatable not reported"
        />
        <MetricTile
          label="Cost today"
          value={cost.dailyCost}
          format={(v) => formatUsd(v)}
          detail={cost.monthlyCost !== null ? `${formatUsd(cost.monthlyCost, 0)} a month${cost.monthlyFromDaily ? ' (daily × 30)' : ''}` : undefined}
          title={cost.monthlyFromDaily ? MONTHLY_FROM_DAILY : undefined}
          source={cost.label}
          missingText="No cost source"
        />
        <MetricTile
          label="Critical vulnerabilities"
          value={derived.trivy?.critical ?? null}
          detail={derived.trivy ? `${derived.trivy.high} high` : undefined}
          source={TRIVY_SOURCE}
        />
      </div>

      <section className={card} aria-label={FINDINGS_TITLE}>
        <div className={cardHeader}>
          <h3 className={cardTitle}>{FINDINGS_TITLE}</h3>
          <Link to={ALERTS_HREF} className={linkClass}>
            {VIEW_ALL_ALERTS}
            <Icon icon="mdi:arrow-right" width={14} aria-hidden="true" />
          </Link>
        </div>
        {derived.findings.length === 0 ? (
          <p className={`px-4 py-3 ${secondary}`}>{NO_FINDINGS}</p>
        ) : (
          <ul className="divide-y divide-[rgba(0,0,0,0.06)]">
            {derived.findings.map((alert) => {
              const target = findingTarget(alert);
              return (
                <li key={alert.id} className="flex flex-wrap items-start gap-x-3 gap-y-1 px-4 py-2.5" data-finding={alert.source}>
                  <SeverityMark severity={alert.severity} />
                  <span className="min-w-0 flex-1">
                    <span className="block text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                      {alert.title}
                      {alert.count && alert.count > 1 ? <span className="font-normal text-[rgba(0,0,0,0.6)]">{` · ${alert.count}×`}</span> : null}
                    </span>
                    <span className={`block truncate ${secondary}`} title={alert.message}>{alert.message}</span>
                  </span>
                  <Link to={route.hrefFor(target.tab, target.sub)} state={inPageState(location)} className={linkClass}>
                    {target.label}
                    <Icon icon="mdi:chevron-right" width={14} aria-hidden="true" />
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section className={card} aria-label="Data sources">
        <div className={cardHeader}>
          <h3 className={cardTitle}>Data sources</h3>
        </div>
        <div className="px-4 py-3">
          {caps.status === 'loading' && <p className={secondary}>Checking data sources…</p>}
          {caps.status === 'error' && <p className={secondary}>Couldn't check data sources.</p>}
          {caps.status === 'ready' && sources.length === 0 && <p className={secondary}>The agent hasn't reported any data yet.</p>}
          {sources.length > 0 && (
            <ul className="flex flex-wrap gap-2">
              {sources.map((s) => (
                <li key={s.type} data-source-type={s.type}
                  className="inline-flex items-center gap-1.5 rounded-full border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.02)] px-2.5 py-1">
                  <SourceLabel source={DATA_SOURCE_LABELS[s.type] ?? s.type} at={s.collectedAt} verb="data" missingText="no data yet" />
                  {!s.displayed && <span className={secondary}>· received, not shown</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      <section className={card} aria-label="Namespace utilisation">
        <div className={cardHeader}>
          <h3 className={cardTitle}>Namespace utilisation</h3>
          <SourceLabel source={PROMETHEUS_SOURCE} at={promAt} verb="data" missingText="no data yet" />
        </div>
        <div className="px-4 py-3">
          <K8sDataTable
            rows={derived.namespaces}
            columns={NAMESPACE_COLUMNS}
            rowKey={(r) => r.namespace}
            caption="Namespace utilisation"
            initialSort={{ columnId: 'cpu', direction: 'desc' }}
            emptyState={<p className={secondary}>{`${DASH} Not reported by ${PROMETHEUS_SOURCE}`}</p>}
          />
        </div>
      </section>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <TopPods title="Top CPU pods" pods={derived.topCpu} format={(v) => formatCpu(v)} at={promAt} />
        <TopPods title="Top memory pods" pods={derived.topMemory} format={(v) => formatMemoryMiB(v)} at={promAt} />
      </div>
    </div>
  );
}
