/**
 * Automation › HPA › one HPA (`?hpa=<namespace>/<name>`; UI v3 plan §2 "HPA"; glossary §13).
 *
 * Rendered by HpaPage, so the HPA read and this session's apply replies stay mounted. It shows the
 * HPA's live numbers from the XamOps agent as tiles (replicas now, its minimum and maximum, what it
 * scales on, CPU now against its target and, when the agent reported all three, what the HPA itself
 * asks for by its own formula), a banner while it is held at its maximum, its conditions in plain
 * words, the "Works with XamOps" checks, XamOps's decision with the same gated Apply as the list,
 * and the two history charts, which need kube-state-metrics' series XamOps doesn't read yet.
 */
import type { ReactNode } from 'react';
import { Icon } from '@iconify/react';
import { DASH, hpaApplyGate } from '../../../../../api/hpaAdapter';
import type { UseHpaAutomationState } from '../../../../../hooks/useHpaAutomation';
import type { HpaPageView } from '../../../../../types/hpaAutomation';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { TermTip } from '../../../../CloudK8s/shared/status/TermTip';
import { RangeBar } from '../../../../CloudK8s/shared/charts/RangeBar';
import { hpaConditionText, hpaWants, hpaWorksChecks, rowHasFlag, type HpaRow } from '../automationDerivations';
import { CARD, Card, FAINT, INK, InPageLink, LINK, MUTED, RULE } from '../automationUi';
import { HpaDecisionPanel } from './HpaDecision';
import { FROM_AGENT, HpaHistoryCards } from './hpaShared';

export interface HpaDetailProps {
  row: HpaRow;
  /** Endpoint 1's HPA view; null when it didn't answer. */
  view: HpaPageView | null;
  engineRead: boolean;
  dryRun: boolean;
  onDryRun: (next: boolean) => void;
  state: UseHpaAutomationState;
  /** The HPA list (this page without `?hpa=`). */
  listHref: string;
}

const n = (v: number | null | undefined): string => (v === null || v === undefined ? DASH : String(v));

function Tile({ label, children, sub }: { label: string; children: ReactNode; sub?: ReactNode }) {
  return (
    <div className={`${CARD} px-4 py-3`} data-hpa-tile={label}>
      <p className={`text-[11px] font-semibold uppercase tracking-wide ${MUTED}`}>{label}</p>
      <div className={`mt-1 text-[18px] font-semibold tabular-nums ${INK}`}>{children}</div>
      {sub && <div className={`mt-1 text-[12px] ${FAINT}`}>{sub}</div>}
    </div>
  );
}

