/**
 * "Proof of no harm" (Phase A design §4): what the activity log recorded about the pods XamOps resized
 * in place, one de-boxed row per fact, dot + word for status, an em-dash for anything nobody measured.
 *
 * Nothing is invented. Requests that succeeded, test requests sent and PDB disruptions allowed are not
 * measured by XamOps today, so they always read "—" with a note saying so. A change written before the
 * log kept pod proofs is never counted as restart-free. The "Consolidation runs" group (cluster scope)
 * is labelled for what the endpoint returns: it does not cover every kind of drain.
 */
import { useState, type ReactNode } from 'react';
import useConsolidationRuns from '../../../../hooks/useConsolidationRuns';
import type { UseAutomationActivityState } from '../../../../hooks/useAutomationActivity';
import type { ReadState } from '../../../../hooks/useNodeAutomationRead';
import type { ConsolidationRunResponse } from '../../../../types/k8sAutomation';
import { Surface } from '../../../CloudK8s/shared/Surface';
import { StatusDot } from '../../../CloudK8s/shared/status/StatusDot';
import type { Tone } from '../../../CloudK8s/shared/status/statusTones';
import { BUTTON, DIVIDE, FAINT, INK, MUTED } from './automationUi';
import { summarizeDrains, summarizeProof, type DrainSummary, type ProofSummary } from './proofOfNoHarm';

export const PROOF_TITLE = 'Proof of no harm';
export const DASH = '—';
export const NOT_MEASURED_NOTE = "Not measured. XamOps doesn't send or watch test requests.";
export const PDB_NOTE =
  'Not applicable to in-place resizes: no pod is evicted. Node drains check it before moving a pod; not shown here yet.';
export const CONSOLIDATION_LABEL = 'Consolidation runs';
export const CONSOLIDATION_NOTE = "Drains by the evictor, idle drain, rebalancer and spot handling aren't in this history.";
const DAY_MS = 24 * 60 * 60 * 1000;

export interface ProofFeed
  extends Pick<UseAutomationActivityState, 'loading' | 'result' | 'entries' | 'hasOlder' | 'reload'> {}

export interface ProofOfNoHarmPanelProps {
  scope: 'cluster' | 'workload';
  feed: ProofFeed;
  /** "last 7 days", "last 20 log entries". */
  windowLabel: string;
  /** Workload scope: each container's current QoS class. */
  qosClasses?: ReadonlyArray<{ container: string; qosClass: string | null }>;
  /** Cluster scope: the consolidation controller's run history. Omitted at workload scope. */
  drains?: ReadState<ConsolidationRunResponse>;
  onRetryDrains?: () => void;
  /** Start of the Overview window (epoch ms), so a short run history isn't read as a quiet week. */
  windowStartMs?: number;
}

const plural = (n: number, one: string, many: string) => (n === 1 ? one : many);
const day = (ms: number | null) => (ms === null ? DASH : new Date(ms).toISOString().slice(0, 10));

function Row({ label, children, note, id }: { label: string; children: ReactNode; note?: ReactNode; id: string }) {
  return (
    <div className="grid grid-cols-[minmax(0,13rem)_minmax(0,1fr)] gap-x-4 py-2.5" data-proof-row={id}>
      <dt className={`text-[13px] ${MUTED}`}>{label}</dt>
      <dd className="min-w-0">
        <div className={`text-[13px] ${INK}`}>{children}</div>
        {note && <div className={`mt-0.5 text-[12px] ${FAINT}`}>{note}</div>}
      </dd>
    </div>
  );
}

function headline(s: ProofSummary): { tone: Tone; label: string; detail?: string } {
  if (s.changes === 0) return { tone: 'off', label: 'No resizes in this window' };
  const label = `${s.changesRestartFree} of ${s.changes} changes proved restart-free`;
  if (s.podsRestarted > 0) return { tone: 'bad', label, detail: 'Restarts seen' };
  if (s.changesRestartFree === s.changes) return { tone: 'good', label };
  const k = s.changes - s.changesRestartFree;
  return { tone: 'warn', label, detail: `Not proved on ${k} ${plural(k, 'change', 'changes')}` };
}

