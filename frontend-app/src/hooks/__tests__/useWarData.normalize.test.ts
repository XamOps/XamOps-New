import { describe, expect, it } from 'vitest';
import { __normalizeReportForTest as normalizeReport } from '../useWarData';

/**
 * Regression tests for the payload boundary.
 *
 * A WAR report cached before `blockedActions` existed came back without the field, and
 * `blockedActions.length` took the whole page down with "Cannot read properties of null".
 * The backend now versions its cache keys so old payloads are never read, but these
 * assertions cover the case where a payload arrives malformed anyway.
 */
describe('normalizeReport', () => {
  it('returns null for a null payload rather than throwing', () => {
    expect(normalizeReport(null)).toBeNull();
  });

  it('fills every array field that the UI reads with .length or .map', () => {
    const report = normalizeReport({ accountId: '1' } as never)!;

    // Each of these is dereferenced directly by a component.
    expect(report.blockedActions).toEqual([]);
    expect(report.deniedActions).toEqual([]);
    expect(report.scanWarnings).toEqual([]);
    expect(report.scannedRegions).toEqual([]);
    expect(report.findings).toEqual([]);
    expect(report.topRisks).toEqual([]);
    expect(report.attackPaths).toEqual([]);
    expect(report.categories).toEqual([]);
    expect(report.statusCounts).toEqual({});
    expect(report.severityCounts).toEqual({});
  });

  it('reproduces the crash payload: a v1 report with no blockedActions', () => {
    const v1Payload = {
      accountId: '605134457560',
      warScore: 0,
      riskLevel: 'CRITICAL',
      totalControls: 86,
      statusCounts: { PASS: 0, FAIL: 2, WARN: 0, UNKNOWN: 61, MANUAL: 22 },
      deniedActions: ['iam:ListUsers'],
      findings: [],
      // blockedActions, verifiedControls, assessable all absent
    };

    const report = normalizeReport(v1Payload as never)!;

    expect(report.blockedActions).toEqual([]);
    // Derived from statusCounts: 0 + 2 + 0
    expect(report.verifiedControls).toBe(2);
    // 2 verified against 63 checkable is far below the 50% gate.
    expect(report.assessable).toBe(false);
  });

  it('defaults nested arrays on findings so cards can render evidence', () => {
    const report = normalizeReport({
      findings: [{ controlId: 'AWS-1' }],
      topRisks: [{ controlId: 'AWS-1' }],
    } as never)!;

    expect(report.findings[0].evidence).toEqual([]);
    expect(report.findings[0].attackPaths).toEqual([]);
    expect(report.findings[0].attackPathLabels).toEqual([]);
    expect(report.topRisks[0].evidence).toEqual([]);
  });

  it('defaults unverified on attack paths so open counts stay numeric', () => {
    const report = normalizeReport({
      attackPaths: [{ path: 'CRYPTO_MINING', failed: 1, warned: 0 }],
    } as never)!;

    expect(report.attackPaths[0].unverified).toBe(0);
    expect(report.attackPaths[0].topControlIds).toEqual([]);
  });

  it('preserves a well-formed payload untouched', () => {
    const report = normalizeReport({
      verifiedControls: 60,
      assessable: true,
      blockedActions: ['ec2:DescribeVpcs'],
      statusCounts: { PASS: 55, FAIL: 5, UNKNOWN: 2 },
    } as never)!;

    expect(report.verifiedControls).toBe(60);
    expect(report.assessable).toBe(true);
    expect(report.blockedActions).toEqual(['ec2:DescribeVpcs']);
  });
});
