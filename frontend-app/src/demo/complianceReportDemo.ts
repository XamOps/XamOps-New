/**
 * Builds a ComplianceReport from the existing OCSF demo findings.
 *
 * ComplianceOps now reads a server-aggregated report instead of raw scanner output, so
 * demo mode needs a report to serve. This derives one from the same
 * `demo*ProwlerFindings` fixtures rather than introducing a second hand-authored dataset,
 * which would drift from the first the moment anyone edited either.
 *
 * It intentionally mirrors the aggregation in ComplianceScanRecorder and
 * ComplianceOpsService — group by check id, fail-if-any-resource-fails, severity-weighted
 * score with MANUAL and WAIVED excluded. If those rules change on the backend, change
 * them here too; demo mode is the only place the two are duplicated.
 */

import type {
  ComplianceControl,
  ComplianceFrameworkScore,
  ComplianceReport,
  ComplianceStatus,
  ComplianceTrendPoint,
} from '../types/compliance';

interface RawFinding {
  status_code?: string;
  severity?: string;
  message?: string;
  finding_info?: { title?: string; desc?: string };
  resources?: Array<{ uid?: string; name?: string; region?: string; type?: string }>;
  metadata?: { event_code?: string; product?: { version?: string } };
  remediation?: { desc?: string; references?: string[] };
  // Values are optional because the fixture literals are heterogeneous — not every
  // finding maps to every framework, so TypeScript infers `string[] | undefined`.
  unmapped?: { compliance?: Record<string, string[] | undefined> };
}

const SEVERITY_WEIGHT: Record<string, number> = {
  CRITICAL: 10, HIGH: 6, MEDIUM: 3, LOW: 1,
};

const weight = (severity: string) => SEVERITY_WEIGHT[severity.toUpperCase()] ?? 1;

const scoreLabel = (score: number | null): string => {
  if (score == null) return 'Unable to assess';
  if (score >= 90) return 'Strong';
  if (score >= 75) return 'Adequate';
  if (score >= 50) return 'Weak';
  return 'Critical';
};

/**
 * Falls back to a slug of the title when the fixture has no event_code. The GCP and Azure
 * fixtures predate the check-id requirement; dropping them would leave those providers
 * with an empty demo page.
 */
const checkIdOf = (f: RawFinding, index: number): string => {
  if (f.metadata?.event_code) return f.metadata.event_code;
  const title = f.finding_info?.title ?? `check_${index}`;
  return title.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '').slice(0, 64);
};

const normalizeStatus = (code: string | undefined): ComplianceStatus => {
  const upper = (code ?? '').toUpperCase();
  if (upper === 'PASS') return 'PASS';
  if (upper === 'FAIL') return 'FAIL';
  return 'MANUAL';
};

