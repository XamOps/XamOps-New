/**
 * Measures how long a user spends on each page, and reports it in batches.
 *
 * This has to live in the browser. XamOps is a single-page app with client-side routing, so
 * navigating from Billing to Invoices usually issues no HTTP request at all — the server
 * never sees a page entered and never sees one left. No amount of backend instrumentation can
 * recover dwell time; only the client can observe it.
 *
 * Two durations are reported per visit:
 *
 * - `dwellMs`  — wall-clock time between entering and leaving the route.
 * - `visibleMs` — the portion of that during which the tab was actually visible.
 *
 * Both exist because only the second is honest. A dashboard left open on a second monitor
 * accumulates hours of `dwellMs` without anyone looking at it. Reports read `visibleMs`; the
 * ratio between the two is what indicates how much of the figure to trust. Note that even
 * `visibleMs` measures tab focus, not attention — a visible tab in front of someone reading
 * email next to it still counts.
 */

import { isDemoMode } from '../demo/demoConfig';
import { isTrackableRoute, normalizeRoute } from './routeNormalizer';

const ENDPOINT = '/api/xamops/activity/page-views';

/** Flush when this many visits are queued. */
const BATCH_SIZE = 10;

/** Also flush on this cadence, so a user who parks on one page still reports. */
const FLUSH_INTERVAL_MS = 60_000;

/**
 * Close a visit once it reaches this length, even if the page is still open.
 *
 * Caps the damage from a tab left open over a weekend. Without it a single visit could claim
 * sixty hours and swamp every aggregate it appears in. The server clamps as well — a
 * suspended laptop or a clock change can produce a nonsense figure with no client bug
 * involved.
 */
const MAX_VISIT_MS = 4 * 60 * 60 * 1000;

/** Visits shorter than this are dropped as pass-through navigation. */
const MIN_VISIT_MS = 1000;

/** Cap on the queue, so an offline spell cannot grow it without bound. */
const MAX_QUEUE = 100;

export type ExitReason = 'NAVIGATE' | 'HIDDEN' | 'UNLOAD' | 'TIMEOUT';

interface CompletedVisit {
  route: string;
  path: string;
  enteredAt: number;
  exitedAt: number;
  dwellMs: number;
  visibleMs: number;
  exitReason: ExitReason;
}

interface OpenVisit {
  route: string;
  path: string;
  enteredAt: number;
  /** Visible milliseconds banked from completed visible runs. */
  visibleMs: number;
  /** Start of the current visible run, or null while the tab is hidden. */
  visibleSince: number | null;
}

let open: OpenVisit | null = null;
let queue: CompletedVisit[] = [];
let timer: ReturnType<typeof setInterval> | null = null;
let listenersBound = false;

/**
 * Sends the queue.
 *
 * Uses `fetch` with `keepalive` rather than `navigator.sendBeacon`, which matters during a
 * "Login as" session. `main.tsx` patches `window.fetch` to attach `X-Tenant-ID`, CSRF and —
 * when impersonating — an `Authorization: Bearer` header with `credentials: 'omit'`.
 * `sendBeacon` cannot set headers at all, so a beacon sent from an impersonated tab arrives
 * with neither bearer token nor cookie and is rejected. `keepalive` survives unload the same
 * way a beacon does while still going through the patched fetch, so one code path
 * authenticates correctly in both ordinary and impersonated sessions.
 */
function flush(): void {
  if (queue.length === 0) return;

  const visits = queue;
  queue = [];

  try {
    void fetch(ENDPOINT, {
      method: 'POST',
      credentials: 'include',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ visits }),
    }).catch(() => {
      // Telemetry is not worth surfacing or retrying. Re-queueing on failure would mean an
      // offline user accumulating a batch that keeps failing and keeps growing.
    });
  } catch {
    // Ignore: a failed measurement must never affect the page the user is on.
  }
}

function enqueue(visit: CompletedVisit): void {
  if (visit.dwellMs < MIN_VISIT_MS) return;
  if (queue.length >= MAX_QUEUE) queue.shift();
  queue.push(visit);
  if (queue.length >= BATCH_SIZE) flush();
}

