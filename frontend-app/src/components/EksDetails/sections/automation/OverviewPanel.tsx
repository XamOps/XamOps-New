/**
 * Automation › Overview (docs/cloudk8s/ui-revamp-design.md §9.1 items 1 and 4; plan item 19).
 *
 * The access overlays, the Access block (connection, your permission, the XamOps agent), and
 * "What automation can do on this cluster today": one row per capability, what XamOps analyses and
 * whether it may apply, with the server's message for anything not ready. Everything comes from
 * capabilities; the panel makes no automation-agent or vertical-engine request.
 */
import { useId, type ReactNode } from 'react';
import {
  LOADING_CAPABILITY,
  findCluster,
  useK8sCapabilities,
  type K8sCapabilitiesValue,
} from '../../../../hooks/useK8sCapabilities';
import type { ActionId, CapabilitiesDto, Capability, ReadinessKey } from '../../../../types/k8sAutomation';
import { AccessBanner } from '../../../CloudK8s/shared/AccessBanner';
import { StatusMark } from '../../../CloudK8s/shared/StatusMark';
import { CHECKING_ACCESS_TEXT, LockIcon, Remedies } from '../../../CloudK8s/shared/ReasonText';
import { alsoNeedsLine, reasonCopy } from '../../../CloudK8s/shared/reasonCopy';
import { formatAgo } from '../../../CloudK8s/shared/SourceLabel';

/** The workload row while the vertical engine is switched off (V7). */
export const NOT_TURNED_ON_TEXT = 'Not turned on in this environment';

export interface TodayRow {
  id: string;
  label: string;
  /** What XamOps analyses today; null renders a dash. */
  analysis: string | null;
  /** Where the Apply column comes from: an action's capability, or a readiness key with no control yet. */
  apply: { actionId: ActionId } | { readiness: ReadinessKey };
  /** The vertical engine's row, replaced by {@link NOT_TURNED_ON_TEXT} while the engine is off. */
  vertical?: boolean;
}

/** §9.1 item 4, in display order. */
export const TODAY_ROWS: readonly TodayRow[] = [
  { id: 'workload-rightsizing', label: 'Workload rightsizing', analysis: 'Recommendations', apply: { actionId: 'vertical.apply' }, vertical: true },
  { id: 'node-consolidation', label: 'Node consolidation', analysis: 'Analysis not yet reliable', apply: { actionId: 'evictor.live' } },
  { id: 'node-optimizer', label: 'Node optimizer', analysis: 'Idle-node thresholds', apply: { actionId: 'nodeOptimizer.actions' } },
  { id: 'rebalancing', label: 'Rebalancing', analysis: null, apply: { actionId: 'rebalance.trigger' } },
  { id: 'hpa-manager', label: 'HPA manager', analysis: 'Not shown yet', apply: { actionId: 'hpa.apply' } },
  { id: 'scale-out', label: 'Scale-out', analysis: 'Not built', apply: { actionId: 'scaleOut.settings' } },
  { id: 'node-templates', label: 'Node templates', analysis: 'Not built', apply: { actionId: 'nodeTemplates.edit' } },
  { id: 'vpa-baseline', label: 'VPA baseline', analysis: 'Collected by the automation agent (display coming)', apply: { readiness: 'vpa-apply' } },
  { id: 'spot-interruption', label: 'Spot interruption handling', analysis: 'Protective · state not shown yet', apply: { actionId: 'nodeOptimizer.settings' } },
];

/** A readiness key with no control of its own (VPA): ready, or its server message. */
function readinessCapability(caps: K8sCapabilitiesValue, key: ReadinessKey): Capability {
  if (caps.status === 'loading') return LOADING_CAPABILITY;
  if (caps.status === 'error' || !caps.data) return { allowed: false, editable: false, gate: { reason: 'UNKNOWN' }, also: [] };
  const readiness = caps.data.readiness?.[key];
  if (readiness?.ready) return { allowed: true, editable: true, gate: null, also: [] };
  return {
    allowed: false,
    editable: false,
    gate: { reason: 'CONTROLLER_NOT_READY', message: readiness?.message ?? undefined },
    also: [],
  };
}

