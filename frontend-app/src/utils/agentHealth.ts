/**
 * Presentation layer for agent (host) health.
 *
 * Classification deliberately does NOT live here. The backend
 * (`AgentHealthService` / `GET /api/observability/agents`) decides what "offline" means,
 * because the same judgement drives the agent-silence alert emails — when the thresholds
 * existed in both places, the console could contradict an alert a user had just received.
 *
 * This module only turns a server-provided status into labels, colours and copy.
 */

export type AgentStatus = 'healthy' | 'delayed' | 'offline' | 'gone';

export interface AgentHealth {
  hostName: string;
  lastSeenMs: number;
  status: AgentStatus;
  /** Milliseconds since the host last reported. */
  ageMs: number;
  /**
   * Collector self-telemetry is arriving while host metrics are not: the agent process is
   * up and its pipeline is broken. Needs a config fix rather than a restart.
   */
  collectorAlive?: boolean;
}

/** Wraps a server-classified agent for display. Does not re-derive status. */
export function toAgentHealth(
  hostName: string,
  lastSeenMs: number,
  status: AgentStatus,
  collectorAlive = false,
  now: number = Date.now(),
): AgentHealth {
  return {
    hostName,
    lastSeenMs,
    status,
    ageMs: lastSeenMs ? now - lastSeenMs : Number.POSITIVE_INFINITY,
    collectorAlive,
  };
}

export interface AgentStatusPresentation {
  label: string;
  /** Short sentence explaining what the state means and what to do about it. */
  hint: string;
  /** Tailwind classes for the status dot. */
  dot: string;
  /** Tailwind classes for a pill/badge. */
  pill: string;
  icon: string;
  /** True for states the user should act on. */
  needsAttention: boolean;
}

export const AGENT_STATUS_PRESENTATION: Record<AgentStatus, AgentStatusPresentation> = {
  healthy: {
    label: 'Healthy',
    hint: 'Reporting normally.',
    dot: 'bg-emerald-500',
    pill: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    icon: 'mdi:check-circle',
    needsAttention: false,
  },
  delayed: {
    label: 'Delayed',
    hint: 'Metrics are arriving late. Usually a brief network or collector hiccup.',
    dot: 'bg-amber-400',
    pill: 'bg-amber-50 text-amber-700 border-amber-200',
    icon: 'mdi:clock-alert-outline',
    needsAttention: true,
  },
  offline: {
    label: 'Not reporting',
    hint: 'No metrics received for over 15 minutes. Check that the agent is running on this host.',
    dot: 'bg-rose-500',
    pill: 'bg-rose-50 text-rose-700 border-rose-200',
    icon: 'mdi:alert-circle',
    needsAttention: true,
  },
  gone: {
    label: 'Removed',
    hint: 'Silent for more than 24 hours. The host may have been terminated or the agent uninstalled.',
    dot: 'bg-[rgba(0,0,0,0.25)]',
    pill: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.12)]',
    icon: 'mdi:server-off',
    needsAttention: true,
  },
};

/**
 * Copy for an agent whose collector is alive but shipping nothing. Distinct from the
 * status hint because the remedy is different: reconfigure, don't restart.
 */
export const BROKEN_PIPELINE_HINT =
  'The agent is running but its metrics pipeline is not delivering. Check the collector configuration on this host.';

export function statusHint(agent: AgentHealth): string {
  if (agent.collectorAlive && agent.status !== 'healthy') return BROKEN_PIPELINE_HINT;
  return AGENT_STATUS_PRESENTATION[agent.status].hint;
}

/** Coarse ordering so the most urgent agents sort to the top of a list. */
const SEVERITY: Record<AgentStatus, number> = { offline: 0, delayed: 1, gone: 2, healthy: 3 };

export function bySeverityThenRecency(a: AgentHealth, b: AgentHealth): number {
  const s = SEVERITY[a.status] - SEVERITY[b.status];
  return s !== 0 ? s : b.lastSeenMs - a.lastSeenMs;
}

export interface AgentHealthSummary {
  total: number;
  healthy: number;
  delayed: number;
  offline: number;
  gone: number;
  /** Agents in a non-healthy state, most urgent first. */
  problems: AgentHealth[];
  /** Newest lastSeen across all agents — drives the "data is N old" messaging. */
  latestDataMs: number;
}

export function summarizeAgents(agents: AgentHealth[]): AgentHealthSummary {
  const summary: AgentHealthSummary = {
    total: agents.length,
    healthy: 0, delayed: 0, offline: 0, gone: 0,
    problems: [],
    latestDataMs: 0,
  };

  agents.forEach(a => {
    // Guard against an unrecognised status from a newer backend.
    if (a.status in SEVERITY) summary[a.status] += 1;
    if (a.status !== 'healthy') summary.problems.push(a);
    if (a.lastSeenMs > summary.latestDataMs) summary.latestDataMs = a.lastSeenMs;
  });

  summary.problems.sort(bySeverityThenRecency);
  return summary;
}

/** "3 min", "2 hours", "4 days" — compact age for status lines. */
export function formatAge(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return 'unknown';
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return 'less than a minute';
  if (mins < 60) return `${mins} min`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'}`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'}`;
}
