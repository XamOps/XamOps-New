/**
 * Automation › VPA: how XamOps and any Kubernetes VerticalPodAutoscaler share a workload. UI v3 plan
 * §2 "VPA"; glossary §14.
 *
 * One read of endpoint 1 (`useAutomationRead`) gives both views of the same body: the HPA/VPA view
 * (each native VPA's target and the coexistence verdict) and the Workload autoscaler view (the
 * request now and XamOps's suggestion), so the page compares them without a second `GET`. It shows
 * the Now sentence, the KPIs, CPU (and memory) per pod side by side on a log scale (request now,
 * XamOps, the Kubernetes VPA, hatched: same meaning, a different source), and main's coexistence
 * table with its conflict chips. The page is read-only: there is no Apply here, because applying a
 * vertical change is the Workload autoscaler's job on the workload.
 *
 * Nothing is fabricated: a native VPA value XamOps did not read is a dash, never 0; a VPA with no
 * target says so; the VPA components aren't read yet, so they are "Not checked"; and an empty table
 * names the gate that kept XamOps from reading the cluster rather than "no VPAs".
 */

import { useId, useState, type FC } from 'react';
import useAutomationRead from '../../../../../hooks/useAutomationRead';
import { DASH } from '../../../../../api/hpaAdapter';
import { VPA_OFF_TEXT } from '../hpaCopy';
import { EmptyState, HpaReadFallback, gateEmptyState, type GateCopy } from '../hpaReadStates';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { KpiCard } from '../../../../CloudK8s/shared/status/KpiCard';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import { Bars } from '../../../../CloudK8s/shared/charts/Bars';
import { ChartCard } from '../../../../CloudK8s/shared/charts/ChartCard';
import { ChartDataTable } from '../../../../CloudK8s/shared/charts/ChartDataTable';
import { SeriesLegend } from '../../../../CloudK8s/shared/charts/SeriesLegend';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import type { HpaPageView, NativeVpaTargetView, VpaCoexistenceKind } from '../../../../../types/hpaAutomation';
import {
  VPA_CONFLICT_CHIPS,
  formatMemory,
  vpaConflictTone,
  vpaKpis,
  vpaRows,
  type Resource,
  type VpaKpis,
  type VpaRowView,
} from '../automationDerivations';
import { CARD, FAINT, INK, MUTED, RULE } from '../automationUi';

export interface VpaPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** The page's dashboard (the XamOps agent's view), for the visuals that build on this page. */
  eksData?: UseEksDashboardReturn;
}

/** A millicore value, or a dash when XamOps didn't measure it. Never 0. */
const millis = (value: number | null): string => (value === null ? DASH : `${value}m`);
const mib = (value: number | null): string =>
  value === null ? DASH : value < 1024 ? `${value}Mi` : `${Number((value / 1024).toFixed(2))} GiB`;

// ─── VPA coexistence view (main's table) ─────────────────────────────────────

const COEXIST_TEXT: Record<string, string> = {
  managed: 'XamOps stands down — the VPA owns the requests',
  unreadable: "XamOps can't read the VPA, so it leaves requests alone",
  off: VPA_OFF_TEXT,
  none: 'No VPA',
};

const vpaTargetText = (t: NativeVpaTargetView): string => `${millis(t.cpuRequestM)} CPU / ${mib(t.memRequestMi)} memory`;

const NO_VPAS: GateCopy = {
  icon: 'mdi:compare-vertical',
  title: 'No Kubernetes VPAs on this cluster',
  subtitle: "When a VerticalPodAutoscaler targets a workload, it appears here beside XamOps's view.",
};

function CoexistChip({ kind }: { kind: VpaCoexistenceKind }) {
  return <StatusChip {...VPA_CONFLICT_CHIPS[kind]} label={COEXIST_TEXT[kind]} />;
}

