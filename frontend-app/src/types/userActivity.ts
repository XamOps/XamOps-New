/**
 * Shapes returned by GET /api/xamops/billops-admin/users/{userId}/activity.
 *
 * Mirrors `UserActivitySummaryDto` in xamops-service. Timestamps arrive as ISO strings.
 */

/** ACTIVE | IDLE | DORMANT | NEVER, derived from days since last login. */
export type ActivityStatus = 'ACTIVE' | 'IDLE' | 'DORMANT' | 'NEVER';

export interface DayBucket {
  day: string | null;
  logins: number;
  visibleMs: number;
  visits: number;
}

export interface HourBucket {
  /** 0-23. All 24 are always present so the chart keeps a stable axis. */
  hour: number;
  logins: number;
}

export interface ProviderSlice {
  provider: string | null;
  logins: number;
}

export interface IpSlice {
  ipAddress: string | null;
  hits: number;
  lastSeenAt: string | null;
}

export interface DeviceSlice {
  /** Coarse bucket, e.g. "Chrome on Windows". */
  label: string | null;
  hits: number;
}

export interface PageStat {
  route: string | null;
  label: string | null;
  visibleMs: number;
  dwellMs: number;
  visits: number;
  /**
   * Mean visible time per visit. Distinguishes a page bounced off forty times from one
   * studied three times — same total, opposite meaning.
   */
  avgVisibleMs: number;
  lastVisitAt: string | null;
}

export interface ImpersonationEntry {
  at: string | null;
  actorUserId: number | null;
  /** Admin's email where resolvable, otherwise "unrecorded". */
  actorLabel: string | null;
  ipAddress: string | null;
  /** Raw JSON, currently carrying the granted role. */
  metadata: string | null;
}

export interface TimelineEntry {
  at: string | null;
  action: string;
  authProvider: string | null;
  ipAddress: string | null;
  outcome: string | null;
  failureReason: string | null;
  /** Set when an admin caused the event rather than the user. */
  actorUserId: number | null;
}

export interface UserActivitySummary {
  // identity
  userId: number;
  username: string | null;
  email: string | null;
  role: string | null;
  /** The account's configured provider, as opposed to what was used per login. */
  configuredAuthProvider: string | null;
  contact: string | null;
  parentId: number | null;
  emailNotificationsEnabled: boolean;
  tourSeen: boolean;
  hasGoogleLink: boolean;
  hasMicrosoftLink: boolean;

  // window
  windowDays: number;
  /**
   * Earliest recorded event. Rendered so an empty chart reads as "not measured yet" rather
   * than "this user is inactive" — there is nothing to backfill from.
   */
  collectingSince: string | null;

  // logins
  lastLoginAt: string | null;
  daysSinceLastLogin: number | null;
  status: ActivityStatus | null;
  loginCount: number;
  failedLoginCount: number;
  activeDays: number;
  longestGapDays: number;
  avgSessionMs: number | null;
  /** Sample size behind avgSessionMs. Only deliberate sign-outs are measurable. */
  closedSessionCount: number;
  loginsPerDay: DayBucket[];
  loginsPerHour: HourBucket[];
  providers: ProviderSlice[];
  ipAddresses: IpSlice[];
  devices: DeviceSlice[];

  // page time
  totalVisibleMs: number;
  totalDwellMs: number;
  totalPageVisits: number;
  /** visibleMs / dwellMs. Low values mean the totals are mostly parked tabs. */
  engagementRatio: number | null;
  pages: PageStat[];
  pageTimePerDay: DayBucket[];
  entryRoutes: PageStat[];
  /** Catalogued product areas never opened. The most actionable list here. */
  neverVisited: PageStat[];

  // access
  impersonations: ImpersonationEntry[];
  timeline: TimelineEntry[];
}
