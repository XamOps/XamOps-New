/**
 * EKS Security Tab — Trivy image scanning, Falco runtime and Kubescape compliance.
 *
 * FEAT-005 v3 roll-out (docs/cloudk8s/ui-revamp-design.md §4.2, §7, §8): the tab stamps the one
 * PageTemplate (width='table') the flagship Pods/Cost slices use, so it renders inside the
 * redesigned [data-k8s-redesigned-main] region and obeys the visual gate. The raw KPI grid collapses
 * into ONE hero MetricStrip (security score · critical · runtime alerts · compliance); the Trivy
 * vulnerable-image list becomes a DataTable whose rows open a DetailDrawer of the real CVE rows.
 *
 * Colour carries meaning only: the decorative hex (#cf222e / #9a6700 / #1a7f37 / #0550ae) and the
 * inline `style={{ background }}` severity pills are gone — severity reaches the page only as a
 * StatusDot (dot + word) or a token-filled bar (bg-status-*), so dark mode is automatic. Radii are
 * only the sanctioned {8px surface, 6px control, pill}.
 *
 * HONESTY (CLAUDE.md): a score is shown only when its inputs exist — a missing Trivy / Falco /
 * Kubescape read renders the em-dash, never a fabricated 100 or 0. The securityScore formula
 * (Math.max(0, 100 - (crit*10 + high*3 + med*1))), the Falco totalEvents / eventsByPriority roll-up,
 * the Kubescape complianceScore and the vulnerable-image pagination are unchanged.
 */

import React, { useMemo, useState, type ReactNode } from 'react';
import RuntimeSecurityView from './RuntimeSecurityView';
import KubescapeComplianceView from './KubescapeComplianceView';
import { PageTemplate } from '../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../CloudK8s/shared/MetricStrip';
import { Surface } from '../../CloudK8s/shared/Surface';
import { DataTable } from '../../CloudK8s/shared/DataTable';
import { EmptyState } from '../../CloudK8s/shared/EmptyState';
import { StatusDot } from '../../CloudK8s/shared/status/StatusDot';
import type { Tone } from '../../CloudK8s/shared/status/statusTones';
import { toneClasses } from '../../CloudK8s/shared/status/toneClasses';
import type { ColumnDef } from '../../CloudK8s/shared/K8sDataTable';
import type { DrawerSection, DrawerDatum } from '../../CloudK8s/shared/DetailDrawer';
import { FIGURE, TITLE, BODY, SECONDARY, LABEL } from '../../CloudK8s/shared/typeScale';

interface EksSecurityTabProps {
  eksData: any;
}

const DASH = '—';

/** Chrome ink / muted / hairline, all through the chrome tokens so dark mode is free. */
const INK = 'text-[color:var(--chrome-ink)]';
const MUTED = 'text-[color:var(--chrome-muted)]';
const HAIRLINE = 'border-[color:var(--chrome-line)]';

/** CVE/finding severity → a semantic tone: colour only through a dot + word, never a tinted pill. */
function severityTone(severity: string | null | undefined): { tone: Tone; label: string } {
  switch ((severity ?? '').toUpperCase()) {
    case 'CRITICAL': return { tone: 'bad', label: 'Critical' };
    case 'HIGH': return { tone: 'warn', label: 'High' };
    case 'MEDIUM': return { tone: 'info', label: 'Medium' };
    case 'LOW': return { tone: 'off', label: 'Low' };
    default: return { tone: 'info', label: severity ?? 'Unknown' };
  }
}

/** A severity → tone map for the CVE-breakdown bars (same meaning as the severity dots). */
const BREAKDOWN_TONES: Record<'Critical' | 'High' | 'Medium' | 'Low', Tone> = {
  Critical: 'bad', High: 'warn', Medium: 'info', Low: 'off',
};

interface VulnRow {
  key: string;
  workload: any;
}

