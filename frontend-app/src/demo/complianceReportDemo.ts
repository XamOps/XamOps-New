/**
 * Builds a ComplianceReport from the existing OCSF demo findings.
 *
 * ComplianceOps now reads a server-aggregated report instead of raw scanner output, so
 * demo mode needs a report to serve. This derives one from the same
 * `demo*ProwlerFindings` fixtures rather than introducing a second hand-authored dataset,
 * which would drift from the first the moment anyone edited either.
 *
 * It intentionally mirrors the aggregation in ComplianceScanRecorder, ComplianceOpsService and
 * ComplianceScoring — group by check id, fail-if-any-resource-fails, then an unweighted pass rate
 * over settled controls with MANUAL excluded from the score and reported as coverage, WAIVED held
 * in the denominator, and severity weighting kept aside as a separate risk figure. If those rules
 * change on the backend, change them here too; demo mode is the only place the two are duplicated.
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

/** Mirrors ComplianceScoring.weight. Unrecognised weighs as medium, not as the floor. */
const SEVERITY_WEIGHT: Record<string, number> = {
  CRITICAL: 10, HIGH: 6, MEDIUM: 3, LOW: 1, INFORMATIONAL: 1,
};

const weight = (severity: string) => SEVERITY_WEIGHT[severity.toUpperCase()] ?? 3;

/** Mirrors ComplianceScoring.isUnclassified. */
const isUnclassified = (severity: string) =>
  !Object.prototype.hasOwnProperty.call(SEVERITY_WEIGHT, (severity ?? '').toUpperCase());

/** Mirrors ComplianceScoring.MIN_SCOREABLE_CONTROLS / MIN_COVERAGE_RATIO. */
const MIN_SCOREABLE_CONTROLS = 5;
const MIN_COVERAGE_RATIO = 0.5;

/**
 * Mirrors ComplianceFrameworkBaseline, restricted to the families the demo fixtures map to.
 *
 * Counts are the same Prowler requirement totals the backend table carries, resolved per provider
 * because the pinned framework versions differ — AWS is on PCI 3.2.1 and ISO 27001:2013 while GCP
 * and Azure are on PCI 4.0 and ISO 27001:2022.
 */
const BASELINE: Record<string, Record<string, number>> = {
  AWS: { CIS: 62, SOC2: 27, PCI: 147, NIST_CSF: 56, FSBP: 286, ISO27001: 79 },
  GCP: { CIS: 84, SOC2: 28, PCI: 1672, ISO27001: 92, MITRE: 46, HIPAA: 23 },
  AZURE: { CIS: 159, SOC2: 26, PCI: 1669, ISO27001: 92, MITRE: 46, HIPAA: 34 },
};

/** Mirrors ComplianceFrameworkBaseline.family. Order matters, as it does on the backend. */
const family = (raw: string): string | null => {
  // Separators normalised to spaces, matching the backend: the same framework shows up both
  // underscore- and hyphen-delimited depending on whether it came from the scan argument or a
  // finding. Digits survive so NIST 800-53 and ISO 27001 stay distinguishable.
  const u = (raw ?? '').toUpperCase().replace(/[^A-Z0-9]+/g, ' ');
  if (!u.trim()) return null;
  if (u.includes('FOUNDATIONAL SECURITY') || u.includes('FSBP')) return 'FSBP';
  if (u.includes('CIS')) return 'CIS';
  if (u.includes('SOC')) return 'SOC2';
  if (u.includes('PCI')) return 'PCI';
  if (u.includes('HIPAA')) return 'HIPAA';
  if (u.includes('NIST') && u.includes('800')) return 'NIST800';
  if (u.includes('NIST')) return 'NIST_CSF';
  if (u.includes('ISO') && u.includes('27001')) return 'ISO27001';
  if (u.includes('MITRE')) return 'MITRE';
  return null;
};