function applyCapability(caps: K8sCapabilitiesValue, row: TodayRow, clusterName: string): Capability {
  return 'actionId' in row.apply
    ? caps.can(row.apply.actionId, { cluster: clusterName })
    : readinessCapability(caps, row.apply.readiness);
}

/** "data 4 min old", or "no data yet" when the agent reported no time. */
function dataAge(at: string | null, now: number): string {
  const ago = formatAgo(at, now);
  return ago ? `data ${ago.replace(/ ago$/, '')} old` : 'no data yet';
}

function ApplyCell({ capability }: { capability: Capability }) {
  if (capability.allowed) return <StatusMark state="healthy" label="Available" />;
  const gate = capability.gate;
  if (!gate) return <span className="text-[rgba(0,0,0,0.6)]">{CHECKING_ACCESS_TEXT}</span>;
  const copy = reasonCopy(gate);
  const also = alsoNeedsLine(capability.also);
  return (
    <span className="flex items-start gap-1.5">
      <LockIcon className="mt-0.5" />
      <span className="min-w-0">
        <span className="block text-[rgba(0,0,0,0.88)]" title={gate.raw}>
          {copy.heading && <span className="font-semibold">{copy.heading}: </span>}
          {copy.text}
        </span>
        {also && <span className="mt-0.5 block text-[12px] text-[rgba(0,0,0,0.6)]">{also}</span>}
      </span>
    </span>
  );
}

function AccessRow({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[160px_minmax(0,1fr)] gap-3 py-2.5 text-[13px]">
      <dt className="font-semibold text-[rgba(0,0,0,0.6)]">{term}</dt>
      <dd className="min-w-0 text-[rgba(0,0,0,0.88)]">{children}</dd>
    </div>
  );
}

const noteClass = 'mt-1 block text-[12px] text-[rgba(0,0,0,0.6)]';

function ConnectionValue({ data, clusterName }: { data: CapabilitiesDto; clusterName: string }) {
  const connection = findCluster(data, clusterName)?.connection ?? data.connection;
  const readOnly = connection.accessLevel !== 'READ_WRITE';
  const verified = (connection.status ?? '').toUpperCase() === 'CONNECTED';
  const readOnlyCopy = reasonCopy({ reason: 'READ_ONLY_CONNECTION' });
  const unverifiedCopy = reasonCopy({ reason: 'CONNECTION_NOT_VERIFIED' });
  return (
    <>
      {readOnly ? <StatusMark state="not-set-up" label="Read-only" /> : <StatusMark state="healthy" label="Read-write" />}
      {readOnly && (
        <>
          <span className={noteClass}>{readOnlyCopy.text}</span>
          <Remedies remedies={readOnlyCopy.remedies} />
        </>
      )}
      {!verified && (
        <>
          <span className="mt-1 block"><StatusMark state="unverified" label="Not verified" /></span>
          <span className={noteClass}>{unverifiedCopy.text}</span>
          <Remedies remedies={unverifiedCopy.remedies} />
        </>
      )}
    </>
  );
}

function PermissionValue({ data }: { data: CapabilitiesDto }) {
  const { reasons, detail } = data.user;
  if (reasons.includes('NO_USER_PERMISSION')) {
    if (detail === 'ROLE') return <StatusMark state="not-set-up" label="View only: your role can't change automation" />;
    if (detail === 'IMPERSONATION') return <StatusMark state="not-set-up" label="View only while signed in as another user" />;
    return (
      <>
        <StatusMark state="not-set-up" label="View only" />
        <Remedies remedies={reasonCopy({ reason: 'NO_USER_PERMISSION', detail: 'TAB_GRANT' }).remedies} />
      </>
    );
  }
  if (reasons.includes('DEMO')) return <StatusMark state="not-set-up" label={reasonCopy({ reason: 'DEMO' }).text} />;
  return <StatusMark state="healthy" label="You can change automation" />;
}

