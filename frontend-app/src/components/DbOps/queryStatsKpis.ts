import type { AgentQueryStat } from '../../hooks/useDbOpsData';

export interface QueryStatsKpis {
  /** Sum of call counts across every digest. */
  totalCalls: number;
  /** Call-weighted mean execution time, in milliseconds. */
  overallAvgMs: number;
  /** Slowest single execution recorded across all digests, in milliseconds. */
  overallMaxMs: number;
  /** Number of digests flagged as running without an index. */
  noIndexCount: number;
  /** Total execution time, in seconds, over the no-index digests only. */
  noIndexTotalExecS: number;
}

/**
 * Pure aggregation for the Query Stats KPI strip.
 *
 * `overallAvgMs` is call-weighted — totalTime / totalCalls — not an unweighted mean of per-digest
 * averages (which over-weights rarely-run slow queries). `noIndexTotalExecS` sums execution time
 * over the no-index digests only, not every query.
 */
export function computeQueryStatsKpis(stats: AgentQueryStat[]): QueryStatsKpis {
  const totalCalls = stats.reduce((s, q) => s + q.callCount, 0);
  const sumTotalTimeSeconds = stats.reduce((s, q) => s + q.totalTimeSeconds, 0);
  const overallAvgMs = totalCalls > 0 ? (sumTotalTimeSeconds / totalCalls) * 1000 : 0;
  const overallMaxMs = stats.length > 0 ? Math.max(...stats.map(q => q.maxTimeSeconds * 1000)) : 0;
  const noIndexStats = stats.filter(q => q.noIndexUsed);
  const noIndexCount = noIndexStats.length;
  const noIndexTotalExecS = noIndexStats.reduce((s, q) => s + q.totalTimeSeconds, 0);
  return { totalCalls, overallAvgMs, overallMaxMs, noIndexCount, noIndexTotalExecS };
}
