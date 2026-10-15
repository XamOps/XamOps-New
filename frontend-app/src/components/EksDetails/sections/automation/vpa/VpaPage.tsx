/**
 * Automation › VPA: how XamOps and any Kubernetes VerticalPodAutoscaler share a workload.
 *
 * The VPA coexistence half of main's combined Pod autoscaler page (`EksHpaTab`), moved here
 * unchanged in behaviour; the HPA half is its own page (`../hpa/HpaPage`). It reads the same
 * vertical rightsizer payload (one `GET` per cluster) and shows each native VPA's mode and target
 * beside XamOps's view, with the coexistence verdict. An Off-mode VPA reads "None — Off doesn't
 * change pods", never a warning. The page is read-only: there is no Apply here, because applying a
 * vertical change is the Workload autoscaler's job on the workload.
 *
 * Nothing is fabricated: a native VPA value XamOps did not read is a dash, never 0, and an empty
 * table names the gate that kept XamOps from reading the cluster rather than "no VPAs".
 */

import type { FC } from 'react';
import useHpaAutomation from '../../../../../hooks/useHpaAutomation';
import { DASH } from '../../../../../api/hpaAdapter';
import { VPA_OFF_TEXT } from '../hpaCopy';
import { EmptyState, HpaReadFallback, gateEmptyState, type GateCopy } from '../hpaReadStates';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import type { Tone } from '../../../../CloudK8s/shared/status/statusTones';
import type { UseEksDashboardReturn } from '../../../../../hooks/useEksDashboard';
import type { HpaPageView, NativeVpaTargetView, VpaCoexistenceKind } from '../../../../../types/hpaAutomation';

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

// ─── VPA coexistence view ────────────────────────────────────────────────────

const COEXIST_TEXT: Record<string, string> = {
  managed: 'XamOps stands down — the VPA owns the requests',
  unreadable: "XamOps can't read the VPA, so it leaves requests alone",
  off: VPA_OFF_TEXT,
  none: DASH,
};

/**
 * The verdict's tone and icon (glossary-additions §2.1): XamOps leaving a VPA-changed workload alone
 * is a neutral "left alone"; a VPA XamOps can't read needs attention; an Off-mode VPA is no conflict.
 */
const COEXIST_TONE: Record<Exclude<VpaCoexistenceKind, 'none'>, { tone: Tone; icon: string }> = {
  managed: { tone: 'off', icon: 'mdi:minus-circle-outline' },
  unreadable: { tone: 'warn', icon: 'mdi:eye-off-outline' },
  off: { tone: 'good', icon: 'mdi:check' },
};

const vpaTargetText = (t: NativeVpaTargetView): string => `${millis(t.cpuRequestM)} CPU / ${mib(t.memRequestMi)} memory`;

const NO_VPAS: GateCopy = {
  icon: 'mdi:compare-vertical',
  title: 'No Kubernetes VPAs on this cluster',
  subtitle: "When a VerticalPodAutoscaler targets a workload, it appears here beside XamOps's view.",
};

const VpaCoexistenceTable: FC<{ view: HpaPageView }> = ({ view }) => {
  const rows = view.vpaWorkloads;
  if (rows.length === 0) {
    const empty = gateEmptyState(view.gateReasons, 'vpa', NO_VPAS);
    return <EmptyState icon={empty.icon} title={empty.title} subtitle={empty.subtitle} />;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
            {['Workload', 'Native VPA mode', 'Native VPA suggests', 'XamOps view', 'Coexistence'].map((h) => (
              <th key={h} className="px-4 py-2 text-[10px] uppercase tracking-wide font-bold text-[rgba(0,0,0,0.4)] whitespace-nowrap">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((w) => {
            const kind = w.vpaCoexistence.kind;
            // Off is the common, benign case: a VPA exists but changes no pods.
            const mode = kind === 'managed' ? 'Mutating' : kind === 'unreadable' ? 'Not readable' : 'Off';
            const target =
              w.nativeVpa.length === 0
                ? DASH
                : w.nativeVpa.map((t) => `${t.containerName}: ${vpaTargetText(t)}`).join('  ·  ');
            return (
              <tr key={w.id} className="border-b border-[rgba(0,0,0,0.04)] last:border-0 align-top">
                <td className="px-4 py-3 text-[12px] font-semibold text-[rgba(0,0,0,0.88)] whitespace-nowrap">
                  {w.ref.name}
                  <span className="block text-[11px] font-normal text-[rgba(0,0,0,0.5)]">
                    {w.ref.kind} · {w.ref.namespace}
                  </span>
                </td>
                <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.6)] whitespace-nowrap">{mode}</td>
                <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.6)] tabular-nums">{target}</td>
                <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                  {/* XamOps writes requests only when the VPA is NOT mutating; otherwise it stands down. */}
                  {kind === 'managed' || kind === 'unreadable' ? 'Requests left to the VPA' : 'Rightsizer manages requests'}
                </td>
                <td className="px-4 py-3 text-[12px] text-[rgba(0,0,0,0.78)]">
                  {kind === 'none' ? COEXIST_TEXT.none : <StatusChip {...COEXIST_TONE[kind]} label={COEXIST_TEXT[kind]} />}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

// ─── Page ────────────────────────────────────────────────────────────────────

export const VpaPage: FC<VpaPageProps> = ({ clusterName, accountId, region }) => {
  // The same read as the HPA page; this page never applies anything.
  const { loading, result, reload } = useHpaAutomation({ clusterName, accountId, region });

  if (result?.kind !== 'ok') {
    return <HpaReadFallback feature="vpa" loading={loading} result={result} reload={reload} />;
  }

  const view = result.view;

  return (
    <div className="space-y-4">
      <section className="bg-white rounded-lg border border-[rgba(0,0,0,0.12)] overflow-hidden">
        <div className="flex flex-wrap items-start justify-between gap-4 px-6 py-4">
          <div className="min-w-0">
            <h3 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Kubernetes VPA coexistence</h3>
            <p className="mt-1 text-[12px] text-[rgba(0,0,0,0.6)] max-w-[820px]">
              How XamOps and any native VerticalPodAutoscaler share a workload. XamOps sizes requests itself and reads
              native VPAs to stay out of their way; a VPA in Mode Off changes no pods, so it is never a conflict.
            </p>
          </div>
          <button
            type="button"
            onClick={reload}
            className="px-4 py-2 text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
          >
            Refresh
          </button>
        </div>

        {view.anomalies.length > 0 && (
          <div className="mx-6 mb-3 border-t border-[rgba(0,0,0,0.06)] pt-3">
            <p className="text-[11px] font-bold text-[rgba(0,0,0,0.6)]">Some numbers couldn&apos;t be shown</p>
            <ul className="mt-1 space-y-0.5">
              {view.anomalies.map((a) => (
                <li key={a} className="text-[11px] text-[rgba(0,0,0,0.5)]">
                  {a}
                </li>
              ))}
            </ul>
          </div>
        )}

        <VpaCoexistenceTable view={view} />
      </section>
    </div>
  );
};

export default VpaPage;
