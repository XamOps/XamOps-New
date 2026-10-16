/**
 * Automation › Workload autoscaler › Workloads › one workload (`?workload=<ns>/<Kind>/<name>`; UI v3
 * plan §2 "Workload detail"; design-delta §4.3-§4.5).
 *
 * What it shows, and where each part comes from:
 *  - the Now sentence and the lifecycle, from endpoint 1 and this session's apply reply;
 *  - Apply progress, rebuilt from the reply's `result.pods[]` (live while the POST runs), with the
 *    put-back rows from `pods[].revert`;
 *  - What changed, per pod, from the same reply: same pod or replaced, restarts before and after,
 *    ready, and what each container runs now;
 *  - request now, suggested and limit per container (endpoint 1), and the latest recorded change;
 *  - the time series and cost charts, each in its honest empty state with the source it needs;
 *  - the workload's own card (Apply, test run, mode switch, why and how), through the page's single
 *    read, so nothing is fetched twice and every gate stays the tab's.
 */
import { useId } from 'react';
import { Icon } from '@iconify/react';
import EksAutomationTab from '../../../tabs/EksAutomationTab';
import type { UseVerticalRightsizerState } from '../../../../../hooks/useVerticalRightsizer';
import {
  DASH,
  formatMib,
  formatMillis,
  workloadWindowHours,
  windowWords,
  writeGateText,
} from '../../../../../api/verticalRightsizerAdapter';
import type {
  ApplyOutcomeWire,
  ApplyPodProofWire,
  ApplyResult,
  MetricValue,
  VerticalResourcesWire,
  WorkloadView,
} from '../../../../../types/verticalRightsizer';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { nowSentence, type WorkloadDetailInputs } from '../../../../CloudK8s/shared/status/nowSentence';
import { StepIndicator } from '../../../../CloudK8s/shared/status/StepIndicator';
import { ApplyStepper } from '../../../../CloudK8s/shared/status/ApplyStepper';
import { restartedContainers, skippedPodsOf } from '../../../../CloudK8s/shared/status/applyProof';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { Bars } from '../../../../CloudK8s/shared/charts/Bars';
import { ChartCard } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend } from '../../../../CloudK8s/shared/charts/SeriesLegend';
import {
  COOLDOWN_MS,
  actionResult,
  formatCapacity,
  freedPart,
  addedPart,
  instantMs,
  istShort,
  isRecordedPodChange,
  liveOutcome,
  outcomeStartsWait,
  podRequests,
  workloadChange,
  workloadLifecycle,
  workloadStatusChip,
} from '../automationDerivations';
import { CARD, Card, FAINT, INK, InPageLink, LINK, MUTED, RULE } from '../automationUi';

export interface WorkloadDetailProps {
  clusterName: string;
  accountId: string;
  region: string;
  workload: WorkloadView;
  /** The Workloads page's single read and apply state. */
  controller: UseVerticalRightsizerState;
  /** When this session's reply for the workload arrived (epoch ms). */
  replyAt?: number;
  /** The Workloads list, without the `workload` parameter. */
  listHref: string;
  now: number;
}

const millis = (v: number | null | undefined): string => (v === null || v === undefined ? DASH : formatMillis({ kind: 'value', value: v }));
const mib = (v: number | null | undefined): string => (v === null || v === undefined ? DASH : formatMib({ kind: 'value', value: v }));
const valueOf = (m: MetricValue | undefined): number | null => (m && m.kind === 'value' ? m.value : null);

