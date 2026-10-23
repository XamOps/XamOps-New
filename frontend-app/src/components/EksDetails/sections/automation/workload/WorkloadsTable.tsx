/**
 * Workload autoscaler › Workloads, the at-a-glance table (UI v3 plan §2; design-delta §3.1): one row
 * per workload endpoint 1 analysed, with its status chip, one pod's CPU and memory request now and
 * suggested, what the suggestion frees or adds across its pods, a mini-bar of the request against
 * the suggestion, its lifecycle stage, and a 3-day sparkline of its busiest pod's CPU use against its
 * request (the usage feed's sparklines, one read for the table; "no pod running" when none ran).
 * Filters (status, namespace, text) are client-side; the row name opens the workload's detail.
 */
import { useId, useMemo, useState } from 'react';
import { formatMib, formatMillis, formatMonthlyUsd, confidenceLine, DASH } from '../../../../../api/verticalRightsizerAdapter';
import type { ApplyResult, ConfidenceView, WorkloadView } from '../../../../../types/verticalRightsizer';
import type { SparklinesView, SparklineView } from '../../../../../types/automationInsights';
import type { InsightReadState } from '../../../../../hooks/useInsightRead';
import { costUnavailableText } from '../verticalCopy';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { ConfidenceChip } from '../../../../CloudK8s/shared/status/ConfidenceChip';
import { StepIndicator } from '../../../../CloudK8s/shared/status/StepIndicator';
import { Delta } from '../../../../CloudK8s/shared/status/Delta';
import { MiniBar } from '../../../../CloudK8s/shared/charts/MiniBar';
import { Sparkline } from '../../../../CloudK8s/shared/charts/Sparkline';
import type { WorkloadStatusKey } from '../../../../CloudK8s/shared/status/statusTones';
import { insightCardState, millisText, sourceService } from '../insightCharts';
import {
  addedPart,
  formatMemory,
  formatVcpu,
  freedPart,
  podRequests,
  workloadChange,
  workloadLifecycle,
  workloadStatusChip,
  type RequestPair,
} from '../automationDerivations';
import { CARD, CONTROL, FAINT, INK, InPageLink, MUTED, RULE } from '../automationUi';

/** The status filter's groups, in the glossary's bucket order. */
const STATUS_FILTERS: ReadonlyArray<{ value: string; label: string; keys: readonly WorkloadStatusKey[] }> = [
  { value: 'over-provisioned', label: 'Over-provisioned', keys: ['over-provisioned'] },
  { value: 'needs-more', label: 'Needs more', keys: ['needs-more'] },
  { value: 'right-sized', label: 'Right-sized', keys: ['right-sized'] },
  { value: 'left-alone', label: 'Left alone', keys: ['left-alone-recently', 'left-alone-managed', 'applied-watching'] },
  { value: 'not-enough-data', label: 'Not enough data yet', keys: ['not-enough-data'] },
  { value: 'skipped', label: 'Skipped or off', keys: ['skipped', 'off', 'not-running'] },
];

export interface WorkloadsTableProps {
  workloads: readonly WorkloadView[];
  /** The detail link for a workload (`?workload=<ns>/<Kind>/<name>`). */
  detailHref: (workload: WorkloadView) => string;
  /** Workload ids with an apply in flight, and this session's replies (with when each arrived). */
  applyingIds: ReadonlySet<string>;
  applyResults: Record<string, ApplyResult>;
  replyAt: Record<string, number>;
  now: number;
  /**
   * The sparklines read (FEAT-004: each workload's busiest pod, CPU use against its request, 72 h at
   * 1 h). Without it, or when the backend doesn't serve it, the column isn't shown.
   */
  sparklines?: InsightReadState<SparklinesView>;
}

/** The sparkline column's state, from its one read for the whole table. */
type SparkColumn =
  | { show: false }
  | { show: true; loading: true; note: string }
  | { show: true; loading: false; byId: Map<string, SparklineView> | null; note: string; source: string };

