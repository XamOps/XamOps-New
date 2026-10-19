/**
 * The activity feed (`GET /api/eks/automation/activity/{cluster}`, FEAT-007): what XamOps changed,
 * tested or was refused on one cluster, newest first, from the `vertical_workload_action` audit
 * table.
 *
 * Two layers, as in `verticalRightsizer.ts`:
 *  - the wire types, field-for-field with `ActivityPageDto` / `ActivityEntryDto`, every field
 *    optional because the reader must survive an older or newer backend;
 *  - the view types the Activity page renders, where anything the row does not hold is `null`
 *    (shown as a dash or "not recorded"), never a guess and never 0.
 *
 * Rows written before the activity log existed keep their old shape: their "before" is the size the
 * apply asked for (`beforeBasis: REQUESTED`), not the size observed before, so the page says
 * "before not recorded" for them.
 */
import type { CodeView } from './verticalRightsizer';

// ─── Wire ────────────────────────────────────────────────────────────────────

/** `feature` query values and entry field. */
export type ActivityFeatureWire = 'VERTICAL' | 'HPA';

/** `result` query values and entry field (the server's mapping of the stored outcome). */
export type ActivityResultWire = 'APPLIED' | 'PUT_BACK' | 'REFUSED' | 'TEST_RUN' | 'FAILED';

export const ACTIVITY_RESULTS: readonly ActivityResultWire[] = ['APPLIED', 'PUT_BACK', 'REFUSED', 'TEST_RUN', 'FAILED'];

export interface ActivityResourcesWire {
  cpuRequestMillis?: number | null;
  cpuLimitMillis?: number | null;
  memRequestBytes?: number | null;
  memLimitBytes?: number | null;
}

export interface ActivityContainerChangeWire {
  container?: string | null;
  before?: ActivityResourcesWire | null;
  /** `OBSERVED` (read off the pod before the change) or `REQUESTED` (an older row: the target). */
  beforeBasis?: string | null;
  after?: ActivityResourcesWire | null;
  /** The fields the apply asked to change; a field it left alone is absent. */
  requested?: ActivityResourcesWire | null;
}

export interface ActivityHpaBoundsWire {
  minReplicas?: number | null;
  maxReplicas?: number | null;
  targetUtilizationPct?: number | null;
}

export interface ActivityChangeWire {
  containers?: ActivityContainerChangeWire[] | null;
  hpa?: { before?: ActivityHpaBoundsWire | null; after?: ActivityHpaBoundsWire | null } | null;
  mode?: { from?: string | null; to?: string | null } | null;
}

export interface ActivityPodProofWire {
  pod?: string | null;
  node?: string | null;
  uidChanged?: boolean | null;
  restartsBefore?: number | null;
  restartsAfter?: number | null;
  ready?: boolean | null;
  revert?: { state?: string | null; detail?: string | null } | null;
}

export interface ActivityProofWire {
  restartFree?: boolean | null;
  reverted?: boolean | null;
  pods?: ActivityPodProofWire[] | null;
}

export interface ActivityEntryWire {
  id?: number | string | null;
  at?: string | null;
  feature?: string | null;
  origin?: string | null;
  who?: string | null;
  action?: string | null;
  target?: { namespace?: string | null; kind?: string | null; name?: string | null } | null;
  dryRun?: boolean | null;
  outcome?: string | null;
  result?: string | null;
  terminal?: string | null;
  restartFree?: boolean | null;
  change?: ActivityChangeWire | null;
  proof?: ActivityProofWire | null;
  detail?: string | null;
  reference?: string | null;
}

export interface ActivityPageWire {
  engine?: { enabled?: boolean | null } | null;
  entries?: ActivityEntryWire[] | null;
  nextCursor?: string | null;
}

// ─── Query ───────────────────────────────────────────────────────────────────

/** One page's filters. An empty list means every value. */
export interface ActivityQuery {
  features?: readonly ActivityFeatureWire[];
  results?: readonly ActivityResultWire[];
  /** Part of a workload or HPA name; at most 100 characters (the server's limit). */
  q?: string | null;
  /** Entries at or after this instant (ISO-8601, UTC); at most 90 days before `to` or now. */
  from?: string | null;
  /** Entries at or before this instant (ISO-8601, UTC). */
  to?: string | null;
  limit?: number;
  cursor?: string | null;
}

// ─── Summary (FEAT-005): GET /api/eks/automation/activity/{cluster}/summary ──

export interface ActivityDayCountsWire {
  applied?: number | null;
  putBack?: number | null;
  refused?: number | null;
  testRuns?: number | null;
  failed?: number | null;
}

export interface ActivityDayWire extends ActivityDayCountsWire {
  /** `yyyy-MM-dd`, an Asia/Kolkata calendar day. */
  date?: string | null;
  /** `VERTICAL` and `HPA`, each present when selected. */
  byFeature?: Record<string, ActivityDayCountsWire | null> | null;
}

