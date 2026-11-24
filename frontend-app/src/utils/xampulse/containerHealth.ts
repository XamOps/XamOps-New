/**
 * One status for a row of the Containers list, from what the row itself reports, so the table can lead with state the
 * way Hosts does. Same thresholds as the memory meter (warn at 75% of a real limit, critical at 90%); any restart or
 * logged error is a warning; a container that has not reported for five minutes before the range end is "Not reporting".
 * A host-sized "limit" (no limit set) never counts as memory pressure.
 */
import type { XpContainerRow, XpHealth } from '../../types/xampulse';

export const STALE_AFTER_MS = 5 * 60_000;

export type ContainerRowState = 'CRIT' | 'WARN' | 'OK' | 'STALE' | 'NO_DATA';

export interface ContainerRowHealth {
  state: ContainerRowState;
  /** XamPulse health for the pill colour: STALE shows as NO_DATA. */
  health: XpHealth;
  label: string;
  /** Short reasons, worst first: "memory 91% of limit", "3 restarts", "19 errors in 15 min". Empty when healthy. */
  reasons: string[];
}

const plural = (n: number, one: string, many = `${one}s`): string => `${n.toLocaleString()} ${n === 1 ? one : many}`;

/** Memory as a share of a real limit (0..1+), or null when no limit is set or either side is missing. */
export function memoryShare(c: XpContainerRow): number | null {
  if (c.memBytes == null || c.memLimitBytes == null || c.memLimitBytes <= 0 || c.memLimitIsHost === true) return null;
  return c.memBytes / c.memLimitBytes;
}

export function containerRowHealth(c: XpContainerRow, now: number): ContainerRowHealth {
  if (c.cpuPct == null && c.memBytes == null) return { state: 'NO_DATA', health: 'NO_DATA', label: 'No data', reasons: ['no CPU or memory samples'] };
  const seen = c.lastSeenAt ? Date.parse(c.lastSeenAt) : NaN;
  if (Number.isFinite(seen) && now - seen > STALE_AFTER_MS) return { state: 'STALE', health: 'NO_DATA', label: 'Not reporting', reasons: ['no samples in the last 5 minutes of the range'] };

  let state: ContainerRowState = 'OK';
  const reasons: string[] = [];
  const share = memoryShare(c);
  if (share != null && share >= 0.75) {
    state = share >= 0.9 ? 'CRIT' : 'WARN';
    reasons.push(`memory ${Math.round(share * 100)}% of limit`);
  }
  if ((c.restarts ?? 0) > 0) {
    if (state === 'OK') state = 'WARN';
    reasons.push(plural(c.restarts as number, 'restart'));
  }
  if ((c.errors ?? 0) > 0) {
    if (state === 'OK') state = 'WARN';
    reasons.push(`${plural(c.errors as number, 'error')} in 15 min`);
  }
  const health: XpHealth = state === 'CRIT' ? 'CRIT' : state === 'WARN' ? 'WARN' : 'OK';
  return { state, health, label: state === 'CRIT' ? 'Critical' : state === 'WARN' ? 'Warning' : 'Healthy', reasons };
}

/** True for rows someone should look at: critical, warning or gone quiet. */
export const needsAttention = (h: ContainerRowHealth): boolean => h.state === 'CRIT' || h.state === 'WARN' || h.state === 'STALE';

const RANK: Record<ContainerRowState, number> = { CRIT: 0, WARN: 1, STALE: 2, NO_DATA: 3, OK: 4 };
export const healthRank = (h: ContainerRowHealth): number => RANK[h.state];
