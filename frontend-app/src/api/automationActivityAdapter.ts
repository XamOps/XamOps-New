/**
 * Adapts the activity feed's wire (`ActivityPageDto`, FEAT-007) and its per-day summary
 * (`ActivitySummaryDto`, FEAT-005) into the views Automation › Activity and the Overview render.
 * The words (result chip, proof chip, before → after) live with the page, in
 * `sections/automation/activity/activityText.ts`.
 *
 * Rules (CLAUDE.md, glossary rule 4): a field the row does not hold stays `null`, never 0 and never a
 * guess. An older row's "before" is the target it asked for, so it is not shown as a before
 * (`beforeNotRecorded`).
 */
import type {
  ActivityAction,
  ActivityContainerChange,
  ActivityDayCounts,
  ActivityDayCountsWire,
  ActivityDayView,
  ActivityEntryView,
  ActivityEntryWire,
  ActivityFeature,
  ActivityHpaBounds,
  ActivityHpaBoundsWire,
  ActivityPageView,
  ActivityPageWire,
  ActivityPodProof,
  ActivityResourcesWire,
  ActivityResultKey,
  ActivityResultWire,
  ActivitySizes,
  ActivitySummaryView,
  ActivitySummaryWire,
} from '../types/automationActivity';

const MI = 1024 * 1024;

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null);
const bool = (v: unknown): boolean | null => (typeof v === 'boolean' ? v : null);

/** Bytes as Mi, to one decimal when it isn't a whole Mi (Kubernetes allows "100M"). */
const bytesToMi = (v: unknown): number | null => {
  const bytes = num(v);
  if (bytes === null) return null;
  const mi = bytes / MI;
  return Number.isInteger(mi) ? mi : Math.round(mi * 10) / 10;
};

function sizes(wire: ActivityResourcesWire | null | undefined): ActivitySizes | null {
  if (!wire || typeof wire !== 'object') return null;
  const s: ActivitySizes = {
    cpuRequestM: num(wire.cpuRequestMillis),
    cpuLimitM: num(wire.cpuLimitMillis),
    memRequestMi: bytesToMi(wire.memRequestBytes),
    memLimitMi: bytesToMi(wire.memLimitBytes),
  };
  return Object.values(s).every((v) => v === null) ? null : s;
}

function bounds(wire: ActivityHpaBoundsWire | null | undefined): ActivityHpaBounds | null {
  if (!wire || typeof wire !== 'object') return null;
  const b: ActivityHpaBounds = {
    minReplicas: num(wire.minReplicas),
    maxReplicas: num(wire.maxReplicas),
    targetUtilizationPct: num(wire.targetUtilizationPct),
  };
  return Object.values(b).every((v) => v === null) ? null : b;
}

/** An instant (nanoseconds and all) as epoch ms, or null when missing or unreadable. */
function epochMs(at: string | null): number | null {
  if (!at) return null;
  const ms = Date.parse(at.replace(/(\.\d{3})\d+/, '$1'));
  return Number.isFinite(ms) ? ms : null;
}

const FEATURES: Record<string, ActivityFeature> = { VERTICAL: 'workload', HPA: 'hpa' };
const RESULTS: Record<string, ActivityResultKey> = {
  APPLIED: 'applied',
  PUT_BACK: 'put-back',
  REFUSED: 'refused',
  TEST_RUN: 'test-run',
  FAILED: 'failed',
};
const ACTIONS: Record<string, ActivityAction> = { RESIZE: 'resize', HPA_CHANGE: 'hpa-change', MODE_CHANGE: 'mode-change' };

/** The view's result key as the query's wire value. */
export const RESULT_WIRE: Record<ActivityResultKey, ActivityResultWire> = {
  applied: 'APPLIED',
  'put-back': 'PUT_BACK',
  refused: 'REFUSED',
  'test-run': 'TEST_RUN',
  failed: 'FAILED',
};