function sparkColumn(read: InsightReadState<SparklinesView> | undefined): SparkColumn {
  if (!read) return { show: false };
  const card = insightCardState(read);
  if (!card.render) return { show: false };
  if (card.state.kind === 'loading') return { show: true, loading: true, note: 'Reading Prometheus…' };
  const view = card.view;
  const source = view
    ? `Source: usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}/sparklines?resource=cpu&window=72h), ${sourceService(view.source)}, the same pods the suggestion reads, step 1 hour.`
    : 'Source: usage feed (FEAT-004, GET /api/eks/automation/usage/{cluster}/sparklines).';
  if (card.state.kind === 'ok' && view) {
    return { show: true, loading: false, byId: new Map(view.workloads.map((w) => [w.id, w])), note: 'Busiest pod: CPU use and its request', source };
  }
  const reason = card.state.kind === 'unavailable' ? card.state.reason : card.state.kind === 'error' ? card.state.message : '';
  return { show: true, loading: false, byId: null, note: reason, source };
}

/** "CPU use of the busiest pod, last 3 days: 10m to 48m, request 100m." */
function sparkLabel(s: SparklineView): string {
  const used = s.used.map((p) => p.v).filter((v): v is number => v !== null);
  const request = [...s.request].reverse().find((p) => p.v !== null)?.v ?? null;
  if (used.length === 0) return 'No pod running in the last 3 days.';
  return `CPU use of the busiest pod, last 3 days: ${millisText(Math.min(...used))} to ${millisText(Math.max(...used))}${
    request !== null ? `, request ${millisText(request)}` : ''
  }.`;
}

function SparkCell({ column, workload }: { column: SparkColumn; workload: WorkloadView }) {
  if (!column.show) return null;
  if (column.loading) {
    return (
      <td className="px-4 py-2.5">
        <span className="skeleton inline-block h-4 w-[72px] rounded-[6px] dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" aria-hidden="true" />
        <span className="sr-only">Reading</span>
      </td>
    );
  }
  const s = column.byId?.get(workload.id);
  if (!s) {
    return (
      <td className="px-4 py-2.5" title={column.byId ? 'Prometheus returned no series for this workload.' : column.note}>
        <span className={`text-[12px] ${FAINT}`}>{DASH}</span>
      </td>
    );
  }
  const label = sparkLabel(s);
  return (
    <td className="px-4 py-2.5" title={label} data-sparkline-cell={workload.id}>
      <Sparkline values={s.used.map((p) => p.v)} reference={s.request.map((p) => p.v)} ariaLabel={label} />
    </td>
  );
}

/** "1000m → 25m", "80m · no change", "Not set". */
function pairText(pair: RequestPair, format: (v: number) => string): string {
  if (pair.now === null) return pair.suggested === null ? 'Not set' : `Not set → ${format(pair.suggested)}`;
  if (pair.suggested === null || pair.suggested === pair.now) return `${format(pair.now)} · no change`;
  return `${format(pair.now)} → ${format(pair.suggested)}`;
}

const millis = (v: number) => formatMillis({ kind: 'value', value: v });
const mib = (v: number) => formatMib({ kind: 'value', value: v });

/**
 * The confidence to show on the row: the primary recommended container's. "Primary" is the
 * most-impactful recommended container, approximated by the largest current CPU request, so a
 * sidecar's recommendation does not speak for the workload. Null when nothing is recommended.
 */
function primaryConfidence(workload: WorkloadView): ConfidenceView | null {
  const scored = workload.containers.filter((c) => c.confidence !== null);
  if (scored.length === 0) return null;
  const cpuOf = (c: (typeof scored)[number]) => (c.current.cpuRequestM.kind === 'value' ? c.current.cpuRequestM.value : -1);
  return scored.reduce((best, c) => (cpuOf(c) > cpuOf(best) ? c : best)).confidence;
}