/** Banks the in-progress visible run and returns the total visible milliseconds. */
function settleVisible(visit: OpenVisit, now: number): number {
  let visible = visit.visibleMs;
  if (visit.visibleSince !== null) {
    visible += now - visit.visibleSince;
  }
  return visible;
}

function closeOpenVisit(reason: ExitReason, now = Date.now()): void {
  if (!open) return;

  const dwellMs = Math.max(0, now - open.enteredAt);
  const visibleMs = Math.min(Math.max(0, settleVisible(open, now)), dwellMs);

  enqueue({
    route: open.route,
    path: open.path,
    enteredAt: open.enteredAt,
    exitedAt: now,
    dwellMs: Math.min(dwellMs, MAX_VISIT_MS),
    visibleMs: Math.min(visibleMs, MAX_VISIT_MS),
    exitReason: reason,
  });

  open = null;
}

function onVisibilityChange(): void {
  if (!open) return;
  const now = Date.now();

  if (document.visibilityState === 'hidden') {
    open.visibleMs = settleVisible(open, now);
    open.visibleSince = null;
  } else if (open.visibleSince === null) {
    open.visibleSince = now;
  }
}

/**
 * Closes and flushes on unload.
 *
 * Both `pagehide` and `beforeunload` are bound because iOS Safari frequently skips
 * `beforeunload` entirely, and mobile is exactly where a tab gets discarded without warning.
 * The same reasoning is documented on the call teardown in `CallContext.tsx`.
 */
function onUnload(): void {
  closeOpenVisit('UNLOAD');
  flush();
}

/** Closes a visit that has run past the cap, reopening it so tracking continues. */
function enforceVisitCap(): void {
  if (!open) return;
  const now = Date.now();
  if (now - open.enteredAt < MAX_VISIT_MS) return;

  const { route, path } = open;
  closeOpenVisit('TIMEOUT', now);
  open = {
    route,
    path,
    enteredAt: now,
    visibleMs: 0,
    visibleSince: document.visibilityState === 'visible' ? now : null,
  };
}

function bindListeners(): void {
  if (listenersBound) return;
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('pagehide', onUnload);
  window.addEventListener('beforeunload', onUnload);
  timer = setInterval(() => {
    enforceVisitCap();
    flush();
  }, FLUSH_INTERVAL_MS);
  listenersBound = true;
}

/**
 * Records a navigation: closes the previous visit and opens one for `pathname`.
 *
 * Safe to call with the same path twice; a repeat is ignored so that a re-render or a
 * query-string change does not fragment one visit into several.
 */
export function trackNavigation(pathname: string): void {
  // Demo sessions render fixture data and would otherwise pollute real usage figures with
  // browsing that never happened.
  if (isDemoMode()) return;

  if (!isTrackableRoute(pathname)) {
    closeOpenVisit('NAVIGATE');
    return;
  }

  const route = normalizeRoute(pathname);
  if (open && open.route === route) return;

  bindListeners();
  closeOpenVisit('NAVIGATE');

  const now = Date.now();
  open = {
    route,
    path: pathname.slice(0, 512),
    enteredAt: now,
    visibleMs: 0,
    visibleSince: document.visibilityState === 'visible' ? now : null,
  };
}

/** Closes the current visit and flushes. Called on sign-out, before the session ends. */
export function endTracking(reason: ExitReason = 'NAVIGATE'): void {
  closeOpenVisit(reason);
  flush();
}

/** Tears down listeners and timers. Exported for tests and hot reload. */
export function resetTracker(): void {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  document.removeEventListener('visibilitychange', onVisibilityChange);
  window.removeEventListener('pagehide', onUnload);
  window.removeEventListener('beforeunload', onUnload);
  listenersBound = false;
  open = null;
  queue = [];
}

/** Current state, for tests and debugging. */
export function trackerState(): { route: string | null; queued: number } {
  return { route: open?.route ?? null, queued: queue.length };
}