/** The Now sentence's inputs: this session's reply first, then endpoint 1 (glossary §3). */
function sentenceInputs(w: WorkloadView, reply: ApplyResult | undefined, replyAt: number | undefined, now: number): WorkloadDetailInputs {
  const name = w.ref.name;
  const live = liveOutcome(reply);
  const nextOf = (start: number | null) => (start !== null && start + COOLDOWN_MS > now ? istShort(start + COOLDOWN_MS) : undefined);
  if (live && live.terminal === 'APPLIED') {
    const pod = live.pods?.[0];
    const c = pod?.containers?.[0];
    const b = c?.observedBefore;
    const a = c?.observedAfter;
    let change: { resource: string; from: string; to: string } | undefined;
    if (b && a && b.cpuRequestM !== a.cpuRequestM) change = { resource: 'CPU request', from: millis(b.cpuRequestM), to: millis(a.cpuRequestM) };
    else if (b && a && b.memRequestMi !== a.memRequestMi) change = { resource: 'memory request', from: mib(b.memRequestMi), to: mib(a.memRequestMi) };
    const limit = change?.resource === 'CPU request' && b && a && b.cpuLimitM !== a.cpuLimitM ? { from: millis(b.cpuLimitM), to: millis(a.cpuLimitM) } : undefined;
    return {
      workload: name,
      applied: {
        time: replyAt !== undefined ? istShort(replyAt) : undefined,
        change,
        limit,
        pods: live.pods?.length || undefined,
        skipped: skippedPodsOf(live).length || undefined,
        restartFree: live.restartFree,
        restarted: restartedContainers(live).length > 0,
        next: replyAt !== undefined && outcomeStartsWait(live) ? nextOf(replyAt) : undefined,
      },
    };
  }
  if (live && live.terminal === 'DEFERRED') {
    const rec = w.containers.find((c) => c.recommended !== null)?.recommended;
    return {
      workload: name,
      deferred: {
        suggests: rec && rec.cpuRequestM.kind === 'value' ? formatMillis(rec.cpuRequestM) : undefined,
        limit: rec && rec.cpuLimitM.kind === 'value' ? formatMillis(rec.cpuLimitM) : undefined,
        putBack: live.reverted ?? null,
      },
    };
  }
  const status = workloadStatusChip(w);
  const inputs: WorkloadDetailInputs = { workload: name };
  if (status.key === 'not-enough-data') {
    const hours = workloadWindowHours(w);
    const coverage = w.provenance?.sampleCoverage;
    inputs.measuring = hours !== null && coverage !== null && coverage !== undefined
      ? { covered: windowWords(Math.round(coverage * hours)), window: windowWords(hours) }
      : {};
  } else if (status.key === 'over-provisioned' || status.key === 'needs-more') {
    const cpu = podRequests(w, 'cpu');
    const mem = podRequests(w, 'memory');
    const total = workloadChange(w).total;
    const over = status.key === 'over-provisioned';
    const amount = total.cpuM !== null && total.memMi !== null
      ? formatCapacity((over ? freedPart(total.cpuM) : addedPart(total.cpuM)) ?? 0, (over ? freedPart(total.memMi) : addedPart(total.memMi)) ?? 0)
      : undefined;
    const pair = cpu.now !== null && cpu.suggested !== null && cpu.now !== cpu.suggested
      ? { from: millis(cpu.now), to: millis(cpu.suggested) }
      : { from: mib(mem.now), to: mib(mem.suggested) };
    inputs.ready = { ...pair, direction: over ? 'freeing' : 'adding', amount, pods: w.replicas ?? undefined };
  } else {
    inputs.status = { label: status.label, detail: status.detail || undefined };
  }
  const a = w.lastAction;
  const result = a ? actionResult(a.outcome, a.dryRun) : null;
  const at = instantMs(a?.at);
  if (a && result && at !== null) {
    inputs.lastChange = {
      time: istShort(at),
      result: result.label.charAt(0).toLowerCase() + result.label.slice(1),
      next: isRecordedPodChange(a) ? nextOf(at) : undefined,
    };
  }
  return inputs;
}

/** "100m → 25m", or the one value with "no change". */
function changeText(before: number | null | undefined, after: number | null | undefined, format: (v: number | null | undefined) => string): { text: string; same: boolean } {
  if (before === undefined && after === undefined) return { text: DASH, same: false };
  if ((before ?? null) === (after ?? null)) return { text: format(after), same: true };
  return { text: `${format(before)} → ${format(after)}`, same: false };
}

function Cell({ before, after, format }: { before?: number | null; after?: number | null; format: (v: number | null | undefined) => string }) {
  const c = changeText(before, after, format);
  return (
    <td className={`whitespace-nowrap px-3 py-2 tabular-nums ${INK}`}>
      {c.text}
      {c.same && <span className={`block text-[11px] ${FAINT}`}>no change</span>}
    </td>
  );
}

const field = (r: VerticalResourcesWire | null | undefined, key: keyof VerticalResourcesWire) => (r ? r[key] : undefined);

