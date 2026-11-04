/**
 * Pause and Resume for one cluster's node automation (Phase A design §5.3).
 *
 * The only writes are `PUT …/mode` with `disabled` (Pause) and with the mode this browser held before
 * pausing (Resume). There is deliberately no "go autonomous" mutation: nothing here can turn
 * automation on from `disabled` except by returning to a mode the user themselves held.
 *
 * "Paused" is only ever shown after the server accepted the write; a failure leaves every piece of
 * state untouched and surfaces the error.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { isK8sApiError, setAutomationScope } from '../api/k8sAutomationApi';
import type { AutomationScope, Capability } from '../types/k8sAutomation';
import { deriveControlView, type ControlView } from '../utils/k8sAutomationControl';
import {
  clearPauseMarker,
  readPauseMarker,
  writePauseMarker,
  type PauseMarker,
} from '../utils/k8sPauseMarker';
import { useK8sCapabilities } from './useK8sCapabilities';

export const STATE_CHANGED_MESSAGE = "The cluster's state changed. Review it and try again.";
/** How often the bar re-checks the clock while it says "Pause sent". */
const TICK_MS = 30_000;

export interface ControlError {
  message: string;
  code: string | null;
}

export interface NodeAutomationControl {
  view: ControlView;
  capability: Capability;
  pending: 'pause' | 'resume' | null;
  error: ControlError | null;
  /** The held prior-mode record, for the dialogs' wording. Null when there is none. */
  marker: PauseMarker | null;
  clearError: () => void;
  pause: () => Promise<boolean>;
  resume: () => Promise<boolean>;
}

function toControlError(err: unknown): ControlError {
  if (isK8sApiError(err)) return { message: err.message, code: err.code };
  return { message: err instanceof Error && err.message ? err.message : "XamOps couldn't complete that.", code: null };
}

export function useNodeAutomationControl({ clusterName, accountId }: { clusterName: string; accountId: string }): NodeAutomationControl {
  const caps = useK8sCapabilities();
  const cluster = caps.forCluster(clusterName);
  const capability = caps.can('automation.scope', { cluster: clusterName });

  const [marker, setMarker] = useState<PauseMarker | null>(() => readPauseMarker({ accountId, clusterName }));
  const [override, setOverride] = useState<AutomationScope | null>(null);
  const [lastWriteAt, setLastWriteAt] = useState<number | null>(null);
  const [pending, setPending] = useState<'pause' | 'resume' | null>(null);
  const [error, setError] = useState<ControlError | null>(null);
  const [, setTick] = useState(0);

  // The override and marker belong to one cluster on one account: start over when either changes.
  const scopeKey = `${accountId}|${clusterName}`;
  const lastScopeKey = useRef(scopeKey);
  useEffect(() => {
    if (lastScopeKey.current === scopeKey) return;
    lastScopeKey.current = scopeKey;
    setMarker(readPauseMarker({ accountId, clusterName }));
    setOverride(null);
    setLastWriteAt(null);
    setError(null);
  }, [scopeKey, accountId, clusterName]);

  const view = deriveControlView({
    status: caps.status, cluster, marker, override, lastWriteAt, now: Date.now(),
  });

  // The server's first answer fetched after our write is the truth, whatever mode it reports.
  // Strict: a snapshot stamped the same millisecond as the write is not newer.
  const fetchedAt = caps.fetchedAt;
  const snapshotIsNewer = fetchedAt !== null && (lastWriteAt === null || fetchedAt > lastWriteAt);
  useEffect(() => {
    if (override !== null && snapshotIsNewer && lastWriteAt !== null) setOverride(null);
  }, [override, snapshotIsNewer, lastWriteAt]);

  // A marker is stale once the server (read after our last write, with nothing in flight) says the
  // mode is full or scoped: someone resumed elsewhere.
  const snapshotMode = cluster?.nodeAutomation?.mode ?? null;
  useEffect(() => {
    if (marker === null || pending !== null || override !== null) return;
    if (caps.status !== 'ready' || !snapshotIsNewer) return;
    if (snapshotMode !== 'full' && snapshotMode !== 'scoped') return;
    clearPauseMarker({ accountId, clusterName });
    setMarker(null);
  }, [marker, pending, override, caps.status, snapshotIsNewer, snapshotMode, accountId, clusterName]);

  // Re-read the clock only while the bar says "Pause sent", so it leaves that wording without a reload.
  const stillActing = view.stillActingAfterPause;
  useEffect(() => {
    if (!stillActing) return undefined;
    const id = setInterval(() => setTick((n) => n + 1), TICK_MS);
    return () => clearInterval(id);
  }, [stillActing]);

  const viewRef = useRef(view);
  viewRef.current = view;
  const capabilityRef = useRef(capability);
  capabilityRef.current = capability;
  const markerRef = useRef(marker);
  markerRef.current = marker;
  const pendingRef = useRef<'pause' | 'resume' | null>(null);
  const capsRef = useRef(caps);
  capsRef.current = caps;

  const write = useCallback(async (kind: 'pause' | 'resume'): Promise<boolean> => {
    if (pendingRef.current !== null || !capabilityRef.current.allowed) return false;
    const current = viewRef.current;
    let target: AutomationScope;
    let priorMode: 'full' | 'scoped';
    if (kind === 'pause') {
      if (current.kind !== 'autonomous' || (current.mode !== 'full' && current.mode !== 'scoped')) {
        setError({ message: STATE_CHANGED_MESSAGE, code: null });
        return false;
      }
      priorMode = current.mode;
      target = 'disabled';
    } else {
      const held = markerRef.current;
      if (current.kind !== 'paused' || !held || (held.priorMode !== 'full' && held.priorMode !== 'scoped')) {
        setError({ message: STATE_CHANGED_MESSAGE, code: null });
        return false;
      }
      priorMode = held.priorMode;
      target = held.priorMode;
    }

    pendingRef.current = kind;
    setPending(kind);
    setError(null);
    try {
      await setAutomationScope({ cluster: clusterName, accountId }, target);
    } catch (err) {
      if (isK8sApiError(err) && err.status === 400) console.warn('CloudK8s mode change was rejected', err);
      setError(toControlError(err));
      capsRef.current.onRefusal(err);
      pendingRef.current = null;
      setPending(null);
      return false;
    }

    const at = Date.now();
    if (kind === 'pause') {
      const next: PauseMarker = { priorMode, pausedAt: new Date(at).toISOString() };
      writePauseMarker({ accountId, clusterName }, next);
      setMarker(next);
    } else {
      clearPauseMarker({ accountId, clusterName });
      setMarker(null);
    }
    setOverride(target);
    setLastWriteAt(at);
    pendingRef.current = null;
    setPending(null);
    capsRef.current.revalidate();
    return true;
  }, [clusterName, accountId]);

  const pause = useCallback(() => write('pause'), [write]);
  const resume = useCallback(() => write('resume'), [write]);
  const clearError = useCallback(() => setError(null), []);

  return useMemo(
    () => ({ view, capability, pending, error, marker, clearError, pause, resume }),
    // `view` is rebuilt every render (it reads the clock), so the memo only spares identity churn of the callbacks.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [view.kind, view.mode, view.priorMode, view.acting, view.stillActingAfterPause, view.lastRunPredatesPause, capability, pending, error, marker, clearError, pause, resume],
  );
}

export default useNodeAutomationControl;
