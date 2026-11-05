/**
 * Where the mode a user held before "Pause node writes" is remembered (Phase A design §5.2).
 *
 * The mode API stores only the new mode, so the previous one can't be read back from the server. It
 * is kept in localStorage, written only after the PUT succeeded, per user (impersonation included),
 * account and cluster, and expires after 7 days. Every failure to read it (missing, corrupt, wrong
 * shape, future-dated, expired, storage blocked) means "no marker": Resume is not offered and no
 * prior mode is guessed. Nothing here throws.
 */
import { userKey } from './k8sCache';

export type PausedFromMode = 'full' | 'scoped';

export interface PauseMarker {
  priorMode: PausedFromMode;
  /** ISO-8601 instant of the pause. */
  pausedAt: string;
}

export interface PauseMarkerScope {
  accountId: string;
  clusterName: string;
}

/** Long enough for a weekend or leave, short enough that a forgotten pause doesn't resume at a stale mode. */
export const PAUSE_MARKER_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
/** Clock skew allowed before a marker counts as future-dated. */
const FUTURE_SKEW_MS = 5 * 60 * 1000;

export function pauseMarkerKey(scope: PauseMarkerScope): string {
  return `xamops:k8s:pause:v1:${userKey()}|${scope.accountId}|${scope.clusterName}`;
}

function storage(): Storage | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** The valid, unexpired marker for this cluster, or null. An expired marker is also removed. */
export function readPauseMarker(scope: PauseMarkerScope, now: number = Date.now()): PauseMarker | null {
  try {
    const store = storage();
    if (!store) return null;
    const key = pauseMarkerKey(scope);
    const raw = store.getItem(key);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    const { priorMode, pausedAt } = parsed as Record<string, unknown>;
    if (priorMode !== 'full' && priorMode !== 'scoped') return null;
    if (typeof pausedAt !== 'string') return null;
    const at = Date.parse(pausedAt);
    if (Number.isNaN(at)) return null;
    if (at - now > FUTURE_SKEW_MS) return null;
    if (now - at > PAUSE_MARKER_MAX_AGE_MS) {
      try { store.removeItem(key); } catch { /* storage blocked: still no marker */ }
      return null;
    }
    return { priorMode, pausedAt };
  } catch {
    return null;
  }
}

/** Whether the marker was persisted. False when storage is blocked. */
export function writePauseMarker(scope: PauseMarkerScope, marker: PauseMarker): boolean {
  try {
    const store = storage();
    if (!store) return false;
    store.setItem(pauseMarkerKey(scope), JSON.stringify(marker));
    return true;
  } catch {
    return false;
  }
}

/** Whether the marker is gone. False when storage is blocked. */
export function clearPauseMarker(scope: PauseMarkerScope): boolean {
  try {
    const store = storage();
    if (!store) return false;
    store.removeItem(pauseMarkerKey(scope));
    return true;
  } catch {
    return false;
  }
}

/** Whether a marker can be persisted here (storage not blocked), probed without touching any marker. */
export function canPersistPauseMarker(): boolean {
  try {
    const store = storage();
    if (!store) return false;
    const probe = 'xamops:k8s:pause:v1:probe';
    store.setItem(probe, '1');
    store.removeItem(probe);
    return true;
  } catch {
    return false;
  }
}
