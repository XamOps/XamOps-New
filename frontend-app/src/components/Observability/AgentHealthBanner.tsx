import React from 'react';
import { Icon } from '@iconify/react';
import { useNavigate } from 'react-router-dom';
import {
  AGENT_STATUS_PRESENTATION,
  AgentHealth,
  formatAge,
  statusHint,
  summarizeAgents,
} from '../../utils/agentHealth';

/**
 * Surfaces agent problems at the top of the Observability tabs.
 *
 * Renders nothing when every agent is healthy, so it stays out of the way in the normal
 * case. When agents are late, silent, or gone it explains which hosts and what to do —
 * previously this information existed in the data but was never shown, so a dead agent
 * presented as an empty dashboard with no explanation.
 */
const AgentHealthBanner: React.FC<{
  agents: AgentHealth[];
  /** Shown when the roster is empty — distinguishes "no agents" from "agents broken". */
  showEmptyState?: boolean;
}> = ({ agents, showEmptyState = false }) => {
  const navigate = useNavigate();
  const summary = summarizeAgents(agents);

  // No agents at all: the user hasn't onboarded a host yet, which is a setup task
  // rather than a fault. Only surfaced where it's useful (the Overview tab).
  if (summary.total === 0) {
    if (!showEmptyState) return null;
    return (
      <div className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.02)] px-4 py-3 flex items-start gap-3">
        <Icon icon="mdi:server-plus" width={18} className="mt-0.5 text-[rgba(0,0,0,0.4)] flex-shrink-0" />
        <div className="min-w-0">
          <p className="text-[13px] font-medium text-black">No agents are reporting yet</p>
          <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-0.5">
            Install the XamOps agent on a host to start collecting metrics and logs.
          </p>
          <button
            onClick={() => navigate('/observability/setup')}
            className="mt-2 text-[12px] font-medium text-blue-600 hover:text-blue-700 inline-flex items-center gap-1"
          >
            Go to Setup <Icon icon="mdi:arrow-right" width={13} />
          </button>
        </div>
      </div>
    );
  }

  if (summary.problems.length === 0) return null;

  // Lead with the most severe state present so the headline matches the real urgency.
  const worst = summary.problems[0];
  const worstPresentation = AGENT_STATUS_PRESENTATION[worst.status];
  const isCritical = worst.status === 'offline';
  // A live collector changes the remedy from "restart the agent" to "fix its config", so the
  // headline advice has to reflect that rather than telling the user to check a running process.
  const leadHint = statusHint(worst);

  const headline = summary.problems.length === 1
    ? `1 agent needs attention`
    : `${summary.problems.length} agents need attention`;

  return (
    <div
      role="status"
      className={`rounded-xl border px-4 py-3 ${
        isCritical
          ? 'border-rose-200 bg-rose-50'
          : 'border-amber-200 bg-amber-50'
      }`}
    >
      <div className="flex items-start gap-3">
        <Icon
          icon={worstPresentation.icon}
          width={18}
          className={`mt-0.5 flex-shrink-0 ${isCritical ? 'text-rose-600' : 'text-amber-600'}`}
        />
        <div className="min-w-0 flex-1">
          <p className={`text-[13px] font-semibold ${isCritical ? 'text-rose-900' : 'text-amber-900'}`}>
            {headline}
          </p>
          <p className={`text-[12px] mt-0.5 ${isCritical ? 'text-rose-800' : 'text-amber-800'}`}>
            {leadHint}
          </p>

          <ul className="mt-2 space-y-1">
            {summary.problems.slice(0, 5).map(agent => {
              const p = AGENT_STATUS_PRESENTATION[agent.status];
              return (
                <li key={agent.hostName} className="flex items-center gap-2 text-[12px]">
                  <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${p.dot}`} />
                  <span className="font-medium text-black truncate" title={agent.hostName}>
                    {agent.hostName}
                  </span>
                  <span className={isCritical ? 'text-rose-700' : 'text-amber-700'}>
                    {p.label} · last seen {formatAge(agent.ageMs)} ago
                    {agent.collectorAlive && ' · agent running, pipeline not delivering'}
                  </span>
                </li>
              );
            })}
          </ul>

          {summary.problems.length > 5 && (
            <p className="text-[11px] mt-1.5 text-[rgba(0,0,0,0.5)]">
              and {summary.problems.length - 5} more
            </p>
          )}

          <button
            onClick={() => navigate('/observability/setup')}
            className={`mt-2 text-[12px] font-medium inline-flex items-center gap-1 ${
              isCritical ? 'text-rose-700 hover:text-rose-900' : 'text-amber-700 hover:text-amber-900'
            }`}
          >
            View agent setup <Icon icon="mdi:arrow-right" width={13} />
          </button>
        </div>

        <span className="text-[11px] font-medium text-[rgba(0,0,0,0.5)] flex-shrink-0 whitespace-nowrap">
          {summary.healthy}/{summary.total} healthy
        </span>
      </div>
    </div>
  );
};

export default AgentHealthBanner;