export function buildDemoComplianceReport(
  accountId: string,
  provider: string,
  findings: RawFinding[],
  regions: string[],
): ComplianceReport {
  const byCheck = new Map<string, { checkId: string; items: RawFinding[] }>();
  findings.forEach((f, i) => {
    const checkId = checkIdOf(f, i);
    const entry = byCheck.get(checkId) ?? { checkId, items: [] };
    entry.items.push(f);
    byCheck.set(checkId, entry);
  });

  const controls: ComplianceControl[] = [];
  let passWeight = 0;
  let failWeight = 0;
  let scoreable = 0;

  byCheck.forEach(({ checkId, items }) => {
    let passed = 0;
    let failed = 0;
    const frameworkMap = new Map<string, Set<string>>();
    const resources: ComplianceControl['resources'] = [];
    let severity = 'LOW';
    let title: string | null = null;
    let remediation: string | null = null;
    let refs: string[] = [];

    items.forEach((f) => {
      const status = normalizeStatus(f.status_code);
      if (status === 'PASS') passed += 1;
      else if (status === 'FAIL') failed += 1;

      if (f.severity) severity = f.severity.toUpperCase();
      if (!title) title = f.finding_info?.title ?? null;
      if (!remediation && f.remediation?.desc) {
        remediation = f.remediation.desc;
        refs = f.remediation.references ?? [];
      }

      Object.entries(f.unmapped?.compliance ?? {}).forEach(([fw, clauses]) => {
        const set = frameworkMap.get(fw) ?? new Set<string>();
        (clauses ?? []).forEach((c) => set.add(c));
        frameworkMap.set(fw, set);
      });

      if (status !== 'PASS') {
        const r = f.resources?.[0];
        resources.push({
          uid: r?.uid ?? null,
          name: r?.name ?? null,
          type: r?.type ?? null,
          region: r?.region ?? null,
          detail: f.message ?? f.finding_info?.desc ?? null,
          status,
        });
      }
    });

    const status: ComplianceStatus = failed > 0 ? 'FAIL' : passed > 0 ? 'PASS' : 'MANUAL';
    if (status === 'PASS') { passWeight += weight(severity); scoreable += 1; }
    if (status === 'FAIL') { failWeight += weight(severity); scoreable += 1; }

    controls.push({
      checkId,
      title,
      service: items[0]?.resources?.[0]?.type ?? null,
      severity,
      status,
      passedResources: passed,
      failedResources: failed,
      remediation,
      remediationRefs: refs,
      frameworks: Array.from(frameworkMap.entries())
        .map(([framework, clauses]) => ({ framework, controls: Array.from(clauses) })),
      resources,
      owner: null,
      exception: null,
      task: null,
      newlyFailing: false,
      failingForScans: status === 'FAIL' ? 1 : 0,
    });
  });

  const rank = (s: ComplianceStatus) => (s === 'FAIL' ? 0 : s === 'MANUAL' ? 1 : s === 'WAIVED' ? 2 : 3);
  controls.sort((a, b) => rank(a.status) - rank(b.status)
    || weight(b.severity) - weight(a.severity)
    || b.failedResources - a.failedResources);

  const assessable = scoreable > 0 && passWeight + failWeight > 0;
  const score = assessable
    ? Math.round((100 * passWeight) / (passWeight + failWeight))
    : null;

  const counts: ComplianceReport['counts'] = {
    totalControls: controls.length,
    passedControls: controls.filter((c) => c.status === 'PASS').length,
    failedControls: controls.filter((c) => c.status === 'FAIL').length,
    manualControls: controls.filter((c) => c.status === 'MANUAL').length,
    waivedControls: 0,
    passedResources: controls.reduce((s, c) => s + c.passedResources, 0),
    failedResources: controls.reduce((s, c) => s + c.failedResources, 0),
    critical: controls.filter((c) => c.status === 'FAIL' && c.severity === 'CRITICAL').length,
    high: controls.filter((c) => c.status === 'FAIL' && c.severity === 'HIGH').length,
    medium: controls.filter((c) => c.status === 'FAIL' && c.severity === 'MEDIUM').length,
    low: controls.filter((c) => c.status === 'FAIL'
      && !['CRITICAL', 'HIGH', 'MEDIUM'].includes(c.severity)).length,
    newlyFailing: 0,
    newlyPassing: 0,
  };

  const frameworks: ComplianceFrameworkScore[] = [];
  const byFramework = new Map<string, ComplianceControl[]>();
  controls.forEach((c) => c.frameworks.forEach((f) => {
    const list = byFramework.get(f.framework) ?? [];
    list.push(c);
    byFramework.set(f.framework, list);
  }));
  byFramework.forEach((list, framework) => {
    let fwPass = 0;
    let fwFail = 0;
    list.forEach((c) => {
      if (c.status === 'PASS') fwPass += weight(c.severity);
      if (c.status === 'FAIL') fwFail += weight(c.severity);
    });
    const clauses = new Set<string>();
    list.forEach((c) => c.frameworks
      .filter((f) => f.framework === framework)
      .forEach((f) => f.controls.forEach((x) => clauses.add(x))));
    frameworks.push({
      framework,
      totalControls: list.length,
      passedControls: list.filter((c) => c.status === 'PASS').length,
      failedControls: list.filter((c) => c.status === 'FAIL').length,
      manualControls: list.filter((c) => c.status === 'MANUAL').length,
      waivedControls: 0,
      score: fwPass + fwFail > 0 ? Math.round((100 * fwPass) / (fwPass + fwFail)) : null,
      assessable: fwPass + fwFail > 0,
      critical: list.filter((c) => c.status === 'FAIL' && c.severity === 'CRITICAL').length,
      high: list.filter((c) => c.status === 'FAIL' && c.severity === 'HIGH').length,
      medium: list.filter((c) => c.status === 'FAIL' && c.severity === 'MEDIUM').length,
      low: list.filter((c) => c.status === 'FAIL'
        && !['CRITICAL', 'HIGH', 'MEDIUM'].includes(c.severity)).length,
      frameworkControlCount: clauses.size,
    });
  });
  frameworks.sort((a, b) => b.failedControls - a.failedControls);

  // Four prior scans so the demo trend has something real-shaped to draw. These are
  // fixed offsets rather than a generated curve, and they are demo fixtures — the live
  // page only ever plots stored scores.
  const now = Date.now();
  const day = 86_400_000;
  const trend: ComplianceTrendPoint[] = [
    { offsetDays: 21, delta: -9 },
    { offsetDays: 14, delta: -6 },
    { offsetDays: 7, delta: -2 },
    { offsetDays: 0, delta: 0 },
  ].map((p, i) => ({
    scanId: 1000 + i,
    at: new Date(now - p.offsetDays * day).toISOString(),
    score: score == null ? null : Math.max(0, Math.min(100, score + p.delta)),
    passedControls: counts.passedControls,
    failedControls: counts.failedControls + (p.offsetDays > 0 ? Math.ceil(p.offsetDays / 7) : 0),
    critical: counts.critical,
    high: counts.high + (p.offsetDays > 0 ? 1 : 0),
    medium: counts.medium,
    low: counts.low,
  }));

  return {
    accountId,
    provider,
    scanId: 1003,
    scannedAt: new Date(now).toISOString(),
    scanStatus: 'COMPLETED',
    scanError: null,
    regions,
    frameworksRequested: frameworks.map((f) => f.framework),
    scannerVersion: '5.5.0',
    score,
    assessable,
    scoreLabel: scoreLabel(score),
    scoreMethod: 'Severity-weighted across evaluated controls (critical 10, high 6, '
      + 'medium 3, low 1). Manual and waived controls are excluded from the denominator.',
    counts,
    frameworks,
    controls,
    topRisks: controls.filter((c) => c.status === 'FAIL').slice(0, 12),
    trend,
    program: {
      activeExceptions: 1,
      exceptionsExpiringSoon: 1,
      lapsedExceptions: 0,
      pendingExceptions: 1,
      failingControlsWithOwner: 0,
      failingControlsWithoutOwner: counts.failedControls,
      remediationOpen: 2,
      remediationInProgress: 1,
      remediationAwaitingVerification: 1,
      remediationOverdue: 0,
      completedScans: trend.length,
      previousScanAt: trend[trend.length - 2]?.at ?? null,
    },
    notes: [],
  };
}
