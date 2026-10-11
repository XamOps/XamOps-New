/**
 * The "Not monitored" state (docs/cloudk8s/ui-revamp-design.md §8.1 M0, D10).
 *
 * Replaces a section's content when XamOps has no agent data for the cluster, so an unmonitored
 * cluster never shows zeros as values. It says what XamOps would show and opens the setup guide at
 * Quick Install.
 */
import { useId } from 'react';
import { Icon } from '@iconify/react';

export const NOT_MONITORED_TITLE = "This cluster isn't monitored yet";

/** What the sections show once the XamOps agent reports. */
export const WOULD_SHOW: readonly string[] = [
  'Node health, capacity and node groups',
  'Pods, workloads, containers and Kubernetes events',
  'Resource requests, and usage where the cluster reports it',
  'Cost by namespace, workload and node, with its source',
  'Vulnerabilities, compliance and runtime security findings',
];

export interface NotMonitoredPanelProps {
  clusterName: string;
  /** Opens the setup guide at Quick Install. */
  onSetUp: () => void;
}

export function NotMonitoredPanel({ clusterName, onSetUp }: NotMonitoredPanelProps) {
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      data-state="not-monitored"
      className="rounded-lg border border-[rgba(0,0,0,0.12)] bg-white p-6"
    >
      <div className="flex items-start gap-3">
        <Icon icon="mdi:circle-off-outline" width={22} aria-hidden="true" className="mt-0.5 shrink-0 text-[rgba(0,0,0,0.6)]" />
        <div className="min-w-0 flex-1">
          <h2 id={titleId} className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">{NOT_MONITORED_TITLE}</h2>
          <p className="mt-1 text-[14px] text-[rgba(0,0,0,0.6)]">
            {`XamOps has no agent data for ${clusterName}. Install the XamOps agent and this page shows:`}
          </p>
          <ul className="mt-3 space-y-1.5 text-[13px] text-[rgba(0,0,0,0.88)]">
            {WOULD_SHOW.map((item) => (
              <li key={item} className="flex items-start gap-2">
                <Icon icon="mdi:circle-small" width={18} aria-hidden="true" className="shrink-0 text-[rgba(0,0,0,0.6)]" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
          <button
            type="button"
            onClick={onSetUp}
            className="mt-4 inline-flex items-center gap-1.5 rounded-md bg-[rgba(0,0,0,0.88)] px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-black outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2"
          >
            <Icon icon="mdi:download-outline" width={16} aria-hidden="true" />
            Set up monitoring
          </button>
        </div>
      </div>
    </section>
  );
}

export default NotMonitoredPanel;