const EksSecurityTab: React.FC<EksSecurityTabProps> = ({ eksData }) => {
  const { dashboard } = eksData ?? {};
  const [vulnImagePage, setVulnImagePage] = useState(1);
  const imagesPerPage = 5;

  const trivyData = dashboard?.trivy ?? null;

  // Pagination for vulnerable images (unchanged logic).
  const vulnerableImages = useMemo(
    () =>
      (trivyData?.workloads ?? []).filter(
        (w: any) => (w.critical || 0) + (w.high || 0) + (w.medium || 0) + (w.low || 0) > 0,
      ),
    [trivyData],
  );

  const totalVulnPages = Math.ceil(vulnerableImages.length / imagesPerPage) || 1;
  const paginatedImages = vulnerableImages.slice(
    (vulnImagePage - 1) * imagesPerPage,
    vulnImagePage * imagesPerPage,
  );

  if (!dashboard) return null;

  // ── Hero figures (PRESERVED formulae; honest em-dash when the read is absent) ──
  const hasTrivy = Boolean(trivyData);
  const totalCritical = trivyData?.summary?.totalCritical || 0;
  const totalHigh = trivyData?.summary?.totalHigh || 0;
  const totalMedium = trivyData?.summary?.totalMedium || 0;
  const totalLow = trivyData?.summary?.totalLow || 0;
  const totalVulns = totalCritical + totalHigh + totalMedium + totalLow;
  // Only a real Trivy read yields a score; without it the figure is the honest dash, never a fake 100.
  const securityScore = hasTrivy
    ? Math.max(0, 100 - (totalCritical * 10 + totalHigh * 3 + totalMedium * 1))
    : null;

  // Falco roll-up (totalEvents + eventsByPriority, PRESERVED). A missing securityMetrics read dashes.
  const hasFalco = Boolean(dashboard.securityMetrics);
  const falcoTotal = hasFalco
    ? dashboard.securityMetrics?.totalEvents ||
      Object.values(dashboard.securityMetrics?.eventsByPriority || {}).reduce(
        (sum: number, v: any) => sum + (Number(v) || 0),
        0,
      )
    : null;

  // Kubescape complianceScore (PRESERVED). 0 / absent renders the dash, never a fabricated 0%.
  const kubescapeScore = dashboard.kubescape?.complianceScore ?? null;

  const scoreTone = (score: number): Tone => (score >= 80 ? 'good' : score >= 50 ? 'warn' : 'bad');

  const stripCells: readonly MetricCell[] = [
    {
      label: 'Security score',
      value:
        securityScore === null ? (
          <span className={MUTED} title="No Trivy image scan reported for this cluster.">{DASH}</span>
        ) : (
          <span className={toneClasses(scoreTone(securityScore)).text}>{securityScore}</span>
        ),
      sub: securityScore === null ? 'No image scan yet' : 'out of 100',
    },
    {
      label: 'Critical CVEs',
      value:
        !hasTrivy ? (
          <span className={MUTED} title="No Trivy image scan reported for this cluster.">{DASH}</span>
        ) : (
          <span className={totalCritical > 0 ? toneClasses('bad').text : toneClasses('good').text}>{totalCritical}</span>
        ),
      sub: hasTrivy ? (totalVulns === 0 ? 'No vulnerabilities' : `${totalVulns} total CVEs`) : 'Trivy scan',
    },
    {
      label: 'Runtime alerts',
      value:
        falcoTotal === null ? (
          <span className={MUTED} title="No Falco runtime data reported for this cluster.">{DASH}</span>
        ) : (
          <span className={falcoTotal > 0 ? toneClasses('warn').text : toneClasses('good').text}>{falcoTotal}</span>
        ),
      sub: 'Falco events',
    },
    {
      label: 'Compliance',
      value:
        kubescapeScore === null || kubescapeScore === 0 ? (
          <span className={MUTED} title="No Kubescape compliance scan reported for this cluster.">{DASH}</span>
        ) : (
          <span className={toneClasses(scoreTone(kubescapeScore)).text}>{`${kubescapeScore.toFixed(0)}%`}</span>
        ),
      sub: 'Kubescape score',
    },
  ];

  // ── Trivy CVE breakdown rows (PRESERVED counts) ──
  const breakdown: ReadonlyArray<{ label: 'Critical' | 'High' | 'Medium' | 'Low'; val: number }> = [
    { label: 'Critical', val: totalCritical },
    { label: 'High', val: totalHigh },
    { label: 'Medium', val: totalMedium },
    { label: 'Low', val: totalLow },
  ];

  // ── Trivy summary cells (PRESERVED values) ──
  const summaryCells: ReadonlyArray<{ label: string; val: number; sub: string; tone?: Tone }> = [
    { label: 'Images scanned', val: trivyData?.summary?.uniqueImagesScanned || 0, sub: `${trivyData?.summary?.namespacesCovered || 0} namespaces` },
    { label: 'Critical', val: totalCritical, sub: 'Immediate action', tone: totalCritical > 0 ? 'bad' : undefined },
    { label: 'High', val: totalHigh, sub: 'High priority', tone: totalHigh > 0 ? 'warn' : undefined },
    { label: 'Exposed secrets', val: trivyData?.summary?.totalExposedSecrets || 0, sub: 'Credential risk', tone: (trivyData?.summary?.totalExposedSecrets || 0) > 0 ? 'bad' : undefined },
  ];

  // ── Vulnerable-image DataTable rows + drawer ──
  const vulnRows: readonly VulnRow[] = paginatedImages.map((w: any, i: number) => ({
    key: `${w.namespace ?? ''}/${w.name ?? i}`,
    workload: w,
  }));

  const sevCount = (w: any, kind: 'critical' | 'high' | 'medium' | 'low') => w[kind] || 0;

  const columns: ReadonlyArray<ColumnDef<VulnRow>> = [
    {
      id: 'name', header: 'Image', priority: 'essential', width: '260px',
      cell: (r) => (
        <span className="flex min-w-0 flex-col">
          <span className={`truncate font-semibold ${BODY}`} title={r.workload.name}>{r.workload.name}</span>
          <span className={`truncate font-mono text-[12px] ${MUTED}`} title={r.workload.namespace}>{r.workload.namespace}</span>
        </span>
      ),
      sortValue: (r) => r.workload.name, csv: (r) => r.workload.name,
    },
    {
      id: 'top', header: 'Highest severity', width: '160px',
      cell: (r) => {
        const w = r.workload;
        const top = sevCount(w, 'critical') > 0 ? 'CRITICAL' : sevCount(w, 'high') > 0 ? 'HIGH' : sevCount(w, 'medium') > 0 ? 'MEDIUM' : 'LOW';
        const m = severityTone(top);
        return <StatusDot tone={m.tone} label={m.label} />;
      },
      sortValue: (r) => (sevCount(r.workload, 'critical') > 0 ? 4 : sevCount(r.workload, 'high') > 0 ? 3 : sevCount(r.workload, 'medium') > 0 ? 2 : 1),
    },
    {
      id: 'critical', header: 'Critical', align: 'right', width: '96px',
      cell: (r) => <span className={`tabular-nums ${sevCount(r.workload, 'critical') > 0 ? toneClasses('bad').text : MUTED}`}>{sevCount(r.workload, 'critical') || DASH}</span>,
      sortValue: (r) => sevCount(r.workload, 'critical'), csv: (r) => sevCount(r.workload, 'critical'),
    },
    {
      id: 'high', header: 'High', align: 'right', width: '88px',
      cell: (r) => <span className={`tabular-nums ${sevCount(r.workload, 'high') > 0 ? toneClasses('warn').text : MUTED}`}>{sevCount(r.workload, 'high') || DASH}</span>,
      sortValue: (r) => sevCount(r.workload, 'high'), csv: (r) => sevCount(r.workload, 'high'),
    },
    {
      id: 'medium', header: 'Medium', align: 'right', width: '96px',
      cell: (r) => <span className={`tabular-nums ${MUTED}`}>{sevCount(r.workload, 'medium') || DASH}</span>,
      sortValue: (r) => sevCount(r.workload, 'medium'), csv: (r) => sevCount(r.workload, 'medium'),
    },
    {
      id: 'low', header: 'Low', align: 'right', width: '88px',
      cell: (r) => <span className={`tabular-nums ${MUTED}`}>{sevCount(r.workload, 'low') || DASH}</span>,
      sortValue: (r) => sevCount(r.workload, 'low'), csv: (r) => sevCount(r.workload, 'low'),
    },
  ];

  /** The per-image drawer: Identity, the severity count summary, and a section per CVE (real data). */
  const rowToSections = (r: VulnRow): DrawerSection[] => {
    const w = r.workload;
    const identity: DrawerSection = {
      title: 'Image',
      data: [
        { label: 'Name', value: <span className="break-all">{w.name}</span> },
        { label: 'Namespace', value: w.namespace ? <span className="break-all font-mono text-[12px]">{w.namespace}</span> : null, reason: 'Trivy reported no namespace for this image.' },
      ],
    };
    const counts: DrawerSection = {
      title: 'Severity',
      data: [
        { label: 'Critical', value: sevCount(w, 'critical') > 0 ? <span className="tabular-nums">{sevCount(w, 'critical')}</span> : null, reason: 'None at this severity.' },
        { label: 'High', value: sevCount(w, 'high') > 0 ? <span className="tabular-nums">{sevCount(w, 'high')}</span> : null, reason: 'None at this severity.' },
        { label: 'Medium', value: sevCount(w, 'medium') > 0 ? <span className="tabular-nums">{sevCount(w, 'medium')}</span> : null, reason: 'None at this severity.' },
        { label: 'Low', value: sevCount(w, 'low') > 0 ? <span className="tabular-nums">{sevCount(w, 'low')}</span> : null, reason: 'None at this severity.' },
      ],
    };
    const cveSections: DrawerSection[] = (w.vulnerabilities ?? []).map((vuln: any, idx: number): DrawerSection => {
      const m = severityTone(vuln.severity);
      const data: DrawerDatum[] = [
        { label: 'Severity', value: <StatusDot tone={m.tone} label={m.label} /> },
        {
          label: 'CVE',
          value: vuln.primaryLink
            ? <a href={vuln.primaryLink} target="_blank" rel="noopener noreferrer" className={`font-mono text-[12px] underline-offset-2 hover:underline ${INK}`}>{vuln.vulnerabilityId}</a>
            : vuln.vulnerabilityId ? <span className={`font-mono text-[12px] ${INK}`}>{vuln.vulnerabilityId}</span> : null,
          reason: 'Trivy reported no identifier for this finding.',
        },
        { label: 'Package', value: vuln.packageName ? <span className="font-mono text-[12px]">{vuln.packageName}</span> : null, reason: 'Trivy reported no package.' },
        { label: 'Installed', value: vuln.installedVersion ? <span className="font-mono text-[12px]">{vuln.installedVersion}</span> : null, reason: 'Trivy reported no installed version.' },
        { label: 'Fixed in', value: vuln.fixedVersion ? <span className={`font-mono text-[12px] ${toneClasses('good').text}`}>{vuln.fixedVersion}</span> : null, reason: 'No fix is available yet.' },
        { label: 'CVSS score', value: typeof vuln.score === 'number' ? <span className="tabular-nums">{vuln.score.toFixed(1)}</span> : null, reason: 'Trivy reported no CVSS score.' },
      ];
      return { title: `CVE · ${vuln.vulnerabilityId ?? idx + 1}`, data };
    });
    return [identity, counts, ...cveSections];
  };

  const intro: ReactNode =
    'Container image CVEs (Trivy), runtime threats (Falco) and configuration compliance (Kubescape). Open an image for its CVE detail.';

  return (
    <PageTemplate
      title="Security"
      intro={intro}
      width="table"
      hero={<MetricStrip cells={stripCells} heroLead aria-label="Security posture" />}
    >
      <div data-panel="security" className="flex min-w-0 flex-col gap-6">

        {/* Runtime Security Section — Falco */}
        {dashboard?.securityMetrics && (
          <RuntimeSecurityView securityMetrics={dashboard.securityMetrics} />
        )}

        {/* Security & Compliance Section — Kubescape */}
        {dashboard?.kubescape && (
          <KubescapeComplianceView kubescapeData={dashboard.kubescape} />
        )}

        {/* Trivy Image Scanning Section */}
        {trivyData ? (
          <Surface tier="table" className="overflow-hidden">
            <div className={`flex items-center justify-between border-b px-5 py-3.5 ${HAIRLINE}`}>
              <span className={TITLE}>Container vulnerability scanning</span>
              <span className={SECONDARY}>Trivy-powered analysis</span>
            </div>

            {/* Summary figures (PRESERVED counts) */}
            <div className={`grid grid-cols-2 divide-x md:grid-cols-4 divide-[color:var(--chrome-line)] border-b ${HAIRLINE}`}>
              {summaryCells.map((item) => (
                <div key={item.label} className="flex flex-col gap-1 px-5 py-4">
                  <span className={LABEL}>{item.label}</span>
                  <span className={`${FIGURE} ${item.tone ? toneClasses(item.tone).text : INK}`}>{item.val}</span>
                  <span className={SECONDARY}>{item.sub}</span>
                </div>
              ))}
            </div>

            {/* CVE breakdown (PRESERVED counts), token-filled bars instead of decorative hex */}
            <div className={`border-b px-5 py-4 ${HAIRLINE}`}>
              <span className={LABEL}>CVE breakdown</span>
              <div className="mt-3 flex flex-col gap-2">
                {breakdown.map((row) => {
                  const pct = totalVulns > 0 ? (row.val / totalVulns) * 100 : 0;
                  const tone = BREAKDOWN_TONES[row.label];
                  return (
                    <div key={row.label} className="flex items-center gap-3">
                      <span className={`w-16 ${BODY} ${toneClasses(tone).text}`}>{row.label}</span>
                      <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-[color:var(--chrome-surface-2)]">
                        <span className={`block h-full rounded-full ${toneClasses(tone).text} bg-current`} style={{ width: `${pct}%` }} />
                      </span>
                      <span className={`w-8 text-right tabular-nums ${BODY} ${INK}`}>{row.val}</span>
                      <span className={`w-10 tabular-nums ${SECONDARY}`}>{pct.toFixed(0)}%</span>
                    </div>
                  );
                })}
              </div>
              <div className={`mt-3 flex items-center justify-between border-t pt-3 ${HAIRLINE}`}>
                <span className={SECONDARY}>Total vulnerabilities</span>
                <span className={`${FIGURE} ${INK}`}>{totalVulns}</span>
              </div>
            </div>

            {/* Most vulnerable images — DataTable + per-row DetailDrawer of real CVE detail */}
            <div>
              <div className="flex flex-wrap items-center justify-between gap-2 px-5 py-3.5">
                <span className={TITLE}>Most vulnerable images</span>
                {vulnerableImages.length > 0 && (
                  <div className="flex items-center gap-2">
                    <span className={SECONDARY}>{vulnerableImages.length} images</span>
                    {totalVulnPages > 1 && (
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setVulnImagePage((p) => Math.max(1, p - 1))}
                          disabled={vulnImagePage === 1}
                          className={`rounded-[6px] border px-2.5 py-1 text-[12px] font-semibold ${HAIRLINE} ${INK} hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]`}
                        >
                          Previous
                        </button>
                        <span className={SECONDARY}>{vulnImagePage}/{totalVulnPages}</span>
                        <button
                          type="button"
                          onClick={() => setVulnImagePage((p) => Math.min(totalVulnPages, p + 1))}
                          disabled={vulnImagePage === totalVulnPages}
                          className={`rounded-[6px] border px-2.5 py-1 text-[12px] font-semibold ${HAIRLINE} ${INK} hover:bg-[color:var(--chrome-surface-2)] disabled:opacity-40 outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]`}
                        >
                          Next
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
              <div className="px-5 pb-5">
                {vulnerableImages.length > 0 ? (
                  <DataTable<VulnRow>
                    rows={vulnRows}
                    columns={columns}
                    rowKey={(r) => r.key}
                    caption="Most vulnerable images"
                    rowLabel={(r) => r.workload.name}
                    rowTitle={(r) => r.workload.name}
                    rowSubtitle={(r) => r.workload.namespace}
                    rowToSections={rowToSections}
                  />
                ) : (
                  <EmptyState icon="mdi:shield-check-outline" message="No vulnerable images in the latest Trivy scan." />
                )}
              </div>
            </div>
          </Surface>
        ) : (
          <Surface tier="table">
            <EmptyState icon="mdi:shield-search-outline" message="No Trivy image scan reported for this cluster yet." />
          </Surface>
        )}
      </div>
    </PageTemplate>
  );
};

export default EksSecurityTab;
