/**
 * Automation › Activity's main table (FEAT-007): every change XamOps recorded on this cluster, from
 * the activity feed, newest first. One row per entry: when (IST, the raw UTC instant in the title),
 * the workload or HPA with its feature dot and name, the action, before → after, the result chip, the
 * restart-free proof, who, and the reference. A row with pods, a detail or a reference expands to
 * the per-pod proof. Colour only through StatusChip and FeatureDot, each with its label beside it.
 */
import type { ReactNode } from 'react';
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { K8sDataTable, type ColumnDef } from '../../../../CloudK8s/shared/K8sDataTable';
import { FeatureDot, featureName } from '../../../../CloudK8s/shared/status/FeatureMark';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import type { ActivityEntryView } from '../../../../../types/automationActivity';
import { istShort } from '../automationDerivations';
import { FAINT, INK, MUTED, RULE } from '../automationUi';
import {
  actionText,
  activityResultChip,
  containerChangeText,
  entryRef,
  hpaChangeText,
  modeChangeText,
  noProofText,
  proofChip,
  resultDetail,
  whoText,
} from './activityText';

export const ACTIVITY_FEED_CAPTION = 'Every change XamOps recorded';

const featureOf = (e: ActivityEntryView) => e.feature ?? 'workload';

function ChangeCell({ e }: { e: ActivityEntryView }) {
  if (e.action === 'hpa-change') {
    const hpa = hpaChangeText(e);
    return <span className="tabular-nums">{hpa ?? DASH}</span>;
  }
  if (e.action === 'mode-change') return <span>{modeChangeText(e) ?? DASH}</span>;
  if (e.containers.length === 0) return <span className={FAINT}>{DASH}</span>;
  return (
    <span className="flex flex-col gap-1">
      {e.containers.map((c) => {
        const lines = containerChangeText(c, e);
        return (
          <span key={c.container} className="block">
            <span className="font-semibold">{c.container}</span>
            {lines.length === 0 ? (
              <span className={`block text-[12px] ${FAINT}`}>{DASH}</span>
            ) : (
              lines.map((l) => (
                <span key={l.field} className="block text-[12px] tabular-nums">
                  {l.field} {l.text}
                  {l.note && <span className={FAINT}>{`, ${l.note}`}</span>}
                </span>
              ))
            )}
          </span>
        );
      })}
    </span>
  );
}

function ResultCell({ e }: { e: ActivityEntryView }) {
  const chip = activityResultChip(e);
  const detail = resultDetail(e);
  return (
    <span className="inline-flex max-w-[360px] flex-col gap-1">
      <span><StatusChip tone={chip.tone} icon={chip.icon} label={chip.label} /></span>
      {detail && <span className={`block break-words text-[12px] ${MUTED}`} title={e.detail ?? undefined}>{detail.short}</span>}
    </span>
  );
}

/** "1 pod: same pod, restarts 0 → 0, ready" for each pod the proof holds. */
export function podSummary(e: ActivityEntryView): string | null {
  const pods = e.proof?.pods ?? [];
  if (pods.length === 0) return null;
  const same = pods.every((p) => p.uidChanged === false);
  const replaced = pods.some((p) => p.uidChanged === true);
  const before = pods.map((p) => p.restartsBefore);
  const after = pods.map((p) => p.restartsAfter);
  const sum = (xs: Array<number | null>) => (xs.some((x) => x === null) ? null : xs.reduce<number>((a, b) => a + (b ?? 0), 0));
  const b = sum(before);
  const a = sum(after);
  const ready = pods.every((p) => p.ready === true);
  const parts = [
    same ? (pods.length === 1 ? 'same pod' : 'same pods') : replaced ? 'a pod was replaced' : 'pod identity not checked',
    `restarts ${b ?? DASH} → ${a ?? DASH}`,
  ];
  if (ready) parts.push(pods.length === 1 ? 'ready' : 'all ready');
  return `${pods.length} ${pods.length === 1 ? 'pod' : 'pods'}: ${parts.join(', ')}`;
}

