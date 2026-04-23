/**
 * Runtime Security View - Comprehensive Falco Security Monitoring
 *
 * Customer-centric security dashboard with detailed threat analysis and remediation guidance
 */

import React, { useState, useMemo } from 'react';

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

const RuntimeSecurityView: React.FC<RuntimeSecurityViewProps> = ({ securityMetrics }) => {
  const [currentPage, setCurrentPage] = useState(1);
  const [selectedSeverity, setSelectedSeverity] = useState<string>('all');
  const [selectedNamespace, setSelectedNamespace] = useState<string>('all');
  const [expandedAlert, setExpandedAlert] = useState<number | null>(null);
  const [hoveredSegment, setHoveredSegment] = useState<string | null>(null);
  const alertsPerPage = 5;

  const totalEvents = securityMetrics?.totalEvents || 0;
  const criticalEvents = securityMetrics?.eventsByPriority?.['CRITICAL'] ||
    securityMetrics?.eventsByPriority?.['4'] || 0;
  const highEvents = securityMetrics?.eventsByPriority?.['HIGH'] ||
    securityMetrics?.eventsByPriority?.['3'] || 0;
  const warningEvents = securityMetrics?.eventsByPriority?.['WARNING'] ||
    securityMetrics?.eventsByPriority?.['2'] || 0;

  // Get unique namespaces
  const namespaces = useMemo(() => {
    const ns = new Set<string>();
    securityMetrics?.alerts?.forEach(alert => {
      if (alert.namespace) ns.add(alert.namespace);
    });
    return Array.from(ns).sort();
  }, [securityMetrics?.alerts]);

  // Filter alerts
  const filteredAlerts = useMemo(() => {
    let alerts = securityMetrics?.alerts || [];

    if (selectedSeverity !== 'all') {
      alerts = alerts.filter(alert => {
        const priority = alert.priority?.toUpperCase();
        if (selectedSeverity === 'CRITICAL') return priority === 'CRITICAL' || priority === '4';
        if (selectedSeverity === 'HIGH') return priority === 'HIGH' || priority === '3';
        if (selectedSeverity === 'WARNING') return priority === 'WARNING' || priority === '2';
        return true;
      });
    }

    if (selectedNamespace !== 'all') {
      alerts = alerts.filter(alert => alert.namespace === selectedNamespace);
    }

    return [...alerts].sort((a, b) => (b.count || 0) - (a.count || 0));
  }, [securityMetrics?.alerts, selectedSeverity, selectedNamespace]);

  // Pagination
  const totalPages = Math.ceil(filteredAlerts.length / alertsPerPage);
  const startIndex = (currentPage - 1) * alertsPerPage;
  const paginatedAlerts = filteredAlerts.slice(startIndex, startIndex + alertsPerPage);

  // Namespace breakdown with distinct light colors for better differentiation
  const namespaceData = useMemo(() => {
    const breakdown: Record<string, number> = {};
    securityMetrics?.alerts?.forEach(alert => {
      const ns = alert.namespace || 'unknown';
      breakdown[ns] = (breakdown[ns] || 0) + (alert.count || 0);
    });

    // Professional light color palette with excellent visual differentiation
    const colorPalette = [
      { fill: '#60A5FA', stroke: '#3B82F6', name: 'Sky Blue' },      // Light blue
      { fill: '#FCD34D', stroke: '#F59E0B', name: 'Amber' },         // Light yellow
      { fill: '#6EE7B7', stroke: '#10B981', name: 'Emerald' },       // Light green
      { fill: '#F87171', stroke: '#EF4444', name: 'Red' },           // Light red
      { fill: '#A78BFA', stroke: '#8B5CF6', name: 'Purple' },        // Light purple
      { fill: '#FB923C', stroke: '#F97316', name: 'Orange' },        // Light orange
      { fill: '#5EEAD4', stroke: '#14B8A6', name: 'Teal' },          // Light teal
      { fill: '#F9A8D4', stroke: '#EC4899', name: 'Pink' },          // Light pink
      { fill: '#A3E635', stroke: '#84CC16', name: 'Lime' },          // Light lime
      { fill: '#93C5FD', stroke: '#3B82F6', name: 'Blue' },          // Lighter blue
      { fill: '#FDE047', stroke: '#EAB308', name: 'Yellow' },        // Bright yellow
      { fill: '#86EFAC', stroke: '#22C55E', name: 'Green' }          // Bright green
    ];

    return Object.entries(breakdown)
      .map(([name, count], index) => ({
        name,
        count,
        color: colorPalette[index % colorPalette.length].fill,
        strokeColor: colorPalette[index % colorPalette.length].stroke,
        percentage: 0
      }))
      .sort((a, b) => b.count - a.count);
  }, [securityMetrics?.alerts]);

  // Calculate percentages
  const totalCount = namespaceData.reduce((sum, ns) => sum + ns.count, 0);
  namespaceData.forEach(ns => {
    ns.percentage = totalCount > 0 ? (ns.count / totalCount) * 100 : 0;
  });

  const getSeverityIcon = (priority: string) => {
    const p = priority?.toUpperCase();
    if (p === 'CRITICAL' || p === '4') return 'fa-skull-crossbones';
    if (p === 'HIGH' || p === '3') return 'fa-exclamation-triangle';
    if (p === 'WARNING' || p === '2') return 'fa-exclamation-circle';
    return 'fa-info-circle';
  };

  const getSeverityColor = (priority: string) => {
    const p = priority?.toUpperCase();
    if (p === 'CRITICAL' || p === '4') return {
      bg: 'bg-red-50',
      border: 'border-red-200',
      text: 'text-red-700',
      badge: 'bg-red-600'
    };
    if (p === 'HIGH' || p === '3') return {
      bg: 'bg-orange-50',
      border: 'border-orange-200',
      text: 'text-orange-700',
      badge: 'bg-orange-600'
    };
    if (p === 'WARNING' || p === '2') return {
      bg: 'bg-amber-50',
      border: 'border-amber-200',
      text: 'text-amber-700',
      badge: 'bg-amber-600'
    };
    return {
      bg: 'bg-blue-50',
      border: 'border-blue-200',
      text: 'text-blue-700',
      badge: 'bg-blue-600'
    };
  };

  const getRuleInfo = (ruleName: string) => {
    return FALCO_RULE_CATALOG[ruleName] || {
      category: 'Security Event',
      description: 'A security event was detected in your cluster. Review the details and take appropriate action.',
      impact: 'Potential security risk that requires investigation.',
      recommendations: [
        'Review the affected resource and verify its legitimacy',
        'Check container logs for suspicious activity',
        'Implement appropriate security policies',
        'Monitor for recurring patterns'
      ],
      references: ['Falco Default Rules', 'Kubernetes Security Best Practices']
    };
  };

  // Create pie chart segments
  const createPieChart = () => {
    if (namespaceData.length === 0) return null;

    let currentAngle = -90;
    const segments = namespaceData.map((ns) => {
      const angle = (ns.percentage / 100) * 360;
      const startAngle = currentAngle;
      const endAngle = currentAngle + angle;
      currentAngle = endAngle;

      const startX = 100 + 80 * Math.cos((startAngle * Math.PI) / 180);
      const startY = 100 + 80 * Math.sin((startAngle * Math.PI) / 180);
      const endX = 100 + 80 * Math.cos((endAngle * Math.PI) / 180);
      const endY = 100 + 80 * Math.sin((endAngle * Math.PI) / 180);

      const largeArcFlag = angle > 180 ? 1 : 0;

      const pathData = [
        `M 100 100`,
        `L ${startX} ${startY}`,
        `A 80 80 0 ${largeArcFlag} 1 ${endX} ${endY}`,
        `Z`
      ].join(' ');

      return {
        pathData,
        color: ns.color,
        strokeColor: ns.strokeColor,
        name: ns.name,
        count: ns.count,
        percentage: ns.percentage
      };
    });

    return segments;
  };

  const pieSegments = createPieChart();

  return (
    <div className="space-y-6">
      {/* Header Section - Compact */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-red-50 flex items-center justify-center">
              <i className="fas fa-shield-virus text-red-600 text-lg"></i>
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-900">Runtime Security</h3>
              <p className="text-sm text-gray-600">Real-time threat detection and behavioral monitoring</p>
            </div>
          </div>

          <div className={`px-4 py-2 rounded-lg border flex items-center gap-2 ${totalEvents > 0
            ? 'bg-red-50 border-red-200 text-red-700'
            : 'bg-green-50 border-green-200 text-green-700'
            }`}>
            <div className={`w-2 h-2 rounded-full animate-pulse ${totalEvents > 0 ? 'bg-red-600' : 'bg-green-600'
              }`}></div>
            <span className="font-semibold text-sm">
              {totalEvents > 0 ? `${totalEvents} Events` : 'All Clear'}
            </span>
          </div>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Critical</span>
            <div className="w-8 h-8 rounded-lg bg-red-100 flex items-center justify-center">
              <i className="fas fa-skull-crossbones text-red-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{criticalEvents}</div>
          <div className="text-xs text-gray-600">Immediate action required</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">High</span>
            <div className="w-8 h-8 rounded-lg bg-orange-100 flex items-center justify-center">
              <i className="fas fa-exclamation-triangle text-orange-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{highEvents}</div>
          <div className="text-xs text-gray-600">Priority response needed</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Warning</span>
            <div className="w-8 h-8 rounded-lg bg-amber-100 flex items-center justify-center">
              <i className="fas fa-exclamation-circle text-amber-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{warningEvents}</div>
          <div className="text-xs text-gray-600">Review and investigate</div>
        </div>

        <div className="bg-white rounded-xl shadow-sm p-5 border border-gray-200">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total Events</span>
            <div className="w-8 h-8 rounded-lg bg-blue-100 flex items-center justify-center">
              <i className="fas fa-stream text-blue-600 text-sm"></i>
            </div>
          </div>
          <div className="text-3xl font-bold text-gray-900 mb-1">{totalEvents}</div>
          <div className="text-xs text-gray-600">Last hour activity</div>
        </div>
      </div>

      {/* Namespace Distribution Chart - Professional with light, distinct colors */}
      {namespaceData.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
          <h3 className="text-lg font-bold text-gray-900 mb-6 flex items-center gap-2">
            <i className="fas fa-chart-pie text-red-600"></i>
            Alerts by Namespace
            <span className="ml-2 px-2 py-1 bg-red-100 text-red-700 rounded-lg text-xs font-bold">
              {namespaceData.length} Namespaces
            </span>
          </h3>

          <div className="flex items-center gap-8">
            {/* Professional Donut Chart with light colors - Compact size */}
            <div className="relative flex-shrink-0">
              <svg width="200" height="200" viewBox="0 0 200 200" className="transform -rotate-90 drop-shadow-lg">
                {/* Outer ring with namespace segments */}
                {pieSegments?.map((segment, index) => {
                  const nsData = namespaceData.find(ns => ns.name === segment.name);
                  return (
                    <g key={index}>
                      <path
                        d={segment.pathData}
                        fill={segment.color}
                        stroke={nsData?.strokeColor || '#FFFFFF'}
                        strokeWidth="2.5"
                        className="transition-all duration-200 cursor-pointer"
                        opacity={hoveredSegment === null || hoveredSegment === segment.name ? 1 : 0.4}
                        onMouseEnter={() => setHoveredSegment(segment.name)}
                        onMouseLeave={() => setHoveredSegment(null)}
                        style={{
                          filter: hoveredSegment === segment.name ? 'brightness(1.15) drop-shadow(0 4px 8px rgba(0,0,0,0.2))' : 'none',
                          transform: hoveredSegment === segment.name ? 'scale(1.05)' : 'scale(1)',
                          transformOrigin: '100px 100px',
                          transition: 'all 0.2s ease-out'
                        }}
                      />
                    </g>
                  );
                })}
                {/* Inner circle to create donut effect with gradient */}
                <defs>
                  <radialGradient id="innerGradient" cx="50%" cy="50%" r="50%">
                    <stop offset="0%" stopColor="#ffffff" />
                    <stop offset="100%" stopColor="#f9fafb" />
                  </radialGradient>
                </defs>
                <circle cx="100" cy="100" r="50" fill="url(#innerGradient)" stroke="#e5e7eb" strokeWidth="2" />
              </svg>

              {/* Center Info - Dynamic and professional */}
              <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 text-center pointer-events-none">
                {hoveredSegment ? (
                  <div className="bg-white px-3 py-2 rounded-md border border-[#d0d7de]">
                    <div className="text-[20px] font-semibold text-[#1a1f2e] mb-0.5">
                      {namespaceData.find(ns => ns.name === hoveredSegment)?.count || 0}
                    </div>
                    <div className="text-[10px] text-[#8b949e] uppercase tracking-wide mb-0.5">Alerts</div>
                    <div className="text-[10px] text-[#57606a] font-mono max-w-[80px] truncate">
                      {hoveredSegment}
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="text-[26px] font-semibold text-[#1a1f2e] mb-0.5">{totalCount}</div>
                    <div className="text-[10px] text-[#8b949e] uppercase tracking-wide">Total Alerts</div>
                  </>
                )}
              </div>
            </div>

            {/* Enhanced Legend with color-coded cards */}
            <div className="flex-1 grid grid-cols-2 gap-3">
              {namespaceData.map((ns, index) => (
                <div
                  key={index}
                  className={`flex items-center gap-3 p-3 rounded-md border transition-all cursor-pointer ${hoveredSegment === ns.name
                    ? 'border-[#d0d7de] bg-[#f6f8fa]'
                    : 'border-[#e8ecf0] hover:border-[#d0d7de]'
                    }`}
                  onMouseEnter={() => setHoveredSegment(ns.name)}
                  onMouseLeave={() => setHoveredSegment(null)}
                >
                  <div className="flex-shrink-0">
                    <div
                      className="w-5 h-5 rounded"
                      style={{ backgroundColor: ns.color }}
                    ></div>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="text-[12px] font-medium text-[#1a1f2e] truncate font-mono">
                      {ns.name}
                    </div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[12px] font-semibold text-[#1a1f2e]">{ns.count}</span>
                      <span className="text-[11px] text-[#8b949e]">({ns.percentage.toFixed(1)}%)</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Horizontal Bar Chart View */}
          <div className="mt-6 pt-5 border-t border-[#e8ecf0]">
            <h4 className="text-[11px] font-medium text-[#8b949e] mb-4 uppercase tracking-wider flex items-center gap-2">
              <i className="fas fa-chart-bar text-[#cf222e]"></i>
              Alert Distribution by Namespace
            </h4>
            <div className="space-y-2.5">
              {namespaceData.map((ns, index) => (
                <div
                  key={index}
                  className="group cursor-pointer"
                  onMouseEnter={() => setHoveredSegment(ns.name)}
                  onMouseLeave={() => setHoveredSegment(null)}
                >
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center gap-2">
                      <div
                        className="w-3 h-3 rounded-sm"
                        style={{ backgroundColor: ns.color }}
                      ></div>
                      <span className="text-[12px] font-medium text-[#57606a] font-mono">{ns.name}</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[12px] font-semibold text-[#1a1f2e]">{ns.count}</span>
                      <span className="text-[11px] text-[#8b949e]">alerts</span>
                    </div>
                  </div>
                  <div className="w-full bg-[#e8ecf0] rounded-full h-1.5 overflow-hidden">
                    <div
                      className="h-1.5 rounded-full transition-all duration-500"
                      style={{
                        width: `${ns.percentage}%`,
                        backgroundColor: ns.color
                      }}
                    ></div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Alerts Section */}
      <div className="bg-white rounded-xl shadow-sm p-6 border border-gray-200">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
            <i className="fas fa-bell text-red-600"></i>
            Security Alerts
            <span className="text-sm font-normal text-gray-500 bg-gray-100 px-2 py-1 rounded">
              {filteredAlerts.length}
            </span>
          </h3>

          <div className="flex items-center gap-2">
            <select
              value={selectedSeverity}
              onChange={(e) => {
                setSelectedSeverity(e.target.value);
                setCurrentPage(1);
              }}
              className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 bg-white hover:border-gray-400 focus:outline-none focus:ring-2 focus:ring-red-500"
            >
              <option value="all">All Severities</option>
              <option value="CRITICAL">Critical</option>
              <option value="HIGH">High</option>
              <option value="WARNING">Warning</option>
            </select>

            <select
              value={selectedNamespace}
              onChange={(e) => {
                setSelectedNamespace(e.target.value);
                setCurrentPage(1);
              }}
              className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 bg-white hover:border-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="all">All Namespaces</option>
              {namespaces.map(ns => (
                <option key={ns} value={ns}>{ns}</option>
              ))}
            </select>
          </div>
        </div>

        {/* Alerts List */}
        <div className="space-y-3">
          {paginatedAlerts.length === 0 ? (
            <div className="text-center py-12 bg-gray-50 rounded-lg border border-gray-200">
              <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-3">
                <i className="fas fa-shield-check text-green-600 text-2xl"></i>
              </div>
              <h4 className="text-lg font-bold text-gray-900 mb-1">No Security Threats</h4>
              <p className="text-sm text-gray-600">Your cluster is secure with the selected filters</p>
            </div>
          ) : (
            paginatedAlerts.map((alert, index) => {
              const colors = getSeverityColor(alert.priority);
              const icon = getSeverityIcon(alert.priority);
              const ruleInfo = getRuleInfo(alert.rule);
              const isExpanded = expandedAlert === startIndex + index;

              return (
                <div
                  key={startIndex + index}
                  className={`border rounded-lg transition-all ${isExpanded ? `${colors.border} border-2 shadow-md` : 'border-gray-200 hover:border-gray-300'
                    }`}
                >
                  {/* Alert Header */}
                  <div
                    className="p-4 cursor-pointer"
                    onClick={() => setExpandedAlert(isExpanded ? null : startIndex + index)}
                  >
                    <div className="flex items-start gap-3">
                      <div className={`w-10 h-10 ${colors.badge} rounded-lg flex items-center justify-center text-white flex-shrink-0`}>
                        <i className={`fas ${icon}`}></i>
                      </div>

                      <div className="flex-1 min-w-0">
                        <div className="flex items-start justify-between gap-3">
                          <div className="flex-1">
                            <h4 className="text-base font-semibold text-gray-900 mb-2">{alert.rule}</h4>
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-semibold ${colors.bg} ${colors.text}`}>
                                {alert.priority?.toUpperCase()}
                              </span>
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-50 text-blue-700 rounded text-xs font-medium font-mono">
                                <i className="fas fa-cube text-xs"></i>
                                {alert.namespace}
                              </span>
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-purple-50 text-purple-700 rounded text-xs font-medium font-mono">
                                <i className="fas fa-server text-xs"></i>
                                {alert.pod}
                              </span>
                              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-gray-100 text-gray-700 rounded text-xs font-semibold">
                                {alert.count} occurrences
                              </span>
                            </div>
                          </div>

                          <button className="text-gray-400 hover:text-gray-600 transition-colors">
                            <i className={`fas fa-chevron-${isExpanded ? 'up' : 'down'}`}></i>
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Expanded Details */}
                  {isExpanded && (
                    <div className={`border-t p-5 ${colors.bg} space-y-4`}>
                      <div>
                        <span className={`px-2 py-1 ${colors.badge} text-white rounded text-xs font-semibold uppercase tracking-wide`}>
                          {ruleInfo.category}
                        </span>
                        <p className="text-sm text-gray-700 mt-3 leading-relaxed">{ruleInfo.description}</p>
                      </div>

                      <div className="bg-white rounded-lg p-4 border border-amber-200">
                        <h5 className="font-semibold text-gray-900 mb-2 flex items-center gap-2 text-sm">
                          <i className="fas fa-exclamation-triangle text-amber-600"></i>
                          Potential Impact
                        </h5>
                        <p className="text-sm text-gray-700">{ruleInfo.impact}</p>
                      </div>

                      <div className="bg-white rounded-lg p-4 border border-green-200">
                        <h5 className="font-semibold text-gray-900 mb-3 flex items-center gap-2 text-sm">
                          <i className="fas fa-lightbulb text-green-600"></i>
                          Recommended Actions
                        </h5>
                        <ul className="space-y-2">
                          {ruleInfo.recommendations.map((rec, idx) => (
                            <li key={idx} className="flex items-start gap-2 text-sm">
                              <div className="w-5 h-5 bg-green-100 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5">
                                <span className="text-green-700 font-bold text-xs">{idx + 1}</span>
                              </div>
                              <span className="text-gray-700">{rec}</span>
                            </li>
                          ))}
                        </ul>
                      </div>

                      <div className="bg-white rounded-lg p-4 border border-blue-200">
                        <h5 className="font-semibold text-gray-900 mb-2 flex items-center gap-2 text-sm">
                          <i className="fas fa-book text-blue-600"></i>
                          Industry Standards
                        </h5>
                        <div className="flex flex-wrap gap-2">
                          {ruleInfo.references.map((ref, idx) => (
                            <span key={idx} className="px-2 py-1 bg-blue-50 text-blue-700 rounded text-xs font-medium">
                              {ref}
                            </span>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between mt-6 pt-4 border-t border-gray-200">
            <div className="text-sm text-gray-600">
              Showing {startIndex + 1}-{Math.min(startIndex + alertsPerPage, filteredAlerts.length)} of {filteredAlerts.length}
            </div>

            <div className="flex items-center gap-1">
              <button
                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="px-3 py-1 border border-gray-300 rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-50"
              >
                <i className="fas fa-chevron-left"></i>
              </button>

              {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                let page;
                if (totalPages <= 5) {
                  page = i + 1;
                } else if (currentPage <= 3) {
                  page = i + 1;
                } else if (currentPage >= totalPages - 2) {
                  page = totalPages - 4 + i;
                } else {
                  page = currentPage - 2 + i;
                }

                return (
                  <button
                    key={page}
                    onClick={() => setCurrentPage(page)}
                    className={`w-8 h-8 rounded-lg text-sm font-semibold transition-all ${page === currentPage
                      ? 'bg-red-600 text-white'
                      : 'border border-gray-300 text-gray-700 hover:bg-gray-50'
                      }`}
                  >
                    {page}
                  </button>
                );
              })}

              <button
                onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="px-3 py-1 border border-gray-300 rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-50"
              >
                <i className="fas fa-chevron-right"></i>
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Info Footer */}
      <div className="bg-blue-50 rounded-lg p-4 border border-blue-200">
        <div className="flex items-start gap-3">
          <div className="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center flex-shrink-0">
            <i className="fas fa-info-circle text-white text-sm"></i>
          </div>
          <div>
            <h4 className="font-semibold text-gray-900 text-sm mb-1">About Runtime Security</h4>
            <p className="text-xs text-gray-700 leading-relaxed">
              Runtime security monitoring , a CNCF-graduated project that provides real-time threat detection
              for Kubernetes workloads. Review alerts regularly and implement recommended security policies.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default RuntimeSecurityView;
