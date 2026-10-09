import { describe, it, expect, beforeEach } from 'vitest';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';

/**
 * The generic `/api/xamops/*` fallback answers `{}`, which is wrong for any
 * endpoint the app treats as a list. CallProvider mounts above the app's error
 * boundary, so a `{}` where it expects an array blanks the entire page instead
 * of degrading one panel — these routes have to stay explicit.
 */
const readDemoBody = async (url: string) => {
  const response = resolveDemoResponse(url);
  expect(response, `expected a demo response for ${url}`).not.toBeNull();
  return JSON.parse(await response!.text());
};

describe('demo router — ticket calls (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  it('answers the missed-call badge with an array', async () => {
    expect(await readDemoBody('/api/xamops/calls/missed')).toEqual([]);
  });

  it('answers ticket call history with an array', async () => {
    expect(await readDemoBody('/api/xamops/calls/ticket/42')).toEqual([]);
  });

  it('answers invitable users with an array', async () => {
    expect(await readDemoBody('/api/xamops/calls/7/invitable-users')).toEqual([]);
  });

  it('reports calling as disabled and issues no ws token', async () => {
    const body = await readDemoBody('/api/xamops/calls/ws-token');
    expect(body.callingEnabled).toBe(false);
    expect(body.token).toBe('');
  });
});

/** The `YYYY-MM-DD` buckets the XamSecure trend charts read, oldest first. */
const last7Keys = (): string[] => {
  const out: string[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - i);
    out.push(d.toISOString().slice(0, 10));
  }
  return out;
};

describe('demo router — XamSecure (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const acct = 'accountId=demo-aws-001&';

  // Every tab's endpoints, with the query strings the hook actually sends. A route
  // that falls through to the `{}` catch-all shows up here as a shape mismatch.
  it.each([
    ['summary',              `/api/xamsecure/summary?${acct}`,                       'object'],
    ['issues',               `/api/xamsecure/issues?${acct}`,                        'array'],
    ['attack surface',       `/api/xamsecure/attack-surface?${acct}`,                'object'],
    ['stories',              `/api/xamsecure/stories?${acct}`,                       'object'],
    ['threats',              `/api/xamsecure/threats?${acct}page=0&size=50`,         'page'],
    ['threat policies',      `/api/xamsecure/threat-policies?${acct}`,               'array'],
    ['vuln dashboard',       `/api/xamsecure/vulnerabilities/dashboard?${acct}`,     'object'],
    ['vulns by CVE',         `/api/xamsecure/vulnerabilities/by-cve?${acct}page=0&size=50`, 'page'],
    ['vulns',                `/api/xamsecure/vulnerabilities?${acct}page=0&size=50`, 'page'],
    ['posture',              `/api/xamsecure/posture?${acct}page=0&size=50`,         'page'],
    ['inventory',            `/api/xamsecure/inventory?${acct}page=0&size=50`,       'page'],
    ['inventory categories', `/api/xamsecure/inventory/categories?${acct}`,          'array'],
    ['network topology',     `/api/xamsecure/inventory/network?${acct}`,             'object'],
    ['compliance',           `/api/xamsecure/compliance?${acct}`,                    'array'],
    ['config dashboard',     `/api/xamsecure/configurations/dashboard?${acct}`,      'object'],
    ['api security',         `/api/xamsecure/api-security?${acct}`,                  'array'],
    ['components',           `/api/xamsecure/components?${acct}`,                    'array'],
    ['workflows',            `/api/xamsecure/workflows?${acct}`,                     'array'],
    ['onboarding',           '/api/xamsecure/onboarding/status',                     'array'],
  ])('serves populated demo data for %s', async (_label, url, shape) => {
    const body = await readDemoBody(url);
    if (shape === 'array') {
      expect(Array.isArray(body)).toBe(true);
      expect(body.length).toBeGreaterThan(0);
    } else if (shape === 'page') {
      expect(Array.isArray(body.content)).toBe(true);
      expect(body.content.length).toBeGreaterThan(0);
      // content is one page of totalElements, so it can never exceed the total
      expect(body.totalElements).toBeGreaterThanOrEqual(body.content.length);
    } else {
      expect(Object.keys(body).length).toBeGreaterThan(0);
    }
  });

  it('resolves an issue detail for the requested check', async () => {
    const body = await readDemoBody(
      `/api/xamsecure/issues/detail?${acct}check=s3_bucket_public_read_acl`);
    expect(body.title).toMatch(/public read/i);
    expect(body.resources.length).toBe(body.resourceCount);
  });

  it('falls back to a generic detail for an unmodelled check', async () => {
    const body = await readDemoBody(`/api/xamsecure/issues/detail?${acct}check=some_other_check`);
    expect(body.resources.length).toBeGreaterThan(0);
  });

  it('keys the new-CVE trend to the trailing 7 days the chart renders', async () => {
    const body = await readDemoBody(`/api/xamsecure/vulnerabilities/dashboard?${acct}`);
    expect(Object.keys(body.overTime).sort()).toEqual(last7Keys().sort());
    expect(body.severityOverTime).toHaveLength(7);
    body.severityOverTime.forEach((row: Record<string, unknown>) => expect(row.date).toBeTruthy());
  });

  it('narrows the runtime exploit funnel monotonically', async () => {
    const body = await readDemoBody(`/api/xamsecure/vulnerabilities/dashboard?${acct}`);
    const values = body.funnel.map((s: { value: number }) => s.value);
    values.forEach((v: number, i: number) => {
      if (i > 0) expect(v).toBeLessThanOrEqual(values[i - 1]);
    });
  });

  /**
   * The stacked ribbon takes each segment's height as its share of the stage height, so bands
   * that do not sum to the stage total would draw segments that overflow or underfill it.
   */
  it('splits every funnel stage into bands that sum to the stage total', async () => {
    const body = await readDemoBody(`/api/xamsecure/vulnerabilities/dashboard?${acct}`);
    type Stage = { label: string; value: number; bands: Record<string, number> };
    body.funnel.forEach((s: Stage) => {
      expect(s.bands, `${s.label} must carry bands`).toBeTruthy();
      const { CRITICAL, HIGH, OTHER } = s.bands;
      [CRITICAL, HIGH, OTHER].forEach((n) => expect(n).toBeGreaterThanOrEqual(0));
      expect(CRITICAL + HIGH + OTHER, `${s.label} bands must sum to value`).toBe(s.value);
    });
  });

  it('acknowledges a threat archive without hitting the backend', async () => {
    const body = await readDemoBody('/api/xamsecure/threats/thr-9f21a/status');
    expect(body.success).toBe(true);
  });

  // Every table below holds more rows than fit on one page, so the tabs open on a
  // full table and the Prev/Next controls have somewhere to go.
  it.each([
    ['threats',         `/api/xamsecure/threats?${acct}`],
    ['CVEs',            `/api/xamsecure/vulnerabilities/by-cve?${acct}`],
    ['vulnerabilities', `/api/xamsecure/vulnerabilities?${acct}`],
    ['posture',         `/api/xamsecure/posture?${acct}`],
    ['inventory',       `/api/xamsecure/inventory?${acct}`],
  ])('fills a full page and pages beyond it for %s', async (_label, base) => {
    const first = await readDemoBody(`${base}page=0&size=50`);
    expect(first.content).toHaveLength(50);
    expect(first.totalPages).toBeGreaterThan(1);
    expect(first.number).toBe(0);

    const second = await readDemoBody(`${base}page=1&size=50`);
    expect(second.number).toBe(1);
    expect(second.totalElements).toBe(first.totalElements);
    // A second page that repeats the first would mean the page param was ignored
    expect(second.content[0]).not.toEqual(first.content[0]);
  });

  it('clamps a page request past the end instead of returning nothing', async () => {
    const body = await readDemoBody(`/api/xamsecure/threats?${acct}page=99&size=50`);
    expect(body.content.length).toBeGreaterThan(0);
    expect(body.number).toBe(body.totalPages - 1);
  });

  it('narrows the inventory to the category the rail selected', async () => {
    const all = await readDemoBody(`/api/xamsecure/inventory?${acct}page=0&size=50`);
    const categories = await readDemoBody(`/api/xamsecure/inventory/categories?${acct}`);
    const networking = categories.find((c: { category: string }) => c.category === 'networking');

    const filtered = await readDemoBody(
      `/api/xamsecure/inventory?${acct}page=0&size=50&category=networking`);
    expect(filtered.totalElements).toBeLessThan(all.totalElements);
    // The rail's count is what the table then reports
    expect(filtered.totalElements).toBe(networking.count);
    expect(filtered.content.every((a: { assetCategory: string }) => a.assetCategory === 'networking')).toBe(true);
  });

  it('filters threats by severity when one is requested', async () => {
    const body = await readDemoBody(`/api/xamsecure/threats?${acct}page=0&size=50&severity=CRITICAL`);
    expect(body.content.length).toBeGreaterThan(0);
    expect(body.content.every((t: { severity: string }) => t.severity === 'CRITICAL')).toBe(true);
  });

  it('sizes a CVE drill-down to the row that opened it', async () => {
    const cves = await readDemoBody(`/api/xamsecure/vulnerabilities/by-cve?${acct}page=0&size=50`);
    const row = cves.content[0];
    const findings = await readDemoBody(
      `/api/xamsecure/vulnerabilities/cve/findings?${acct}cveId=${row.cveId}`);
    expect(findings).toHaveLength(Math.min(row.resourceCount, 30));
  });
});

