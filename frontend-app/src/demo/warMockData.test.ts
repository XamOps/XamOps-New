import { describe, it, expect, beforeEach } from 'vitest';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';
import { demoWarReport } from './warMockData';
import { AWS_BASELINE, AZURE_BASELINE, GCP_BASELINE } from './warControlBaseline';

/**
 * The WAR page derives all five tab badges from one report, so the invariants that keep
 * those badges honest are what these tests guard — not the specific numbers, which are
 * free to move when the postures are retuned.
 */
describe('demo WAR report', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  it('is served on the route the page actually calls', async () => {
    const response = resolveDemoResponse(
      '/api/xamops/war/report?accountId=demo-aws-001&forceRefresh=false',
    );
    expect(response).not.toBeNull();
    const body = JSON.parse(await response!.text());
    // The generic /api/xamops/ fallback answers {}, which normalises into an
    // unassessable report with five empty tabs — the exact failure this route prevents.
    expect(body.accountId).toBe('demo-aws-001');
    expect(body.findings.length).toBe(AWS_BASELINE.controls.length);
  });

  it.each([
    ['demo-aws-001', 'AWS', AWS_BASELINE.controls.length],
    ['demo-gcp-001', 'GCP', GCP_BASELINE.controls.length],
    ['demo-azure-001', 'Azure', AZURE_BASELINE.controls.length],
  ])('covers the whole %s baseline as the right provider', (accountId, provider, size) => {
    const report = demoWarReport(accountId);
    expect(report.provider).toBe(provider);
    expect(report.totalControls).toBe(size);
    expect(report.findings).toHaveLength(size);
    // The loading state promises this many controls; a short fixture would contradict it.
    expect(new Set(report.findings.map((f) => f.controlId)).size).toBe(size);
  });

  it('keeps status counts, coverage and the score in agreement', () => {
    const report = demoWarReport('demo-aws-001');
    const counted = Object.values(report.statusCounts).reduce((a, b) => a + b, 0);
    expect(counted).toBe(report.totalControls);

    const verified = report.statusCounts.PASS + report.statusCounts.FAIL
      + report.statusCounts.WARN;
    expect(report.verifiedControls).toBe(verified);

    // Coverage gate: above 50% of checkable controls, so the score is publishable and
    // the blocked banner stays hidden.
    const scoreable = verified + report.statusCounts.UNKNOWN;
    expect(verified * 2).toBeGreaterThanOrEqual(scoreable);
    expect(report.assessable).toBe(true);
    expect(report.blockedReason).toBeNull();
    expect(report.warScore).toBeGreaterThan(0);
    expect(report.riskLevel).not.toBe('UNKNOWN');
  });

  it('counts severities for open findings only', () => {
    const report = demoWarReport('demo-aws-001');
    const open = report.findings.filter(
      (f) => f.status === 'FAIL' || f.status === 'WARN' || f.status === 'UNKNOWN',
    );
    const summed = Object.values(report.severityCounts).reduce((a, b) => a + b, 0);
    expect(summed).toBe(open.length);
  });

  it('lists only confirmed exposures in the fix list, worst first', () => {
    const report = demoWarReport('demo-aws-001');
    expect(report.topRisks.length).toBeGreaterThan(0);
    expect(report.topRisks.length).toBeLessThanOrEqual(10);
    // An unverified control has no fix beyond restoring the scan's access, so it must
    // not appear under "fix these first".
    report.topRisks.forEach((f) => expect(['FAIL', 'WARN']).toContain(f.status));
    const weights = report.topRisks.map((f) => f.riskWeight);
    expect([...weights].sort((a, b) => b - a)).toEqual(weights);
  });

  it('gives every attack path a state its own counts support', () => {
    const report = demoWarReport('demo-aws-001');
    expect(report.attackPaths.length).toBeGreaterThan(0);
    report.attackPaths.forEach((path) => {
      expect(path.total).toBeGreaterThan(0);
      if (path.failed > 0) expect(path.state).toBe('OPEN');
      else if (path.warned > 0) expect(path.state).toBe('PARTIAL');
      else if (path.unverified > 0) expect(path.state).toBe('UNVERIFIED');
      // CONTAINED requires something to have actually passed — see the assembler.
      if (path.state === 'CONTAINED') {
        expect(path.failed + path.warned + path.unverified).toBe(0);
      }
    });
  });

  it('splits every control into exactly one family bucket', () => {
    const report = demoWarReport('demo-aws-001');
    const total = report.categories.reduce((sum, c) => sum + c.total, 0);
    expect(total).toBe(report.totalControls);
    report.categories.forEach((c) => {
      expect(c.passed + c.failed + c.warned + c.manual + c.unknown + c.notApplicable)
        .toBe(c.total);
    });
  });

  it('populates the scan health tab rather than leaving it empty', () => {
    const report = demoWarReport('demo-aws-001');
    expect(report.scanWarnings.length).toBeGreaterThan(0);
    expect(report.blockedActions.length).toBeGreaterThan(0);
    // Denied actions are derived from the unverified findings, so the two must match.
    const unverified = report.findings.filter((f) => f.status === 'UNKNOWN');
    expect(unverified.length).toBeGreaterThan(0);
    unverified.forEach((f) => {
      expect(f.deniedAction).toBeTruthy();
      expect(report.deniedActions).toContain(f.deniedAction as string);
    });
  });

  it('gives confirmed failures the evidence that makes them actionable', () => {
    const report = demoWarReport('demo-aws-001');
    const failing = report.findings.filter((f) => f.status === 'FAIL');
    expect(failing.length).toBeGreaterThan(0);
    failing.forEach((f) => {
      expect(f.affectedCount).toBeGreaterThan(0);
      expect(f.evidence.length).toBeGreaterThan(0);
      expect(f.summary).toBeTruthy();
      // Real reference data, not invented copy — these drive the drawer and the CSV.
      expect(f.reference).toMatch(/^https:\/\//);
      expect(f.businessImpact.length).toBeGreaterThan(20);
      expect(f.attackPaths.length).toBeGreaterThan(0);
      expect(f.attackPathLabels).toHaveLength(f.attackPaths.length);
    });
  });

  it('reports non-automated controls as manual review, never as passing', () => {
    const report = demoWarReport('demo-aws-001');
    report.findings
      .filter((f) => !f.automated)
      .forEach((f) => expect(f.status).toBe('MANUAL'));
    expect(report.automatedControls)
      .toBe(report.findings.filter((f) => f.automated).length);
  });

  it('is stable per account and different between accounts', () => {
    // Every tab reads the report separately; a re-roll between reads would make the
    // badges disagree with the tables they label.
    expect(demoWarReport('demo-aws-001')).toBe(demoWarReport('demo-aws-001'));
    expect(demoWarReport('demo-aws-002').warScore)
      .not.toBe(demoWarReport('demo-aws-001').warScore);
  });
});
