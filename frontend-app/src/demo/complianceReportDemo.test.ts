import { describe, expect, it } from 'vitest';
import { buildDemoComplianceReport } from './complianceReportDemo';
import {
  demoAwsProwlerFindings,
  demoAzureProwlerFindings,
  demoGcpProwlerFindings,
} from './xamopsMockData';

/**
 * Demo mode is the one place the scoring rules are duplicated.
 *
 * `complianceReportDemo.ts` reimplements ComplianceScoring and ComplianceOpsService in TypeScript
 * so demo accounts get a report without a backend. That duplication is the risk these tests exist
 * to contain: they assert the same properties as ComplianceScoringTest on the Java side, so the
 * mirror cannot quietly drift back to the old severity-weighted maths.
 */
describe('buildDemoComplianceReport', () => {
  const aws = () => buildDemoComplianceReport(
    'demo-aws-001', 'AWS', demoAwsProwlerFindings, ['ap-south-1', 'us-east-1'],
  );

  // ── The headline agrees with the sentence beside it ─────────────────────────

  it('scores as an unweighted share of settled controls', () => {
    const report = aws();
    const { counts } = report;

    // The exact identity the header now renders. The dial used to be severity-weighted while the
    // caption was a raw control count, so the two disagreed on screen.
    expect(report.score).toBe(
      Math.round((100 * counts.passedControls) / counts.settledControls),
    );
  });

  it('publishes settledControls as passed + failed + waived', () => {
    const { counts } = aws();

    expect(counts.settledControls).toBe(
      counts.passedControls + counts.failedControls + counts.waivedControls,
    );
  });

  it('keeps the severity-weighted figure as a separate number', () => {
    const report = aws();

    expect(report.riskWeightedScore).not.toBeNull();
    // Not asserting a relationship between the two — the point is that they are distinct
    // measures, and the compliance score is the unweighted one.
    expect(report.score).not.toBeNull();
  });

  // ── Coverage ───────────────────────────────────────────────────────────────

  it('reports coverage over the whole control set', () => {
    const report = aws();
    const { counts } = report;

    expect(report.coverage).toBe(
      Math.round((100 * counts.settledControls) / counts.totalControls),
    );
    // The AWS fixtures are all PASS or FAIL, so nothing is left undecided.
    expect(counts.manualControls).toBe(0);
    expect(report.coverage).toBe(100);
    expect(report.coverageAdequate).toBe(true);
  });

  it('rates the account only when both assessability floors are cleared', () => {
    const report = aws();

    expect(report.assessable).toBe(true);
    expect(report.scoreLabel).not.toBe('Unable to assess');
  });

  it('refuses to rate a scan with too few controls', () => {
    // Four controls is under the floor, so no percentage is published however they landed.
    const report = buildDemoComplianceReport(
      'demo-aws-001', 'AWS', demoAwsProwlerFindings.slice(0, 4), ['ap-south-1'],
    );

    expect(report.assessable).toBe(false);
    expect(report.score).toBeNull();
    expect(report.riskWeightedScore).toBeNull();
    expect(report.scoreLabel).toBe('Unable to assess');
  });

  it('returns nulls rather than zeros for an empty scan', () => {
    const report = buildDemoComplianceReport('demo-aws-001', 'AWS', [], []);

    expect(report.score).toBeNull();
    expect(report.coverage).toBeNull();
    expect(report.assessable).toBe(false);
  });

  // ── Framework baselines ────────────────────────────────────────────────────

  it('gives frameworks the clause denominator their provider version actually has', () => {
    const cis = aws().frameworks.find((f) => f.framework === 'CIS');

    expect(cis).toBeDefined();
    // AWS is pinned to CIS 3.0, which Prowler defines with 62 requirements.
    expect(cis!.baselineControlCount).toBe(62);
    expect(cis!.clauseCoverage).toBe(
      Math.round((100 * cis!.frameworkControlCount) / 62),
    );
  });

  it('resolves the same family to a different baseline per provider', () => {
    const gcp = buildDemoComplianceReport(
      'demo-gcp-001', 'GCP', demoGcpProwlerFindings, ['us-central1'],
    ).frameworks.find((f) => f.framework === 'CIS');
    const azure = buildDemoComplianceReport(
      'demo-azure-001', 'Azure', demoAzureProwlerFindings, ['eastus'],
    ).frameworks.find((f) => f.framework === 'CIS');

    // GCP scans CIS 2.0 (84 requirements), Azure CIS 3.0 for Azure (159).
    expect(gcp!.baselineControlCount).toBe(84);
    expect(azure!.baselineControlCount).toBe(159);
  });

  it('leaves the baseline null for a framework with none on record', () => {
    // NIST CSF is scanned on AWS but the fixtures also map findings to frameworks that are not on
    // any provider's list. Whichever those are, they must not invent a denominator.
    const unbaselined = aws().frameworks.filter((f) => f.baselineControlCount == null);

    unbaselined.forEach((f) => expect(f.clauseCoverage).toBeNull());
  });

  it('scores each framework by the same unweighted rule as the headline', () => {
    aws().frameworks.forEach((f) => {
      if (!f.assessable) {
        expect(f.score).toBeNull();
        return;
      }
      expect(f.settledControls).toBe(
        f.passedControls + f.failedControls + f.waivedControls,
      );
      expect(f.score).toBe(Math.round((100 * f.passedControls) / f.settledControls));
    });
  });

  // ── Severity handling ──────────────────────────────────────────────────────

  it('buckets failing controls without losing any to an unknown severity', () => {
    const { counts } = aws();

    // Every failing control lands in exactly one bucket. Unclassified severities go to medium,
    // matching the weight the risk score gives them, rather than silently to low.
    expect(counts.critical + counts.high + counts.medium + counts.low)
      .toBe(counts.failedControls);
  });

  it('describes its own methodology without claiming severity weighting', () => {
    const report = aws();

    expect(report.scoreMethod).toContain('Unweighted');
    expect(report.scoreMethod).toContain('never raise the score');
  });
});
