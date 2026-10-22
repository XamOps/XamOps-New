/**
 * "Enable automation": what automation still needs on this cluster, each unmet row with its remedy
 * (docs/cloudk8s/ui-revamp-design.md §8.2, D1). It is the path to enablement from every access
 * overlay, so nobody is left with a lock and no explanation.
 *
 * Rows come from capabilities only: the connection (read-write, verified), the user's permission,
 * the XamOps agent and the node automation agent on the cluster, and controller safety validation.
 */
import type { CapabilitiesDto, Gate } from '../../../types/k8sAutomation';
import { findCluster } from '../../../hooks/useK8sCapabilities';
import { StatusMark } from './StatusMark';
import { ReadWriteHelp, Remedies } from './ReasonText';
import { reasonCopy } from './reasonCopy';

export interface ChecklistRow {
  id: 'read-write' | 'verified' | 'permission' | 'xamops-agent' | 'automation-agent' | 'controllers' | 'demo';
  /** Short label of the requirement. */
  label: string;
  met: boolean;
  /** Why it isn't met; drives the text and the remedy. */
  gate?: Gate;
}

const present = (status: string | undefined) => status === 'CONNECTED' || status === 'STALE';

/** Every requirement, met or not, in the order a user would fix them. */
export function automationChecklist(data: CapabilitiesDto, clusterName: string | null | undefined, demo = false): ChecklistRow[] {
  const cluster = findCluster(data, clusterName);
  const connection = cluster?.connection ?? data.connection;
  const rows: ChecklistRow[] = [];

  rows.push({
    id: 'read-write',
    label: 'Read-write connection',
    met: connection.accessLevel === 'READ_WRITE',
    gate: { reason: 'READ_ONLY_CONNECTION' },
  });
  rows.push({
    id: 'verified',
    label: 'Verified connection',
    met: (connection.status ?? '').toUpperCase() === 'CONNECTED',
    gate: { reason: 'CONNECTION_NOT_VERIFIED' },
  });

  const detail = data.user.detail;
  const permissionDetail = detail === 'TAB_GRANT' || detail === 'ROLE' || detail === 'IMPERSONATION' ? detail : 'TAB_GRANT';
  rows.push({
    id: 'permission',
    label: 'Permission to change automation',
    met: !data.user.reasons.includes('NO_USER_PERMISSION'),
    gate: { reason: 'NO_USER_PERMISSION', detail: permissionDetail },
  });

  if (clusterName) {
    rows.push({
      id: 'xamops-agent',
      label: 'XamOps agent',
      met: present(cluster?.agent.status),
      gate: { reason: 'AGENT_NOT_INSTALLED', detail: 'XAMOPS_AGENT' },
    });
    const automationUnknown = cluster?.automationAgent.status === 'UNKNOWN';
    rows.push({
      id: 'automation-agent',
      label: 'Node automation agent',
      met: present(cluster?.automationAgent.status),
      gate: automationUnknown ? { reason: 'UNKNOWN' } : { reason: 'AGENT_NOT_INSTALLED', detail: 'AUTOMATION_AGENT' },
    });
  }

  const readiness = Object.values(data.readiness ?? {});
  const notReady = readiness.filter((r) => !r.ready).length;
  rows.push({
    id: 'controllers',
    label: 'Controller safety validation',
    met: readiness.length > 0 && notReady === 0,
    gate: {
      reason: 'CONTROLLER_NOT_READY',
      message: readiness.length === 0
        ? "XamOps couldn't read which controllers have passed safety validation."
        : `${notReady} of ${readiness.length} controllers haven't passed safety validation yet, so their live changes stay off.`,
    },
  });

  if (demo) {
    rows.push({ id: 'demo', label: 'Demo workspace', met: false, gate: { reason: 'DEMO' } });
  }
  return rows;
}

export interface EnableAutomationPanelProps {
  /** Element id, for the toggle's aria-controls. */
  id: string;
  data: CapabilitiesDto;
  cluster: string | null | undefined;
  demo?: boolean;
}

export function EnableAutomationPanel({ id, data, cluster, demo = false }: EnableAutomationPanelProps) {
  const unmet = automationChecklist(data, cluster, demo).filter((row) => !row.met);
  return (
    <section
      id={id}
      aria-labelledby={`${id}-title`}
      className="mt-3 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white p-4"
    >
      <h3 id={`${id}-title`} className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">What automation needs</h3>
      {unmet.length === 0 ? (
        <p className="mt-2 text-[13px] text-[rgba(0,0,0,0.6)]">
          Nothing else is needed. Automation changes are allowed on this cluster.
        </p>
      ) : (
        <ul className="mt-2 divide-y divide-[rgba(0,0,0,0.08)]">
          {unmet.map((row) => {
            const copy = row.gate ? reasonCopy(row.gate) : null;
            return (
              <li key={row.id} className="py-2.5" data-row={row.id}>
                <StatusMark state={row.id === 'automation-agent' && row.gate?.reason === 'UNKNOWN' ? 'unknown' : 'not-set-up'}
                  label={row.label} />
                {copy && <p className="mt-1 text-[13px] text-[rgba(0,0,0,0.6)]">{copy.text}</p>}
                {row.id === 'read-write'
                  ? <ReadWriteHelp className="mt-1" />
                  : copy && <Remedies remedies={copy.remedies} />}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

export default EnableAutomationPanel;
