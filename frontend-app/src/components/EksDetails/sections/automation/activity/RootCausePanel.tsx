/**
 * Automation › Activity › Probable root causes (FEAT-002).
 *
 * A ranked, read-only view of the root causes XamOps can prove from its own durable apply/refusal/
 * expiry history for this cluster — each with an evidence-strength score, the workload identity, a
 * concrete remediation sentence (built server-side from structured evidence), and a disclosure of
 * the durable rows behind it. A KpiCard row summarises counts by kind.
 *
 * RCA applies nothing. When there is nothing to show, the panel renders an honest empty state, and a
 * muted note names the two causes (OOM/eviction, HPA flap) that arrive once XamOps persists the data
 * they need — never a fabricated cause.
 */
import { useId, useMemo } from 'react';
import { Icon } from '@iconify/react';
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { formatIST } from '../../../../../utils/timeUtils';
import useRca from '../../../../../hooks/useRca';
import type { RcaCauseView } from '../../../../../api/rcaApi';
import { MetricStrip, type MetricCell } from '../../../../CloudK8s/shared/MetricStrip';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import {
  RCA_DEPENDENCIES_NOTE,
  RCA_EMPTY_STATE,
  RCA_PANEL_SOURCE,
  RCA_PANEL_TITLE,
  rcaConfidenceLine,
  rcaKindIcon,
  rcaKindLabel,
  rcaKindTone,
} from '../rcaCopy';
import { CARD, FAINT, INK, MUTED, ReadFailureCard, type ReadFailure } from '../automationUi';

export interface RootCausePanelProps {
  clusterName: string;
  accountId: string;
  region: string;
}

function CauseCard({ cause }: { cause: RcaCauseView }) {
  const evidenceId = useId();
  const tone = rcaKindTone(cause.kind);
  const workload = `${cause.workload.namespace || DASH}/${cause.workload.name || DASH}`;
  return (
    <div className={`${CARD} px-3.5 py-3`} data-rca-cause={cause.kind} data-k8s-kit="">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <StatusChip
              tone={tone}
              icon={rcaKindIcon(cause.kind)}
              label={rcaKindLabel(cause.kind)}
              detail={rcaConfidenceLine(cause.confidence)}
            />
            <span className={`font-mono text-[12px] ${INK}`}>{workload}</span>
          </div>
          <p className={`mt-2 text-[12px] ${INK}`}>{cause.remediation}</p>
        </div>
      </div>
      {cause.evidence.length > 0 && (
        <details className="mt-2" data-rca-evidence="">
          <summary className={`cursor-pointer text-[12px] font-semibold ${MUTED}`}>
            {cause.occurrences ?? cause.evidence.length} recorded{' '}
            {(cause.occurrences ?? cause.evidence.length) === 1 ? 'occurrence' : 'occurrences'}
            {cause.windowHours ? ` in ${cause.windowHours}h` : ''}
          </summary>
          <ul className="mt-2 space-y-1" aria-label={`Evidence for ${rcaKindLabel(cause.kind)}`} id={evidenceId}>
            {cause.evidence.map((e, i) => (
              <li key={`${e.correlationId ?? 'row'}-${i}`} className={`text-[11px] ${FAINT}`}>
                <span className="tabular-nums" title={e.at ?? undefined}>
                  {e.at ? formatIST(e.at, { short: true }) || DASH : DASH}
                </span>{' '}
                · <span className="font-semibold">{e.outcome ?? DASH}</span>
                {e.detail ? <span> — {e.detail}</span> : null}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}

function KpiRow({ causes }: { causes: readonly RcaCauseView[] }) {
  const counts = useMemo(() => {
    const byKind = new Map<string, number>();
    for (const c of causes) byKind.set(c.kind, (byKind.get(c.kind) ?? 0) + 1);
    return Array.from(byKind.entries());
  }, [causes]);
  if (counts.length === 0) return null;
  // One honest MetricStrip, one cell per root-cause kind (peers, no hero). The former KpiCard copy —
  // the kind label and "n workloads affected" — is preserved verbatim.
  const cells: MetricCell[] = counts.map(([kind, n]) => ({
    label: rcaKindLabel(kind),
    value: n,
    sub: <span>{n === 1 ? 'workload affected' : 'workloads affected'}</span>,
  }));
  return (
    <div data-rca-kpis="">
      <MetricStrip cells={cells} aria-label="Root causes by kind" />
    </div>
  );
}

export function RootCausePanel({ clusterName, accountId, region }: RootCausePanelProps) {
  const titleId = useId();
  const { loading, result, reload } = useRca({ clusterName, accountId, region });

  const view = result?.kind === 'ok' ? result.view : null;
  const failure: ReadFailure | null =
    result && result.kind !== 'ok' ? (result as ReadFailure) : null;

  // data-k8s-kit: every colour here carries its dark variant, so the legacy dark CSS leaves it alone.
  return (
    <section className={`${CARD} px-4 py-3`} aria-labelledby={titleId} data-rca="" data-k8s-kit="">
      <div className="mb-2">
        <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>{RCA_PANEL_TITLE}</h3>
        <p className={`mt-0.5 text-[12px] ${MUTED}`}>{RCA_PANEL_SOURCE}</p>
      </div>

      {loading && !result && (
        <div className="skeleton h-24 rounded-[8px] dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" />
      )}

      {failure && <ReadFailureCard failure={failure} onRetry={reload} />}

      {view && view.causes.length > 0 && (
        <div className="space-y-3">
          <KpiRow causes={view.causes} />
          <div className="space-y-2" data-rca-causes="">
            {view.causes.map((cause, i) => (
              <CauseCard key={`${cause.kind}-${cause.workload.namespace}/${cause.workload.name}-${i}`} cause={cause} />
            ))}
          </div>
        </div>
      )}

      {view && view.causes.length === 0 && (
        <p className={`flex items-start gap-1.5 px-1 py-6 text-[12px] ${MUTED}`} data-rca-empty="">
          <Icon icon="mdi:information-outline" width={16} aria-hidden="true" className={`mt-0.5 shrink-0 ${FAINT}`} />
          <span>{RCA_EMPTY_STATE}</span>
        </p>
      )}

      {view && view.dependencies.length > 0 && (
        <p className={`mt-2 text-[11px] ${FAINT}`} data-rca-dependencies="">{RCA_DEPENDENCIES_NOTE}</p>
      )}
    </section>
  );
}

export default RootCausePanel;
