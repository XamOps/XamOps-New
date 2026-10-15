/**
 * Automation › Workload autoscaler › Workloads (UI v3 plan §2 "Workloads"; automation-v2 design
 * §3.2, §4.2).
 *
 * The page reads endpoint 1 once, through `useVerticalRightsizer`, and hands that one state to the
 * existing Workload rightsizing cards (`EksAutomationTab` with `controller`), so the Apply, test run
 * and mode switch keep every gate, the 403-safe apply path and the mode switch exactly as they are,
 * and nothing is fetched twice.
 *
 * Above the cards: the Now sentence, How it works (collapsed), the KPI row, the top savings by
 * workload and the at-a-glance table. `?workload=<ns>/<Kind>/<name>` opens one workload's detail on
 * the same page (the page stays mounted, so this session's apply replies stay with it). A malformed
 * value is removed with `replace` and a notice; a well-formed one that isn't in the cluster shows the
 * list with a notice.
 */
import { useEffect, useId, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import EksAutomationTab from '../../../tabs/EksAutomationTab';
import useVerticalRightsizer from '../../../../../hooks/useVerticalRightsizer';
import { formatInstant } from '../../../../../api/verticalRightsizerAdapter';
import { inPageState } from '../../../../../utils/inPageNavigation';
import type { ApplyResult, WorkloadView } from '../../../../../types/verticalRightsizer';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { HowItWorks } from '../../../../CloudK8s/shared/status/HowItWorks';
import { KpiCard } from '../../../../CloudK8s/shared/status/KpiCard';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { Bars } from '../../../../CloudK8s/shared/charts/Bars';
import { ChartCard } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend } from '../../../../CloudK8s/shared/charts/SeriesLegend';
import {
  automationKpis,
  formatCapacity,
  formatMemory,
  formatVcpu,
  freedPart,
  howItWorksChips,
  howSummary,
  instantMs,
  isLiveApplied,
  istShort,
  parseWorkloadParam,
  withParam,
  workloadChange,
  workloadStatusChip,
  workloadParamOf,
  type AutomationKpis,
} from '../automationDerivations';
import { INK, MUTED, Notice } from '../automationUi';
import WorkloadsTable from './WorkloadsTable';
import WorkloadDetail from './WorkloadDetail';

/** Exact copy, automation-v2 design §3.2. */
export const MALFORMED_WORKLOAD_NOTICE = "That link pointed to something XamOps can't read.";
export const missingWorkloadNotice = (ref: string): string => `${ref} isn't in this cluster any more.`;

export interface WorkloadsPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** This page's own link (`route.hrefFor('automation', 'workload-autoscaler')`). */
  workloadsHref: string;
  /** Workload autoscaler › Policies. */
  policiesHref?: string;
}

/** Records when each apply reply first arrived, so its wait can be timed. */
function useReplyTimes(applyResults: Record<string, ApplyResult>): Record<string, number> {
  const [times, setTimes] = useState<Record<string, number>>({});
  const seen = useRef<Record<string, ApplyResult>>({});
  useEffect(() => {
    const now = Date.now();
    const next: Record<string, number> = {};
    for (const [id, reply] of Object.entries(applyResults)) {
      if (seen.current[id] !== reply) next[id] = now;
    }
    seen.current = applyResults;
    if (Object.keys(next).length > 0) setTimes((prev) => ({ ...prev, ...next }));
  }, [applyResults]);
  return times;
}

/** "a, b, c and 2 more", for the Now sentence. */
function readyNames(names: string[]): string[] {
  return names.length <= 3 ? names : [...names.slice(0, 3), `and ${names.length - 3} more`];
}

