/**
 * What the cluster page's automation bar shows, derived from capabilities, the pause marker and a
 * one-shot override of the mode this browser just wrote (Phase A design §5.1).
 *
 * Pure: the clock is injected. Honest by construction: "Acting" needs the agent's own word
 * (`effectiveActing`), a snapshot taken while the cluster was paused never claims anything about the
 * resumed state, and nothing here can enable automation. The view kind consults only the node
 * automation agent; whether Pause or Resume may be pressed always comes from the capability gate.
 */
import type { CapabilitiesStatus } from '../hooks/useK8sCapabilities';
import type { AutomationScope, CapabilitiesCluster } from '../types/k8sAutomation';
import type { PauseMarker } from './k8sPauseMarker';

export type ControlKind = 'loading' | 'unknown' | 'not-installed' | 'not-enabled' | 'off' | 'paused' | 'autonomous';
export type ActingWord = 'acting' | 'ready-to-enable' | 'analysis-only' | 'not-confirmed';

export interface ControlView {
  kind: ControlKind;
  /** Effective mode: the confirmed override, else the capabilities snapshot's. */
  mode: AutomationScope | null;
  /** Only when kind is 'paused'. */
  priorMode: 'full' | 'scoped' | null;
  /** Only when kind is 'autonomous'. */
  acting: ActingWord | null;
  /** Mode disabled, the newest run still says ACTING, and the pause is younger than PAUSE_SETTLE_MS. */
  stillActingAfterPause: boolean;
  /** Mode disabled, the newest run still says ACTING, and the settle window has passed. */
  lastRunPredatesPause: boolean;
}

/**
 * How long after a pause the agent may still act on its previous policy. The agent re-reads its policy
 * every 60 s by default; 10 minutes is ten polls. A stated choice, not a measured one; it only changes
 * wording, never behaviour.
 */
export const PAUSE_SETTLE_MS = 10 * 60 * 1000;

export interface ControlInput {
  status: CapabilitiesStatus;
  cluster: CapabilitiesCluster | null;
  marker: PauseMarker | null;
  override: AutomationScope | null;
  /** Date.now() of this hook's last successful mode PUT; null when none. */
  lastWriteAt: number | null;
  now: number;
}

const view = (kind: ControlKind, extra: Partial<ControlView> = {}): ControlView => ({
  kind, mode: null, priorMode: null, acting: null, stillActingAfterPause: false, lastRunPredatesPause: false, ...extra,
});

const isMode = (m: unknown): m is AutomationScope => m === 'full' || m === 'scoped' || m === 'disabled';

function actingWord(cluster: CapabilitiesCluster | null): ActingWord {
  const derived = cluster?.nodeAutomation?.derivedState;
  // Anything but AUTOMATED / ANALYSIS (OFF right after a resume, UNKNOWN, NOT_INSTALLED, absent) is a
  // snapshot that says nothing about the mode now in force.
  if (derived !== 'AUTOMATED' && derived !== 'ANALYSIS') return 'not-confirmed';
  if (derived === 'ANALYSIS') return 'analysis-only';
  const acting = cluster?.nodeAutomation?.effectiveActing;
  if (acting === 'ACTING') return 'acting';
  if (acting === 'NOT_ACTING') return 'ready-to-enable';
  return 'not-confirmed';
}

/** Whether the pause is still settling, and whether the snapshot's "ACTING" predates it. */
function pauseWording(
  cluster: CapabilitiesCluster | null, marker: PauseMarker | null, lastWriteAt: number | null, now: number,
): Pick<ControlView, 'stillActingAfterPause' | 'lastRunPredatesPause'> {
  const none = { stillActingAfterPause: false, lastRunPredatesPause: false };
  if (cluster?.nodeAutomation?.effectiveActing !== 'ACTING') return none;
  const markerAt = marker ? Date.parse(marker.pausedAt) : Number.NaN;
  const clock = !Number.isNaN(markerAt) ? markerAt : lastWriteAt;
  if (clock === null) return none;
  const inside = now - clock < PAUSE_SETTLE_MS;
  return { stillActingAfterPause: inside, lastRunPredatesPause: !inside };
}

export function deriveControlView(input: ControlInput): ControlView {
  const { status, cluster, marker, override, lastWriteAt, now } = input;

  // 1
  if (status === 'loading' && override === null) return view('loading');

  // 2: a confirmed PUT outranks a failed read; nothing is known about the agent.
  if (override !== null && cluster === null) {
    if (override === 'disabled') {
      return marker
        ? view('paused', { mode: override, priorMode: marker.priorMode, ...pauseWording(null, marker, lastWriteAt, now) })
        : view('off', { mode: override });
    }
    return view('autonomous', { mode: override, acting: 'not-confirmed' });
  }

  // 3
  if (cluster === null) return view('unknown');

  // 4
  if (cluster.registered === false) return view('not-installed');

  // 5: null-safe, fail closed. Only the node automation agent is consulted here.
  if (
    cluster.nodeAutomation?.derivedState === 'NOT_INSTALLED'
    || !cluster.automationAgent
    || cluster.automationAgent.status === 'NOT_INSTALLED'
  ) return view('not-installed');

  // 6: an accepted mode write does not make a not-enabled cluster enabled.
  if (cluster.nodeAutomation?.enabled === false) return view('not-enabled');

  // 7
  const eff = override ?? cluster.nodeAutomation?.mode ?? null;
  if (!isMode(eff)) return view('unknown');

  // 8, 9
  if (eff === 'disabled') {
    const wording = pauseWording(cluster, marker, lastWriteAt, now);
    return marker
      ? view('paused', { mode: eff, priorMode: marker.priorMode, ...wording })
      : view('off', { mode: eff, ...wording });
  }

  // 10
  return view('autonomous', { mode: eff, acting: actingWord(cluster) });
}