export function adaptActivityEntry(wire: ActivityEntryWire, index: number): ActivityEntryView {
  const atRaw = text(wire.at);
  const origin = wire.origin === 'MANUAL' || wire.origin === 'AUTO' ? wire.origin : null;
  const containersWire = Array.isArray(wire.change?.containers) ? wire.change!.containers! : [];
  let beforeNotRecorded = false;
  const containers: ActivityContainerChange[] = containersWire.flatMap((c) => {
    const name = text(c?.container);
    if (!c || !name) return [];
    const observedBefore = c.beforeBasis === 'OBSERVED';
    // REQUESTED: a row from before the activity log, whose "before" is the target it asked for.
    if (c.beforeBasis === 'REQUESTED') beforeNotRecorded = true;
    return [{
      container: name,
      // An older row's "before" is the target it asked for, so it is not shown as a before.
      before: observedBefore ? sizes(c.before) : null,
      after: sizes(c.after),
      requested: sizes(c.requested),
    }];
  });
  const hpaWire = wire.change?.hpa;
  const hpa = hpaWire && typeof hpaWire === 'object'
    ? { before: bounds(hpaWire.before), after: bounds(hpaWire.after) }
    : null;
  const modeWire = wire.change?.mode;
  const mode = modeWire && typeof modeWire === 'object' ? { from: text(modeWire.from), to: text(modeWire.to) } : null;
  const podsWire = Array.isArray(wire.proof?.pods) ? wire.proof!.pods! : [];
  const pods: ActivityPodProof[] = podsWire.flatMap((p) => {
    const pod = text(p?.pod);
    if (!p || !pod) return [];
    const revert = p.revert && typeof p.revert === 'object' ? { state: text(p.revert.state), detail: text(p.revert.detail) } : null;
    return [{
      pod,
      node: text(p.node),
      uidChanged: bool(p.uidChanged),
      restartsBefore: num(p.restartsBefore),
      restartsAfter: num(p.restartsAfter),
      ready: bool(p.ready),
      revert,
    }];
  });
  return {
    id: wire.id === null || wire.id === undefined ? `row-${index}` : String(wire.id),
    at: epochMs(atRaw),
    atRaw,
    feature: FEATURES[wire.feature ?? ''] ?? null,
    origin,
    who: text(wire.who),
    action: ACTIONS[wire.action ?? ''] ?? null,
    target: { namespace: text(wire.target?.namespace), kind: text(wire.target?.kind), name: text(wire.target?.name) },
    dryRun: wire.dryRun === true,
    outcome: text(wire.outcome),
    result: RESULTS[wire.result ?? ''] ?? null,
    terminal: text(wire.terminal),
    restartFree: wire.restartFree === true,
    containers,
    beforeNotRecorded: containers.length > 0 && beforeNotRecorded,
    hpa: hpa && (hpa.before || hpa.after) ? hpa : null,
    mode: mode && (mode.from || mode.to) ? mode : null,
    proof: pods.length > 0
      ? { restartFree: wire.proof?.restartFree === true, reverted: bool(wire.proof?.reverted), pods }
      : null,
    detail: text(wire.detail),
    reference: text(wire.reference),
  };
}

const count = (v: unknown): number => {
  const n = num(v);
  return n !== null && n >= 0 ? Math.round(n) : 0;
};

function dayCounts(wire: ActivityDayCountsWire | null | undefined): ActivityDayCounts {
  return {
    applied: count(wire?.applied),
    putBack: count(wire?.putBack),
    refused: count(wire?.refused),
    testRuns: count(wire?.testRuns),
    failed: count(wire?.failed),
  };
}

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The per-day summary. A listed day's counts are real (a missing count on a listed day is 0, since
 * the server always sends all five); a day the server didn't list stays unlisted, never zero-filled.
 * Days whose date can't be read are dropped.
 */
export function adaptActivitySummary(wire: ActivitySummaryWire): ActivitySummaryView {
  const days: ActivityDayView[] = (Array.isArray(wire.days) ? wire.days : []).flatMap((d) => {
    const date = text(d?.date);
    if (!d || !date || !DAY.test(date)) return [];
    const byFeature: ActivityDayView['byFeature'] = {};
    if (d.byFeature && typeof d.byFeature === 'object') {
      for (const [key, counts] of Object.entries(d.byFeature)) {
        const feature = FEATURES[key];
        if (feature && counts && typeof counts === 'object') byFeature[feature] = dayCounts(counts);
      }
    }
    return [{ date, ...dayCounts(d), byFeature }];
  });
  days.sort((a, b) => a.date.localeCompare(b.date));
  const day = (v: unknown) => {
    const s = text(v);
    return s && DAY.test(s) ? s : null;
  };
  return {
    engineEnabled: bool(wire.engine?.enabled),
    timezone: text(wire.timezone),
    from: day(wire.from),
    to: day(wire.to),
    firstRecordedAt: epochMs(text(wire.firstRecordedAt)),
    completeSince: epochMs(text(wire.completeSince)),
    truncated: wire.truncated === true,
    days,
  };
}

export function adaptActivityPage(wire: ActivityPageWire | null | undefined): ActivityPageView {
  const entries = Array.isArray(wire?.entries) ? wire!.entries! : [];
  return {
    engineEnabled: bool(wire?.engine?.enabled),
    entries: entries
      .filter((e): e is ActivityEntryWire => typeof e === 'object' && e !== null)
      .map((e, i) => adaptActivityEntry(e, i)),
    nextCursor: text(wire?.nextCursor),
  };
}
