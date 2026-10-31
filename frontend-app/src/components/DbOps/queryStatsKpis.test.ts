import { describe, it, expect } from 'vitest';
import { computeQueryStatsKpis } from './queryStatsKpis';
import type { AgentQueryStat } from '../../hooks/useDbOpsData';

function stat(partial: Partial<AgentQueryStat>): AgentQueryStat {
  return {
    dsnAlias: 'db',
    dbEngine: 'postgres',
    dbHost: 'host',
    queryDigest: 'SELECT ?',
    callCount: 0,
    totalTimeSeconds: 0,
    avgTimeSeconds: 0,
    maxTimeSeconds: 0,
    rowsExamined: 0,
    rowsSent: 0,
    sharedBlksHit: 0,
    sharedBlksRead: 0,
    noIndexUsed: false,
    lockTimeSeconds: 0,
    ...partial,
  };
}

describe('computeQueryStatsKpis', () => {
  // Per-digest average latencies are 5 ms, 300 ms, 40 ms -> unweighted mean 115 ms.
  // But almost all calls hit the fast query, so the call-weighted mean is far lower (~14.9 ms).
  const stats: AgentQueryStat[] = [
    stat({ queryDigest: 'A', callCount: 1000, avgTimeSeconds: 0.005, totalTimeSeconds: 5.0, maxTimeSeconds: 0.02, noIndexUsed: false }),
    stat({ queryDigest: 'B', callCount: 33, avgTimeSeconds: 0.300, totalTimeSeconds: 9.9, maxTimeSeconds: 0.5, noIndexUsed: true }),
    stat({ queryDigest: 'C', callCount: 17, avgTimeSeconds: 0.040, totalTimeSeconds: 0.68, maxTimeSeconds: 0.1, noIndexUsed: true }),
  ];

  it('uses a call-weighted mean for overallAvgMs, not an unweighted mean of per-digest averages', () => {
    const unweightedMeanMs = stats.reduce((s, q) => s + q.avgTimeSeconds * 1000, 0) / stats.length;
    expect(unweightedMeanMs).toBeCloseTo(115, 0); // the old (wrong) number

    const { overallAvgMs } = computeQueryStatsKpis(stats);
    // (5.0 + 9.9 + 0.68) s / (1000 + 33 + 17) calls * 1000 = 14.84 ms
    expect(overallAvgMs).toBeCloseTo(14.9, 0);
  });

  it('sums total exec time over no-index digests only, not every query', () => {
    const { noIndexCount, noIndexTotalExecS, totalCalls, overallMaxMs } = computeQueryStatsKpis(stats);
    const allQueryTotalExecS = stats.reduce((s, q) => s + q.totalTimeSeconds, 0); // 15.58

    expect(noIndexCount).toBe(2);
    expect(noIndexTotalExecS).toBeCloseTo(10.58, 5); // 9.9 + 0.68
    expect(noIndexTotalExecS).not.toBeCloseTo(allQueryTotalExecS, 5);
    expect(totalCalls).toBe(1050);
    expect(overallMaxMs).toBeCloseTo(500, 5);
  });

  it('returns zeroed KPIs for an empty stats list', () => {
    expect(computeQueryStatsKpis([])).toEqual({
      totalCalls: 0,
      overallAvgMs: 0,
      overallMaxMs: 0,
      noIndexCount: 0,
      noIndexTotalExecS: 0,
    });
  });
});