export interface ActivitySummaryWire {
  engine?: { enabled?: boolean | null } | null;
  timezone?: string | null;
  from?: string | null;
  to?: string | null;
  firstRecordedAt?: string | null;
  completeSince?: string | null;
  truncated?: boolean | null;
  days?: ActivityDayWire[] | null;
}

/** One day's counts, by the feed's own result mapping. */
export interface ActivityDayCounts {
  applied: number;
  putBack: number;
  refused: number;
  testRuns: number;
  failed: number;
}

export interface ActivityDayView extends ActivityDayCounts {
  /** `yyyy-MM-dd`, IST. */
  date: string;
  byFeature: Partial<Record<ActivityFeature, ActivityDayCounts>>;
}

/**
 * Changes per IST day. Only days XamOps can count honestly are listed (from the latest of `from`,
 * the first record and `completeSince`, through `to`); a listed day with nothing on it is a real
 * zero, and a day that isn't listed is "not recorded yet", never 0.
 */
export interface ActivitySummaryView {
  engineEnabled: boolean | null;
  timezone: string | null;
  /** The window's first and last day, `yyyy-MM-dd` in IST. */
  from: string | null;
  to: string | null;
  firstRecordedAt: number | null;
  /** When XamOps started recording every kind of attempt here; null until it has. */
  completeSince: number | null;
  /** More rows than one summary counts: the day the cap cut into and every day before it are left out. */
  truncated: boolean;
  days: ActivityDayView[];
}

// ─── View ────────────────────────────────────────────────────────────────────

export type ActivityFeature = 'workload' | 'hpa';

export type ActivityResultKey = 'applied' | 'put-back' | 'refused' | 'test-run' | 'failed';

export type ActivityAction = 'resize' | 'hpa-change' | 'mode-change';

/** One container's size, in the units the rest of the product shows: m and Mi. */
export interface ActivitySizes {
  cpuRequestM: number | null;
  cpuLimitM: number | null;
  memRequestMi: number | null;
  memLimitMi: number | null;
}

export interface ActivityContainerChange {
  container: string;
  /** The size observed before the change; null when the row didn't record it (older rows). */
  before: ActivitySizes | null;
  /** The size observed on the pod afterwards (after a put-back, the put-back size). */
  after: ActivitySizes | null;
  /** What the apply asked for; a field it left alone is null. */
  requested: ActivitySizes | null;
}

export interface ActivityHpaBounds {
  minReplicas: number | null;
  maxReplicas: number | null;
  targetUtilizationPct: number | null;
}

export interface ActivityPodProof {
  pod: string;
  node: string | null;
  uidChanged: boolean | null;
  restartsBefore: number | null;
  restartsAfter: number | null;
  ready: boolean | null;
  revert: { state: string | null; detail: string | null } | null;
}

export interface ActivityEntryView {
  /** Stable row key: the row id. */
  id: string;
  /** Epoch ms; null when missing or unreadable. */
  at: number | null;
  /** The instant as the server sent it (UTC), for a title. */
  atRaw: string | null;
  feature: ActivityFeature | null;
  /** MANUAL (a user) or AUTO (XamOps's automatic loop); null when the row doesn't say. */
  origin: 'MANUAL' | 'AUTO' | null;
  /** "A user" or "XamOps (automatic)"; null when the row doesn't say. */
  who: string | null;
  action: ActivityAction | null;
  target: { namespace: string | null; kind: string | null; name: string | null };
  dryRun: boolean;
  /** The stored outcome, verbatim. */
  outcome: string | null;
  result: ActivityResultKey | null;
  /** The executor's full outcome on newer rows (DEFERRED, INFEASIBLE, …); null on older and HPA rows. */
  terminal: string | null;
  /** True only when every pod touched was proved not to have restarted. */
  restartFree: boolean;
  /** Per container, for a resize; empty when the row records none. */
  containers: ActivityContainerChange[];
  /** True when the containers' "before" is the target an older row recorded, not an observed size. */
  beforeNotRecorded: boolean;
  hpa: { before: ActivityHpaBounds | null; after: ActivityHpaBounds | null } | null;
  mode: { from: string | null; to: string | null } | null;
  /** Per-pod evidence on newer rows that touched pods; null otherwise. */
  proof: { restartFree: boolean; reverted: boolean | null; pods: ActivityPodProof[] } | null;
  detail: string | null;
  reference: string | null;
}

export interface ActivityPageView {
  /** Whether the Workload autoscaler is on in this environment; null when the server didn't say. */
  engineEnabled: boolean | null;
  entries: ActivityEntryView[];
  nextCursor: string | null;
}

/** What the feed answered. */
export type ActivityReadResult =
  | { kind: 'ok'; page: ActivityPageView }
  /** An untyped 404: this backend has no activity feed yet. */
  | { kind: 'not-deployed' }
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };
