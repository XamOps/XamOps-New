/**
 * Automation › Pending actions (Phase B design §3.10): the Manual mode queue. In Manual the automation
 * agent and the workload engine propose each action and wait; nothing here runs until a person
 * approves ONE proposal. Approve always sends the version and fingerprint of the row as it was when
 * the confirm line opened, so a row that changed underneath is refused by the server, never approved
 * by accident. Read-only users see the rows with the gate reason in place of Approve and Reject, and
 * no request is sent.
 *
 * v3 style: a dot and a word for status (no pills), de-boxed sections separated by rules, an em dash
 * for anything unavailable.
 */
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { isK8sApiError, approveProposal, rejectProposal } from '../../../../../api/k8sAutomationApi';
import useProposals from '../../../../../hooks/useProposals';
import { useK8sCapabilities } from '../../../../../hooks/useK8sCapabilities';
import type { ProposalDto, ProposalListDto } from '../../../../../types/automationProposals';
import type { Capability } from '../../../../../types/k8sAutomation';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { StatusDot } from '../../../../CloudK8s/shared/status/StatusDot';
import { PageTemplate } from '../../../../CloudK8s/shared/PageTemplate';
import { MetricStrip } from '../../../../CloudK8s/shared/MetricStrip';
import { ReasonText } from '../../../../CloudK8s/shared/ReasonText';
import { BUTTON, CONTROL, FAINT, FOCUS, INK, MUTED, ReadFailureCard, RULE } from '../automationUi';
import {
  DASH,
  MODE_OFF_LINE,
  REFRESH_ON_CODES,
  SWITCHING_LINE,
  UNKNOWN_EFFECTIVE_LINE,
  actionWord,
  errorLine,
  outcomeLine,
  refusalLines,
  statusView,
  unavailableLine,
} from './proposalCopy';
import {
  approvableFromText,
  consequenceText,
  countdownText,
  groupProposals,
  isExpired,
  metricCells,
  notYetApprovable,
  targetText,
  whyText,
} from './proposalDerivations';

export interface PendingActionsPageProps {
  clusterName: string;
  accountId: string;
  region: string;
}

const INTRO =
  'In Manual mode XamOps proposes each action and waits. Nothing runs until you approve that one action.';
const TH = 'px-4 py-2 font-semibold';
const TD = 'px-4 py-2.5';
const TBODY = 'divide-y divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]';
const TICK_MS = 30_000;
const primary = `rounded-[6px] bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-black dark:bg-[rgba(255,255,255,0.92)] dark:text-[rgba(0,0,0,0.88)] ${FOCUS} focus-visible:ring-offset-2`;

function Section({ title, id, children }: { title: string; id: string; children: ReactNode }) {
  const titleId = useId();
  return (
    <section aria-labelledby={titleId} className={`border-t ${RULE} pt-4`} data-pending-section={id}>
      <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>{title}</h3>
      {children}
    </section>
  );
}

function Table({ caption, headers, children }: { caption: string; headers: string[]; children: ReactNode }) {
  return (
    <div className="mt-2 overflow-x-auto">
      <table className="w-full border-collapse text-left text-[12px]">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className={`border-b ${RULE} text-[11px] ${MUTED}`}>
            {headers.map((h) => <th key={h} scope="col" className={TH}>{h}</th>)}
          </tr>
        </thead>
        <tbody className={TBODY}>{children}</tbody>
      </table>
    </div>
  );
}

const asRecord = (v: unknown): Record<string, unknown> | null =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
const asList = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const text = (v: unknown): string => (typeof v === 'string' || typeof v === 'number' ? String(v) : '');

const GUARD_WORDS: Record<string, string> = {
  respectPodDisruptionBudgets: 'Respects PodDisruptionBudgets',
  protectSingleReplicas: 'Protects single-replica workloads',
  protectStatefulSets: 'Protects StatefulSets',
  protectCriticalNamespaces: 'Protects critical namespaces',
};