const VpaCoexistenceTable: FC<{ view: HpaPageView; rows: readonly VpaRowView[] }> = ({ view, rows }) => {
  if (rows.length === 0) {
    const empty = gateEmptyState(view.gateReasons, 'vpa', NO_VPAS);
    return <EmptyState icon={empty.icon} title={empty.title} subtitle={empty.subtitle} />;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className={`border-b ${RULE} bg-[rgba(0,0,0,0.02)] dark:bg-[rgba(255,255,255,0.03)]`}>
            {['Workload', 'Native VPA mode', 'Native VPA suggests', 'XamOps view', 'Compared (CPU per pod)', 'Coexistence'].map((h) => (
              <th key={h} scope="col" className={`whitespace-nowrap px-4 py-2 text-[10px] font-bold uppercase tracking-wide ${FAINT}`}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const w = r.hpa;
            const kind = w.vpaCoexistence.kind;
            // Off is the common, benign case: a VPA exists but changes no pods.
            const mode = kind === 'managed' ? 'Mutating' : kind === 'unreadable' ? 'Not readable' : 'Off';
            const target =
              w.nativeVpa.length === 0
                ? DASH
                : w.nativeVpa.map((t) => `${t.containerName}: ${vpaTargetText(t)}`).join('  ·  ');
            return (
              <tr key={w.id} className={`border-b ${RULE} align-top last:border-0`} data-vpa-row={w.id}>
                <td className={`whitespace-nowrap px-4 py-3 text-[12px] font-semibold ${INK}`}>
                  {w.ref.name}
                  <span className={`block text-[11px] font-normal ${FAINT}`}>
                    {w.ref.kind} · {w.ref.namespace}
                  </span>
                </td>
                <td className={`whitespace-nowrap px-4 py-3 text-[12px] ${MUTED}`}>{mode}</td>
                <td className={`px-4 py-3 text-[12px] tabular-nums ${MUTED}`}>{target}</td>
                <td className={`whitespace-nowrap px-4 py-3 text-[12px] ${MUTED}`}>
                  {/* XamOps writes requests only when the VPA is NOT mutating; otherwise it stands down. */}
                  {kind === 'managed' || kind === 'unreadable' ? 'Requests left to the VPA' : 'Rightsizer manages requests'}
                </td>
                <td className="px-4 py-3 text-[12px]">
                  {r.compare ? <StatusChip tone={r.compare.tone} icon={r.compare.icon} label={r.compare.label} weight="normal" /> : <span className={FAINT}>{DASH}</span>}
                </td>
                <td className={`px-4 py-3 text-[12px] ${INK}`}>
                  <CoexistChip kind={kind} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

// ─── KPIs and bars ───────────────────────────────────────────────────────────

function VpaKpiRow({ k }: { k: VpaKpis }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" data-vpa-kpis="">
      <KpiCard tone="info" icon="mdi:compare-vertical" label="VPAs on analysed workloads" value={k.vpas}>
        <p>{k.vpas === 0 ? 'None targets a workload XamOps analyses' : k.off === k.vpas ? 'All in Off mode' : `${k.off} Off, ${k.changingPods} changing pods`}</p>
      </KpiCard>
      <KpiCard tone={k.changingPods > 0 ? 'bad' : 'good'} icon={k.changingPods > 0 ? 'mdi:alert-octagon-outline' : 'mdi:check-circle'} label="Changing pods" value={k.changingPods}>
        <p>{k.changingPods > 0 ? 'XamOps leaves these workloads alone' : 'No VPA here changes pods'}</p>
      </KpiCard>
      {/* Not "Conflicts with XamOps": that card on the HPA page counts VPA-managed only. This one adds
          the VPAs XamOps can't read, and takes the table chips' tones (a conflict bad, unreadable warn). */}
      <KpiCard {...vpaConflictTone(k)} label="Conflicts or unreadable" value={k.conflictsOrUnreadable}>
        <p>{`${k.changingPods} a VPA changes, ${k.unreadable} XamOps can't read`}</p>
      </KpiCard>
      <KpiCard
        tone={k.agreement.compared === 0 ? 'off' : 'good'}
        icon="mdi:check-all"
        label="CPU: VPA agrees"
        value={k.agreement.compared === 0 ? DASH : `${k.agreement.agree} of ${k.agreement.compared}`}
      >
        <p>{k.agreement.compared === 0 ? 'No workload that both size' : 'within 20% of XamOps, per pod'}</p>
      </KpiCard>
    </div>
  );
}

const BAR_SERIES = [
  { dataKey: 'now', name: 'Request now', kind: 'requested' as const },
  { dataKey: 'xamops', name: 'XamOps suggests', kind: 'suggested' as const },
  { dataKey: 'vpa', name: 'Kubernetes VPA suggests', kind: 'suggested' as const, hatch: 'suggested' as const },
];

const short = (name: string): string => (name.length < 16 ? name : `${name.slice(0, 14)}…`);

function PerPodBars({ rows }: { rows: readonly VpaRowView[] }) {
  const [resource, setResource] = useState<Resource>('cpu');
  const cpu = resource === 'cpu';
  const sized = rows.filter((r) => {
    const s = cpu ? r.cpu : r.memory;
    return s.now !== null || s.xamops !== null || s.vpa !== null;
  });
  const text = (v: number | null) => (v === null ? DASH : cpu ? `${v}m` : formatMemory(v));
  const noTarget = rows.filter((r) => r.noTarget);
  return (
    <ChartCard
      title={cpu ? 'CPU per pod: now, XamOps, Kubernetes VPA' : 'Memory per pod: now, XamOps, Kubernetes VPA'}
      subline="Log scale, so 10m and 1000m both read. XamOps's value is its suggestion, or the request now when it suggests no change; a workload XamOps skips has none. The Kubernetes VPA's target is hatched."
      source="Workload autoscaler (endpoint 1, GET /api/eks/automation/vertical/{cluster}): containers[].current, recommended and upstreamVpa, summed over each pod's containers."
      state={sized.length === 0 ? { kind: 'unavailable', reason: 'No workload has a Kubernetes VPA target to compare yet.' } : { kind: 'ok' }}
      table={
        sized.length > 0 ? (
          <ChartDataTable
            caption={cpu ? 'CPU per pod' : 'Memory per pod'}
            rowHeader="Workload"
            columns={BAR_SERIES.map((b) => ({ key: b.dataKey, header: b.name }))}
            rows={sized.map((r) => {
              const s = cpu ? r.cpu : r.memory;
              return { key: r.id, label: r.hpa.ref.name, values: { now: text(s.now), xamops: text(s.xamops), vpa: text(s.vpa) } };
            })}
          />
        ) : undefined
      }
    >
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <SeriesLegend items={BAR_SERIES.map((b) => ({ label: b.name, kind: b.kind, hatch: 'hatch' in b ? b.hatch : undefined }))} />
        <div role="group" aria-label="Resource" className="inline-flex overflow-hidden rounded-lg border border-[rgba(0,0,0,0.12)] dark:border-[rgba(255,255,255,0.16)]">
          {(['cpu', 'memory'] as const).map((r) => (
            <button
              key={r}
              type="button"
              aria-pressed={resource === r}
              onClick={() => setResource(r)}
              className={`px-3 py-1 text-[12px] font-semibold ${resource === r ? 'bg-[rgba(0,0,0,0.88)] text-white dark:bg-[rgba(255,255,255,0.92)] dark:text-black' : `bg-white dark:bg-[#141414] ${MUTED}`}`}
            >
              {r === 'cpu' ? 'CPU' : 'Memory'}
            </button>
          ))}
        </div>
      </div>
      <Bars
        mode="grouped"
        scale="log"
        data={sized.map((r) => {
          const s = cpu ? r.cpu : r.memory;
          return { workload: short(r.hpa.ref.name), now: s.now, xamops: s.xamops, vpa: s.vpa };
        })}
        bars={BAR_SERIES}
        xKey="workload"
        height={240}
        tickFormat={(v) => (cpu ? `${v}m` : `${v}Mi`)}
        ariaLabel={cpu ? 'CPU per pod: request now, XamOps and the Kubernetes VPA, log scale' : 'Memory per pod: request now, XamOps and the Kubernetes VPA, log scale'}
      />
      {noTarget.length > 0 && (
        <ul className={`mt-2 space-y-0.5 text-[11px] ${FAINT}`}>
          {noTarget.map((r) => (
            <li key={r.id}>
              {r.hpa.ref.name}: {r.compare?.label ?? 'No VPA suggestion reported'}
            </li>
          ))}
        </ul>
      )}
    </ChartCard>
  );
}

// ─── Page ────────────────────────────────────────────────────────────────────

export const VpaPage: FC<VpaPageProps> = ({ clusterName, accountId, region }) => {
  // One read of endpoint 1 for both views; this page never applies anything.
  const { loading, result, reload } = useAutomationRead({ clusterName, accountId, region });
  const explainId = useId();

  const view = result?.kind === 'ok' ? result.view.hpa : null;
  const rows = view && result?.kind === 'ok' ? vpaRows(view, result.view.vertical) : [];
  const k = vpaKpis(rows);

  let sentence: NowSentence;
  if (loading && !result) sentence = { clauses: ['Reading the Kubernetes VPAs on this cluster…'], chips: [] };
  else if (view) {
    sentence = nowSentence('vpa', {
      vpas: k.vpas,
      changingPods: k.changingPods,
      off: k.off,
      unreadable: k.unreadable,
      agreement: k.agreement,
    });
  } else sentence = nowSentence('vpa', {});

  return (
    // data-k8s-kit: every colour on this page has its dark variant, so the legacy dark CSS leaves it alone.
    <div className="space-y-8" data-vpa-page="" data-k8s-kit="">
      <div className="space-y-1">
        <FeatureMark feature="vpa" title="VPA" />
        <p className={`text-[12px] ${MUTED}`}>
          Kubernetes Vertical Pod Autoscaler objects and what they suggest, next to XamOps. XamOps never resizes a workload that a
          VPA is changing.
        </p>
      </div>
      <NowBanner sentence={sentence} />

      <section className={`${CARD} px-4 py-3`} aria-labelledby={explainId} data-vpa-explain="">
        <h3 id={explainId} className={`text-[13px] font-semibold ${INK}`}>How XamOps and Kubernetes VPAs share a workload</h3>
        <p className={`mt-1 text-[12px] ${INK}`}>
          XamOps&apos;s own engine is the vertical autoscaler. Kubernetes VPAs here are only compared with it: XamOps never edits
          them, and <span title="Code: VPA_MANAGED">stands down for a workload whose VPA changes pods</span>.
        </p>
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[12px]" data-vpa-components="">
          <span className={MUTED}>VPA components (recommender, updater, admission controller):</span>
          <StatusChip tone="off" icon="mdi:help-circle-outline" label="Not checked" weight="normal" />
          <span className={FAINT}>XamOps doesn&apos;t read them yet.</span>
        </div>
      </section>

      {result?.kind !== 'ok' ? (
        <HpaReadFallback feature="vpa" loading={loading} result={result} reload={reload} />
      ) : (
        <>
          <VpaKpiRow k={k} />
          <PerPodBars rows={rows} />
          <section className={`${CARD} overflow-hidden`}>
            <div className="flex flex-wrap items-start justify-between gap-4 px-4 py-3">
              <div className="min-w-0">
                <h3 className={`text-[13px] font-semibold ${INK}`}>Kubernetes VPA coexistence</h3>
                <p className={`mt-1 max-w-[820px] text-[12px] ${MUTED}`}>
                  How XamOps and any native VerticalPodAutoscaler share a workload. XamOps sizes requests itself and reads
                  native VPAs to stay out of their way; a VPA in Mode Off changes no pods, so it is never a conflict.
                </p>
              </div>
              <button
                type="button"
                onClick={reload}
                className="rounded-xl border border-[rgba(0,0,0,0.12)] bg-white px-4 py-2 text-xs font-bold text-[rgba(0,0,0,0.6)] transition-all hover:bg-[rgba(0,0,0,0.03)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.7)]"
              >
                Refresh
              </button>
            </div>

            {result.view.hpa.anomalies.length > 0 && (
              <div className={`mx-4 mb-3 border-t ${RULE} pt-3`}>
                <p className={`text-[11px] font-bold ${MUTED}`}>Some numbers couldn&apos;t be shown</p>
                <ul className="mt-1 space-y-0.5">
                  {result.view.hpa.anomalies.map((a) => (
                    <li key={a} className={`text-[11px] ${FAINT}`}>
                      {a}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <VpaCoexistenceTable view={result.view.hpa} rows={rows} />
          </section>
        </>
      )}
    </div>
  );
};

export default VpaPage;
