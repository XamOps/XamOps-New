import { describe, it, expect, beforeEach } from 'vitest';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';
import { demoCapabilities } from './k8sAutomationMockData';
import { demoAccounts, demoK8sClusters } from './xamopsMockData';
import { READINESS_KEYS } from '../types/k8sAutomation';
import { isEmptyDashboard, nodeGroups, podCounts } from '../utils/k8sDerivations';

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

describe('demo router — CloudK8s capabilities (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const capabilitiesUrl = (accountId: string) =>
    `/api/xamops/k8s/capabilities?accountId=${encodeURIComponent(accountId)}`;

  it('serves a read-write account with every readiness key not ready', async () => {
    const body = await readDemoBody(capabilitiesUrl('demo-aws-001'));
    // The catch-all below it answers the cluster list; capabilities must be its own object
    expect(Array.isArray(body)).toBe(false);
    expect(body.accountId).toBe('demo-aws-001');
    expect(body.connection).toEqual({ accessLevel: 'READ_WRITE', status: 'CONNECTED' });
    expect(body.writeGate).toEqual({ allowed: true, reasons: [] });
    expect(body.user.canChangeAutomation).toBe(true);
    expect(body.verticalEngine).toEqual({ enabled: true });
    expect(Object.keys(body.readiness).sort()).toEqual([...READINESS_KEYS].sort());
    Object.values(body.readiness).forEach((entry) => {
      const { ready, reason, message } = entry as { ready: boolean; reason: string; message: string };
      expect(ready).toBe(false);
      expect(reason).toMatch(/^(CONTROLLER_NOT_READY|ANALYSE_ONLY)$/);
      expect(message.length).toBeGreaterThan(0);
    });
  });

  it('serves the demo clusters, leaving staging unregistered', async () => {
    const before = Date.now();
    const body = await readDemoBody(capabilitiesUrl('demo-aws-001'));
    type Cluster = {
      clusterName: string;
      writeGate: { allowed: boolean };
      automationAgent: { lastSeenAt: string };
      nodeAutomation: { enabled: boolean; derivedState: string };
    };
    const byName = new Map<string, Cluster>(body.clusters.map((c: Cluster) => [c.clusterName, c]));
    expect([...byName.keys()].sort()).toEqual(['eu-eks-cluster', 'prod-eks-cluster']);
    expect(byName.has('staging-eks-cluster')).toBe(false);

    const prod = byName.get('prod-eks-cluster')!;
    expect(prod.writeGate.allowed).toBe(true);
    expect(prod.nodeAutomation).toMatchObject({ enabled: true, derivedState: 'ANALYSIS' });
    const seenSecondsAgo = (before - Date.parse(prod.automationAgent.lastSeenAt)) / 1000;
    expect(seenSecondsAgo).toBeGreaterThanOrEqual(35);
    expect(seenSecondsAgo).toBeLessThanOrEqual(45);

    expect(byName.get('eu-eks-cluster')!.nodeAutomation).toMatchObject({ enabled: false, derivedState: 'OFF' });
  });

  it('serves a read-only account whose writes are refused for the connection', async () => {
    const body = await readDemoBody(capabilitiesUrl('demo-aws-002'));
    expect(body.connection.accessLevel).toBe('READ_ONLY');
    expect(body.writeGate).toEqual({ allowed: false, reasons: ['READ_ONLY_CONNECTION'] });
    body.clusters.forEach((c: { connection: { accessLevel: string }; writeGate: { allowed: boolean; reasons: string[] } }) => {
      expect(c.connection.accessLevel).toBe('READ_ONLY');
      expect(c.writeGate).toEqual({ allowed: false, reasons: ['READ_ONLY_CONNECTION'] });
    });
  });

  it.each(['demo-gcp-001', 'someone-elses-account', ''])(
    'refuses %j like an account that is not the caller\'s',
    async (accountId) => {
      const response = resolveDemoResponse(capabilitiesUrl(accountId));
      expect(response).not.toBeNull();
      expect(response!.status).toBe(403);
      expect(JSON.parse(await response!.text())).toMatchObject({ success: false, code: 'K8S_NO_ACCOUNT_ACCESS' });
    },
  );

  it('still answers the cluster list from the k8s catch-all', async () => {
    expect(await readDemoBody('/api/xamops/k8s/clusters?accountId=demo-aws-001')).toEqual(demoK8sClusters);
  });

  it('labels the demo AWS accounts with the access level their capabilities report', () => {
    const access = Object.fromEntries(demoAccounts.map((a) => [a.id, a.access]));
    expect(access['demo-aws-001']).toBe('read-write');
    expect(access['demo-aws-002']).toBe('read-only');
  });

  it('builds no capabilities for an account outside the demo', () => {
    expect(demoCapabilities('demo-azure-001')).toBeNull();
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

/**
 * The BillOps demo login is a real ROLE_BILLOPS_ADMIN user, so an AutoSpotting admin read that
 * reached the backend would show real customers' names and savings. Every read the BillOps card
 * and the admin page send must be answered here, for both demo roles.
 */
describe.each(['ROLE_XAMOPS', 'ROLE_BILLOPS_ADMIN'])('demo router — AutoSpotting admin reads (%s)', (role) => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, role);
  });

  it.each([
    '/api/xamops/autospotting-admin/customer-savings',
    '/api/xamops/autospotting-admin/recent-activity?limit=50',
    '/api/xamops/autospotting-admin/historical-savings?days=7',
    '/api/xamops/autospotting-admin/historical-savings?days=90',
    '/api/xamops/autospotting-admin/account-breakdown',
  ])('answers %s with an empty list', async (url) => {
    expect(await readDemoBody(url)).toEqual([]);
  });

  it('answers total-savings with zeros', async () => {
    expect(await readDemoBody('/api/xamops/autospotting-admin/total-savings')).toEqual({
      totalSavings: 0,
      legacyUnverifiedTotal: 0,
      activeSpotInstances: 0,
    });
  });

  it('answers any other path under the prefix itself, with a 404', () => {
    const response = resolveDemoResponse('/api/xamops/autospotting-admin/not-a-read');
    expect(response).not.toBeNull();
    expect(response!.status).toBe(404);
  });
});

describe('demo router — EKS cluster dashboards (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const dashboardUrl = (cluster: string) =>
    `/api/xamops/eks/${cluster}/dashboard?accountId=demo-aws-001&region=us-east-1&forceRefresh=false`;

  it('answers staging with the empty shell, score 100 included, which reads as not monitored', async () => {
    const body = await readDemoBody(dashboardUrl('staging-eks-cluster'));
    expect(body.clusterOverview).toMatchObject({ totalNodes: 0, totalPods: 0 });
    expect(body.workloadHealth.clusterHealthScore).toBe(100);
    expect(isEmptyDashboard(body)).toBe(true);
  });

  it('gives the prod nodes their node-group, NodePool and capacity-type labels', async () => {
    const body = await readDemoBody(dashboardUrl('prod-eks-cluster'));
    expect(isEmptyDashboard(body)).toBe(false);
    const groups = nodeGroups(body).map((g) => [g.type, g.name, g.nodes]);
    expect(groups).toEqual(expect.arrayContaining([
      ['Managed node group', 'general', 2],
      ['Karpenter NodePool', 'default', 3],
      ['Unmanaged', 'Unmanaged', 1],
    ]));
    // Running and total agree with the pod list.
    expect(podCounts(body)).toMatchObject({ running: body.clusterOverview.runningPods, total: body.clusterOverview.totalPods });
  });
});