/** "What changed" (glossary §7): per pod, from the apply reply's proof. */
function WhatChanged({ outcome, replyAt }: { outcome: ApplyOutcomeWire; replyAt?: number }) {
  const titleId = useId();
  const pods = outcome.pods ?? [];
  if (pods.length === 0) return null;
  const rows = pods.flatMap((pod: ApplyPodProofWire, i: number) =>
    (pod.containers ?? []).map((c) => ({ pod, i, c })),
  );
  const names = new Set(rows.map((r) => r.c.name));
  let freedCpu: number | null = 0;
  for (const r of rows) {
    const b = r.c.observedBefore?.cpuRequestM;
    const a = r.c.observedAfter?.cpuRequestM;
    if (b === null || b === undefined || a === null || a === undefined) {
      freedCpu = null;
      break;
    }
    freedCpu += b - a;
  }
  const next = outcome.terminal === 'APPLIED' && replyAt !== undefined ? istShort(replyAt + COOLDOWN_MS) : null;
  // Pods the reply left out aren't in the table, so "each pod" names only the ones it resized.
  const skipped = skippedPodsOf(outcome).length;
  const each = skipped > 0 ? 'in each pod XamOps resized' : 'in each pod';
  const subline = names.size === 1 && freedCpu !== null && freedCpu !== 0
    ? `Container ${[...names][0]}, ${each}. ${millis(Math.abs(freedCpu))} CPU ${freedCpu > 0 ? 'freed' : 'added'} across ${pods.length} ${pods.length === 1 ? 'pod' : 'pods'}.`
    : `${pods.length} ${pods.length === 1 ? 'pod' : 'pods'}, read from each pod's status after the change.`;
  const leftOut = skipped > 0 ? ` ${skipped} more ${skipped === 1 ? "pod wasn't" : "pods weren't"} touched: ${skipped === 1 ? 'it runs' : 'they run'} at the workload's own size.` : '';
  return (
    <section className={`${CARD} px-4 py-3`} aria-labelledby={titleId} data-what-changed="">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>What changed</h3>
        {next && <StatusChip tone="info" icon="mdi:timer-sand" label={`Next change allowed at ${next}`} />}
      </div>
      <p className={`mt-0.5 text-[12px] ${MUTED}`}>{subline}{leftOut}</p>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full border-collapse text-left text-[12px]" aria-labelledby={titleId}>
          <thead>
            <tr className={`border-y ${RULE} text-[11px] ${MUTED}`}>
              <th scope="col" className="px-3 py-1.5 font-semibold">Pod</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">Container</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">Same pod</th>
              <th scope="col" className="px-3 py-1.5 font-semibold" title="m = millicores. 1000m = 1 vCPU.">CPU request</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">CPU limit</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">Memory request</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">Memory limit</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">Restarts</th>
              <th scope="col" className="px-3 py-1.5 font-semibold">Now</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]">
            {rows.map(({ pod, i, c }) => {
              const same = pod.uidBefore !== null && pod.uidAfter !== null ? pod.uidBefore === pod.uidAfter : pod.samePod;
              return (
                <tr key={`${pod.podName}/${c.name}`} className="align-top">
                  <th scope="row" className={`px-3 py-2 font-semibold ${INK}`}>
                    {pod.podName}
                    <span className={`block text-[11px] font-normal ${FAINT}`}>{i === 0 ? 'test pod, first' : 'with the next pod'}</span>
                  </th>
                  <td className={`px-3 py-2 ${INK}`}>{c.name}</td>
                  <td className={`px-3 py-2 ${INK}`} title={`uid ${pod.uidBefore ?? DASH} → ${pod.uidAfter ?? DASH}`}>
                    {same === true ? 'Same pod' : same === false ? 'Replaced' : DASH}
                  </td>
                  <Cell before={field(c.observedBefore, 'cpuRequestM')} after={field(c.observedAfter, 'cpuRequestM')} format={millis} />
                  <Cell before={field(c.observedBefore, 'cpuLimitM')} after={field(c.observedAfter, 'cpuLimitM')} format={millis} />
                  <Cell before={field(c.observedBefore, 'memRequestMi')} after={field(c.observedAfter, 'memRequestMi')} format={mib} />
                  <Cell before={field(c.observedBefore, 'memLimitMi')} after={field(c.observedAfter, 'memLimitMi')} format={mib} />
                  <td className={`whitespace-nowrap px-3 py-2 tabular-nums ${INK}`}>
                    {c.restartCountBefore ?? DASH} → {c.restartCountAfter ?? DASH}
                  </td>
                  <td className="px-3 py-2">
                    {pod.ready === true ? (
                      <StatusChip tone="good" icon="mdi:check-circle" label="Ready" />
                    ) : pod.ready === false ? (
                      <StatusChip tone="bad" icon="mdi:alert-octagon-outline" label="Not ready" />
                    ) : (
                      <span className={FAINT}>{DASH}</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className={`mt-2 text-[11px] ${FAINT}`}>
        Source: the apply reply's proof, read from each pod&apos;s status (uid, restart count, ready, resources in place).
      </p>
    </section>
  );
}

/** The stepper's outcome: null while the POST runs, the reply's result, or a refusal before any patch. */
function stepperOutcome(applying: boolean, reply: ApplyResult | undefined): ApplyOutcomeWire | null | undefined {
  if (applying) return null;
  if (!reply) return undefined;
  if (reply.kind === 'submitted') return reply.result ?? undefined;
  if (reply.kind === 'refused') {
    const code = reply.code.kind === 'known' ? reply.code.code : '';
    const gate = writeGateText(code, reply.detail);
    return {
      method: null,
      terminal: 'REFUSED',
      detail: gate ? `${gate.reason}${gate.nextStep ? ` ${gate.nextStep}` : ''}` : reply.message,
      restartFree: null,
      containers: null,
      pods: null,
    };
  }
  return undefined;
}

const SIZE_BARS = [
  { dataKey: 'now', name: 'Request now', kind: 'requested' as const },
  { dataKey: 'suggested', name: 'Suggested', kind: 'suggested' as const },
  { dataKey: 'limit', name: 'Limit', kind: 'limit' as const },
];

function SizesCard({ workload }: { workload: WorkloadView }) {
  const cpu = workload.containers.map((c) => ({
    container: c.name,
    now: valueOf(c.current.cpuRequestM),
    suggested: c.recommended ? valueOf(c.recommended.cpuRequestM) : null,
    limit: valueOf(c.current.cpuLimitM),
  }));
  const mem = workload.containers.map((c) => ({
    container: c.name,
    now: valueOf(c.current.memRequestMi),
    suggested: c.recommended ? valueOf(c.recommended.memRequestMi) : null,
    limit: valueOf(c.current.memLimitMi),
  }));
  const text = (v: number | null, format: (n: number) => string, unset: string) => (v === null ? unset : format(v));
  return (
    <ChartCard
      title="Request now, suggested and limit, per container"
      subline="One pod's size, per container. No suggested bar means XamOps suggests no change for it."
      source="Workload autoscaler (endpoint 1, GET /api/eks/automation/vertical/{cluster}): containers[].current and recommended."
      state={workload.containers.length === 0 ? { kind: 'unavailable', reason: 'XamOps has no container sizes for this workload yet.' } : { kind: 'ok' }}
      table={
        workload.containers.length > 0 ? (
          <ChartDataTable
            caption="Request now, suggested and limit, per container"
            rowHeader="Container"
            columns={[
              { key: 'cpuNow', header: 'CPU request now' },
              { key: 'cpuSuggested', header: 'CPU suggested' },
              { key: 'cpuLimit', header: 'CPU limit' },
              { key: 'memNow', header: 'Memory request now' },
              { key: 'memSuggested', header: 'Memory suggested' },
              { key: 'memLimit', header: 'Memory limit' },
            ]}
            rows={workload.containers.map((c, i) => ({
              key: c.name,
              label: c.name,
              values: {
                cpuNow: text(cpu[i].now, (v) => millis(v), 'Not set'),
                cpuSuggested: c.recommended ? text(cpu[i].suggested, (v) => millis(v), 'Not set') : 'No change',
                cpuLimit: text(cpu[i].limit, (v) => millis(v), 'Not set'),
                memNow: text(mem[i].now, (v) => mib(v), 'Not set'),
                memSuggested: c.recommended ? text(mem[i].suggested, (v) => mib(v), 'Not set') : 'No change',
                memLimit: text(mem[i].limit, (v) => mib(v), 'Not set'),
              },
            }))}
          />
        ) : undefined
      }
    >
      <SeriesLegend items={SIZE_BARS.map((b) => ({ label: b.name, kind: b.kind }))} className="mb-2" />
      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <p className={`mb-1 text-[11px] font-semibold ${MUTED}`}>CPU, millicores</p>
          <Bars mode="grouped" data={cpu} bars={SIZE_BARS} xKey="container" height={200} ariaLabel={`CPU per container for ${workload.ref.name}`} />
        </div>
        <div>
          <p className={`mb-1 text-[11px] font-semibold ${MUTED}`}>Memory, MiB</p>
          <Bars mode="grouped" data={mem} bars={SIZE_BARS} xKey="container" height={200} ariaLabel={`Memory per container for ${workload.ref.name}`} />
        </div>
      </div>
    </ChartCard>
  );
}

function RecentChange({ workload }: { workload: WorkloadView }) {
  const a = workload.lastAction;
  const result = a ? actionResult(a.outcome, a.dryRun) : null;
  const at = instantMs(a?.at);
  return (
    <Card title="Recent change">
      {a && result ? (
        <div className="flex flex-wrap items-center gap-2 text-[12px]">
          <span className={`tabular-nums ${INK}`} title={a.at ?? undefined}>{at !== null ? istShort(at) : DASH}</span>
          <StatusChip tone={result.tone} icon={result.icon} label={result.label} />
          <span className={MUTED}>{a.dryRun === true ? 'Test run' : 'Live'}</span>
        </div>
      ) : (
        <p className={`text-[12px] ${MUTED}`}>XamOps hasn&apos;t recorded a change to this workload.</p>
      )}
      <p className={`mt-2 text-[11px] ${FAINT}`}>
        Source: Workload autoscaler (endpoint 1): lastAction, the newest change XamOps recorded for this workload. Test runs
        and changes that were put back aren&apos;t recorded yet.
      </p>
    </Card>
  );
}

export function WorkloadDetail({ clusterName, accountId, region, workload, controller, replyAt, listHref, now }: WorkloadDetailProps) {
  const reply = controller.applyResults[workload.id];
  const applying = controller.applyingIds.has(workload.id);
  const life = workloadLifecycle(workload, { applying, reply, replyAt, now });
  const sentence = nowSentence('workload-detail', sentenceInputs(workload, reply, replyAt, now));
  const outcome = stepperOutcome(applying, reply);
  const live = liveOutcome(reply);
  const changeHeading = useId();

  return (
    <div className="space-y-4" data-workload-detail={workload.id}>
      <InPageLink to={listHref} className={`inline-flex items-center gap-1 text-[12px] ${LINK}`}>
        <Icon icon="mdi:arrow-left" width={14} aria-hidden="true" />
        All workloads
      </InPageLink>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <FeatureMark feature="workload" title={workload.ref.name} />
        <p className={`text-[12px] ${MUTED}`}>
          {workload.ref.kind} · Namespace {workload.ref.namespace} · {workload.replicas ?? DASH} {workload.replicas === 1 ? 'replica' : 'replicas'}
        </p>
      </div>

      <NowBanner sentence={sentence} />

      <Card title="Where it is now">
        <StepIndicator variant="full" states={life.states} label={life.label} details={life.details} />
        <p className={`mt-2 text-[11px] ${FAINT}`}>
          Source: Workload autoscaler (endpoint 1) and this session&apos;s apply reply.
        </p>
      </Card>

      {outcome !== undefined && <ApplyStepper outcome={outcome} live={applying} workloadKind={workload.ref.kind} />}
      {!applying && reply?.kind === 'submitted' && reply.result === null && (
        <Card title="Apply progress">
          <p className={`text-[12px] ${MUTED}`}>Submitted. No result reported yet.</p>
        </Card>
      )}
      {live && <WhatChanged outcome={live} replyAt={replyAt} />}

      <SizesCard workload={workload} />
      <RecentChange workload={workload} />

      <div className="grid gap-4 md:grid-cols-2">
        <ChartCard
          title="CPU and memory over the window"
          source="Usage feed (planned): this workload's pods from Prometheus in your cluster, the same pods the suggestion reads; request, limit and use."
          state={{ kind: 'history-pending', from: "It will come from Prometheus in your cluster: this workload's use, request and limit over the look-back window." }}
        />
        <ChartCard
          title="Impact around the change"
          source="Usage feed (planned): the test pod's request, limit and use from 3 hours before the change."
          state={{ kind: 'history-pending', from: "It will come from Prometheus in your cluster: the test pod's CPU around the time of the change." }}
        />
        <ChartCard
          title="Cost per day"
          source="Prices (planned): what the workload reserves per day, at list price."
          state={{ kind: 'unavailable', reason: "Needs CPU and memory prices, which XamOps doesn't read yet." }}
        />
        <ChartCard
          title="Did the new size take effect?"
          source="cgroup series (planned): the limit declared in the pod spec against the limit in effect on the node."
          state={{ kind: 'unavailable', reason: "Needs the pod's cgroup series (what the node actually gives the container), which XamOps doesn't read yet." }}
        />
      </div>

      <section aria-labelledby={changeHeading} className="space-y-2">
        <h3 id={changeHeading} className={`text-[14px] font-semibold ${INK}`}>Change this workload</h3>
        <EksAutomationTab
          clusterName={clusterName}
          accountId={accountId}
          region={region}
          controller={controller}
          onlyWorkloadId={workload.id}
        />
      </section>
    </div>
  );
}

export default WorkloadDetail;
