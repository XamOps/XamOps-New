/**
 * Runtime Security View — Falco runtime threat monitoring.
 *
 * FEAT-005 v3 roll-out: the decorative per-severity hex ramps and the tinted red / amber Tailwind
 * pills are gone — severity reaches the page only as a StatusDot (dot + word) or a token-filled bar,
 * so dark mode is automatic. The summary cards collapse into one hero MetricStrip; the hand-rolled
 * SVG donut becomes a token-filled "alerts by namespace" bar list inside a Surface; the alert list
 * is a DataTable whose rows open a DetailDrawer carrying the real Falco rule catalog (description,
 * impact, recommendations, references). Radii are only the sanctioned {8px, 6px, pill}.
 *
 * The Falco totalEvents / eventsByPriority roll-up and the alert pagination are unchanged.
 */

import React, { useMemo, useState } from 'react';
import { Surface } from '../../CloudK8s/shared/Surface';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import { EmptyState } from '../../CloudK8s/shared/EmptyState';
import { StatusDot } from '../../CloudK8s/shared/status/StatusDot';
import type { Tone } from '../../CloudK8s/shared/status/statusTones';
import { toneClasses } from '../../CloudK8s/shared/status/toneClasses';
import type { ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import type { DrawerSection, DrawerDatum } from '../../CloudK8s/shared/DetailDrawer';
import { TITLE, BODY, SECONDARY, LABEL } from '../../CloudK8s/shared/typeScale';

interface SecurityAlert {
  rule: string;
  priority: string;
  pod: string;
  namespace: string;
  count: number;
}

interface RuntimeSecurityViewProps {
  securityMetrics: {
    totalEvents: number;
    eventsLastHour: number;
    eventsByPriority: Record<string, number>;
    alerts: SecurityAlert[];
  };
}

// Falco rule descriptions and recommendations
const FALCO_RULE_CATALOG: Record<string, {
  category: string;
  description: string;
  impact: string;
  recommendations: string[];
  references: string[];
}> = {
  'Contact K8S API Server From Container': {
    category: 'Network Security',
    description: 'A container attempted to contact the Kubernetes API server, which may indicate an attempt to enumerate cluster resources or exploit RBAC permissions.',
    impact: 'Reconnaissance, potential privilege escalation, unauthorized access to cluster state.',
    recommendations: [
      'Restrict access to the Kubernetes API server using Network Policies',
      'Ensure service account tokens are not mounted if not needed (automountServiceAccountToken: false)',
      'Review RBAC permissions for the service account',
      'Enable API server auditing'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.1.5',
      'MITRE ATT&CK T1613'
    ]
  },
  'System procs network activity': {
    category: 'Network Security',
    description: 'A system process (e.g., ps, top, mount) initiated a network connection, which is highly anomalous.',
    impact: 'Potential malware masquerading as system processes, command and control communication.',
    recommendations: [
      'Investigate the process and destination IP immediately',
      'Scan the container for rootkits or replaced binaries',
      'Isolate the node or pod',
      'Review outbound network policies'
    ],
    references: [
      'MITRE ATT&CK T1071',
      'NIST SP 800-190'
    ]
  },
  'Launch Suspicious Network Tool': {
    category: 'Network Security',
    description: 'A known network scanning or attack tool (e.g., nmap, dig, nc, telnet) was launched inside a container.',
    impact: 'Network mapping, service discovery, potential lateral movement.',
    recommendations: [
      'Remove network tools from production images',
      'Implement distroless images',
      'Investigate why the tool was present and who executed it',
      'Restrict egress network traffic'
    ],
    references: [
      'MITRE ATT&CK T1046',
      'Kubernetes Security Best Practices'
    ]
  },
  'Unexpected K8s User': {
    category: 'Unauthorized Access',
    description: 'An operation was performed by a user that is not normally expected to access the cluster.',
    impact: 'Unauthorized access, stolen credentials, insider threat.',
    recommendations: [
      'Audit user activity logs',
      'Rotate credentials for the affected user',
      'Review RBAC bindings',
      'Implement MFA for Kubernetes access'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.6',
      'MITRE ATT&CK T1078'
    ]
  },
  'Non-whitelisted Process': {
    category: 'Runtime Behavior',
    description: 'A process that is not on the allowlist was executed. This deviates from the expected container behavior.',
    impact: 'Execution of unauthorized code, potential malware, cryptomining.',
    recommendations: [
      'Define a strict allowlist of expected processes',
      'Use immutable infrastructure patterns',
      'Investigate the source of the unexpected process',
      'Rebuild the container image'
    ],
    references: [
      'NIST SP 800-190',
      'MITRE ATT&CK T1204'
    ]
  },
  'Exec to pod': {
    category: 'Unauthorized Access',
    description: 'A user executed "kubectl exec" to enter a running pod. This should be restricted in production.',
    impact: 'Direct access to container shell, potential data exfiltration or modification.',
    recommendations: [
      'Restrict "create pods/exec" permission to admins only',
      'Use ephemeral containers for debugging if necessary',
      'Audit all exec sessions',
      'Enforce read-only root filesystems'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.6',
      'PCI DSS 8.2'
    ]
  },
  'Launch Encrypted Network Connection': {
    category: 'Network Security',
    description: 'A process initiated an encrypted connection (SSH, HTTPS) on a non-standard port or from an unexpected binary.',
    impact: 'Data exfiltration over encrypted channels, C2 communication.',
    recommendations: [
      'Inspect network traffic metadata',
      'Verify the destination and process owner',
      'Block outbound traffic to unknown ports',
      'Use a transparent proxy for egress traffic inspection'
    ],
    references: [
      'MITRE ATT&CK T1573',
      'NIST SP 800-190'
    ]
  },
  'Clear Log Activities': {
    category: 'Defense Evasion',
    description: 'A process attempted to clear system logs (e.g., truncating /var/log/*, dmesg -c).',
    impact: 'Hiding tracks of malicious activity, hindering forensic analysis.',
    recommendations: [
      'Offload logs to a secure, remote centralized server',
      'Alert immediately on log clearing attempts',
      'Restrict write access to log directories',
      'Treat the node as compromised'
    ],
    references: [
      'MITRE ATT&CK T1070',
      'CIS Kubernetes Benchmark 3.1'
    ]
  },
  'Delete or rename shell history': {
    category: 'Defense Evasion',
    description: 'Shell history file (.bash_history) was deleted or renamed.',
    impact: 'Concealing executed commands, impeding investigation.',
    recommendations: [
      'Enable shell auditing/logging beyond just history files',
      'Use a readonly root filesystem',
      'Investigate the session that performed the deletion'
    ],
    references: [
      'MITRE ATT&CK T1070',
      'CIS Linux Benchmark'
    ]
  },
  'Detect crypto miners': {
    category: 'Malware',
    description: 'A known cryptomining binary or pool protocol was detected.',
    impact: 'Resource hijacking, degraded performance, increased cloud costs.',
    recommendations: [
      'Terminate the container immediately',
      'Block known mining pool domains/IPs',
      'Investigate image source for supply chain compromise',
      'Implement network egress filtering'
    ],
    references: [
      'MITRE ATT&CK T1496',
      'Cloud Security Alliance Controls'
    ]
  },
  'Unexpected UDP Traffic': {
    category: 'Network Security',
    description: 'Unexpected UDP traffic was generated, which is often used for C2 or data exfiltration.',
    impact: 'Potential C2 communication, data leak.',
    recommendations: [
      'Filter UDP traffic unrelated to DNS/NTP',
      'Monitor volume of UDP traffic',
      'Investigate the process generating the traffic'
    ],
    references: [
      'MITRE ATT&CK T1048',
      'NIST SP 800-190'
    ]
  },
  'Terminal shell in container': {
    category: 'Unauthorized Access',
    description: 'A shell was spawned inside a container, which may indicate an attacker gaining interactive access or a misconfigured deployment.',
    impact: 'Potential unauthorized access, data exfiltration, or lateral movement within the cluster.',
    recommendations: [
      'Review container logs and identify which user or process spawned the shell',
      'Implement read-only root filesystems in pod security policies',
      'Use distroless or minimal base images that don\'t include shell binaries',
      'Enable audit logging for exec commands in Kubernetes',
      'Restrict exec permissions using RBAC policies'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.6',
      'MITRE ATT&CK T1059'
    ]
  },
  'Write below binary dir': {
    category: 'System Integrity',
    description: 'A process attempted to write to system binary directories (/bin, /sbin, /usr/bin, /usr/sbin), potentially indicating malware installation or system tampering.',
    impact: 'System integrity compromise, malware persistence, privilege escalation.',
    recommendations: [
      'Investigate the process that attempted the write operation',
      'Implement immutable infrastructure practices',
      'Use read-only root filesystems in containers',
      'Enable SELinux or AppArmor profiles to prevent unauthorized writes',
      'Scan container images for vulnerabilities before deployment'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.6',
      'NIST SP 800-190'
    ]
  },
  'Read sensitive file untrusted': {
    category: 'Data Access',
    description: 'A process read sensitive files (e.g., /etc/shadow, SSH keys, certificates) which may indicate credential harvesting or reconnaissance.',
    impact: 'Credential theft, privilege escalation, unauthorized access to sensitive data.',
    recommendations: [
      'Review which process accessed the sensitive file and verify legitimacy',
      'Implement least privilege access controls with RBAC',
      'Use Kubernetes secrets with encryption at rest',
      'Mount secrets as read-only volumes',
      'Implement Pod Security Standards (restricted profile)'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.4.1',
      'MITRE ATT&CK T1552'
    ]
  },
  'Outbound Connection to C2 Servers': {
    category: 'Network Security',
    description: 'A container established an outbound connection to a known command-and-control (C2) server, indicating potential compromise.',
    impact: 'Data exfiltration, remote command execution, botnet participation.',
    recommendations: [
      'Immediately isolate the affected pod and namespace',
      'Review network policies and implement egress filtering',
      'Use service mesh for fine-grained network control',
      'Implement DNS-based threat intelligence filtering',
      'Conduct incident response and forensic analysis'
    ],
    references: [
      'MITRE ATT&CK T1071',
      'CIS Kubernetes Benchmark 5.3.2'
    ]
  },
  'Modify binary dirs': {
    category: 'System Integrity',
    description: 'A process modified files in system binary directories, potentially installing backdoors or modifying system utilities.',
    impact: 'Persistent backdoor access, system compromise, supply chain attack.',
    recommendations: [
      'Immediately investigate and potentially quarantine the affected container',
      'Use immutable container images',
      'Implement admission controllers to prevent privileged containers',
      'Enable audit logging for file system modifications',
      'Regularly scan images for known vulnerabilities'
    ],
    references: [
      'NIST SP 800-190',
      'CIS Kubernetes Benchmark 5.2.1'
    ]
  },
  'Container Run as Root User': {
    category: 'Privilege Management',
    description: 'A container is running with root (UID 0) privileges, violating the principle of least privilege.',
    impact: 'Increased attack surface, potential privilege escalation, broader impact from container breakout.',
    recommendations: [
      'Configure runAsNonRoot in pod security context',
      'Specify a non-root user ID (UID > 1000) in container security context',
      'Implement Pod Security Admission with restricted profile',
      'Use security context constraints (OpenShift) or PSP (older K8s)',
      'Review Dockerfile to ensure USER instruction is set'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.6',
      'Kubernetes Pod Security Standards'
    ]
  },
  'Privileged Container Detected': {
    category: 'Privilege Management',
    description: 'A container is running in privileged mode, granting access to all host devices and kernel capabilities.',
    impact: 'Container breakout, host compromise, cluster-wide impact.',
    recommendations: [
      'Remove privileged: true from pod security context',
      'Use specific capabilities instead of privileged mode',
      'Implement admission controller to block privileged containers',
      'Review workload requirements and minimize privileges',
      'Use Pod Security Admission to enforce restricted profile'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.1',
      'MITRE ATT&CK T1611'
    ]
  },
  'Sensitive Mount Detected': {
    category: 'Host Access',
    description: 'A container mounted sensitive host paths (e.g., /proc, /var/run/docker.sock, /etc), enabling host access.',
    impact: 'Container escape, host filesystem access, cluster-wide compromise.',
    recommendations: [
      'Remove unnecessary host path mounts from pod specifications',
      'Implement admission policies to block sensitive path mounts',
      'Use specific volumes instead of host paths where possible',
      'Set volume mounts as readOnly when write access is not required',
      'Review workload architecture to eliminate host dependencies'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.4',
      'Kubernetes Pod Security Standards'
    ]
  },
  'Package Management Process Launched': {
    category: 'Runtime Behavior',
    description: 'Package management tools (apt, yum, apk) were executed in a running container, indicating runtime modifications.',
    impact: 'Container drift from original image, potential malware installation, compliance violations.',
    recommendations: [
      'Investigate why packages are being installed at runtime',
      'Implement immutable infrastructure - bake all dependencies into images',
      'Use read-only root filesystem in container security context',
      'Remove package managers from production container images',
      'Use distroless or minimal base images'
    ],
    references: [
      'NIST SP 800-190',
      'CIS Docker Benchmark 4.1'
    ]
  },
  'Netcat Remote Code Execution': {
    category: 'Remote Execution',
    description: 'Netcat or similar tools detected, potentially being used for reverse shells or data exfiltration.',
    impact: 'Remote command execution, data exfiltration, persistent backdoor access.',
    recommendations: [
      'Immediately isolate affected pod and investigate',
      'Remove netcat and similar tools from container images',
      'Implement network policies to restrict egress traffic',
      'Use minimal base images without networking utilities',
      'Enable container runtime monitoring and behavioral analysis'
    ],
    references: [
      'MITRE ATT&CK T1059',
      'SANS Critical Security Controls'
    ]
  },
  'Create files below dev': {
    category: 'System Integrity',
    description: 'Files were created in /dev directory, potentially creating device nodes for privilege escalation.',
    impact: 'Privilege escalation, container escape, unauthorized device access.',
    recommendations: [
      'Use Pod Security Standards to prevent device creation',
      'Remove CAP_MKNOD capability from containers',
      'Implement AppArmor or SELinux profiles',
      'Use read-only root filesystem where possible',
      'Review and minimize container capabilities'
    ],
    references: [
      'CIS Kubernetes Benchmark 5.2.7',
      'Linux Capabilities Man Page'
    ]
  }
};

const DASH = '—';

/** Chrome ink / muted / hairline, all through the chrome tokens so dark mode is free. */
const INK = 'text-[color:var(--chrome-ink)]';
const MUTED = 'text-[color:var(--chrome-muted)]';
const HAIRLINE = 'border-[color:var(--chrome-line)]';

/** A Falco priority → a semantic tone: colour only as a dot + word, never a tinted pill. */
function priorityTone(priority: string | null | undefined): { tone: Tone; label: string } {
  const p = (priority ?? '').toUpperCase();
  if (p === 'CRITICAL' || p === '4') return { tone: 'bad', label: 'Critical' };
  if (p === 'HIGH' || p === '3') return { tone: 'warn', label: 'High' };
  if (p === 'WARNING' || p === '2') return { tone: 'warn', label: 'Warning' };
  return { tone: 'info', label: priority ? priority.charAt(0).toUpperCase() + priority.slice(1).toLowerCase() : 'Info' };
}

interface AlertRow {
  key: string;
  alert: SecurityAlert;
}

const RuntimeSecurityView: React.FC<RuntimeSecurityViewProps> = ({ securityMetrics }) => {
  const [currentPage, setCurrentPage] = useState(1);
  const [selectedSeverity, setSelectedSeverity] = useState<string>('all');
  const [selectedNamespace, setSelectedNamespace] = useState<string>('all');
  const alertsPerPage = 5;

  // PRESERVED roll-up: totalEvents + eventsByPriority buckets (CRITICAL/4, HIGH/3, WARNING/2).
  const totalEvents = securityMetrics?.totalEvents || 0;
  const criticalEvents = securityMetrics?.eventsByPriority?.['CRITICAL'] || securityMetrics?.eventsByPriority?.['4'] || 0;
  const highEvents = securityMetrics?.eventsByPriority?.['HIGH'] || securityMetrics?.eventsByPriority?.['3'] || 0;
  const warningEvents = securityMetrics?.eventsByPriority?.['WARNING'] || securityMetrics?.eventsByPriority?.['2'] || 0;

  const namespaces = useMemo(() => {
    const ns = new Set<string>();
    securityMetrics?.alerts?.forEach((alert) => { if (alert.namespace) ns.add(alert.namespace); });
    return Array.from(ns).sort();
  }, [securityMetrics?.alerts]);

  const filteredAlerts = useMemo(() => {
    let alerts = securityMetrics?.alerts || [];
    if (selectedSeverity !== 'all') {
      alerts = alerts.filter((alert) => {
        const priority = alert.priority?.toUpperCase();
        if (selectedSeverity === 'CRITICAL') return priority === 'CRITICAL' || priority === '4';
        if (selectedSeverity === 'HIGH') return priority === 'HIGH' || priority === '3';
        if (selectedSeverity === 'WARNING') return priority === 'WARNING' || priority === '2';
        return true;
      });
    }
    if (selectedNamespace !== 'all') alerts = alerts.filter((alert) => alert.namespace === selectedNamespace);
    return [...alerts].sort((a, b) => (b.count || 0) - (a.count || 0));
  }, [securityMetrics?.alerts, selectedSeverity, selectedNamespace]);

  // PRESERVED pagination.
  const totalPages = Math.ceil(filteredAlerts.length / alertsPerPage) || 1;
  const startIndex = (currentPage - 1) * alertsPerPage;
  const paginatedAlerts = filteredAlerts.slice(startIndex, startIndex + alertsPerPage);

  // Alert count per namespace, for the "alerts by namespace" token bar list.
  const namespaceData = useMemo(() => {
    const breakdown: Record<string, number> = {};
    securityMetrics?.alerts?.forEach((alert) => {
      const ns = alert.namespace || 'unknown';
      breakdown[ns] = (breakdown[ns] || 0) + (alert.count || 0);
    });
    const total = Object.values(breakdown).reduce((sum, c) => sum + c, 0);
    return Object.entries(breakdown)
      .map(([name, count]) => ({ name, count, percentage: total > 0 ? (count / total) * 100 : 0 }))
      .sort((a, b) => b.count - a.count);
  }, [securityMetrics?.alerts]);

  const getRuleInfo = (ruleName: string) =>
    FALCO_RULE_CATALOG[ruleName] || {
      category: 'Security Event',
      description: 'A security event was detected in your cluster. Review the details and take appropriate action.',
      impact: 'Potential security risk that requires investigation.',
      recommendations: [
        'Review the affected resource and verify its legitimacy',
        'Check container logs for suspicious activity',
        'Implement appropriate security policies',
        'Monitor for recurring patterns',
      ],
      references: ['Falco Default Rules', 'Kubernetes Security Best Practices'],
    };

  const stripCells: readonly MetricCell[] = [
    { label: 'Total events', value: <span className={totalEvents > 0 ? toneClasses('warn').text : toneClasses('good').text}>{totalEvents}</span>, sub: 'last hour' },
    { label: 'Critical', value: <span className={criticalEvents > 0 ? toneClasses('bad').text : INK}>{criticalEvents}</span>, sub: 'immediate action' },
    { label: 'High', value: <span className={highEvents > 0 ? toneClasses('warn').text : INK}>{highEvents}</span>, sub: 'priority response' },
    { label: 'Warning', value: <span className={warningEvents > 0 ? toneClasses('warn').text : INK}>{warningEvents}</span>, sub: 'review and investigate' },
  ];

  const alertRows: readonly AlertRow[] = paginatedAlerts.map((alert, i) => ({
    key: `${startIndex + i}-${alert.rule}-${alert.namespace}`,
    alert,
  }));

  const columns: ReadonlyArray<ColumnDef<AlertRow>> = [
    {
      id: 'rule', header: 'Rule', priority: 'essential', width: '280px',
      cell: (r) => <span className={`block truncate font-semibold ${BODY}`} title={r.alert.rule}>{r.alert.rule}</span>,
      sortValue: (r) => r.alert.rule, csv: (r) => r.alert.rule,
    },
    {
      id: 'priority', header: 'Priority', width: '140px',
      cell: (r) => { const m = priorityTone(r.alert.priority); return <StatusDot tone={m.tone} label={m.label} />; },
      sortValue: (r) => r.alert.priority, csv: (r) => priorityTone(r.alert.priority).label,
    },
    {
      id: 'namespace', header: 'Namespace', width: '160px',
      cell: (r) => <span className={`block truncate font-mono text-[12px] ${MUTED}`} title={r.alert.namespace}>{r.alert.namespace || DASH}</span>,
      sortValue: (r) => r.alert.namespace, csv: (r) => r.alert.namespace ?? '',
    },
    {
      id: 'pod', header: 'Pod', width: '200px',
      cell: (r) => <span className={`block truncate font-mono text-[12px] ${MUTED}`} title={r.alert.pod}>{r.alert.pod || DASH}</span>,
      sortValue: (r) => r.alert.pod, csv: (r) => r.alert.pod ?? '',
    },
    {
      id: 'count', header: 'Occurrences', align: 'right', width: '120px',
      cell: (r) => <span className="tabular-nums">{r.alert.count}</span>,
      sortValue: (r) => r.alert.count, csv: (r) => r.alert.count,
    },
  ];

  /** The per-alert drawer: Identity + the real Falco rule catalog (category, impact, actions, refs). */
  const rowToSections = (r: AlertRow): DrawerSection[] => {
    const alert = r.alert;
    const info = getRuleInfo(alert.rule);
    const m = priorityTone(alert.priority);
    const identity: DrawerSection = {
      title: 'Alert',
      data: [
        { label: 'Rule', value: <span className="break-words">{alert.rule}</span> },
        { label: 'Priority', value: <StatusDot tone={m.tone} label={m.label} /> },
        { label: 'Category', value: info.category },
        { label: 'Namespace', value: alert.namespace ? <span className="break-all font-mono text-[12px]">{alert.namespace}</span> : null, reason: 'Falco reported no namespace for this event.' },
        { label: 'Pod', value: alert.pod ? <span className="break-all font-mono text-[12px]">{alert.pod}</span> : null, reason: 'Falco reported no pod for this event.' },
        { label: 'Occurrences', value: <span className="tabular-nums">{alert.count}</span> },
      ],
    };
    const context: DrawerSection = {
      title: 'What this means',
      data: [
        { label: 'Description', value: <span className="leading-relaxed">{info.description}</span> },
        { label: 'Potential impact', value: <span className="leading-relaxed">{info.impact}</span> },
      ],
    };
    const actionsData: DrawerDatum[] = info.recommendations.map((rec, i) => ({
      label: `Action ${i + 1}`,
      value: <span className="leading-relaxed">{rec}</span>,
    }));
    const actions: DrawerSection = { title: 'Recommended actions', data: actionsData };
    const refs: DrawerSection = {
      title: 'Industry standards',
      data: info.references.map((ref, i) => ({ label: `Reference ${i + 1}`, value: <span className="font-mono text-[12px]">{ref}</span> })),
    };
    return [identity, context, actions, refs];
  };

  const selectClass =
    `rounded-[6px] border px-3 py-1.5 text-[12px] font-medium ${HAIRLINE} ${INK} bg-[color:var(--chrome-card)] ` +
    'outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]';

  return (
    <Surface tier="table" className="overflow-hidden" aria-label="Runtime security">
      <div className={`flex items-center justify-between border-b px-5 py-3.5 ${HAIRLINE}`}>
        <div className="min-w-0">
          <span className={TITLE}>Runtime security</span>
          <p className={SECONDARY}>Real-time threat detection, powered by Falco.</p>
        </div>
        <StatusDot tone={totalEvents > 0 ? 'bad' : 'good'} label={totalEvents > 0 ? `${totalEvents} events` : 'All clear'} />
      </div>

      {/* Hero strip (PRESERVED roll-up) */}
      <div className={`border-b px-5 py-4 ${HAIRLINE}`}>
        <MetricStrip cells={stripCells} aria-label="Runtime events" />
      </div>

      {/* Alerts by namespace — token-filled bars (replaces the hand-rolled hex donut) */}
      {namespaceData.length > 0 && (
        <div className={`border-b px-5 py-4 ${HAIRLINE}`}>
          <span className={LABEL}>Alerts by namespace</span>
          <div className="mt-3 flex flex-col gap-2">
            {namespaceData.map((ns) => (
              <div key={ns.name} className="flex items-center gap-3">
                <span className={`w-40 truncate font-mono text-[12px] ${MUTED}`} title={ns.name}>{ns.name}</span>
                <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-[color:var(--chrome-surface-2)]">
                  <span className="block h-full rounded-full bg-[color:var(--chrome-muted)]" style={{ width: `${ns.percentage}%` }} />
                </span>
                <span className={`w-10 text-right tabular-nums ${BODY} ${INK}`}>{ns.count}</span>
                <span className={`w-12 tabular-nums ${SECONDARY}`}>{ns.percentage.toFixed(0)}%</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Security alerts — DataTable + per-row DetailDrawer of the real rule catalog */}
      <div>
        <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5">
          <span className={TITLE}>Security alerts <span className={SECONDARY}>({filteredAlerts.length})</span></span>
          <div className="flex items-center gap-2">
            <select
              aria-label="Filter by severity"
              value={selectedSeverity}
              onChange={(e) => { setSelectedSeverity(e.target.value); setCurrentPage(1); }}
              className={selectClass}
            >
              <option value="all">All severities</option>
              <option value="CRITICAL">Critical</option>
              <option value="HIGH">High</option>
              <option value="WARNING">Warning</option>
            </select>
            <select
              aria-label="Filter by namespace"
              value={selectedNamespace}
              onChange={(e) => { setSelectedNamespace(e.target.value); setCurrentPage(1); }}
              className={selectClass}
            >
              <option value="all">All namespaces</option>
              {namespaces.map((ns) => <option key={ns} value={ns}>{ns}</option>)}
            </select>
          </div>
        </div>

        <div className="px-5 pb-5">
          {filteredAlerts.length > 0 ? (
            <DataTable<AlertRow>
              rows={alertRows}
              columns={columns}
              rowKey={(r) => r.key}
              caption="Security alerts"
              rowLabel={(r) => r.alert.rule}
              rowTitle={(r) => r.alert.rule}
              rowSubtitle={(r) => r.alert.namespace}
              rowToSections={rowToSections}
            />
          ) : (
            <EmptyState icon="mdi:shield-check-outline" message="No runtime security alerts with the selected filters." />
          )}
        </div>

        {totalPages > 1 && (
          <div className={`flex items-center justify-between border-t px-5 py-3 ${HAIRLINE}`}>
            <span className={SECONDARY}>
              {startIndex + 1}–{Math.min(startIndex + alertsPerPage, filteredAlerts.length)} of {filteredAlerts.length}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className={`rounded-[6px] border px-3 py-1.5 text-[12px] font-semibold ${HAIRLINE} ${INK} hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]`}
              >
                Previous
              </button>
              <button
                type="button"
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className={`rounded-[6px] border px-3 py-1.5 text-[12px] font-semibold ${HAIRLINE} ${INK} hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]`}
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Info footer */}
      <div className={`border-t px-5 py-4 ${HAIRLINE}`}>
        <p className={SECONDARY}>
          Runtime security monitoring with Falco, a CNCF-graduated project for real-time threat detection on Kubernetes workloads.
        </p>
      </div>
    </Surface>
  );
};

export default RuntimeSecurityView;