const baselineFor = (provider: string, rawFramework: string): number | null => {
  const byFamily = BASELINE[(provider ?? '').toUpperCase()];
  if (!byFamily) return null;
  const f = family(rawFramework);
  return f ? byFamily[f] ?? null : null;
};

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
  let passCount = 0;
  let failCount = 0;
  let passWeight = 0;
  let failWeight = 0;

  byCheck.forEach(({ checkId, items }) => {
    let passed = 0;
    let failed = 0;
    const frameworkMap = new Map<string, Set<string>>();
    const resources: ComplianceControl['resources'] = [];
    // 'UNKNOWN' rather than 'LOW', matching ComplianceScanRecorder: an absent severity is an
    // unknown, and labelling it as the lowest band was a fabricated fact.
    let severity = 'UNKNOWN';
    let title: string | null = null;
    let remediation: string | null = null;
    let refs: string[] = [];

    items.forEach((f) => {
      const status = normalizeStatus(f.status_code);
      if (status === 'PASS') passed += 1;
      else if (status === 'FAIL') failed += 1;

      // First non-null wins, matching the recorder's grouping.
      if (severity === 'UNKNOWN' && f.severity) severity = f.severity.toUpperCase();
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
    if (status === 'PASS') { passCount += 1; passWeight += weight(severity); }
    if (status === 'FAIL') { failCount += 1; failWeight += weight(severity); }

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

  // No waivers in the fixtures, so settled is pass + fail. Kept written out rather than
  // simplified so the shape matches the backend if a waived fixture is ever added.
  const waivedCount = 0;
  const settled = passCount + failCount + waivedCount;
  const coverage = controls.length > 0
    ? Math.round((100 * settled) / controls.length)
    : null;
  const coverageAdequate = controls.length > 0
    && settled >= Math.ceil(MIN_COVERAGE_RATIO * controls.length);
  const assessable = settled >= MIN_SCOREABLE_CONTROLS && coverageAdequate;

  // Unweighted, waived in the denominator only — identical to ComplianceScoring.
  const score = assessable ? Math.round((100 * passCount) / settled) : null;
  const scoreWithExceptions = assessable
    ? Math.round((100 * (passCount + waivedCount)) / settled)
    : null;
  const riskWeightedScore = assessable && passWeight + failWeight > 0
    ? Math.round((100 * passWeight) / (passWeight + failWeight))
    : null;

  /** Mirrors ComplianceOpsService.bumpSeverity: unclassified lands in medium, not low. */
  const failingWith = (bucket: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW') => controls.filter((c) => {
    if (c.status !== 'FAIL') return false;
    const s = (c.severity ?? '').toUpperCase();
    if (bucket === 'LOW') return s === 'LOW' || s === 'INFORMATIONAL';
    if (bucket === 'MEDIUM') return s === 'MEDIUM' || isUnclassified(s);
    return s === bucket;
  }).length;

  const counts: ComplianceReport['counts'] = {
    totalControls: controls.length,
    passedControls: passCount,
    failedControls: failCount,
    manualControls: controls.filter((c) => c.status === 'MANUAL').length,
    waivedControls: waivedCount,
    settledControls: settled,
    unclassifiedSeverity: controls.filter((c) => isUnclassified(c.severity)).length,
    passedResources: controls.reduce((s, c) => s + c.passedResources, 0),
    failedResources: controls.reduce((s, c) => s + c.failedResources, 0),
    critical: failingWith('CRITICAL'),
    high: failingWith('HIGH'),
    medium: failingWith('MEDIUM'),
    low: failingWith('LOW'),
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
    let fwPassWeight = 0;
    let fwFailWeight = 0;
    list.forEach((c) => {
      if (c.status === 'PASS') fwPassWeight += weight(c.severity);
      if (c.status === 'FAIL') fwFailWeight += weight(c.severity);
    });
    const clauses = new Set<string>();
    list.forEach((c) => c.frameworks
      .filter((f) => f.framework === framework)
      .forEach((f) => f.controls.forEach((x) => clauses.add(x))));

    const fwPass = list.filter((c) => c.status === 'PASS').length;
    const fwFail = list.filter((c) => c.status === 'FAIL').length;
    const fwSettled = fwPass + fwFail;
    const fwAssessable = fwSettled >= MIN_SCOREABLE_CONTROLS
      && fwSettled >= Math.ceil(MIN_COVERAGE_RATIO * list.length);
    const baseline = baselineFor(provider, framework);

    const fwFailingWith = (bucket: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW') => list.filter((c) => {
      if (c.status !== 'FAIL') return false;
      const s = (c.severity ?? '').toUpperCase();
      if (bucket === 'LOW') return s === 'LOW' || s === 'INFORMATIONAL';
      if (bucket === 'MEDIUM') return s === 'MEDIUM' || isUnclassified(s);
      return s === bucket;
    }).length;

    frameworks.push({
      framework,
      totalControls: list.length,
      passedControls: fwPass,
      failedControls: fwFail,
      manualControls: list.filter((c) => c.status === 'MANUAL').length,
      waivedControls: 0,
      settledControls: fwSettled,
      score: fwAssessable ? Math.round((100 * fwPass) / fwSettled) : null,
      riskWeightedScore: fwAssessable && fwPassWeight + fwFailWeight > 0
        ? Math.round((100 * fwPassWeight) / (fwPassWeight + fwFailWeight))
        : null,
      coverage: list.length > 0 ? Math.round((100 * fwSettled) / list.length) : null,
      assessable: fwAssessable,
      critical: fwFailingWith('CRITICAL'),
      high: fwFailingWith('HIGH'),
      medium: fwFailingWith('MEDIUM'),
      low: fwFailingWith('LOW'),
      frameworkControlCount: clauses.size,
      baselineControlCount: baseline,
      clauseCoverage: baseline != null && baseline > 0
        ? Math.round((100 * Math.min(clauses.size, baseline)) / baseline)
        : null,
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
    riskWeightedScore,
    scoreWithExceptions,
    coverage,
    coverageAdequate,
    assessable,
    scoreLabel: scoreLabel(score),
    // Verbatim from ComplianceScoring.method(). If that changes, change this.
    scoreMethod: 'Unweighted share of settled controls that pass, counted once per control. '
      + 'Severity is not part of this figure — frameworks do not rank their own controls, so '
      + 'the risk-weighted score is reported separately. Controls the scanner could not decide '
      + 'are excluded and reported as coverage; waived controls sit in the denominator, so an '
      + 'exception can never raise the score.',
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