function KpiRow({ k, workloads }: { k: AutomationKpis; workloads: readonly WorkloadView[] }) {
  const buckets = new Map<string, number>();
  for (const w of workloads) {
    const label = workloadStatusChip(w).label.replace(/^(Needs more).*$/, '$1').replace(/^(Left alone).*$/, '$1').replace(/^(Skipped).*$/, '$1');
    buckets.set(label, (buckets.get(label) ?? 0) + 1);
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <KpiCard tone="info" icon="mdi:view-grid-outline" label="Workloads analysed" value={k.analysed}>
        <p>{[...buckets.entries()].map(([label, n]) => `${label} ${n}`).join(' · ') || 'No workloads'}</p>
      </KpiCard>
      <KpiCard
        tone="warn"
        icon="mdi:arrow-collapse-down"
        label="Can save now"
        value={k.overProvisioned > 0 && k.canSave.counted === 0 ? '—' : formatVcpu(k.canSave.cpuM)}
      >
        <p>
          {k.overProvisioned > 0 && k.canSave.counted === 0
            ? "The replica counts aren't known, so the amount isn't either"
            : `${formatMemory(k.canSave.memMi)} memory, freed across every pod`}
        </p>
        <p>{k.overProvisioned} over-provisioned, waiting for you</p>
      </KpiCard>
      <KpiCard tone="warn" icon="mdi:speedometer" label="Needs more" value={k.needsMore}>
        <p>{k.needsMore > 0 ? `+${formatCapacity(k.needsMoreAdds.cpuM, k.needsMoreAdds.memMi)} if applied` : 'No workload needs more'}</p>
      </KpiCard>
      <KpiCard tone="good" icon="mdi:check-circle" label="Applied" value={k.liveApplied}>
        <p>latest change applied live, from endpoint 1</p>
      </KpiCard>
      {k.notCounted > 0 && (
        <p className={`text-[11px] sm:col-span-2 xl:col-span-4 ${MUTED}`}>
          {k.notCounted} {k.notCounted === 1 ? 'workload is' : 'workloads are'} not in the totals: the replica count isn&apos;t
          known, or the pods run different sizes.
        </p>
      )}
    </div>
  );
}

function TopSavings({ workloads }: { workloads: readonly WorkloadView[] }) {
  const top = workloads
    .filter((w) => workloadStatusChip(w).key === 'over-provisioned')
    .map((w) => ({ w, total: workloadChange(w).total }))
    .filter((x) => x.total.cpuM !== null && (freedPart(x.total.cpuM) ?? 0) > 0)
    .sort((a, b) => (freedPart(b.total.cpuM) ?? 0) - (freedPart(a.total.cpuM) ?? 0))
    .slice(0, 8);
  const data = top.map(({ w, total }) => ({ workload: w.ref.name, freed: Number(((freedPart(total.cpuM) ?? 0) / 1000).toFixed(3)) }));
  return (
    <ChartCard
      title="Top savings by workload"
      subline="CPU each over-provisioned workload's suggestion frees across its pods, largest first (up to 8)."
      source="Workload autoscaler (endpoint 1, GET /api/eks/automation/vertical/{cluster}): (request now − suggested) × replicas."
      state={top.length === 0 ? { kind: 'no-suggestions' } : { kind: 'ok' }}
      table={
        top.length > 0 ? (
          <ChartDataTable
            caption="Top savings by workload"
            rowHeader="Workload"
            columns={[{ key: 'cpu', header: 'CPU freed' }, { key: 'mem', header: 'Memory freed' }]}
            rows={top.map(({ w, total }) => ({
              key: w.id,
              label: w.ref.name,
              values: { cpu: formatVcpu(freedPart(total.cpuM) ?? 0), mem: total.memMi === null ? '—' : formatMemory(freedPart(total.memMi) ?? 0) },
            }))}
          />
        ) : undefined
      }
    >
      <SeriesLegend items={[{ label: 'CPU freed (vCPU)', kind: 'saved', money: true }]} className="mb-2" />
      <Bars
        mode="capacity"
        data={data}
        bars={[{ dataKey: 'freed', name: 'CPU freed (vCPU)', kind: 'saved', money: true }]}
        xKey="workload"
        height={Math.max(120, 36 * data.length + 40)}
        categoryWidth={150}
        ariaLabel="CPU freed by workload, in vCPU"
      />
    </ChartCard>
  );
}