function ProofCell({ e }: { e: ActivityEntryView }) {
  const chip = proofChip(e);
  if (!chip) return <span className={`text-[12px] ${FAINT}`}>{noProofText(e)}</span>;
  const pods = podSummary(e);
  return (
    <span className="inline-flex flex-col gap-1">
      <span><StatusChip tone={chip.tone} icon={chip.icon} label={chip.label} weight="normal" /></span>
      <span className={`text-[12px] ${MUTED}`}>{pods ?? 'Per-pod proof not recorded for this entry'}</span>
    </span>
  );
}

const COLUMNS: ReadonlyArray<ColumnDef<ActivityEntryView>> = [
  {
    id: 'when',
    header: 'When',
    priority: 'essential',
    cell: (e) => (
      <span className="whitespace-nowrap tabular-nums" title={e.atRaw ? `${e.atRaw} (UTC)` : undefined}>
        {e.at !== null ? istShort(e.at) : DASH}
      </span>
    ),
    sortValue: (e) => e.at,
    csv: (e) => e.atRaw,
  },
  {
    id: 'target',
    header: 'Workload',
    priority: 'essential',
    cell: (e) => (
      <span className="inline-flex flex-col">
        <span className={`inline-flex items-center gap-1.5 font-semibold ${INK}`}>
          <FeatureDot feature={featureOf(e)} />
          {e.target.name ?? DASH}
        </span>
        <span className={`text-[11px] ${FAINT}`}>
          {e.feature === 'hpa' ? 'HPA' : (e.target.kind ?? DASH)} · {e.target.namespace ?? DASH} · {featureName(featureOf(e))}
        </span>
      </span>
    ),
    sortValue: (e) => e.target.name,
    csv: (e) => entryRef(e),
  },
  {
    id: 'action',
    header: 'Action',
    cell: (e) => (
      <span className="inline-flex flex-col">
        <span>{actionText(e)}</span>
        <span className={`text-[11px] ${FAINT}`}>{e.action === 'mode-change' ? 'Setting' : e.dryRun || e.result === 'test-run' ? 'Test run' : 'Live'}</span>
      </span>
    ),
    sortValue: (e) => actionText(e),
    csv: (e) => `${actionText(e)}${e.dryRun ? ' (test run)' : ''}`,
  },
  {
    id: 'change',
    header: 'Before → after',
    cell: (e) => <ChangeCell e={e} />,
    csv: (e) =>
      e.action === 'hpa-change'
        ? hpaChangeText(e)
        : e.action === 'mode-change'
          ? modeChangeText(e)
          : e.containers.map((c) => `${c.container}: ${containerChangeText(c, e).map((l) => `${l.field} ${l.text}${l.note ? ` (${l.note})` : ''}`).join('; ')}`).join(' | ') || null,
  },
  {
    id: 'result',
    header: 'Result',
    priority: 'essential',
    cell: (e) => <ResultCell e={e} />,
    sortValue: (e) => activityResultChip(e).label,
    csv: (e) => activityResultChip(e).label,
  },
  {
    id: 'proof',
    header: 'Restart-free proof',
    cell: (e) => <ProofCell e={e} />,
    csv: (e) => proofChip(e)?.label ?? noProofText(e),
  },
  { id: 'who', header: 'Who', cell: (e) => <span className="whitespace-nowrap">{whoText(e)}</span>, sortValue: (e) => whoText(e), csv: (e) => whoText(e) },
  {
    id: 'reference',
    header: 'Reference',
    cell: (e) =>
      e.reference ? (
        <span className="font-mono text-[12px]" title={e.reference}>{e.reference.slice(0, 8)}</span>
      ) : (
        <span className={FAINT}>{DASH}</span>
      ),
    csv: (e) => e.reference,
  },
];