const UNKNOWN_TITLE: Record<string, string> = {
  'replicas-unknown': "XamOps didn't get this workload's replica count, so the total isn't known.",
  'pods-differ': "This workload's pods run different sizes right now, so the total isn't known.",
};

function FreedCell({ workload }: { workload: WorkloadView }) {
  const change = workloadChange(workload);
  const { cpuM, memMi } = change.total;
  if (cpuM === null || memMi === null) {
    return (
      <span className={`text-[12px] ${FAINT}`} title={change.totalUnknown ? UNKNOWN_TITLE[change.totalUnknown] : "One side of a request is unset, so the change isn't known."}>
        {DASH}
      </span>
    );
  }
  const freedCpu = freedPart(cpuM) ?? 0;
  const freedMem = freedPart(memMi) ?? 0;
  const addCpu = addedPart(cpuM) ?? 0;
  const addMem = addedPart(memMi) ?? 0;
  if (freedCpu + freedMem + addCpu + addMem === 0) return <span className={`text-[12px] ${FAINT}`}>No change</span>;
  const join = (cpu: number, mem: number) => [cpu > 0 ? formatVcpu(cpu) : null, mem > 0 ? formatMemory(mem) : null].filter(Boolean).join(' · ');
  return (
    <span className="inline-flex flex-col gap-0.5">
      {freedCpu + freedMem > 0 && <Delta direction="saved" amount={join(freedCpu, freedMem)} suffix="freed" />}
      {addCpu + addMem > 0 && <Delta direction="extra" amount={`+${join(addCpu, addMem)}`} suffix="more" />}
    </span>
  );
}

/**
 * Monthly cost (FEAT-002): the workload's real $/mo the backend priced (`cost.monthlyCost`), in
 * plain ink — a cost is not a saving, so no money tone. A dash + `cost.reason` tooltip when the
 * node could not be priced (never a fabricated $0), and the muted "assumed on-demand" qualifier is
 * folded into the tooltip when the capacity type was inferred. This is the figure that renders even
 * when there is no recommendation yet, so a cost product's list always shows cost.
 */
function CostCell({ workload }: { workload: WorkloadView }) {
  const cost = workload.cost;
  const assumed = cost?.capacityTypeInferred ? ' Assumed on-demand (no capacity-type label on the node).' : '';
  if (!cost || cost.monthlyCost === null) {
    const title = `${costUnavailableText(cost?.reason)}${assumed}`.trim();
    return (
      <span className={`text-[12px] ${FAINT}`} data-workload-cost="" title={title}>
        {DASH}
      </span>
    );
  }
  const title = `${cost.note ?? ''}${assumed}`.trim() || undefined;
  return (
    <span className={`tabular-nums ${INK}`} data-workload-cost="" title={title}>
      {formatMonthlyUsd(cost.monthlyCost)}
    </span>
  );
}

/**
 * Est. savings / mo (FEAT-002): the real $/mo saving via Delta (money tone + arrow inside Delta, so
 * this file writes no money class), or a plain dash + `cost.reason` tooltip when unavailable. An
 * inferred capacity type adds the muted "assumed on-demand" qualifier to the tooltip. A true 0 is a
 * real "$0/mo" (the recommendation frees nothing), not a dash.
 */
function SavingsCell({ workload }: { workload: WorkloadView }) {
  const cost = workload.cost;
  const assumed = cost?.capacityTypeInferred ? ' Assumed on-demand (no capacity-type label on the node).' : '';
  if (!cost || cost.monthlySavings === null) {
    const title = `${costUnavailableText(cost?.reason)}${assumed}`.trim();
    return (
      <span className={`text-[12px] ${FAINT}`} title={title}>
        {DASH}
      </span>
    );
  }
  const title = `${cost.note ?? ''}${assumed}`.trim() || undefined;
  return (
    <span title={title}>
      <Delta direction="saved" amount={formatMonthlyUsd(cost.monthlySavings)} />
    </span>
  );
}

