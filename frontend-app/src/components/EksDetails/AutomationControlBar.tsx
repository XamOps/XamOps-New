/**
 * The node-automation bar on the cluster page (Phase A design §6, §7): the state in words, an
 * Autonomous | Manual toggle, and Pause / Resume.
 *
 * The only writes are Pause (mode → disabled) and Resume (mode → the one this browser held before the
 * pause), each behind a confirm. Nothing here turns automation on: from an off cluster the Autonomous
 * segment is disabled with the reason and a link to the read-only node automation page. Manual is a
 * shell: it is never pressed and says so when activated. "Paused" appears only after the server
 * accepted the write, and "Acting" only when the agent said so.
 */
import { useId, useState } from 'react';
import { SWITCHING_LINE, UNKNOWN_EFFECTIVE_LINE, unavailableLine } from './sections/automation/pending/proposalCopy';
import { useNodeAutomationControl } from '../../hooks/useNodeAutomationControl';
import { canPersistPauseMarker } from '../../utils/k8sPauseMarker';
import { ConfirmDialog } from '../CloudK8s/shared/ConfirmDialog';
import { GatedAction } from '../CloudK8s/shared/GatedAction';
import { StatusDot } from '../CloudK8s/shared/status/StatusDot';
import type { Tone } from '../CloudK8s/shared/status/statusTones';
import { BUTTON, FAINT, FOCUS, INK, InPageLink, MUTED } from './sections/automation/automationUi';

/** One constant to change once a backend flag can pause the workload auto-apply loop too. */
export const PAUSE_LABEL = 'Pause node writes';
export const PAUSE_SCOPE_TEXT =
  'Pause covers node automation: evicting, draining, rebalancing, spot actions. Workload auto-apply is separate (Workloads).';
export const MANUAL_COMING_TEXT =
  "Manual approval isn't available yet. XamOps will recommend and wait for you to approve each action. "
  + 'Until then it either acts within the gate (Autonomous) or only recommends.';
export const MANUAL_CONFIRM_TEXT =
  'Manual mode: XamOps will propose each action and wait for your approval. The change takes effect when the automation agent next checks in, usually within a minute. '
  + 'Spot-interruption handling is paused while a cluster is in Manual.';
export const AUTONOMOUS_CONFIRM_TEXT = (waiting: number | null): string =>
  `${waiting === null ? 'Any waiting proposals' : waiting === 1 ? '1 waiting proposal' : `${waiting} waiting proposals`} will be marked stale. `
  + 'The cluster acts on its own again, within the gate, when the automation agent next checks in.';
export const OFF_NOTE = "Mode is disabled. This page can't turn it on; node automation is enabled outside the UI.";
export const NOT_ENABLED_NOTE = "Node automation isn't turned on. This page can't turn it on.";
export const PAUSE_SENT_WORD = 'Pause sent';
export const PAUSE_SENT_NOTE = 'The agent applies this on its next check-in and may still act until then.';
export const LAST_RUN_NOTE = 'The last recorded run was live, before the pause. Newer runs will update this.';
export const VIEW_NODE_AUTOMATION = 'View node automation';
export const PAUSE_DIALOG_TITLE = (cluster: string): string => `Pause node writes on ${cluster}?`;
export const RESUME_DIALOG_TITLE = (cluster: string): string => `Resume node automation on ${cluster}?`;

const MODE_WORD: Record<string, string> = { full: 'Full', scoped: 'Scoped' };

export interface AutomationControlBarProps {
  clusterName: string;
  accountId: string;
  /** `route.hrefFor('automation','workload-autoscaler')`. */
  workloadsHref: string;
  /** `route.hrefFor('automation','node-autoscaler')`. */
  nodeAutoscalerHref: string;
  /** `route.hrefFor('automation','pending-actions')`; the Manual queue. Optional so older callers still render. */
  pendingActionsHref?: string;
}

const primary = `rounded-[6px] bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-black dark:bg-[rgba(255,255,255,0.92)] dark:text-[rgba(0,0,0,0.88)] ${FOCUS} focus-visible:ring-offset-2`;
const segment = `px-3 py-1.5 text-[12px] font-semibold ${FOCUS}`;