/** The expanded row: every pod's proof, the stored detail in full and the reference. */
function EntryDetail({ e }: { e: ActivityEntryView }) {
  const pods = e.proof?.pods ?? [];
  return (
    <div className="space-y-2 text-[12px]" data-activity-entry-detail={e.id}>
      {pods.length > 0 && (
        <table className="w-full border-collapse text-left text-[12px]">
          <caption className="sr-only">{`Pods ${e.target.name ?? ''} touched`}</caption>
          <thead>
            <tr className={`border-b ${RULE} text-[11px] ${MUTED}`}>
              <th scope="col" className="px-2 py-1 font-semibold">Pod</th>
              <th scope="col" className="px-2 py-1 font-semibold">Same pod</th>
              <th scope="col" className="px-2 py-1 font-semibold">Restarts</th>
              <th scope="col" className="px-2 py-1 font-semibold">Ready</th>
              <th scope="col" className="px-2 py-1 font-semibold">Put back</th>
            </tr>
          </thead>
          <tbody>
            {pods.map((p) => (
              <tr key={p.pod}>
                <th scope="row" className={`px-2 py-1 font-mono font-normal ${INK}`}>{p.pod}</th>
                <td className="px-2 py-1">{p.uidChanged === false ? 'Same pod' : p.uidChanged === true ? 'Replaced' : DASH}</td>
                <td className="px-2 py-1 tabular-nums">{`${p.restartsBefore ?? DASH} → ${p.restartsAfter ?? DASH}`}</td>
                <td className="px-2 py-1">
                  {p.ready === true ? (
                    <StatusChip tone="good" icon="mdi:check-circle" label="Ready" weight="normal" />
                  ) : p.ready === false ? (
                    <StatusChip tone="bad" icon="mdi:alert-octagon-outline" label="Not ready" weight="normal" />
                  ) : (
                    DASH
                  )}
                </td>
                <td className="px-2 py-1">
                  {p.revert === null ? (
                    <span className={FAINT}>Not needed</span>
                  ) : p.revert.state === 'REVERTED' ? (
                    <StatusChip tone="good" icon="mdi:undo-variant" label="Put back and confirmed" weight="normal" detail={p.revert.detail} />
                  ) : (
                    <StatusChip tone="warn" icon="mdi:progress-question" label={`Put back: ${p.revert.state ?? 'not confirmed'}`} weight="normal" detail={p.revert.detail} />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {e.detail && <p className={`break-words ${MUTED}`}><span className={`font-semibold ${INK}`}>Detail: </span>{e.detail}</p>}
      {e.reference && <p className={MUTED}><span className={`font-semibold ${INK}`}>Reference: </span><span className="font-mono">{e.reference}</span></p>}
      {e.beforeNotRecorded && (
        <p className={FAINT}>Recorded before XamOps kept the size it saw before a change, so only the size asked for and the size after are known.</p>
      )}
    </div>
  );
}

export interface ActivityFeedTableProps {
  entries: readonly ActivityEntryView[];
  clusterName: string;
  /** Shown when there are no entries. */
  emptyState: ReactNode;
}

export function ActivityFeedTable({ entries, clusterName, emptyState }: ActivityFeedTableProps) {
  return (
    <K8sDataTable
      rows={entries}
      columns={COLUMNS}
      rowKey={(e) => e.id}
      caption={ACTIVITY_FEED_CAPTION}
      pageSize={50}
      exportName={`${clusterName}-activity`}
      initialSort={{ columnId: 'when', direction: 'desc' }}
      emptyState={emptyState}
      expandRow={(e) => ((e.proof?.pods.length ?? 0) > 0 || e.detail || e.reference ? <EntryDetail e={e} /> : null)}
      rowLabel={(e) => `${e.target.name ?? 'entry'} at ${e.at !== null ? istShort(e.at) : 'an unknown time'}`}
    />
  );
}

export default ActivityFeedTable;
