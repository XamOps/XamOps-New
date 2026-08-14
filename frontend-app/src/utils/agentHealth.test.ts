import { describe, expect, it } from 'vitest';
import {
  AGENT_STATUS_PRESENTATION,
  AgentHealth,
  BROKEN_PIPELINE_HINT,
  formatAge,
  statusHint,
  summarizeAgents,
  toAgentHealth,
} from './agentHealth';

const NOW = Date.UTC(2026, 7, 12, 12, 0, 0);
const ago = (ms: number) => NOW - ms;
const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

/**
 * Classification itself is tested server-side (AgentHealthServiceTest) because the backend
 * owns that judgement — it also drives the alert emails. These cover presentation only.
 */
describe('toAgentHealth', () => {
  it('carries the server-provided status through without re-deriving it', () => {
    // Deliberately contradictory input: a recent timestamp with an offline status. The UI
    // must not second-guess the backend, or the console will disagree with alert emails.
    const agent = toAgentHealth('host-a', ago(MIN), 'offline', false, NOW);

    expect(agent.status).toBe('offline');
    expect(agent.ageMs).toBe(MIN);
  });

  it('reports an infinite age for a host with no known sample', () => {
    expect(toAgentHealth('host-a', 0, 'gone', false, NOW).ageMs).toBe(Number.POSITIVE_INFINITY);
  });
});

describe('statusHint', () => {
  it('tells the user to fix the config when the collector is alive but not delivering', () => {
    const agent = toAgentHealth('host-a', ago(2 * HOUR), 'offline', true, NOW);

    // Telling someone to check whether a running agent is running wastes their time.
    expect(statusHint(agent)).toBe(BROKEN_PIPELINE_HINT);
  });

  it('uses the plain status hint when the collector is silent too', () => {
    const agent = toAgentHealth('host-a', ago(2 * HOUR), 'offline', false, NOW);
    expect(statusHint(agent)).toBe(AGENT_STATUS_PRESENTATION.offline.hint);
  });

  it('does not raise a pipeline warning for a healthy agent', () => {
    const agent = toAgentHealth('host-a', ago(MIN), 'healthy', true, NOW);
    expect(statusHint(agent)).toBe(AGENT_STATUS_PRESENTATION.healthy.hint);
  });
});

describe('summarizeAgents', () => {
  const agents: AgentHealth[] = [
    toAgentHealth('host-healthy', ago(MIN), 'healthy', false, NOW),
    toAgentHealth('host-delayed', ago(10 * MIN), 'delayed', false, NOW),
    toAgentHealth('host-offline', ago(2 * HOUR), 'offline', false, NOW),
    toAgentHealth('host-gone', ago(3 * DAY), 'gone', false, NOW),
  ];

  it('counts each state and excludes healthy agents from problems', () => {
    const summary = summarizeAgents(agents);

    expect(summary.total).toBe(4);
    expect(summary.healthy).toBe(1);
    expect(summary.delayed).toBe(1);
    expect(summary.offline).toBe(1);
    expect(summary.gone).toBe(1);
    expect(summary.problems.map(p => p.hostName)).not.toContain('host-healthy');
  });

  it('orders problems by urgency so offline hosts lead', () => {
    expect(summarizeAgents(agents).problems.map(p => p.hostName))
      .toEqual(['host-offline', 'host-delayed', 'host-gone']);
  });

  it('reports the newest sample across all agents', () => {
    // Drives the "most recent data is N old" message on the metrics dashboard.
    expect(summarizeAgents(agents).latestDataMs).toBe(ago(MIN));
  });

  it('returns an empty summary for no agents', () => {
    const summary = summarizeAgents([]);
    expect(summary.total).toBe(0);
    expect(summary.problems).toEqual([]);
    expect(summary.latestDataMs).toBe(0);
  });

  it('does not produce NaN counts for an unrecognised status from a newer backend', () => {
    const summary = summarizeAgents([
      { hostName: 'odd', lastSeenMs: ago(MIN), status: 'quarantined' as never, ageMs: MIN },
    ]);

    expect(summary.total).toBe(1);
    expect(summary.healthy).toBe(0);
    expect(summary.offline).toBe(0);
    // Unknown non-healthy states should still surface as needing attention.
    expect(summary.problems).toHaveLength(1);
  });
});

describe('formatAge', () => {
  it('formats sub-minute, minute, hour and day scales', () => {
    expect(formatAge(30_000)).toBe('less than a minute');
    expect(formatAge(5 * MIN)).toBe('5 min');
    expect(formatAge(HOUR)).toBe('1 hour');
    expect(formatAge(3 * HOUR)).toBe('3 hours');
    expect(formatAge(DAY)).toBe('1 day');
    expect(formatAge(4 * DAY)).toBe('4 days');
  });

  it('handles a never-seen agent without rendering NaN', () => {
    expect(formatAge(Number.POSITIVE_INFINITY)).toBe('unknown');
  });
});
