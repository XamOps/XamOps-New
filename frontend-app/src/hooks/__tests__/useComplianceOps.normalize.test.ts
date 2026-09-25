import { describe, expect, it } from 'vitest';
import { normalizeReport } from '../useComplianceOps';

/**
 * The payload boundary.
 *
 * The WAR page shipped a crash of exactly this shape: a cached report was missing an
 * array the UI had started rendering, and a component died on `null.length`. Every
 * collection here therefore has to survive being absent, and this test is the thing that
 * keeps it that way when fields are added to the report.
 */
describe('normalizeReport', () => {
  it('returns null for a null payload rather than an empty shell', () => {
    expect(normalizeReport(null)).toBeNull();
  });

  it('fills every collection when the payload has none of them', () => {
    const report = normalizeReport({ accountId: '112233445566' })!;

    expect(report.regions).toEqual([]);
    expect(report.frameworksRequested).toEqual([]);
    expect(report.frameworks).toEqual([]);
    expect(report.controls).toEqual([]);
    expect(report.topRisks).toEqual([]);
    expect(report.trend).toEqual([]);
    expect(report.notes).toEqual([]);
  });

  it('defaults an unrateable report to not-assessable with a null score', () => {
    const report = normalizeReport({ accountId: 'x' })!;

    // Not zero. "We could not measure this" and "you failed everything" are different
    // statements and the UI renders them differently.
    expect(report.score).toBeNull();
    expect(report.assessable).toBe(false);
    expect(report.scoreLabel).toBe('Unable to assess');
  });

  it('supplies a complete counts object when the payload sends a partial one', () => {
    const report = normalizeReport({
      accountId: 'x',
      counts: { failedControls: 4 } as never,
    })!;

    expect(report.counts.failedControls).toBe(4);
    expect(report.counts.passedControls).toBe(0);
    expect(report.counts.waivedControls).toBe(0);
    expect(report.counts.newlyFailing).toBe(0);
    expect(report.counts.unclassifiedSeverity).toBe(0);
  });

  it('defaults the secondary score measures to null rather than zero', () => {
    const report = normalizeReport({ accountId: 'x' })!;

    // Same reason as `score`: absent is not the same as measured-and-bad. A risk score of 0
    // would read as "every severe control is failing".
    expect(report.riskWeightedScore).toBeNull();
    expect(report.scoreWithExceptions).toBeNull();
    expect(report.coverage).toBeNull();
    expect(report.coverageAdequate).toBe(false);
  });

  it('reconstructs the score denominator when a payload predates settledControls', () => {
    // A report cached before the field existed would spread as 0, and the header divides by it.
    // "11 of 0 settled controls" is worse than recomputing the sum the backend would have sent.
    const report = normalizeReport({
      accountId: 'x',
      counts: { passedControls: 11, failedControls: 24, waivedControls: 2 } as never,
    })!;

    expect(report.counts.settledControls).toBe(37);
  });

  it('keeps a settledControls the server did send', () => {
    const report = normalizeReport({
      accountId: 'x',
      counts: {
        passedControls: 11, failedControls: 24, waivedControls: 2, settledControls: 37,
      } as never,
    })!;

    expect(report.counts.settledControls).toBe(37);
  });

  it('repairs framework rows and never invents a clause baseline', () => {
    const report = normalizeReport({
      accountId: 'x',
      frameworks: [{ framework: 'CIS-3.0', passedControls: 3, failedControls: 5 } as never],
    })!;

    const fw = report.frameworks[0];
    expect(fw.settledControls).toBe(8);
    expect(fw.score).toBeNull();
    expect(fw.riskWeightedScore).toBeNull();
    expect(fw.coverage).toBeNull();
    // Null, not 0. A card that reads "3 of 0 clauses" is a worse lie than one that stays quiet
    // about how much of the framework exists.
    expect(fw.baselineControlCount).toBeNull();
    expect(fw.clauseCoverage).toBeNull();
    expect(fw.frameworkControlCount).toBe(0);
  });

  it('supplies a complete program object when the payload sends a partial one', () => {
    const report = normalizeReport({
      accountId: 'x',
      program: { lapsedExceptions: 2 } as never,
    })!;

    expect(report.program.lapsedExceptions).toBe(2);
    expect(report.program.failingControlsWithoutOwner).toBe(0);
    expect(report.program.completedScans).toBe(0);
  });

  it('repairs controls that arrive without their nested collections', () => {
    const report = normalizeReport({
      accountId: 'x',
      controls: [{ checkId: 'iam_root_mfa_enabled' } as never],
    })!;

    const control = report.controls[0];
    expect(control.frameworks).toEqual([]);
    expect(control.resources).toEqual([]);
    expect(control.remediationRefs).toEqual([]);
    // A control with no status is "nobody established the answer", never a pass.
    expect(control.status).toBe('MANUAL');
    expect(control.failedResources).toBe(0);
    expect(control.owner).toBeNull();
    expect(control.exception).toBeNull();
  });

  it('repairs a framework ref whose controls array is missing', () => {
    const report = normalizeReport({
      accountId: 'x',
      controls: [{
        checkId: 'c',
        frameworks: [{ framework: 'CIS-3.0' } as never],
      } as never],
    })!;

    expect(report.controls[0].frameworks[0].controls).toEqual([]);
  });

  it('drops a non-array collection instead of passing it through', () => {
    const report = normalizeReport({
      accountId: 'x',
      // A stale cache or a serialisation change can hand us the wrong type entirely.
      controls: 'not-an-array' as never,
      notes: null as never,
    })!;

    expect(report.controls).toEqual([]);
    expect(report.notes).toEqual([]);
  });
});