export function AutomationControlBar({ clusterName, accountId, workloadsHref, nodeAutoscalerHref, pendingActionsHref }: AutomationControlBarProps) {
  const ctl = useNodeAutomationControl({ clusterName, accountId });
  const { view, capability, pending, error, marker } = ctl;
  const [dialog, setDialog] = useState<'pause' | 'resume' | 'manual' | 'autonomous' | null>(null);
  const [storageBlocked, setStorageBlocked] = useState(false);
  const [manualNote, setManualNote] = useState(false);
  const noteId = useId();
  const manualId = useId();
  const offReasonId = useId();

  if (view.kind === 'loading') {
    return (
      <section aria-label="Node automation control" data-testid="automation-control-bar" aria-busy="true">
        <div className="skeleton h-6 w-56 rounded-[6px] dark:bg-none dark:bg-[rgba(255,255,255,0.06)]" />
      </section>
    );
  }

  // ── The left side: state in words ──────────────────────────────────────────
  let tone: Tone = 'off';
  let word = 'Unknown';
  let note: string | null = null;
  let showNodeLink = false;
  let showPendingLink = false;
  const settling = view.stillActingAfterPause;
  const stale = view.lastRunPredatesPause;
  const pausedOn = marker ? new Date(marker.pausedAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : null;

  switch (view.kind) {
    case 'unknown':
      note = "Couldn't read this cluster's automation state.";
      break;
    case 'not-installed':
      word = 'Not installed';
      note = "The node automation agent isn't installed on this cluster.";
      showNodeLink = true;
      break;
    case 'not-enabled':
      word = 'Off';
      note = NOT_ENABLED_NOTE;
      showNodeLink = true;
      break;
    case 'off':
      word = 'Off';
      note = settling ? `${PAUSE_SENT_WORD}. ${PAUSE_SENT_NOTE}` : stale ? LAST_RUN_NOTE : OFF_NOTE;
      showNodeLink = true;
      break;
    case 'paused': {
      tone = 'warn';
      word = settling ? PAUSE_SENT_WORD : 'Paused';
      const resumeTo = MODE_WORD[view.priorMode ?? ''] ?? 'the previous mode';
      note = `Paused on ${pausedOn} from this browser. Resume returns it to ${resumeTo}.`;
      break;
    }
    case 'autonomous':
      if (view.storedApproval === 'MANUAL' || view.approval === 'switching') {
        // Manual mode (Phase B): the agent proposes and waits. The word follows the agent's own
        // confirmation (view.approval), the same tri-state as "Acting".
        if (view.approval === 'switching') {
          tone = 'warn';
          word = view.storedApproval === 'MANUAL' ? 'Switching to Manual' : 'Switching to Autonomous';
          note = view.storedApproval === 'MANUAL' ? SWITCHING_LINE : 'Waiting for the automation agent to confirm. It may still wait for approvals until it does.';
        } else if (view.approval === 'manual') {
          tone = 'info';
          word = 'Manual';
          note = 'XamOps proposes each action and waits for your approval.';
          showPendingLink = true;
        } else {
          word = 'Manual, not confirmed';
          note = UNKNOWN_EFFECTIVE_LINE;
          showPendingLink = true;
        }
      } else if (view.acting === 'acting') { tone = 'good'; word = 'Acting'; }
      else if (view.acting === 'ready-to-enable') {
        tone = 'warn'; word = 'Ready to enable';
        note = 'Configured but not acting in this environment (DRY_RUN or controller off).';
      } else if (view.acting === 'analysis-only') {
        word = 'Analysis only';
        note = 'Nothing is configured to act.';
      } else {
        word = 'Not confirmed';
        note = "Configured; the agent hasn't reported whether it acts.";
      }
      break;
    default:
      break;
  }

  // ── Toggle ──────────────────────────────────────────────────────────────────
  const manualPressed = view.kind === 'autonomous' && view.storedApproval === 'MANUAL';
  const autonomousPressed = view.kind === 'autonomous' && !manualPressed;
  const fromPaused = view.kind === 'paused';
  const autonomousDisabled = !autonomousPressed && !(fromPaused && capability.allowed) && !(manualPressed && capability.allowed);
  // Manual is live only when the server says it can be switched on (or the cluster already is Manual).
  const manualEnabled = view.kind === 'autonomous' && (view.manualAvailable || manualPressed);
  const manualBlocked = manualEnabled && !capability.allowed;
  const manualUnavailableText = view.manualReason || view.manualAvailable ? unavailableLine(view.manualReason) : MANUAL_COMING_TEXT;
  const autonomousReason = fromPaused ? undefined : view.kind === 'off' ? OFF_NOTE : note ?? undefined;

  const openPause = () => {
    setStorageBlocked(!canPersistPauseMarker());
    ctl.clearError();
    setDialog('pause');
  };
  const openResume = () => {
    ctl.clearError();
    setDialog('resume');
  };
  const onAutonomous = () => {
    if (autonomousPressed) return;
    if (fromPaused && capability.allowed) openResume();
    else if (manualPressed && capability.allowed) {
      ctl.clearError();
      setDialog('autonomous');
    }
  };
  const onManual = () => {
    if (!manualEnabled) {
      setManualNote(true);
      return;
    }
    if (manualPressed || !capability.allowed) return;
    ctl.clearError();
    setDialog('manual');
  };

  const modeWord = MODE_WORD[view.mode ?? ''] ?? 'the previous mode';
  const resumeWord = MODE_WORD[marker?.priorMode ?? ''] ?? 'the previous mode';
  const pausedAtLocal = marker ? new Date(marker.pausedAt).toLocaleString() : '';

  const errorLine = error ? (
    <p role="alert" className={`text-[12px] ${INK}`}>{error.message}</p>
  ) : null;

  return (
    <section aria-label="Node automation control" data-testid="automation-control-bar" data-control-kind={view.kind} className="space-y-1.5">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className={`text-[12px] font-semibold ${MUTED}`}>Node automation</span>
            <StatusDot tone={tone} label={word} />
          </div>
          {note && (
            <p id={noteId} className={`mt-0.5 max-w-[72ch] text-[12px] ${MUTED}`}>
              <span data-control-note="">{note}</span>
              {view.kind === 'paused' && settling && <span data-control-note="settling">{` ${PAUSE_SENT_NOTE}`}</span>}
              {view.kind === 'paused' && stale && <span data-control-note="stale">{` ${LAST_RUN_NOTE}`}</span>}
              {showNodeLink && (
                <>
                  {' '}
                  <InPageLink to={nodeAutoscalerHref}>{VIEW_NODE_AUTOMATION}</InPageLink>
                </>
              )}
              {showPendingLink && pendingActionsHref && (
                <>
                  {' '}
                  <InPageLink to={pendingActionsHref}>
                    {`Pending actions (${view.pendingProposals === null ? '—' : view.pendingProposals})`}
                  </InPageLink>
                </>
              )}
            </p>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div role="group" aria-label="Automation mode" className="inline-flex overflow-hidden rounded-[6px] border border-[rgba(0,0,0,0.12)] dark:border-[rgba(255,255,255,0.16)]">
            <button
              type="button"
              aria-pressed={autonomousPressed}
              aria-disabled={autonomousDisabled || undefined}
              aria-describedby={autonomousDisabled ? (autonomousReason ? noteId : undefined) : undefined}
              onClick={onAutonomous}
              title="Acts within the gate"
              className={`${segment} ${autonomousPressed ? 'bg-[rgba(0,0,0,0.88)] text-white dark:bg-[rgba(255,255,255,0.92)] dark:text-[rgba(0,0,0,0.88)]' : `bg-white ${INK} dark:bg-[#141414]`} ${autonomousDisabled ? 'cursor-not-allowed opacity-60' : ''}`}
            >
              Autonomous
            </button>
            <button
              type="button"
              aria-pressed={manualPressed}
              aria-disabled={!manualEnabled || manualBlocked || undefined}
              aria-describedby={manualEnabled ? (manualBlocked ? noteId : undefined) : manualId}
              onClick={onManual}
              title={manualEnabled ? 'Proposes each action and waits for your approval' : undefined}
              className={`${segment} border-l border-[rgba(0,0,0,0.12)] dark:border-[rgba(255,255,255,0.16)] ${manualPressed ? 'bg-[rgba(0,0,0,0.88)] text-white dark:bg-[rgba(255,255,255,0.92)] dark:text-[rgba(0,0,0,0.88)]' : manualEnabled ? `bg-white ${INK} dark:bg-[#141414]` : `bg-white ${MUTED} dark:bg-[#141414]`} ${!manualEnabled || manualBlocked ? 'cursor-not-allowed' : ''}`}
            >
              Manual{!manualEnabled && <span className={`ml-1 text-[11px] font-normal ${FAINT}`}>Coming</span>}
            </button>
            {!manualEnabled && <span id={manualId} className="sr-only">{manualUnavailableText}</span>}
          </div>

          {view.kind === 'autonomous' && (
            <GatedAction capability={capability} label={PAUSE_LABEL}>
              {(g) => (
                <button
                  type="button"
                  aria-disabled={g['aria-disabled']}
                  aria-describedby={g['aria-describedby']}
                  onClick={g.onActivate(openPause)}
                  className={g.blocked ? `${BUTTON} ${g.blockedClassName}` : primary}
                >
                  {PAUSE_LABEL}
                </button>
              )}
            </GatedAction>
          )}
          {view.kind === 'paused' && (
            <GatedAction capability={capability} label="Resume">
              {(g) => (
                <button
                  type="button"
                  aria-disabled={g['aria-disabled']}
                  aria-describedby={g['aria-describedby']}
                  onClick={g.onActivate(openResume)}
                  className={g.blocked ? `${BUTTON} ${g.blockedClassName}` : primary}
                >
                  Resume
                </button>
              )}
            </GatedAction>
          )}
          {view.kind === 'off' && (
            <span className="inline-flex items-center gap-2">
              <button
                type="button"
                aria-disabled="true"
                aria-describedby={offReasonId}
                className={`${BUTTON} cursor-not-allowed opacity-60`}
              >
                {PAUSE_LABEL}
              </button>
              <span id={offReasonId} className={`text-[12px] ${FAINT}`}>Already off</span>
            </span>
          )}
        </div>
      </div>

      <p className={`text-[12px] ${FAINT}`} data-testid="pause-scope">
        {'Pause covers node automation: evicting, draining, rebalancing, spot actions. Workload auto-apply is separate ('}
        <InPageLink to={workloadsHref}>Workloads</InPageLink>
        {').'}
      </p>

      {manualNote && (
        <div role="status" className={`flex items-center gap-3 text-[12px] ${INK}`} data-testid="manual-note">
          <span>{manualUnavailableText}</span>
          <button type="button" onClick={() => setManualNote(false)} className={BUTTON}>Dismiss</button>
        </div>
      )}

      {!dialog && errorLine}

      <ConfirmDialog
        isOpen={dialog === 'pause'}
        title={PAUSE_DIALOG_TITLE(clusterName)}
        confirmLabel={PAUSE_LABEL}
        pending={pending === 'pause'}
        onCancel={() => {
          if (pending) return;
          setDialog(null);
          ctl.clearError();
        }}
        onConfirm={() => { void ctl.pause().then((ok) => { if (ok) setDialog(null); }); }}
      >
        <p>
          {"Sets this cluster's node automation mode to disabled. The automation agent stops evicting, draining, rebalancing and changing nodes the next time it checks in. "
            + 'Workload auto-apply and changes you apply yourself are not paused. '
            + `Resume puts the mode back to ${modeWord}. `
            + 'Resume works from this browser only: it remembers the previous mode here, for 7 days. '
            + 'If that record is lost (cleared storage, another browser or user, or after 7 days) this page cannot restore the mode, and node automation must be re-enabled outside the UI.'}
        </p>
        {storageBlocked && (
          <p className="mt-2">{"This browser can't keep a record, so Resume will only work until you reload this page."}</p>
        )}
        {dialog === 'pause' && errorLine && <div className="mt-2">{errorLine}</div>}
      </ConfirmDialog>

      <ConfirmDialog
        isOpen={dialog === 'resume'}
        title={RESUME_DIALOG_TITLE(clusterName)}
        confirmLabel="Resume"
        pending={pending === 'resume'}
        onCancel={() => {
          if (pending) return;
          setDialog(null);
          ctl.clearError();
        }}
        onConfirm={() => { void ctl.resume().then((ok) => { if (ok) setDialog(null); }); }}
      >
        <p>
          {`You paused this on ${pausedAtLocal} from this browser. Resume sets the mode to ${resumeWord}. `
            + 'The agent still acts only where its own switches, readiness and DRY_RUN allow.'}
        </p>
        {dialog === 'resume' && errorLine && <div className="mt-2">{errorLine}</div>}
      </ConfirmDialog>
      <ConfirmDialog
        isOpen={dialog === 'manual'}
        title={`Switch ${clusterName} to Manual?`}
        confirmLabel="Switch to Manual"
        pending={ctl.approvalPending}
        onCancel={() => {
          if (ctl.approvalPending) return;
          setDialog(null);
          ctl.clearError();
        }}
        onConfirm={() => { void ctl.switchApproval('MANUAL').then((ok) => { if (ok) setDialog(null); }); }}
      >
        <p>{MANUAL_CONFIRM_TEXT}</p>
        {dialog === 'manual' && errorLine && <div className="mt-2">{errorLine}</div>}
      </ConfirmDialog>
      <ConfirmDialog
        isOpen={dialog === 'autonomous'}
        title={`Switch ${clusterName} back to Autonomous?`}
        confirmLabel="Switch to Autonomous"
        pending={ctl.approvalPending}
        onCancel={() => {
          if (ctl.approvalPending) return;
          setDialog(null);
          ctl.clearError();
        }}
        onConfirm={() => { void ctl.switchApproval('AUTONOMOUS').then((ok) => { if (ok) setDialog(null); }); }}
      >
        <p>{AUTONOMOUS_CONFIRM_TEXT(view.pendingProposals)}</p>
        {dialog === 'autonomous' && errorLine && <div className="mt-2">{errorLine}</div>}
      </ConfirmDialog>
    </section>
  );
}

export default AutomationControlBar;