export function WorkloadsPage({ clusterName, accountId, region, workloadsHref, policiesHref }: WorkloadsPageProps) {
  const state = useVerticalRightsizer({ clusterName, accountId, region });
  const location = useLocation();
  const navigate = useNavigate();
  const replyAt = useReplyTimes(state.applyResults);
  const [malformed, setMalformed] = useState(false);
  const headingId = useId();

  const raw = new URLSearchParams(location.search).get('workload');
  const parsed = raw === null ? null : parseWorkloadParam(raw);
  const malformedParam = raw !== null && parsed === null;

  /** Takes `workload` out of the URL in place: same history entry, the page stays mounted. */
  const dropWorkloadParam = () => {
    const params = new URLSearchParams(location.search);
    params.delete('workload');
    navigate({ search: params.toString(), hash: location.hash }, { replace: true, state: inPageState(location) });
  };

  // A value XamOps can't read is removed (replace), with a notice that stays after the URL changes.
  useEffect(() => {
    if (malformedParam) {
      setMalformed(true);
      dropWorkloadParam();
    }
    // Runs once per malformed value; dropWorkloadParam reads the location it removes it from.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [malformedParam, raw]);

  const listHref = withParam(workloadsHref, 'workload', null);
  const view = state.result?.kind === 'ok' ? state.result.view : null;
  const now = Date.now();
  const selected = parsed && view ? view.workloads.find((w) => w.id === workloadParamOf(parsed)) ?? null : null;
  const missing = parsed !== null && view !== null && selected === null ? raw : null;
  const derived = view ? { k: automationKpis(view.workloads, now), chips: howItWorksChips(view, now) } : null;

  if (selected && view) {
    return (
      <WorkloadDetail
        clusterName={clusterName}
        accountId={accountId}
        region={region}
        workload={selected}
        controller={state}
        replyAt={replyAt[selected.id]}
        listHref={listHref}
        now={now}
      />
    );
  }

  let sentence: NowSentence;
  if (state.loading && !state.result) {
    sentence = { clauses: ['Reading what the Workload autoscaler found on this cluster…'], chips: [] };
  } else if (view && derived) {
    const over = view.workloads.filter((w) => workloadStatusChip(w).key === 'over-provisioned');
    const latest = [...view.workloads]
      .filter((w) => isLiveApplied(w.lastAction))
      .map((w) => ({ w, at: instantMs(w.lastAction?.at) }))
      .filter((x): x is { w: WorkloadView; at: number } => x.at !== null)
      .sort((a, b) => b.at - a.at)[0];
    sentence = nowSentence('workloads', {
      analysed: view.workloads.length,
      analysedAt: view.engine.lastCycleAt ? formatInstant(view.engine.lastCycleAt) : undefined,
      ready: over.length,
      readyNames: readyNames(over.map((w) => w.ref.name)),
      readyFreed: derived.k.canSave.counted > 0 ? formatCapacity(derived.k.canSave.cpuM, derived.k.canSave.memMi) : undefined,
      resized: latest ? { workload: latest.w.ref.name, time: istShort(latest.at) } : undefined,
    });
  } else {
    sentence = nowSentence('workloads', {});
  }

  return (
    <div className="space-y-4">
      {malformed && <Notice onDismiss={() => setMalformed(false)}>{MALFORMED_WORKLOAD_NOTICE}</Notice>}
      {missing && <Notice onDismiss={dropWorkloadParam}>{missingWorkloadNotice(missing)}</Notice>}
      <NowBanner sentence={sentence} />
      {view && derived && (
        <>
          <HowItWorks
            chips={derived.chips}
            collapsed
            summary={howSummary(derived.chips)}
            policiesHref={policiesHref}
            policiesLinkState={inPageState(location)}
          />
          <KpiRow k={derived.k} workloads={view.workloads} />
          <TopSavings workloads={view.workloads} />
          {view.workloads.length > 0 && (
            <WorkloadsTable
              workloads={view.workloads}
              detailHref={(w) => withParam(listHref, 'workload', w.id)}
              applyingIds={state.applyingIds}
              applyResults={state.applyResults}
              replyAt={replyAt}
              now={now}
            />
          )}
        </>
      )}
      <section aria-labelledby={headingId} className="space-y-2">
        <h3 id={headingId} className={`text-[14px] font-semibold ${INK}`}>Change a workload</h3>
        <EksAutomationTab clusterName={clusterName} accountId={accountId} region={region} controller={state} />
      </section>
    </div>
  );
}

export default WorkloadsPage;
