/**
 * Kubescape Security & Compliance View.
 *
 * FEAT-005 v3 roll-out: the decorative red / orange / green Tailwind ramps, the rounded-xl
 * cards and the tinted severity pills are gone — colour carries meaning only, as a StatusDot
 * (dot + word) or a token-filled bar (score / distribution), so dark mode is automatic. The summary
 * cards collapse into one hero MetricStrip; frameworks render as Surface rows with a token score
 * bar and a per-framework DetailDrawer (its real guidance); the vulnerable-workload list is a
 * DataTable + DetailDrawer. Radii are only the sanctioned {8px, 6px, pill}.
 *
 * The complianceScore / framework scores / severity counts and the workload pagination are unchanged.
 */

import React, { useMemo, useState, type ReactNode } from 'react';
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

interface KubescapeData {
  totalCritical: number;
  totalHigh: number;
  totalMedium: number;
  totalLow: number;
  totalWorkloads?: number;
  namespaceBreakdown: Record<string, NamespaceVulnerabilities>;
  frameworks: FrameworkScore[];
  vulnerabilities?: KubescapeVulnerableWorkload[];
}

interface NamespaceVulnerabilities {
  critical: number;
  high: number;
  medium: number;
  low: number;
}

interface FrameworkScore {
  name: string;
  score: number;
  passedControls: number;
  failedControls: number;
}

interface KubescapeVulnerableWorkload {
  workloadName: string;
  namespace: string;
  workloadKind: string;
  criticalCount: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
}

interface KubescapeComplianceViewProps {
  kubescapeData: KubescapeData;
}

// Compliance framework descriptions and remediation guidance
const FRAMEWORK_GUIDANCE: Record<string, {
  description: string;
  commonIssues: string[];
  quickWins: string[];
  references: string[];
}> = {
  'NSA-CISA': {
    description: 'NSA and CISA Kubernetes Hardening Guidance focuses on protecting against common threat actors and attack vectors.',
    commonIssues: [
      'Pods running as root user',
      'Containers with privileged access',
      'Missing network policies',
      'Inadequate RBAC controls',
      'Unencrypted secrets'
    ],
    quickWins: [
      'Set runAsNonRoot: true in pod security context',
      'Remove privileged: true from container specs',
      'Implement default-deny network policies',
      'Enable audit logging',
      'Use secrets encryption at rest'
    ],
    references: ['NSA/CISA Kubernetes Hardening Guide v1.2', 'CISA Alert AA20-245A']
  },
  'MITRE': {
    description: 'MITRE ATT&CK framework for containers maps adversary tactics and techniques specific to containerized environments.',
    commonIssues: [
      'Exposed container runtime sockets',
      'Unrestricted container capabilities',
      'Missing security contexts',
      'Weak pod security policies',
      'Insufficient monitoring'
    ],
    quickWins: [
      'Remove docker.sock mounts',
      'Drop unnecessary Linux capabilities',
      'Implement Pod Security Admission',
      'Enable runtime security monitoring',
      'Implement behavioral analysis'
    ],
    references: ['MITRE ATT&CK for Containers', 'ATT&CK Matrix v12']
  },
  'CIS Kubernetes Benchmark': {
    description: 'CIS Kubernetes Benchmark provides prescriptive guidance for establishing a secure configuration posture.',
    commonIssues: [
      'Default service accounts in use',
      'Anonymous auth enabled',
      'Insecure API server settings',
      'Missing admission controllers',
      'Weak authentication mechanisms'
    ],
    quickWins: [
      'Disable anonymous-auth on API server',
      'Create dedicated service accounts',
      'Enable PodSecurity admission controller',
      'Implement strong authentication',
      'Regular security audits'
    ],
    references: ['CIS Kubernetes Benchmark v1.7', 'CIS Controls v8']
  }
};

const DASH = '—';

/** Chrome ink / muted / hairline, all through the chrome tokens so dark mode is free. */
const INK = 'text-[color:var(--chrome-ink)]';
const MUTED = 'text-[color:var(--chrome-muted)]';
const HAIRLINE = 'border-[color:var(--chrome-line)]';

/** A compliance score → a semantic tone (good ≥ 80, attention ≥ 60, failure below). */
function scoreTone(score: number): Tone {
  return score >= 80 ? 'good' : score >= 60 ? 'warn' : 'bad';
}

/** A severity → tone (same meaning as the CVE severity dots elsewhere). */
const SEVERITY_TONES: Record<'Critical' | 'High' | 'Medium' | 'Low', Tone> = {
  Critical: 'bad', High: 'warn', Medium: 'info', Low: 'off',
};