function DrainsGroup({
  drains, onRetry, windowStartMs,
}: { drains: ReadState<ConsolidationRunResponse>; onRetry?: () => void; windowStartMs: number }) {
  let body: ReactNode;
  if (drains.kind === 'loading') {
    body = <div className="skeleton h-8 rounded-[6px] dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" aria-busy="true" />;
  } else if (drains.kind === 'error') {
    body = (
      <Row label="Consolidation runs read" id="drains-error" note={drains.message}>
        {DASH}
        {onRetry && <button type="button" onClick={onRetry} className={`ml-3 ${BUTTON}`}>Retry</button>}
      </Row>
    );
  } else {
    const s: DrainSummary = summarizeDrains(drains.data, windowStartMs);
    if (s.kind === 'unreadable') {
      body = <Row label="Pods evicted" id="drains-unreadable" note="Run history couldn't be read.">{DASH}</Row>;
    } else if (s.kind === 'none') {
      body = <p className={`py-2.5 text-[13px] ${INK}`}>No drain runs recorded.</p>;
    } else {
      const span = s.newest !== null || s.oldest !== null ? ` (newest ${day(s.newest)}, oldest ${day(s.oldest)})` : '';
      body = (
        <>
          <p className={`pt-2 text-[12px] ${FAINT}`} data-proof-drains-kept="">
            {`In the ${s.runsKept} ${plural(s.runsKept, 'run', 'runs')} XamOps kept${span}`}
            {s.historyStartsNote && s.oldest !== null ? `. History starts ${day(s.oldest)}.` : ''}
            {!s.historyStartsNote && s.oldest !== null ? '. Covers at least the last 7 days.' : ''}
          </p>
          <Row label="Pods evicted" id="drains-evicted">
            {`${s.podsEvicted} in ${s.liveRuns} live ${plural(s.liveRuns, 'run', 'runs')}`}
          </Row>
          <Row label="Aborted or refused" id="drains-aborted">{s.abortedOrRefused}</Row>
          {s.simulatedRuns > 0 && (
            <Row label="Simulated runs" id="drains-simulated">
              {`${s.simulatedRuns} simulated ${plural(s.simulatedRuns, 'run', 'runs')} evicted nothing.`}
            </Row>
          )}
          <Row label="Restarts caused to other pods" id="drains-restarts" note="Not measured for drains.">{DASH}</Row>
        </>
      );
    }
  }
  return (
    <div className="mt-3" data-proof-group="consolidation-runs">
      <h4 className={`text-[13px] font-semibold ${INK}`}>{CONSOLIDATION_LABEL}</h4>
      <p className={`text-[12px] ${FAINT}`}>{CONSOLIDATION_NOTE}</p>
      <dl className={`divide-y ${DIVIDE}`}>{body}</dl>
    </div>
  );
}

function failureLine(feed: ProofFeed): string | null {
  const r = feed.result;
  if (!r || r.kind === 'ok') return null;
  switch (r.kind) {
    case 'not-deployed': return 'This backend has no activity log.';
    case 'unauthenticated': return 'Your session has expired. Sign in again to see this.';
    default: return r.message;
  }
}