export function HpaDetail({ row, view, engineRead, dryRun, onDryRun, state, listHref }: HpaDetailProps) {
  const live = row.live;
  const wants = live ? hpaWants(live) : null;
  const atMax = rowHasFlag(row, 'at-max');
  const checks = hpaWorksChecks(row);
  const w = row.workload;

  return (
    <div className="space-y-4" data-hpa-detail={row.id}>
      <InPageLink to={listHref} className={`inline-flex items-center gap-1 text-[12px] ${LINK}`}>
        <Icon icon="mdi:arrow-left" width={14} aria-hidden="true" />
        All HPAs
      </InPageLink>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <FeatureMark feature="hpa" title={row.name} />
        <p className={`text-[12px] ${MUTED}`}>
          Namespace {row.namespace} · Scales {row.target ? `${row.target.kind} ${row.target.name}` : DASH}
        </p>
      </div>

      {atMax && live && (
        <div role="status" className={`flex flex-wrap items-center gap-2 rounded-lg border ${RULE} px-4 py-3`} data-hpa-at-limit="">
          <StatusChip tone="warn" icon="mdi:arrow-collapse-up" label="At its maximum" />
          <span className={`text-[13px] ${INK}`}>
            {row.name} runs {n(live.current)} of at most {n(live.max)} replicas
            {wants && wants.wants > (live.max ?? Infinity) ? `, and by its own formula it wants about ${wants.wants}, so it would add pods if it could.` : '.'}
          </span>
        </div>
      )}

      {live ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <Tile label="Replicas now" sub={<RangeBar min={live.min} max={live.max} current={live.current} width={120} />}>
            {n(live.current)}
          </Tile>
          <Tile label="Minimum–maximum" sub={live.desired !== null ? `Kubernetes wants ${live.desired} now` : undefined}>
            {n(live.min)}–{n(live.max)}
          </Tile>
          <Tile label="Scales on" sub={live.cpuTargetPct !== null ? `target ${live.cpuTargetPct}% of request` : 'not reported by the XamOps agent'}>
            {live.cpuTargetPct !== null ? 'CPU' : DASH}
          </Tile>
          <Tile label="CPU now" sub={live.cpuTargetPct !== null ? `against a ${live.cpuTargetPct}% target` : undefined}>
            {live.cpuNowPct !== null ? `${live.cpuNowPct}%` : DASH}
          </Tile>
          {wants && (
            <Tile label="The HPA wants" sub={<TermTip entry={{ term: 'The HPA wants', text: wants.formula, source: 'HPA' }}>How it works this out</TermTip>}>
              about {wants.wants}
            </Tile>
          )}
        </div>
      ) : (
        <Card title="Live numbers">
          <p className={`text-[12px] ${MUTED}`}>
            {DASH} {FROM_AGENT}: the agent didn&apos;t report this HPA, so its replicas, limits and CPU aren&apos;t known here.
          </p>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Conditions">
          {live && live.conditions.length > 0 ? (
            <ul className={`divide-y ${RULE}`}>
              {live.conditions.map((c) => (
                <li key={c.type} className={`py-1.5 text-[12px] ${INK}`} data-hpa-condition={c.type} title={`${c.type} ${c.status}${c.reason ? ` (${c.reason})` : ''}`}>
                  {hpaConditionText(c)}
                </li>
              ))}
            </ul>
          ) : (
            <p className={`text-[12px] ${MUTED}`}>The XamOps agent reported no conditions for this HPA.</p>
          )}
          <p className={`mt-2 text-[11px] ${FAINT}`}>Source: the XamOps agent (the HPA&apos;s status conditions).</p>
        </Card>
        <Card title="Works with XamOps">
          <ul className="space-y-2">
            {checks.map((c) => (
              <li key={c.id} data-works-check={c.id}>
                <StatusChip tone={c.tone} icon={c.icon} label={c.label} />
                {c.detail && <p className={`mt-0.5 text-[12px] ${MUTED}`}>{c.detail}</p>}
              </li>
            ))}
          </ul>
          <p className={`mt-2 text-[11px] ${FAINT}`}>Source: the XamOps agent and the Workload autoscaler (endpoint 1).</p>
        </Card>
      </div>

      <Card
        title="XamOps's suggestion"
        aside={
          <label className={`flex items-center gap-2 text-[11px] font-semibold ${MUTED}`} title="A test run checks the change without sending anything to the cluster">
            <input type="checkbox" checked={dryRun} onChange={(e) => onDryRun(e.target.checked)} className="accent-black" />
            Test run only (nothing is sent)
          </label>
        }
      >
        {w && view ? (
          <HpaDecisionPanel
            workload={w}
            gate={hpaApplyGate(view, w, dryRun, state.callerRefusal)}
            dryRun={dryRun}
            applying={state.applyingIds.has(w.id)}
            reply={state.applyResults[w.id]}
            onApply={() => {
              void state.apply(w, dryRun);
            }}
          />
        ) : (
          <p className={`text-[12px] ${MUTED}`}>
            {engineRead
              ? "XamOps doesn't analyse the workload this HPA scales, so it has no suggestion for it."
              : "XamOps couldn't read its HPA decisions just now."}
          </p>
        )}
      </Card>

      <HpaHistoryCards subject={row.name} />
    </div>
  );
}

export default HpaDetail;
