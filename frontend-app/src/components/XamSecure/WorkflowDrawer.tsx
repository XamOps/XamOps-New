import React from 'react';
import { Icon } from '@iconify/react';
import type { Workflow } from '../../types/xamsecure';
import DetailPanel, { PanelBlock, PanelRow } from './DetailPanel';

interface Props {
  workflow: Workflow;
  onClose: () => void;
}

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : 'Never run';

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/**
 * Renders a 6-field Spring-style cron as a sentence. A raw cron string is precise but
 * unreadable in a product surface, and this is the one field users actually need to
 * verify. Falls back to the raw expression when the shape is unfamiliar rather than
 * guessing wrong.
 */
function describeCron(cron: string | null): string | null {
  if (!cron) return null;
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6) return null;
  const [sec, min, hour, dom, mon, dow] = parts;
  if ([sec, min, hour].some((p) => p.includes('*') || p.includes('/'))) return null;
  const time = `${hour.padStart(2, '0')}:${min.padStart(2, '0')}`;
  if (dow !== '*' && /^\d$/.test(dow)) return `Weekly on ${DAYS[Number(dow) % 7]} at ${time} UTC`;
  if (dom !== '*' && /^\d+$/.test(dom)) return `Monthly on day ${dom} at ${time} UTC`;
  if (dom === '*' && mon === '*' && dow === '*') return `Daily at ${time} UTC`;
  return null;
}

const ACTION_ICON: Record<string, string> = {
  EMAIL: 'mdi:email-outline',
  SLACK: 'mdi:slack',
  WEBHOOK: 'mdi:webhook',
  JIRA: 'mdi:jira',
  TEAMS: 'mdi:microsoft-teams',
};

/**
 * Workflow detail. The table shows action *types*; this shows who each action reaches,
 * what the run produces, and the schedule in words — the fields Upwind surfaces on
 * click and we were previously discarding server-side.
 *
 * Single-pane on purpose: schedule, last run and actions are a handful of fields, so the
 * shared shell is used for chrome and keyboard handling without a nav.
 */
const WorkflowDrawer: React.FC<Props> = ({ workflow: w, onClose }) => {
  const enabled = (w.status ?? '').toUpperCase() === 'ENABLED';
  const schedule = describeCron(w.cron);
  const details = w.actionDetails ?? w.actions.map((t) => ({ type: t, targetAudience: null }));

  const subtitle = (
    <>
      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
        enabled ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-500'}`}>
        {enabled ? 'Enabled' : (w.status ?? 'Disabled')}
      </span>
      {w.type && (
        <span className="px-2 py-0.5 rounded-md text-[10px] font-bold bg-indigo-100 text-indigo-700">
          {w.type}
        </span>
      )}
      {w.reportType && (
        <span className="px-2 py-0.5 rounded-md text-[10px] font-semibold bg-gray-100 text-gray-600">
          {w.reportType.replace(/_/g, ' ')}
        </span>
      )}
    </>
  );

  return (
    <DetailPanel
      title={w.name ?? 'Workflow'}
      subtitle={subtitle}
      onClose={onClose}
      size="narrow"
      sections={[{
        id: 'overview',
        label: w.name ?? 'Workflow',
        render: () => (
          <>
            <PanelBlock title="Schedule">
              {schedule ? (
                <>
                  <p className="text-xs font-medium text-gray-800">{schedule}</p>
                  <p className="text-[10px] font-mono text-gray-400 mt-1">{w.cron}</p>
                </>
              ) : (
                <p className="text-xs text-gray-700 font-mono">
                  {w.cron || 'Not scheduled — runs on trigger.'}
                </p>
              )}
            </PanelBlock>

            <PanelBlock title={`Actions (${details.length})`}>
              {details.length > 0 ? (
                <div className="space-y-1.5">
                  {details.map((a, i) => (
                    <div key={i}
                         className="flex items-center gap-2 border border-gray-200 rounded-lg px-3 py-2">
                      <Icon icon={ACTION_ICON[(a.type ?? '').toUpperCase()] ?? 'mdi:play-circle-outline'}
                            width={14} className="text-gray-500 flex-shrink-0" />
                      <span className="text-xs font-semibold text-gray-800">{a.type ?? '—'}</span>
                      {a.targetAudience && (
                        <span className="ml-auto text-[10px] font-semibold text-gray-500">
                          {a.targetAudience === 'ALL' ? 'All members' : a.targetAudience}
                        </span>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-xs text-gray-400">No actions configured.</p>
              )}
            </PanelBlock>

            <PanelBlock title="Run history">
              <PanelRow label="Last run" value={fmt(w.lastExecutionTime)} />
              <PanelRow label="Workflow ID" value={w.id} mono />
            </PanelBlock>
          </>
        ),
      }]}
    />
  );
};

export default WorkflowDrawer;