export function ProofOfNoHarmPanel({
  scope, feed, windowLabel, qosClasses, drains, onRetryDrains, windowStartMs,
}: ProofOfNoHarmPanelProps) {
  const [fallbackStart] = useState(() => Date.now() - 7 * DAY_MS);
  const loading = feed.loading && !feed.result;
  const ok = feed.result?.kind === 'ok';
  const s = ok ? summarizeProof(feed.entries) : null;
  const failure = failureLine(feed);
  const none = !s || s.changes === 0;

  const head = s ? headline(s) : null;
  const windowText = feed.hasOlder && scope === 'cluster'
    ? `the most recent ${feed.entries.length} log entries`
    : windowLabel;
  const qos = (qosClasses ?? []).filter((q) => q.qosClass);

  return (
    <Surface tier="grouped" title={PROOF_TITLE} aria-label={PROOF_TITLE}>
      <div data-testid="proof-of-no-harm" data-proof-scope={scope} aria-busy={loading || undefined} className="mt-2">
        {loading ? (
          <div className="space-y-2" aria-busy="true">
            {[0, 1, 2].map((i) => (
              <div key={i} className="skeleton h-5 rounded-[6px] dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" />
            ))}
          </div>
        ) : (
          <>
            {failure && (
              <div className="flex items-center gap-3 pb-2" role={feed.result?.kind === 'not-deployed' ? 'status' : 'alert'}>
                <span className={`text-[13px] ${INK}`}>{failure}</span>
                {feed.result?.kind !== 'not-deployed' && feed.result?.kind !== 'unauthenticated' && (
                  <button type="button" onClick={feed.reload} className={BUTTON}>Retry</button>
                )}
              </div>
            )}
            {head && (
              <div className="pb-1" data-proof-row="headline">
                <StatusDot tone={head.tone} label={head.label} detail={head.detail} />
              </div>
            )}
            <dl className={`divide-y ${DIVIDE}`}>
              <Row
                label="Pod restarts"
                id="restarts"
                note={none ? undefined : s!.podsChecked === 0 ? 'No pod proof recorded' : s!.podsUnrecorded > 0 ? `${s!.podsUnrecorded} ${plural(s!.podsUnrecorded, 'pod', 'pods')} not recorded` : undefined}
              >
                {none || s!.podsChecked === 0 ? DASH : `${s!.podsRestarted} across ${s!.podsChecked} ${plural(s!.podsChecked, 'pod', 'pods')}`}
              </Row>
              <Row
                label="Same pod kept"
                id="same-pod"
                note={!none && s!.samePodKnown > 0 && s!.pods - s!.samePodKnown > 0 ? `${s!.pods - s!.samePodKnown} not recorded` : undefined}
              >
                {none || s!.samePodKnown === 0 ? DASH : `${s!.samePod} of ${s!.samePodKnown} ${plural(s!.samePodKnown, 'pod', 'pods')}`}
              </Row>
              <Row label="Ready after the change" id="ready">
                {none || s!.readyKnown === 0 ? DASH : (
                  <>
                    {`${s!.ready} of ${s!.readyKnown} ${plural(s!.readyKnown, 'pod', 'pods')}`}
                    {s!.ready < s!.readyKnown && (
                      <span className="ml-3"><StatusDot tone="warn" label={`${s!.readyKnown - s!.ready} not ready`} /></span>
                    )}
                  </>
                )}
              </Row>
              <Row label="Put back" id="put-back">
                {none ? DASH : s!.putBackConfirmed === 0 && s!.putBackUnconfirmed === 0 ? 'None needed' : (
                  <span className="flex flex-col gap-0.5">
                    {s!.putBackConfirmed > 0 && (
                      <span>{`${s!.putBackConfirmed} ${plural(s!.putBackConfirmed, 'change', 'changes')} put back and confirmed`}</span>
                    )}
                    {s!.putBackUnconfirmed > 0 && (
                      <StatusDot tone="warn" label={`${s!.putBackUnconfirmed} put back, not confirmed`} />
                    )}
                  </span>
                )}
              </Row>
              <Row
                label="QoS class"
                id="qos"
                note={scope === 'workload' ? (qos.length > 0 ? "Current class. The log doesn't record it before a change." : undefined) : 'Shown per workload.'}
              >
                {scope === 'workload' && qos.length > 0
                  ? qos.map((q) => `${q.container}: ${q.qosClass}`).join(', ')
                  : DASH}
              </Row>
              <Row label="PDB disruptions allowed" id="pdb" note={PDB_NOTE}>{DASH}</Row>
              <Row label="Requests that succeeded" id="requests-succeeded" note={NOT_MEASURED_NOTE}>{DASH}</Row>
              <Row label="Test requests sent" id="test-requests" note={NOT_MEASURED_NOTE}>{DASH}</Row>
            </dl>

            {scope === 'cluster' && drains && (
              <DrainsGroup drains={drains} onRetry={onRetryDrains} windowStartMs={windowStartMs ?? fallbackStart} />
            )}

            <p className={`mt-3 text-[11px] ${FAINT}`} data-proof-footer="">
              {`Source: activity log, ${windowText}. Pod proof is recorded when XamOps resizes a pod in place.`}
              {s && s.changesWithoutProof > 0 && (
                ` ${s.changesWithoutProof} older ${plural(s.changesWithoutProof, 'change has', 'changes have')} no recorded pod proof, so ${plural(s.changesWithoutProof, 'it is', 'they are')} not counted as proved.`
              )}
            </p>
          </>
        )}
      </div>
    </Surface>
  );
}

/** The cluster-scope panel: reads the consolidation runs itself so the hook call stays unconditional. */
export function ClusterProofOfNoHarm({
  clusterName, accountId, region, feed, windowLabel,
}: { clusterName: string; accountId: string; region: string; feed: ProofFeed; windowLabel: string }) {
  const { runs, retry } = useConsolidationRuns(clusterName, accountId, region);
  const [windowStartMs] = useState(() => Date.now() - 7 * DAY_MS);
  return (
    <ProofOfNoHarmPanel
      scope="cluster"
      feed={feed}
      windowLabel={windowLabel}
      drains={runs}
      onRetryDrains={retry}
      windowStartMs={windowStartMs}
    />
  );
}

export default ProofOfNoHarmPanel;