function AgentValue({ data, clusterName, now }: { data: CapabilitiesDto; clusterName: string; now: number }) {
  const agent = findCluster(data, clusterName)?.agent;
  if (!agent || agent.status === 'NOT_INSTALLED') {
    return (
      <>
        <StatusMark state="not-set-up" label="Not installed" />
        <Remedies remedies={reasonCopy({ reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' }).remedies} />
      </>
    );
  }
  if (agent.status === 'UNKNOWN') return <StatusMark state="unknown" label="Unknown" />;
  const detail = dataAge(agent.lastDataAt, now);
  return agent.status === 'STALE'
    ? <StatusMark state="attention" label="Stale" detail={detail} />
    : <StatusMark state="healthy" label="Connected" detail={detail} />;
}

function AccessBlock({ caps, clusterName }: { caps: K8sCapabilitiesValue; clusterName: string }) {
  const headingId = useId();
  const data = caps.status === 'ready' ? caps.data : null;
  const unknown = <StatusMark state="unknown" label="Unknown" />;
  return (
    <section aria-labelledby={headingId} className="rounded-lg border border-[rgba(0,0,0,0.12)] bg-white p-4">
      <h2 id={headingId} className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Access</h2>
      {caps.status === 'loading' ? (
        <p className="mt-2 text-[13px] text-[rgba(0,0,0,0.6)]">{CHECKING_ACCESS_TEXT}</p>
      ) : (
        <dl className="mt-1 divide-y divide-[rgba(0,0,0,0.08)]">
          <AccessRow term="Connection">{data ? <ConnectionValue data={data} clusterName={clusterName} /> : unknown}</AccessRow>
          <AccessRow term="Your permission">{data ? <PermissionValue data={data} /> : unknown}</AccessRow>
          <AccessRow term="XamOps agent">
            {data ? <AgentValue data={data} clusterName={clusterName} now={Date.now()} /> : unknown}
          </AccessRow>
        </dl>
      )}
    </section>
  );
}

function analysisCell(caps: K8sCapabilitiesValue, row: TodayRow): ReactNode {
  if (row.vertical && caps.status === 'error') return 'Unknown';
  if (row.analysis === null || (row.vertical && caps.status === 'loading')) {
    return (
      <>
        <span aria-hidden="true">—</span>
        <span className="sr-only">None</span>
      </>
    );
  }
  return row.analysis;
}

function TodayTable({ caps, clusterName }: { caps: K8sCapabilitiesValue; clusterName: string }) {
  const headingId = useId();
  const engineOff = caps.status === 'ready' && !caps.verticalEngineEnabled;
  return (
    <section aria-labelledby={headingId} className="rounded-lg border border-[rgba(0,0,0,0.12)] bg-white pt-4">
      <h2 id={headingId} className="px-4 text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">
        What automation can do on this cluster today
      </h2>
      <p className="px-4 pt-1 text-[12px] text-[rgba(0,0,0,0.6)]">
        Analysis is what XamOps reads and recommends. Apply is whether XamOps may change the cluster.
      </p>
      <div className="mt-3 overflow-x-auto">
        <table aria-labelledby={headingId} className="w-full border-collapse text-left text-[13px]">
          <thead>
            <tr className="border-y border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] text-[12px] text-[rgba(0,0,0,0.6)]">
              <th scope="col" className="w-[22%] px-4 py-2 font-semibold">Capability</th>
              <th scope="col" className="w-[28%] px-4 py-2 font-semibold">Analysis</th>
              <th scope="col" className="px-4 py-2 font-semibold">Apply</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.08)]">
            {TODAY_ROWS.map((row) => (
              <tr key={row.id} data-row={row.id} className="align-top">
                <th scope="row" className="px-4 py-2.5 font-semibold text-[rgba(0,0,0,0.88)]">{row.label}</th>
                {row.vertical && engineOff ? (
                  <td colSpan={2} className="px-4 py-2.5 text-[rgba(0,0,0,0.6)]">{NOT_TURNED_ON_TEXT}</td>
                ) : (
                  <>
                    <td className="px-4 py-2.5 text-[rgba(0,0,0,0.6)]">{analysisCell(caps, row)}</td>
                    <td className="px-4 py-2.5"><ApplyCell capability={applyCapability(caps, row, clusterName)} /></td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export interface OverviewPanelProps {
  clusterName: string;
}

export function OverviewPanel({ clusterName }: OverviewPanelProps) {
  const caps = useK8sCapabilities();
  return (
    <div className="space-y-4">
      <AccessBanner capabilities={caps} cluster={clusterName} />
      <AccessBlock caps={caps} clusterName={clusterName} />
      <TodayTable caps={caps} clusterName={clusterName} />
    </div>
  );
}

export default OverviewPanel;