interface WorkloadRow {
  key: string;
  workload: KubescapeVulnerableWorkload;
}

const KubescapeComplianceView: React.FC<KubescapeComplianceViewProps> = ({ kubescapeData }) => {
  const [workloadPage, setWorkloadPage] = useState(1);
  const [selectedSeverity, setSelectedSeverity] = useState<string>('all');
  const workloadsPerPage = 10;

  const totalVulnerabilities =
    kubescapeData.totalCritical + kubescapeData.totalHigh + kubescapeData.totalMedium + kubescapeData.totalLow;

  // Severity distribution (PRESERVED counts), non-zero only.
  const distribution = ([
    { label: 'Critical' as const, value: kubescapeData.totalCritical },
    { label: 'High' as const, value: kubescapeData.totalHigh },
    { label: 'Medium' as const, value: kubescapeData.totalMedium },
    { label: 'Low' as const, value: kubescapeData.totalLow },
  ]).filter((d) => d.value > 0);

  // Top vulnerable namespaces (PRESERVED aggregation).
  const namespaceVulns = useMemo(
    () =>
      Object.entries(kubescapeData.namespaceBreakdown || {})
        .map(([name, vulns]) => ({
          name,
          total: vulns.critical + vulns.high + vulns.medium + vulns.low,
          critical: vulns.critical,
          high: vulns.high,
          medium: vulns.medium,
        }))
        .sort((a, b) => b.total - a.total)
        .slice(0, 5),
    [kubescapeData.namespaceBreakdown],
  );

  // Workload filter + sort (PRESERVED logic).
  const filteredWorkloads = useMemo(() => {
    let workloads = kubescapeData.vulnerabilities || [];
    if (selectedSeverity === 'critical') workloads = workloads.filter((w) => w.criticalCount > 0);
    else if (selectedSeverity === 'high') workloads = workloads.filter((w) => w.highCount > 0);
    else if (selectedSeverity === 'medium') workloads = workloads.filter((w) => w.mediumCount > 0);
    return [...workloads].sort((a, b) => {
      const scoreA = a.criticalCount * 10 + a.highCount * 5 + a.mediumCount * 2 + a.lowCount;
      const scoreB = b.criticalCount * 10 + b.highCount * 5 + b.mediumCount * 2 + b.lowCount;
      return scoreB - scoreA;
    });
  }, [kubescapeData.vulnerabilities, selectedSeverity]);

  // PRESERVED pagination.
  const totalWorkloadPages = Math.ceil(filteredWorkloads.length / workloadsPerPage) || 1;
  const paginatedWorkloads = filteredWorkloads.slice(
    (workloadPage - 1) * workloadsPerPage,
    workloadPage * workloadsPerPage,
  );

  // Overall score: the mean of the framework scores (PRESERVED). No frameworks → null (honest dash).
  const overallScore = useMemo(() => {
    if (!kubescapeData.frameworks || kubescapeData.frameworks.length === 0) return null;
    const avg = kubescapeData.frameworks.reduce((sum, f) => sum + f.score, 0) / kubescapeData.frameworks.length;
    return Math.round(avg);
  }, [kubescapeData.frameworks]);

  const stripCells: readonly MetricCell[] = [
    {
      label: 'Compliance score',
      value:
        overallScore === null ? (
          <span className={MUTED} title="No compliance framework reported.">{DASH}</span>
        ) : (
          <span className={toneClasses(scoreTone(overallScore)).text}>{`${overallScore}%`}</span>
        ),
      sub: overallScore === null ? 'No framework scored' : 'across frameworks',
    },
    { label: 'Total issues', value: totalVulnerabilities, sub: `across ${kubescapeData.totalWorkloads || 0} workloads` },
    { label: 'Critical', value: <span className={kubescapeData.totalCritical > 0 ? toneClasses('bad').text : INK}>{kubescapeData.totalCritical}</span>, sub: 'fix immediately' },
    { label: 'High', value: <span className={kubescapeData.totalHigh > 0 ? toneClasses('warn').text : INK}>{kubescapeData.totalHigh}</span>, sub: 'priority fixes' },
  ];

  const workloadRows: readonly WorkloadRow[] = paginatedWorkloads.map((w, i) => ({
    key: `${w.namespace}/${w.workloadName}-${i}`,
    workload: w,
  }));

  const columns: ReadonlyArray<ColumnDef<WorkloadRow>> = [
    {
      id: 'name', header: 'Workload', priority: 'essential', width: '240px',
      cell: (r) => (
        <span className="flex min-w-0 flex-col">
          <span className={`truncate font-semibold ${BODY}`} title={r.workload.workloadName}>{r.workload.workloadName}</span>
          <span className={`inline-flex items-center gap-1.5 ${SECONDARY}`}>
            <StatusDot tone="off" label={r.workload.workloadKind} />
          </span>
        </span>
      ),
      sortValue: (r) => r.workload.workloadName, csv: (r) => r.workload.workloadName,
    },
    {
      id: 'namespace', header: 'Namespace', width: '180px',
      cell: (r) => <span className={`block truncate font-mono text-[12px] ${MUTED}`} title={r.workload.namespace}>{r.workload.namespace}</span>,
      sortValue: (r) => r.workload.namespace, csv: (r) => r.workload.namespace,
    },
    {
      id: 'critical', header: 'Critical', align: 'right', width: '96px',
      cell: (r) => <span className={`tabular-nums ${r.workload.criticalCount > 0 ? toneClasses('bad').text : MUTED}`}>{r.workload.criticalCount || DASH}</span>,
      sortValue: (r) => r.workload.criticalCount, csv: (r) => r.workload.criticalCount,
    },
    {
      id: 'high', header: 'High', align: 'right', width: '88px',
      cell: (r) => <span className={`tabular-nums ${r.workload.highCount > 0 ? toneClasses('warn').text : MUTED}`}>{r.workload.highCount || DASH}</span>,
      sortValue: (r) => r.workload.highCount, csv: (r) => r.workload.highCount,
    },
    {
      id: 'medium', header: 'Medium', align: 'right', width: '96px',
      cell: (r) => <span className={`tabular-nums ${MUTED}`}>{r.workload.mediumCount || DASH}</span>,
      sortValue: (r) => r.workload.mediumCount, csv: (r) => r.workload.mediumCount,
    },
    {
      id: 'low', header: 'Low', align: 'right', width: '88px',
      cell: (r) => <span className={`tabular-nums ${MUTED}`}>{r.workload.lowCount || DASH}</span>,
      sortValue: (r) => r.workload.lowCount, csv: (r) => r.workload.lowCount,
    },
  ];

  const rowToSections = (r: WorkloadRow): DrawerSection[] => {
    const w = r.workload;
    const identity: DrawerSection = {
      title: 'Workload',
      data: [
        { label: 'Name', value: <span className="break-all">{w.workloadName}</span> },
        { label: 'Kind', value: <StatusDot tone="off" label={w.workloadKind} /> },
        { label: 'Namespace', value: <span className="break-all font-mono text-[12px]">{w.namespace}</span> },
      ],
    };
    const counts: DrawerSection = {
      title: 'Findings',
      data: [
        { label: 'Critical', value: w.criticalCount > 0 ? <span className="tabular-nums">{w.criticalCount}</span> : null, reason: 'None at this severity.' },
        { label: 'High', value: w.highCount > 0 ? <span className="tabular-nums">{w.highCount}</span> : null, reason: 'None at this severity.' },
        { label: 'Medium', value: w.mediumCount > 0 ? <span className="tabular-nums">{w.mediumCount}</span> : null, reason: 'None at this severity.' },
        { label: 'Low', value: w.lowCount > 0 ? <span className="tabular-nums">{w.lowCount}</span> : null, reason: 'None at this severity.' },
      ],
    };
    return [identity, counts];
  };

  /** The per-framework drawer: score, controls, and its real guidance (description, issues, wins). */
  const frameworkSections = (f: FrameworkScore): DrawerSection[] => {
    const guidance = FRAMEWORK_GUIDANCE[f.name];
    const overview: DrawerSection = {
      title: 'Framework',
      data: [
        { label: 'Name', value: f.name },
        { label: 'Score', value: <span className={`tabular-nums ${toneClasses(scoreTone(f.score)).text}`}>{`${f.score.toFixed(0)}%`}</span> },
        { label: 'Passed controls', value: <span className="tabular-nums">{f.passedControls}</span> },
        { label: 'Failed controls', value: <span className="tabular-nums">{f.failedControls}</span> },
        { label: 'About', value: guidance ? <span className="leading-relaxed">{guidance.description}</span> : null, reason: 'No guidance catalogued for this framework.' },
      ],
    };
    const sections: DrawerSection[] = [overview];
    if (guidance) {
      const issues: DrawerDatum[] = guidance.commonIssues.slice(0, 3).map((issue, i) => ({ label: `Issue ${i + 1}`, value: <span className="leading-relaxed">{issue}</span> }));
      const wins: DrawerDatum[] = guidance.quickWins.slice(0, 3).map((win, i) => ({ label: `Fix ${i + 1}`, value: <span className="leading-relaxed">{win}</span> }));
      sections.push({ title: 'Common issues', data: issues });
      sections.push({ title: 'Quick wins', data: wins });
      sections.push({ title: 'References', data: guidance.references.map((ref, i) => ({ label: `Reference ${i + 1}`, value: <span className="font-mono text-[12px]">{ref}</span> })) });
    }
    return sections;
  };

  const selectClass =
    `rounded-[6px] border px-3 py-1.5 text-[12px] font-medium ${HAIRLINE} ${INK} bg-[color:var(--chrome-card)] ` +
    'outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]';

  const intro: ReactNode = 'Kubescape configuration scanning against NSA-CISA, MITRE ATT&CK and CIS benchmarks.';

  return (
    <Surface tier="table" className="overflow-hidden" aria-label="Security and compliance">
      <div className={`flex items-center justify-between border-b px-5 py-3.5 ${HAIRLINE}`}>
        <div className="min-w-0">
          <span className={TITLE}>Security &amp; compliance</span>
          <p className={SECONDARY}>{intro}</p>
        </div>
        {overallScore !== null && (
          <StatusDot tone={scoreTone(overallScore)} label={`${overallScore}% overall`} />
        )}
      </div>

      {/* Hero strip (PRESERVED counts + score) */}
      <div className={`border-b px-5 py-4 ${HAIRLINE}`}>
        <MetricStrip cells={stripCells} heroLead aria-label="Compliance posture" />
      </div>

      {/* Compliance frameworks — Surface rows with a token score bar + per-framework drawer */}
      {kubescapeData.frameworks && kubescapeData.frameworks.length > 0 && (
        <div className={`border-b px-5 py-4 ${HAIRLINE}`}>
          <span className={LABEL}>Compliance framework status</span>
          <div className="mt-3">
            <DataTable<FrameworkScore>
              rows={kubescapeData.frameworks}
              columns={[
                {
                  id: 'name', header: 'Framework', priority: 'essential', width: '220px',
                  cell: (f) => <span className={`font-semibold ${BODY}`}>{f.name}</span>,
                  sortValue: (f) => f.name, csv: (f) => f.name,
                },
                {
                  id: 'score', header: 'Score', width: '220px',
                  cell: (f) => (
                    <span className="flex items-center gap-3">
                      <span className="h-1.5 w-28 overflow-hidden rounded-full bg-[color:var(--chrome-surface-2)]">
                        <span className={`block h-full rounded-full bg-current ${toneClasses(scoreTone(f.score)).text}`} style={{ width: `${f.score}%` }} />
                      </span>
                      <span className={`tabular-nums ${BODY} ${toneClasses(scoreTone(f.score)).text}`}>{f.score.toFixed(0)}%</span>
                    </span>
                  ),
                  sortValue: (f) => f.score, csv: (f) => f.score,
                },
                {
                  id: 'passed', header: 'Passed', align: 'right', width: '100px',
                  cell: (f) => <span className={`tabular-nums ${toneClasses('good').text}`}>{f.passedControls}</span>,
                  sortValue: (f) => f.passedControls, csv: (f) => f.passedControls,
                },
                {
                  id: 'failed', header: 'Failed', align: 'right', width: '100px',
                  cell: (f) => <span className={`tabular-nums ${f.failedControls > 0 ? toneClasses('bad').text : MUTED}`}>{f.failedControls || DASH}</span>,
                  sortValue: (f) => f.failedControls, csv: (f) => f.failedControls,
                },
              ]}
              rowKey={(f) => f.name}
              caption="Compliance framework status"
              rowLabel={(f) => f.name}
              rowTitle={(f) => f.name}
              rowToSections={frameworkSections}
            />
          </div>
        </div>
      )}

      {/* Severity distribution + top namespaces */}
      <div className={`grid grid-cols-1 gap-x-8 gap-y-6 border-b px-5 py-4 lg:grid-cols-2 ${HAIRLINE}`}>
        <div>
          <span className={LABEL}>Vulnerability distribution</span>
          {distribution.length > 0 ? (
            <div className="mt-3 flex flex-col gap-2">
              {distribution.map((item) => {
                const pct = totalVulnerabilities > 0 ? (item.value / totalVulnerabilities) * 100 : 0;
                const tone = SEVERITY_TONES[item.label];
                return (
                  <div key={item.label} className="flex items-center gap-3">
                    <span className={`w-16 ${BODY} ${toneClasses(tone).text}`}>{item.label}</span>
                    <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-[color:var(--chrome-surface-2)]">
                      <span className={`block h-full rounded-full bg-current ${toneClasses(tone).text}`} style={{ width: `${pct}%` }} />
                    </span>
                    <span className={`w-10 text-right tabular-nums ${BODY} ${INK}`}>{item.value}</span>
                    <span className={`w-12 tabular-nums ${SECONDARY}`}>{pct.toFixed(0)}%</span>
                  </div>
                );
              })}
            </div>
          ) : (
            <p className={`mt-3 ${BODY} ${MUTED}`}>No vulnerabilities detected.</p>
          )}
        </div>

        <div>
          <span className={LABEL}>Most vulnerable namespaces</span>
          {namespaceVulns.length > 0 ? (
            <div className="mt-3 flex flex-col gap-2.5">
              {namespaceVulns.map((ns) => (
                <div key={ns.name} className={`rounded-[6px] border px-3 py-2 ${HAIRLINE}`}>
                  <div className="flex items-center justify-between">
                    <span className={`truncate font-mono text-[12px] ${INK}`} title={ns.name}>{ns.name}</span>
                    <span className={`tabular-nums ${BODY} ${INK}`}>{ns.total} issues</span>
                  </div>
                  <div className="mt-1.5 flex flex-wrap items-center gap-3">
                    {ns.critical > 0 && <StatusDot tone="bad" label={`${ns.critical} critical`} />}
                    {ns.high > 0 && <StatusDot tone="warn" label={`${ns.high} high`} />}
                    {ns.medium > 0 && <StatusDot tone="info" label={`${ns.medium} medium`} />}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p className={`mt-3 ${BODY} ${MUTED}`}>No namespace data available.</p>
          )}
        </div>
      </div>

      {/* Vulnerable workloads — DataTable + per-row DetailDrawer */}
      {kubescapeData.vulnerabilities && kubescapeData.vulnerabilities.length > 0 && (
        <div>
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5">
            <span className={TITLE}>Vulnerable workloads <span className={SECONDARY}>({filteredWorkloads.length})</span></span>
            <select
              aria-label="Filter by severity"
              value={selectedSeverity}
              onChange={(e) => { setSelectedSeverity(e.target.value); setWorkloadPage(1); }}
              className={selectClass}
            >
              <option value="all">All severities</option>
              <option value="critical">Critical only</option>
              <option value="high">High priority</option>
              <option value="medium">Medium priority</option>
            </select>
          </div>

          <div className="px-5 pb-5">
            {filteredWorkloads.length > 0 ? (
              <DataTable<WorkloadRow>
                rows={workloadRows}
                columns={columns}
                rowKey={(r) => r.key}
                caption="Vulnerable workloads"
                rowLabel={(r) => r.workload.workloadName}
                rowTitle={(r) => r.workload.workloadName}
                rowSubtitle={(r) => r.workload.namespace}
                rowToSections={rowToSections}
              />
            ) : (
              <EmptyState icon="mdi:shield-check-outline" message="No vulnerable workloads with the selected filter." />
            )}
          </div>

          {totalWorkloadPages > 1 && (
            <div className={`flex items-center justify-between border-t px-5 py-3 ${HAIRLINE}`}>
              <span className={SECONDARY}>
                {(workloadPage - 1) * workloadsPerPage + 1}–{Math.min(workloadPage * workloadsPerPage, filteredWorkloads.length)} of {filteredWorkloads.length}
              </span>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setWorkloadPage((p) => Math.max(1, p - 1))}
                  disabled={workloadPage === 1}
                  className={`rounded-[6px] border px-3 py-1.5 text-[12px] font-semibold ${HAIRLINE} ${INK} hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]`}
                >
                  Previous
                </button>
                <button
                  type="button"
                  onClick={() => setWorkloadPage((p) => Math.min(totalWorkloadPages, p + 1))}
                  disabled={workloadPage === totalWorkloadPages}
                  className={`rounded-[6px] border px-3 py-1.5 text-[12px] font-semibold ${HAIRLINE} ${INK} hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]`}
                >
                  Next
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Info footer */}
      <div className={`border-t px-5 py-4 ${HAIRLINE}`}>
        <p className={SECONDARY}>
          Kubescape analyses your Kubernetes configuration against NSA-CISA, MITRE ATT&amp;CK and CIS benchmarks. Focus on critical and high-severity issues first.
        </p>
      </div>
    </Surface>
  );
};

export default KubescapeComplianceView;
