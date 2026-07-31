import React from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { Workflow } from '../../types/xamsecure';

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
 */
const WorkflowDrawer: React.FC<Props> = ({ workflow: w, onClose }) => {
  const enabled = (w.status ?? '').toUpperCase() === 'ENABLED';
  const schedule = describeCron(w.cron);
  const details = w.actionDetails ?? w.actions.map((t) => ({ type: t, targetAudience: null }));

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div className="relative w-full max-w-xl h-full bg-white shadow-2xl flex flex-col"
           onClick={(e) => e.stopPropagation()} role="dialog"
           aria-label={w.name ?? 'Workflow'}>
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-200">
          <div className="pr-4 min-w-0">
            <div className="flex flex-wrap items-center gap-1.5 mb-1.5">
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
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug break-words">
              {w.name ?? 'Workflow'}
            </h3>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 flex-shrink-0"
                  aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden px-6 py-5 space-y-6">
          <Section title="Schedule">
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
          </Section>

          <Section title="Last run">
            <p className="text-xs font-medium text-gray-700">{fmt(w.lastExecutionTime)}</p>
          </Section>

          <Section title={`Actions (${details.length})`}>
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
          </Section>

          <Section title="Identifier">
            <p className="text-[11px] font-mono text-gray-500 break-all">{w.id}</p>
          </Section>
        </div>
      </div>
    </div>
  ), document.body);
};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div>
    <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-2">{title}</h4>
    {children}
  </div>
);

export default WorkflowDrawer;