describe('demo router — ComplianceOps Prowler (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  // The page's normalize() runs .filter on the body, so an object here empties
  // the page. AWS is the default demo provider, so it is the one that matters most.
  it.each(['', 'gcp/', 'azure/'])('serves a findings array for %s', async (prefix) => {
    const body = await readDemoBody(`/api/xamops/${prefix}security/prowler/findings?accountId=demo-aws-001`);
    expect(Array.isArray(body)).toBe(true);
    expect(body.length).toBeGreaterThan(0);
  });

  it('reports a completed AWS scan so findings are fetched', async () => {
    const body = await readDemoBody('/api/xamops/security/prowler/status?accountId=demo-aws-001');
    expect(body.status).toBe('COMPLETED');
  });

  it('reports STARTED for a manual AWS rescan', async () => {
    const body = await readDemoBody('/api/xamops/security/prowler/scan?accountId=demo-aws-001');
    expect(body.status).toBe('STARTED');
  });

  it('maps AWS findings to frameworks with both PASS and FAIL results', async () => {
    const body = await readDemoBody('/api/xamops/security/prowler/findings?accountId=demo-aws-001');
    const codes = new Set(body.map((f: { status_code: string }) => f.status_code));
    expect(codes).toContain('PASS');
    expect(codes).toContain('FAIL');
    // the page reads unmapped.compliance to build its framework columns
    body.forEach((f: { unmapped: { compliance: Record<string, string[]> } }) =>
      expect(Object.keys(f.unmapped.compliance).length).toBeGreaterThan(0));
  });
});

describe('demo router — AutoSpotting feature status', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  it('reports AWS and GCP conversions as paused', async () => {
    expect(await readDemoBody('/api/autospotting/feature-status')).toEqual({
      aws: { conversionsEnabled: false },
      gcp: { conversionsEnabled: false },
    });
  });
});