/** What the agent or engine checked: PDBs, guards, stateful verdicts, planned moves, QoS, gate. */
export function EvidenceBody({ p }: { p: ProposalDto }) {
  const evidence = asRecord(p.evidence);
  const fit = asRecord(evidence?.fit);
  const pdbs = asList(fit?.pdbChecks).map(asRecord).filter((r): r is Record<string, unknown> => r !== null);
  const moves = asList(fit?.moves).map(asRecord).filter((r): r is Record<string, unknown> => r !== null);
  const stateful = asList(evidence?.stateful);
  const guards = Object.entries(asRecord(evidence?.guards) ?? {}).filter(([, on]) => on === true);
  const qos = asRecord(evidence?.qos);
  return (
    <div className={`mt-2 space-y-2 text-[12px] ${MUTED}`} data-evidence="">
      <p>
        {p.verdict === 'ALLOWED' ? 'Gate: allowed' : 'Gate: refused'}
        {p.dryRun ? ' · Dry run: simulates, changes nothing' : ' · Live'}
      </p>
      {pdbs.length > 0 && (
        <table className="text-left">
          <caption className="sr-only">PodDisruptionBudget checks</caption>
          <thead><tr><th scope="col" className="pr-4 font-semibold">PodDisruptionBudget</th><th scope="col" className="pr-4 font-semibold">Disruptions allowed</th><th scope="col" className="font-semibold">Result</th></tr></thead>
          <tbody>
            {pdbs.map((r, i) => (
              <tr key={`${text(r.pdb)}-${i}`}>
                <th scope="row" className="pr-4 font-normal">{text(r.pdb) || DASH}</th>
                <td className="pr-4">{typeof r.disruptionsAllowed === 'number' ? r.disruptionsAllowed : DASH}</td>
                <td>{r.ok === true ? 'Passes' : r.ok === false ? 'Blocks' : DASH}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {guards.length > 0 && <p>{`Guards in force: ${guards.map(([k]) => GUARD_WORDS[k] ?? k).join(', ')}`}</p>}
      {stateful.length > 0 && (
        <ul className="list-disc pl-4">
          {stateful.map((s, i) => {
            const r = asRecord(s);
            const line = r
              ? ['pod', 'workload', 'class', 'code', 'reason'].map((k) => text(r[k])).filter(Boolean).join(' · ')
              : text(s);
            return <li key={i}>{`Stateful check: ${line || DASH}`}</li>;
          })}
        </ul>
      )}
      {moves.length > 0 && (
        <ul className="list-disc pl-4">
          {moves.map((m, i) => <li key={i}>{`${text(m.pod)} moves from ${text(m.from)} to ${text(m.to)}`}</li>)}
        </ul>
      )}
      {qos && <p>{`QoS before ${text(qos.before) || DASH}, after ${text(qos.after) || DASH}`}</p>}
      {p.refusalCodes.length > 0 && <p>{`Codes: ${p.refusalCodes.join(', ')}`}</p>}
    </div>
  );
}

function Safety({ p }: { p: ProposalDto }) {
  const refused = p.verdict === 'REFUSED';
  return (
    <details>
      <summary className={`cursor-pointer list-none ${INK}`}>
        <StatusDot tone={refused ? 'warn' : 'good'} label={refused ? 'Refused' : 'Passes all checks'} />
        <span className={`ml-2 underline underline-offset-2 ${MUTED}`}>Evidence</span>
      </summary>
      <EvidenceBody p={p} />
    </details>
  );
}

type Confirming = { id: string; kind: 'approve' | 'reject'; version: number; fingerprint: string };

interface RowProps {
  p: ProposalDto;
  now: number;
  capability: Capability;
  confirming: Confirming | null;
  busy: boolean;
  message: string | undefined;
  onOpen: (p: ProposalDto, kind: 'approve' | 'reject') => void;
  onCancel: () => void;
  onSubmit: (note: string) => void;
}

function WaitingRow({ p, now, capability, confirming, busy, message, onOpen, onCancel, onSubmit }: RowProps) {
  const [note, setNote] = useState('');
  const noteRef = useRef<HTMLInputElement>(null);
  const open = confirming && confirming.id === p.id ? confirming : null;
  useEffect(() => { if (open) noteRef.current?.focus(); }, [open]);
  const expired = isExpired(p, now);
  const waitDwell = notYetApprovable(p, now);
  const target = targetText(p);
  let controls: ReactNode;
  if (!capability.allowed) {
    controls = (
      <div data-readonly="" className={`text-[12px] ${MUTED}`}>
        <span>You can view this action but not approve it.</span>
        <ReasonText id={`${p.id}-reason`} capability={capability} label="Approve" placement="inline" />
      </div>
    );
  } else if (expired) {
    controls = <span className={`text-[12px] ${MUTED}`}>Expired</span>;
  } else if (waitDwell) {
    controls = (
      <span className={`text-[12px] ${MUTED}`} data-dwell="">
        {`Can be approved from ${approvableFromText(p)}: waiting out the consolidation dwell`}
      </span>
    );
  } else {
    controls = (
      <div className="flex items-center gap-2">
        {p.dryRun && <span className={`text-[11px] ${FAINT}`}>Dry run</span>}
        <button type="button" className={primary} onClick={() => onOpen(p, 'approve')} aria-label={`${p.dryRun ? 'Approve simulation' : 'Approve'} ${actionWord(p.actionType, p.operation)} ${target}`}>
          {p.dryRun ? 'Approve simulation' : 'Approve'}
        </button>
        <button type="button" className={BUTTON} onClick={() => onOpen(p, 'reject')} aria-label={`Reject ${actionWord(p.actionType, p.operation)} ${target}`}>
          Reject
        </button>
      </div>
    );
  }
  return (
    <>
      <tr className="align-top" data-proposal={p.id} data-proposal-status={p.status}>
        <td className={`${TD} font-semibold ${INK}`}>{actionWord(p.actionType, p.operation)}</td>
        <td className={`${TD} ${INK}`} title={p.targetRef}>{target}</td>
        <td className={`${TD} ${INK}`}>{whyText(p)}</td>
        <td className={TD}><Safety p={p} /></td>
        <td className={`${TD} ${INK}`}>
          {p.expiresAt ? <time dateTime={p.expiresAt}>{countdownText(p.expiresAt, now)}</time> : DASH}
        </td>
        <td className={TD}>
          {controls}
          {message && <p role="status" className={`mt-1 text-[12px] ${INK}`} data-row-message="">{message}</p>}
        </td>
      </tr>
      {open && (
        <tr data-confirm={open.kind}>
          <td colSpan={6} className={`${TD} ${INK}`}>
            <form
              className="flex flex-col gap-2"
              onSubmit={(e) => { e.preventDefault(); if (!busy) { onSubmit(note); setNote(''); } }}
              onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); onCancel(); } }}
            >
              <p className="text-[12px]" data-consequence="">
                {open.kind === 'approve' ? consequenceText(p) : `Rejects ${actionWord(p.actionType, p.operation).toLowerCase()} on ${target}. Nothing runs.`}
              </p>
              <div className="flex flex-wrap items-center gap-2">
                <label className="sr-only" htmlFor={`${p.id}-note`}>Note (optional)</label>
                <input
                  id={`${p.id}-note`}
                  ref={noteRef}
                  type="text"
                  value={note}
                  maxLength={500}
                  placeholder="Note (optional)"
                  onChange={(e) => setNote(e.target.value)}
                  className={`${CONTROL} w-72`}
                />
                <button type="submit" aria-disabled={busy || undefined} className={primary}>
                  {busy ? 'Working…' : open.kind === 'approve' ? (p.dryRun ? 'Confirm simulation' : 'Confirm approve') : 'Confirm reject'}
                </button>
                <button type="button" onClick={onCancel} className={BUTTON}>Cancel</button>
              </div>
            </form>
          </td>
        </tr>
      )}
    </>
  );
}

function Empty({ list }: { list: ProposalListDto }) {
  const seen = list.lastAgentCheckIn ? new Date(list.lastAgentCheckIn).toLocaleString() : null;
  return (
    <div data-pending-empty="" className={`text-[12px] ${MUTED}`}>
      <p>
        {`No actions are waiting. XamOps checks about every minute. Last agent check-in ${seen ?? DASH}. `
          + 'Manual proposes only the actions your node-automation settings enable; to see proposals for an action, enable it in Node autoscaler.'}
      </p>
      {list.manual && !list.manual.workloadProposals && <p className="mt-1">{"Workload proposals aren't enabled in this environment."}</p>}
      {list.effective === 'unknown' && seen && (
        <p className="mt-1">{`The automation agent hasn't reported since ${seen}. Proposals appear when it does.`}</p>
      )}
    </div>
  );
}

export function PendingActionsPage({ clusterName, accountId, region }: PendingActionsPageProps) {
  const { read, retry, refresh } = useProposals(clusterName, accountId, region);
  const caps = useK8sCapabilities();
  const nodeCap = caps.can('proposals.approve.node', { cluster: clusterName });
  const workloadCap = caps.can('proposals.approve.workload', { cluster: clusterName });
  const [now, setNow] = useState(() => Date.now());
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [busy, setBusy] = useState(false);
  const [messages, setMessages] = useState<Record<string, string>>({});
  const busyRef = useRef(false);

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(id);
  }, []);

  const onOpen = useCallback((p: ProposalDto, kind: 'approve' | 'reject') => {
    setMessages((m) => { const { [p.id]: _drop, ...rest } = m; return rest; });
    setConfirming({ id: p.id, kind, version: p.version, fingerprint: p.planFingerprint });
  }, []);
  const onCancel = useCallback(() => setConfirming(null), []);

  const onSubmit = useCallback(async (note: string) => {
    if (!confirming || busyRef.current) return;
    const { id, kind, version, fingerprint } = confirming;
    busyRef.current = true;
    setBusy(true);
    const target = { cluster: clusterName, accountId, region };
    const trimmed = note.trim();
    try {
      if (kind === 'approve') {
        await approveProposal(target, id, { expectedVersion: version, planFingerprint: fingerprint, ...(trimmed ? { note: trimmed } : {}) });
        setMessages((m) => ({ ...m, [id]: 'Approved. Waiting for the automation agent.' }));
      } else {
        await rejectProposal(target, id, { expectedVersion: version, ...(trimmed ? { note: trimmed } : {}) });
        setMessages((m) => ({ ...m, [id]: 'Rejected.' }));
      }
      setConfirming(null);
      refresh();
    } catch (err) {
      const reason = isK8sApiError(err) ? err.reason : null;
      const fallback = err instanceof Error && err.message ? err.message : "XamOps couldn't complete that.";
      setMessages((m) => ({ ...m, [id]: errorLine(reason, fallback) }));
      setConfirming(null);
      caps.onRefusal(err);
      if (reason === null || REFRESH_ON_CODES.has(reason)) refresh();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }, [confirming, clusterName, accountId, region, refresh, caps]);

  const list = read.kind === 'ok' ? read.data : null;
  const hero = (
    <div className="flex flex-col gap-4">
      <FeatureMark feature="node" title="Pending actions" />
      <div data-pending-kpis="">
        <MetricStrip cells={metricCells(list, now)} heroLead loading={read.kind === 'loading'} aria-label="Pending actions summary" />
      </div>
    </div>
  );

  let body: ReactNode;
  if (read.kind === 'loading') {
    body = <p className={`text-[12px] ${MUTED}`} aria-busy="true">Reading pending actions…</p>;
  } else if (read.kind === 'error') {
    body = <ReadFailureCard failure={{ kind: 'error', message: read.message }} onRetry={retry} title="Couldn't read pending actions" />;
  } else if (read.data.state === 'UNREADABLE') {
    body = (
      <ReadFailureCard
        failure={{ kind: 'error', message: "The stored proposals couldn't be read, so none are shown." }}
        onRetry={retry}
        title="Couldn't read pending actions"
      />
    );
  } else if (read.data.approvalMode !== 'MANUAL') {
    const line = read.data.manual && !read.data.manual.available ? unavailableLine(read.data.manual.reason) : MODE_OFF_LINE;
    body = <p className={`text-[12px] ${INK}`} data-manual-off="">{line}</p>;
  } else {
    const data = read.data;
    const groups = groupProposals(data.items);
    const capFor = (p: ProposalDto) => (p.actionType === 'WORKLOAD_RESIZE' ? workloadCap : nodeCap);
    body = (
      <>
        {data.effective === 'switching' && <p role="status" className={`text-[12px] ${INK}`} data-effective="switching">{`${SWITCHING_LINE} Pause node writes in the Automation mode control above if it must stop sooner.`}</p>}
        {data.effective === 'unknown' && <p role="status" className={`text-[12px] ${INK}`} data-effective="unknown">{UNKNOWN_EFFECTIVE_LINE}</p>}
        <Section title="Waiting for approval" id="waiting">
          {groups.waiting.length === 0 ? (
            <Empty list={data} />
          ) : (
            <Table caption="Actions waiting for approval" headers={['Action', 'Target', 'Why', 'Safety', 'Expires', '']}>
              {groups.waiting.map((p) => (
                <WaitingRow
                  key={p.id}
                  p={p}
                  now={now}
                  capability={capFor(p)}
                  confirming={confirming}
                  busy={busy}
                  message={messages[p.id]}
                  onOpen={onOpen}
                  onCancel={onCancel}
                  onSubmit={(note) => { void onSubmit(note); }}
                />
              ))}
            </Table>
          )}
        </Section>
        {groups.refused.length > 0 && (
          <Section title="Not proposed (refused)" id="refused">
            <Table caption="Actions XamOps refused to propose" headers={['Action', 'Target', 'Why not', 'Evidence']}>
              {groups.refused.map((p) => (
                <tr key={p.id} className="align-top" data-proposal={p.id} data-proposal-status={p.status}>
                  <td className={`${TD} font-semibold ${INK}`}>{actionWord(p.actionType, p.operation)}</td>
                  <td className={`${TD} ${INK}`} title={p.targetRef}>{targetText(p)}</td>
                  <td className={TD}>
                    <StatusDot tone="warn" label="Refused" detail={p.refusalStage === 'EXECUTION' ? 'when it was about to run' : undefined} />
                    <ul className={`mt-0.5 ${INK}`}>
                      {refusalLines(p).map((l) => <li key={l}>{l}</li>)}
                    </ul>
                  </td>
                  <td className={TD}>
                    <details>
                      <summary className={`cursor-pointer ${MUTED}`}>Evidence</summary>
                      <EvidenceBody p={p} />
                    </details>
                  </td>
                </tr>
              ))}
            </Table>
          </Section>
        )}
        {groups.inProgress.length > 0 && (
          <Section title="In progress" id="in-progress">
            <Table caption="Approved actions in progress" headers={['Action', 'Target', 'Status', 'Approved by']}>
              {groups.inProgress.map((p) => (
                <tr key={p.id} data-proposal={p.id} data-proposal-status={p.status}>
                  <td className={`${TD} font-semibold ${INK}`}>{actionWord(p.actionType, p.operation)}</td>
                  <td className={`${TD} ${INK}`} title={p.targetRef}>{targetText(p)}</td>
                  <td className={TD}><StatusDot tone={statusView(p).tone} label={statusView(p).word} /></td>
                  <td className={`${TD} ${INK}`}>{p.decidedByName ?? DASH}</td>
                </tr>
              ))}
            </Table>
          </Section>
        )}
        {groups.history.length > 0 && (
          <Section title="History" id="history">
            <Table caption="Decided and finished actions" headers={['Action', 'Target', 'Outcome', 'Decided by', 'When']}>
              {groups.history.map((p) => (
                <tr key={p.id} className="align-top" data-proposal={p.id} data-proposal-status={p.status}>
                  <td className={`${TD} font-semibold ${INK}`}>{actionWord(p.actionType, p.operation)}</td>
                  <td className={`${TD} ${INK}`} title={p.targetRef}>{targetText(p)}</td>
                  <td className={TD}>
                    <StatusDot tone={statusView(p).tone} label={statusView(p).word} />
                    {(p.status === 'FAILED' || p.status === 'REJECTED' || p.status === 'EXPIRED') && (
                      <span className={`block text-[11px] ${MUTED}`}>{outcomeLine(p)}</span>
                    )}
                  </td>
                  <td className={`${TD} ${INK}`}>{p.decidedByName ?? DASH}</td>
                  <td className={`${TD} ${INK}`}>
                    {p.decidedAt ? <time dateTime={p.decidedAt}>{new Date(p.decidedAt).toLocaleString()}</time> : DASH}
                  </td>
                </tr>
              ))}
            </Table>
          </Section>
        )}
        {data.nextCursor && (
          <p className={`text-[11px] ${FAINT}`} data-capped="">Showing the newest 200 actions. Older ones are not listed here.</p>
        )}
      </>
    );
  }

  return (
    <div data-pending-page="" data-k8s-kit="">
      <PageTemplate title="Pending actions" width="table" intro={INTRO} hero={hero}>
        {body}
      </PageTemplate>
    </div>
  );
}

export default PendingActionsPage;