export function WorkloadsTable({ workloads, detailHref, applyingIds, applyResults, replyAt, now, sparklines }: WorkloadsTableProps) {
  const spark = sparkColumn(sparklines);
  const [status, setStatus] = useState('all');
  const [namespace, setNamespace] = useState('all');
  const [query, setQuery] = useState('');
  const ids = { status: useId(), namespace: useId(), query: useId(), caption: useId() };

  const namespaces = useMemo(() => [...new Set(workloads.map((w) => w.ref.namespace))].sort(), [workloads]);
  const rows = useMemo(() => {
    const filter = STATUS_FILTERS.find((f) => f.value === status);
    const q = query.trim().toLowerCase();
    return workloads.filter((w) => {
      if (filter && !filter.keys.includes(workloadStatusChip(w).key)) return false;
      if (namespace !== 'all' && w.ref.namespace !== namespace) return false;
      if (q && !`${w.ref.name} ${w.ref.namespace} ${w.ref.kind}`.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [workloads, status, namespace, query]);

  return (
    // data-k8s-kit: every colour here has its dark variant, so the legacy dark CSS leaves the table alone.
    <section className={CARD} aria-labelledby={ids.caption} data-k8s-kit="">
      <div className="flex flex-wrap items-end justify-between gap-3 px-4 pt-3">
        <div>
          <h3 id={ids.caption} className={`text-[13px] font-semibold ${INK}`}>Workloads at a glance</h3>
          <p className={`mt-0.5 text-[12px] ${MUTED}`}>
            Requests are per pod. Freed and more are across every pod. Source: Workload autoscaler (endpoint 1).
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <label htmlFor={ids.status} className={`flex flex-col gap-0.5 text-[11px] font-semibold ${MUTED}`}>
            Status
            <select id={ids.status} value={status} onChange={(e) => setStatus(e.target.value)} className={CONTROL}>
              <option value="all">All statuses</option>
              {STATUS_FILTERS.map((f) => (
                <option key={f.value} value={f.value}>{f.label}</option>
              ))}
            </select>
          </label>
          <label htmlFor={ids.namespace} className={`flex flex-col gap-0.5 text-[11px] font-semibold ${MUTED}`}>
            Namespace
            <select id={ids.namespace} value={namespace} onChange={(e) => setNamespace(e.target.value)} className={CONTROL}>
              <option value="all">All namespaces</option>
              {namespaces.map((ns) => (
                <option key={ns} value={ns}>{ns}</option>
              ))}
            </select>
          </label>
          <label htmlFor={ids.query} className={`flex flex-col gap-0.5 text-[11px] font-semibold ${MUTED}`}>
            Search
            <input
              id={ids.query}
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Workload or namespace"
              className={CONTROL}
            />
          </label>
        </div>
      </div>
      <p className={`px-4 pt-2 text-[11px] ${FAINT}`} aria-live="polite">
        Showing {rows.length} of {workloads.length} {workloads.length === 1 ? 'workload' : 'workloads'}
      </p>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full border-collapse text-left text-[12px]" aria-labelledby={ids.caption}>
          <thead>
            <tr className={`border-y ${RULE} bg-[rgba(0,0,0,0.02)] text-[11px] dark:bg-[rgba(255,255,255,0.03)] ${MUTED}`}>
              <th scope="col" className="px-4 py-2 font-semibold">Workload</th>
              <th scope="col" className="px-4 py-2 font-semibold">Status</th>
              <th scope="col" className="px-4 py-2 font-semibold" title="m = millicores. 1000m = 1 vCPU.">CPU request (now → suggested)</th>
              <th scope="col" className="px-4 py-2 font-semibold">Memory request (now → suggested)</th>
              <th scope="col" className="px-4 py-2 font-semibold">Freed or more</th>
              <th scope="col" className="px-4 py-2 text-right font-semibold" title="What this workload reserves per month, at the node's live AWS price.">Monthly cost</th>
              <th scope="col" className="px-4 py-2 text-right font-semibold" title="Estimated monthly saving from the suggestion, at the node's live AWS price.">Est. savings / mo</th>
              <th scope="col" className="px-4 py-2 font-semibold" title="How much to trust the suggestion: window coverage and how steady CPU use was.">Confidence</th>
              <th scope="col" className="px-4 py-2 font-semibold">CPU request vs suggestion</th>
              <th scope="col" className="px-4 py-2 font-semibold">Stage</th>
              {spark.show && (
                <th scope="col" className="px-4 py-2 font-semibold">
                  Last 3 days
                  <span className={`block max-w-[180px] font-normal ${FAINT}`}>{spark.note}</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]">
            {rows.map((w) => {
              const chip = workloadStatusChip(w);
              const cpu = podRequests(w, 'cpu');
              const mem = podRequests(w, 'memory');
              const life = workloadLifecycle(w, { applying: applyingIds.has(w.id), reply: applyResults[w.id], replyAt: replyAt[w.id], now });
              const confidence = primaryConfidence(w);
              const barMax = Math.max(cpu.now ?? 0, cpu.suggested ?? 0);
              return (
                <tr key={w.id} data-workload-row={w.id} className="align-top">
                  <th scope="row" className="px-4 py-2.5 font-normal">
                    <InPageLink to={detailHref(w)} aria-label={`Open ${w.ref.name}`}>{w.ref.name}</InPageLink>
                    <span className={`block text-[11px] ${FAINT}`}>{w.ref.kind} · {w.ref.namespace}</span>
                  </th>
                  <td className="px-4 py-2.5">
                    <span title={chip.detail || undefined}>
                      <StatusChip tone={chip.tone} icon={chip.icon} label={chip.label} />
                    </span>
                  </td>
                  <td className={`whitespace-nowrap px-4 py-2.5 tabular-nums ${INK}`}>{pairText(cpu, millis)}</td>
                  <td className={`whitespace-nowrap px-4 py-2.5 tabular-nums ${INK}`}>{pairText(mem, mib)}</td>
                  <td className="whitespace-nowrap px-4 py-2.5"><FreedCell workload={w} /></td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-right"><CostCell workload={w} /></td>
                  <td className="whitespace-nowrap px-4 py-2.5 text-right"><SavingsCell workload={w} /></td>
                  <td className="whitespace-nowrap px-4 py-2.5">
                    {confidence ? (
                      <span title={confidenceLine(confidence)}>
                        <ConfidenceChip confidence={confidence} compact />
                      </span>
                    ) : (
                      <span className={`text-[12px] ${FAINT}`}>{DASH}</span>
                    )}
                  </td>
                  <td className="px-4 py-2.5">
                    <MiniBar
                      value={cpu.now}
                      max={barMax}
                      kind="requested"
                      tick={cpu.suggested !== null && cpu.now !== null ? { value: cpu.suggested, kind: 'suggested' } : null}
                    />
                  </td>
                  <td className="px-4 py-2.5">
                    <StepIndicator states={life.states} label={life.label} />
                  </td>
                  <SparkCell column={spark} workload={w} />
                </tr>
              );
            })}
          </tbody>
        </table>
        {rows.length === 0 && (
          <p className={`px-4 py-6 text-center text-[12px] ${MUTED}`}>No workload matches these filters.</p>
        )}
      </div>
      {spark.show && !spark.loading && (
        <p className={`px-4 py-2.5 text-[11px] ${FAINT}`} data-sparkline-source="">
          {spark.source} In each sparkline the thicker line is the busiest pod&apos;s use and the thinner one its request, on
          one scale from 0; &quot;no pod running&quot; means nothing ran to measure. A gap is an hour Prometheus didn&apos;t measure.
        </p>
      )}
    </section>
  );
}

export default WorkloadsTable;
