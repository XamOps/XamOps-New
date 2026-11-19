import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';
import { demoCapabilities } from './k8sAutomationMockData';
import { resetAutoSpottingDemo } from './autospottingDemoData';
import { demoAccounts, demoChargeTypes, demoK8sClusters,
  demoDbHealthView, demoDbLockWaits, demoDbReplication, demoAccessGraph, demoFkGraph } from './xamopsMockData';
import { READINESS_KEYS } from '../types/k8sAutomation';
import { isEmptyDashboard, nodeGroups, podCounts } from '../utils/k8sDerivations';
import { toVerticalEngineView } from '../api/verticalRecommendationsAdapter';
import { trendBadge } from '../components/Dspm/datastores/datastoreFormat';
import type { DspmPage } from '../types/dspm';
import type {
  DataFlowColumnPage, DataFlowDatabases, DataFlowSchemas, DataFlowTablePage, DatastoreFilterOptions, DatastoreKpis,
  DatastoreOccurrencePage, DatastoreOverview, DatastoreRisks, DatastoreRow, DatastoreSensitives,
} from '../types/dspmDatastores';
import type { MisconfigurationDetail, MisconfigurationList } from '../types/dspmMisconfigurations';
import type {
  DataFlowErd, DataFlowRelations, EndpointScanPage, EndpointSensitiveMetrics, EndpointSummary, SaasFileActivity,
  SaasFileCopies, SaasFileLineage, SaasFileOverview, SaasFilePage, SaasRecords,
} from '../types/dspmDrawers';
import type {
  LineageCategories, LineageDatastorePage, LineageFilePage, LineagePolicyPage,
} from '../types/dspmLineage';
import { DEMO_LINEAGE_STEPS_DEFAULT, DEMO_LINEAGE_STEPS_KEY } from './xamlensLineageMockData';

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

/**
 * The trial section's hook sorts and maps the list, so the `{}` BillOps catch-all would throw
 * rather than degrade. These assertions exist to keep the three trial routes above it.
 */
describe('demo router — trial management (ROLE_BILLOPS_ADMIN)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_BILLOPS_ADMIN');
  });

  it('answers the trial list with a non-empty array, not the generic {}', async () => {
    const body = await readDemoBody('/api/billops/admin/trials?includeConverted=true');
    expect(Array.isArray(body)).toBe(true);
    expect(body.length).toBeGreaterThan(0);
    body.forEach((row: Record<string, unknown>) => {
      expect(typeof row.clientId).toBe('number');
      expect('planType' in row).toBe(true);
    });
  });

  it('covers both the blocked and the converted case so every verdict branch renders', async () => {
    const body = await readDemoBody('/api/billops/admin/trials?includeConverted=true');
    expect(body.some((r: { trialStatus: string }) => r.trialStatus === 'EXPIRED')).toBe(true);
    expect(body.some((r: { trialStatus: string }) => r.trialStatus === 'CONVERTED')).toBe(true);
    expect(body.some((r: { planType: string }) => r.planType === 'PAID')).toBe(true);
  });

  it('answers extend with its own result rather than the list', async () => {
    const body = await readDemoBody('/api/billops/admin/trials/3/extend');
    expect(Array.isArray(body)).toBe(false);
    expect(body.trialStatus).toBe('ACTIVE');
    expect(typeof body.trialEndAt).toBe('string');
  });

  it('answers convert with its own result rather than the list', async () => {
    const body = await readDemoBody('/api/billops/admin/trials/4/convert');
    expect(Array.isArray(body)).toBe(false);
    expect(body.planType).toBe('PAID');
    expect(body.trialStatus).toBe('CONVERTED');
  });
});

/**
 * The XamLens (DSPM) admin page filters and counts its lists, so the billops-admin catch-all's {}
 * crashed it for the BillOps demo login ("list.filter is not a function"). Empty, synthetic answers;
 * none of these reads may reach the real admin API, which holds customers' Matters keys.
 */
describe('demo router — XamLens (DSPM) admin page (ROLE_BILLOPS_ADMIN)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_BILLOPS_ADMIN');
  });

  it.each([
    '/api/xamops/billops-admin/dspm/mappings',
    '/api/xamops/billops-admin/dspm/clients',
  ])('answers %s with an empty list', async (url) => {
    expect(await readDemoBody(url)).toEqual([]);
  });

  it('answers the audit trail with an empty page in the shape the audit table reads', async () => {
    expect(await readDemoBody('/api/xamops/billops-admin/dspm/audit?page=0&size=25&action=MAPPING_CREATED&days=30'))
      .toEqual({ data: [], page: 0, pageSize: 25, total: 0, totalPages: 0 });
    expect(await readDemoBody('/api/xamops/billops-admin/dspm/audit')).toEqual(
      { data: [], page: 0, pageSize: 50, total: 0, totalPages: 0 });
  });

  it('keeps every other DSPM admin path off the backend', async () => {
    expect(await readDemoBody('/api/xamops/billops-admin/dspm/mappings/7/verify')).toEqual({});
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

describe('demo router — vertical engine, endpoint 1 (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const statusUrl = (cluster: string) => `/api/eks/automation/vertical/${cluster}?accountId=demo-aws-001&region=us-east-1`;

  it('answers prod as V4 with nine workloads in the contract shape', async () => {
    const body = await readDemoBody(statusUrl('prod-eks-cluster'));
    const view = toVerticalEngineView(body);
    expect(view.state).toBe('V4');
    expect(body.workloads).toHaveLength(9);
    expect(body.gateReasons).toContain('CONTROLLER_NOT_READY');
    // NIT 1: the throttled row moves its limit; LIMIT_RAISED no longer exists.
    const throttled = body.workloads.find((w: any) => w.findings.includes('THROTTLED'));
    expect(throttled.containers[0].reasons).toEqual(['LIMIT_MOVED']);
    expect(JSON.stringify(body)).not.toContain('LIMIT_RAISED');
    expect(body.workloads.filter((w: any) => w.skipReason === 'POLICY_OFF')).toHaveLength(1);
  });

  it('sends clusterAccess in the engine vocabulary, which the Workload autoscaler tab prints', async () => {
    const body = await readDemoBody(statusUrl('prod-eks-cluster'));
    expect(['CONFIGURED', 'NOT_CONFIGURED', 'UNREACHABLE', null]).toContain(body.engine.clusterAccess);
    expect(body.engine.executionPathDetail.direct.reason).toBe('CLUSTER_ACCESS_NOT_CONFIGURED');
  });

  it('answers eu as V2 with no workloads', async () => {
    const view = toVerticalEngineView(await readDemoBody(statusUrl('eu-eks-cluster')));
    expect(view.state).toBe('V2');
    expect(view.workloads).toEqual([]);
  });

  it.each(['GET', 'POST', 'PUT'])('refuses the engine writes with 403 K8S_DEMO (%s)', async (method) => {
    for (const path of ['apply', 'policy']) {
      const response = resolveDemoResponse(`/api/eks/automation/vertical/prod-eks-cluster/workloads/production/Deployment/api-server/${path}?accountId=demo-aws-001&region=us-east-1`, method);
      expect(response?.status).toBe(403);
      expect((await response!.json()).code).toBe('K8S_DEMO');
    }
  });

  it('answers any other cluster 404 K8S_CLUSTER_NOT_REGISTERED, so nothing reaches the backend', async () => {
    const response = resolveDemoResponse(statusUrl('staging-eks-cluster'));
    expect(response?.status).toBe(404);
    expect((await response!.json()).code).toBe('K8S_CLUSTER_NOT_REGISTERED');
  });

  it.each(['GET', 'POST'])('refuses the HPA apply with 403 K8S_DEMO (%s)', async (method) => {
    const response = resolveDemoResponse('/api/eks/automation/hpa/prod-eks-cluster/production/worker/apply?accountId=demo-aws-001&region=us-east-1', method);
    expect(response?.status).toBe(403);
    expect((await response!.json()).code).toBe('K8S_DEMO');
  });
});

/** Automation › Activity reads the activity feed (FEAT-007); it may never reach the backend. */
describe('demo router — activity feed (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const feedUrl = (cluster: string, query = '') => `/api/eks/automation/activity/${cluster}?accountId=demo-aws-001&region=us-east-1&limit=50${query}`;
  const statusUrl = (cluster: string) => `/api/eks/automation/vertical/${cluster}?accountId=demo-aws-001&region=us-east-1`;

  it("answers prod with its workloads' history in the feed shape, newest first", async () => {
    const body = await readDemoBody(feedUrl('prod-eks-cluster'));
    expect(body.engine).toEqual({ enabled: true });
    expect(body.nextCursor).toBeNull();
    const ats = body.entries.map((e: any) => Date.parse(e.at));
    expect([...ats].sort((a, b) => b - a)).toEqual(ats);
    expect(body.entries.map((e: any) => `${e.target.name}:${e.result ?? e.action}`)).toEqual([
      'api-server:PUT_BACK',
      'api-staging:TEST_RUN',
      'frontend:REFUSED',
      'worker-hpa:APPLIED',
      'grafana:APPLIED',
      'alertmanager:MODE_CHANGE',
      'api-server:FAILED',
    ]);
    // Every entry names who, and the put-back carries its per-pod proof.
    expect(body.entries.every((e: any) => e.who === 'A user' && e.origin === 'MANUAL')).toBe(true);
    expect(body.entries[0].proof.pods[0].revert.state).toBe('REVERTED');
  });

  it('filters by feature, result and name, and pages with its cursor, as the server does', async () => {
    expect((await readDemoBody(feedUrl('prod-eks-cluster', '&feature=HPA'))).entries.map((e: any) => e.target.name)).toEqual(['worker-hpa']);
    expect((await readDemoBody(feedUrl('prod-eks-cluster', '&result=PUT_BACK,TEST_RUN'))).entries.map((e: any) => e.target.name)).toEqual(['api-server', 'api-staging']);
    expect((await readDemoBody(feedUrl('prod-eks-cluster', '&q=graf'))).entries.map((e: any) => e.target.name)).toEqual(['grafana']);
    const first = await readDemoBody('/api/eks/automation/activity/prod-eks-cluster?accountId=demo-aws-001&region=us-east-1&limit=3');
    expect(first.entries).toHaveLength(3);
    const second = await readDemoBody(`/api/eks/automation/activity/prod-eks-cluster?accountId=demo-aws-001&region=us-east-1&limit=3&cursor=${first.nextCursor}`);
    expect(second.entries.map((e: any) => e.id)).toEqual([105, 104, 103]);
  });

  it("tells the same story as endpoint 1: each workload's lastAction is its newest real change in the feed", async () => {
    // Both answers date their rows from Date.now(); hold the clock still so they share one "now".
    vi.spyOn(Date, 'now').mockReturnValue(Date.now());
    const feed = await readDemoBody(feedUrl('prod-eks-cluster'));
    const status = await readDemoBody(statusUrl('prod-eks-cluster'));
    // endpoint 1 skips test runs, refusals, put-backs (no pod changed) and HPA rows.
    const counted = feed.entries.filter((e: any) => !e.dryRun && e.feature === 'VERTICAL' && !['TEST_RUN', 'REFUSED', 'NOT_CHANGED'].includes(e.outcome));
    for (const w of status.workloads) {
      const newest = counted.find((e: any) => e.target.namespace === w.ref.namespace && e.target.kind === w.ref.kind && e.target.name === w.ref.name);
      if (!newest) {
        expect(w.lastAction, w.ref.name).toBeNull();
        continue;
      }
      expect(w.lastAction, w.ref.name).toEqual({ at: newest.at, dryRun: false, outcome: newest.outcome });
    }
  });

  it('answers eu with no history and any other cluster 404 K8S_CLUSTER_NOT_REGISTERED', async () => {
    expect((await readDemoBody(feedUrl('eu-eks-cluster'))).entries).toEqual([]);
    const response = resolveDemoResponse(feedUrl('staging-eks-cluster'));
    expect(response?.status).toBe(404);
    expect((await response!.json()).code).toBe('K8S_CLUSTER_NOT_REGISTERED');
  });

  it.each(['POST', 'PUT', 'DELETE'])('refuses %s on the feed with 403 K8S_DEMO, never a read', async (method) => {
    const response = resolveDemoResponse(feedUrl('prod-eks-cluster'), method);
    expect(response?.status).toBe(403);
    expect((await response!.json()).code).toBe('K8S_DEMO');
  });
});

/** The Phase-2 reads (FEAT-004/005, FEAT-006's charts): a fixture for every GET; nothing reaches the backend. */
describe('demo router — usage, sparklines, HPA history and the activity summary (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const q = 'accountId=demo-aws-001&region=us-east-1';
  const usage = (cluster: string, query: string) => `/api/eks/automation/usage/${cluster}?${q}&${query}`;
  const answerOf = async (url: string, method = 'GET') => {
    const response = resolveDemoResponse(url, method);
    expect(response, `expected a demo response for ${url}`).not.toBeNull();
    return { status: response!.status, body: JSON.parse(await response!.text()) };
  };
  const istDate = (ms: number) => new Date(ms + 5.5 * 3600_000).toISOString().slice(0, 10);

  it("answers the cluster's CPU over 3 days in the server's shape: 145 points at 30 min, every series to allocatable", async () => {
    const body = await readDemoBody(usage('prod-eks-cluster', 'scope=cluster&resource=cpu&window=72h'));
    expect(body).toMatchObject({ schemaVersion: 1, scope: 'cluster', resource: 'cpu', unit: 'cores', window: '72h', stepSeconds: 1800, workloadRef: null, namespaces: null, unavailable: null });
    expect(body.source).toEqual({ type: 'PROMETHEUS_PROXY', service: 'monitoring/prometheus-server:80' });
    expect(body.series.map((s: any) => s.kind)).toEqual(['used', 'request', 'allocatable', 'capacity']);
    for (const s of body.series) expect(s.points).toHaveLength(145);
    // The agent fixture's six nodes: 25.52 cores allocatable of 26.
    expect(body.series[2].points[0][1]).toBe(25.52);
    expect(body.series[3].points[0][1]).toBe(26);
    expect(body.coverage.covered).toBe(1);
  });

  it('reads 7 days as partial: the demo Prometheus keeps 5, and the points before are null, never 0', async () => {
    const body = await readDemoBody(usage('prod-eks-cluster', 'scope=cluster&resource=memory&window=168h'));
    expect(body.stepSeconds).toBe(3600);
    expect(body.coverage.covered).toBeLessThan(1);
    const used = body.series[0].points;
    expect(used[0][1]).toBeNull();
    const first = used.findIndex((p: [number, number | null]) => p[1] !== null);
    expect(Date.parse(body.coverage.firstSampleAt) / 1000).toBe(used[first][0]);
    expect(Math.round((Date.now() / 1000 - used[first][0]) / 86400)).toBe(5);
  });

  it('answers every namespace with its average use and its request now', async () => {
    const body = await readDemoBody(usage('prod-eks-cluster', 'scope=namespace&resource=cpu&window=72h'));
    expect(body.series).toEqual([]);
    expect(body.namespaces.map((n: any) => n.namespace)).toEqual(['kube-system', 'monitoring', 'production', 'staging']);
    const production = body.namespaces.find((n: any) => n.namespace === 'production');
    expect(production.requestedNow).toBeCloseTo(3 * 0.256 + 2 * 0.1 + 2 * 0.05, 3);
    expect(production.usedAvg).toBeGreaterThan(0.5);
    expect(production.series.map((s: any) => s.kind)).toEqual(['used', 'request']);
  });

  it("answers one workload's busiest pod, its request (grafana's stepped down 26 hours ago) and a gap where no pod ran", async () => {
    const grafana = await readDemoBody(usage('prod-eks-cluster', 'scope=workload&resource=cpu&window=72h&namespace=monitoring&kind=Deployment&name=grafana'));
    expect(grafana.workloadRef).toEqual({ namespace: 'monitoring', kind: 'Deployment', name: 'grafana' });
    const request = grafana.series.find((s: any) => s.kind === 'request').points.map((p: [number, number]) => p[1]);
    expect(request[0]).toBe(0.25);
    expect(request[request.length - 1]).toBe(0.1);
    const staging = await readDemoBody(usage('prod-eks-cluster', 'scope=workload&resource=cpu&window=72h&namespace=staging&kind=Deployment&name=api-staging'));
    const used = staging.series.find((s: any) => s.kind === 'used').points;
    expect(used.some((p: [number, number | null]) => p[1] === null)).toBe(true);
    expect(used.some((p: [number, number | null]) => p[1] === 0)).toBe(false);
    expect(staging.coverage.covered).toBeLessThan(1);
  });

  it("keeps each workload's use in line with the rest of the demo: grafana right-sized, worker near its HPA's 45%", async () => {
    // grafana: endpoint 1 calls it right-sized at 100m / 256Mi, and the feed records a user cutting it
    // from 250m / 384Mi, so its busiest pod stays under its request at every point, before and after.
    for (const resource of ['cpu', 'memory']) {
      const body = await readDemoBody(usage('prod-eks-cluster', `scope=workload&resource=${resource}&window=72h&namespace=monitoring&kind=Deployment&name=grafana`));
      const used = body.series.find((s: any) => s.kind === 'used').points;
      const request = body.series.find((s: any) => s.kind === 'request').points;
      used.forEach((p: [number, number | null], i: number) => {
        if (p[1] !== null) expect(p[1]).toBeLessThan(request[i][1]);
      });
    }
    // worker: worker-hpa reports its CPU at about 45% of the 100m request (78% while at its maximum).
    const worker = await readDemoBody(usage('prod-eks-cluster', 'scope=workload&resource=cpu&window=72h&namespace=production&kind=Deployment&name=worker'));
    const values = worker.series.find((s: any) => s.kind === 'used').points.map((p: [number, number | null]) => p[1]).filter((v: number | null) => v !== null);
    expect(Math.min(...values)).toBeGreaterThan(0.03);
    expect(Math.max(...values)).toBeLessThan(0.08);
  });

  it('answers a custom window around a change at 1-minute steps, as the impact chart asks', async () => {
    const to = new Date(Math.floor(Date.now() / 60_000) * 60_000).toISOString();
    const from = new Date(Date.parse(to) - 3 * 3600_000).toISOString();
    const body = await readDemoBody(usage('prod-eks-cluster', `scope=workload&resource=cpu&window=custom&namespace=monitoring&kind=Deployment&name=grafana&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&step=60`));
    expect(body.window).toBe('custom');
    expect(body.stepSeconds).toBe(60);
    expect(body.series[0].points).toHaveLength(181);
  });

  it('validates as the server does: 400 naming the field, 404 for a workload the cluster lacks or a cluster it lacks', async () => {
    expect(await answerOf(usage('prod-eks-cluster', 'scope=pods&resource=cpu&window=72h'))).toMatchObject({ status: 400, body: { code: 'K8S_INVALID_REQUEST', field: 'scope' } });
    expect(await answerOf(usage('prod-eks-cluster', 'scope=cluster&resource=cpu&window=custom&from=2026-10-14T00:00:00Z&to=2026-10-14T01:00:00Z&step=60'))).toMatchObject({ status: 400, body: { field: 'window' } });
    expect(await answerOf(usage('prod-eks-cluster', 'scope=cluster&resource=cpu&window=72h&name=grafana'))).toMatchObject({ status: 400, body: { field: 'name' } });
    expect(await answerOf(usage('prod-eks-cluster', 'scope=workload&resource=cpu&window=72h&namespace=team-a&kind=Deployment&name=gone'))).toMatchObject({ status: 404, body: { code: 'K8S_INVALID_REQUEST', field: 'name' } });
    expect(await answerOf(usage('staging-eks-cluster', 'scope=cluster&resource=cpu&window=72h'))).toMatchObject({ status: 404, body: { code: 'K8S_CLUSTER_NOT_REGISTERED' } });
  });

  it('answers eu, which has no usage metrics, with unavailable and no series', async () => {
    const body = await readDemoBody(usage('eu-eks-cluster', 'scope=cluster&resource=cpu&window=72h'));
    expect(body.unavailable).toEqual({ code: 'NO_METRICS_SOURCE', message: 'No Prometheus answered in this cluster.' });
    expect(body.series).toEqual([]);
    expect(body.coverage).toBeNull();
  });

  it("answers the sparklines for every analysed workload, 73 hourly points, sorted as the server sorts them", async () => {
    const body = await readDemoBody(`/api/eks/automation/usage/prod-eks-cluster/sparklines?${q}&resource=cpu&window=72h`);
    expect(body).toMatchObject({ window: '72h', stepSeconds: 3600, unavailable: null });
    const status = await readDemoBody(`/api/eks/automation/vertical/prod-eks-cluster?${q}`);
    expect(body.workloads.map((w: any) => `${w.ref.namespace}/${w.ref.kind}/${w.ref.name}`)).toEqual(
      status.workloads.map((w: any) => `${w.ref.namespace}/${w.ref.kind}/${w.ref.name}`).sort(),
    );
    for (const w of body.workloads) expect(w.used).toHaveLength(73);
    expect((await answerOf(`/api/eks/automation/usage/prod-eks-cluster/sparklines?${q}&resource=cpu&window=24h`))).toMatchObject({ status: 400, body: { field: 'window' } });
  });

  it("answers an HPA's history: worker-hpa held at its maximum of 4 until the change the feed records, frontend-hpa never scaled", async () => {
    const hpa = (name: string) => `/api/eks/automation/hpa/prod-eks-cluster/production/${name}/history?${q}&window=72h`;
    const worker = await readDemoBody(hpa('worker-hpa'));
    expect(worker.series.map((s: any) => s.key)).toEqual(['currentReplicas', 'minReplicas', 'maxReplicas', 'cpuUtilization', 'cpuTarget']);
    const max = worker.series[2].points.map((p: [number, number]) => p[1]);
    expect(max[0]).toBe(4);
    expect(max[max.length - 1]).toBe(5);
    expect(worker.atMax.stepSeconds).toBe(60);
    expect(worker.atMax.runs.length).toBeGreaterThanOrEqual(2);
    expect(worker.atMax.share).toBeGreaterThan(0);
    expect(worker.changes.map((c: any) => `${c.from}→${c.to}`)).toEqual(['2→3', '3→4', '4→5', '5→3', '3→2']);
    // The same story as the activity feed: the maximum moved when the user's HPA change was recorded.
    const feed = await readDemoBody(`/api/eks/automation/activity/prod-eks-cluster?${q}&feature=HPA`);
    const raisedAt = Date.parse(feed.entries[0].at) / 1000;
    const firstFive = worker.series[2].points.find((p: [number, number]) => p[1] === 5)[0];
    expect(firstFive - raisedAt).toBeGreaterThanOrEqual(0);
    expect(firstFive - raisedAt).toBeLessThanOrEqual(1800);
    expect((await readDemoBody(hpa('frontend-hpa'))).changes).toEqual([]);
    expect(await answerOf(hpa('gone-hpa'))).toMatchObject({ status: 404, body: { code: 'K8S_INVALID_REQUEST', field: 'name' } });
  });

  it('counts the demo feed per IST day from the first day the full log recorded, with the feed\'s own results', async () => {
    const body = await readDemoBody(`/api/eks/automation/activity/prod-eks-cluster/summary?${q}&days=7`);
    expect(body).toMatchObject({ timezone: 'Asia/Kolkata', to: istDate(Date.now()), from: istDate(Date.now() - 6 * 86400_000), truncated: false });
    // grafana's resize, stored with its proof 26 h 13 min ago, is the first row only the full log writes.
    expect(body.days[0].date).toBe(istDate(Date.parse(body.completeSince)));
    expect(body.days[body.days.length - 1].date).toBe(body.to);
    const sum = (k: string) => body.days.reduce((n: number, d: any) => n + d[k], 0);
    expect({ applied: sum('applied'), putBack: sum('putBack'), refused: sum('refused'), testRuns: sum('testRuns'), failed: sum('failed') })
      .toEqual({ applied: 2, putBack: 1, refused: 1, testRuns: 1, failed: 0 });
    expect(Object.keys(body.days[0].byFeature)).toEqual(['VERTICAL', 'HPA']);
    const hpaOnly = await readDemoBody(`/api/eks/automation/activity/prod-eks-cluster/summary?${q}&days=7&feature=HPA`);
    expect(hpaOnly.days.reduce((n: number, d: any) => n + d.applied, 0)).toBe(1);
    expect(await answerOf(`/api/eks/automation/activity/prod-eks-cluster/summary?${q}&days=31`)).toMatchObject({ status: 400, body: { field: 'days' } });
    expect((await readDemoBody(`/api/eks/automation/activity/eu-eks-cluster/summary?${q}&days=7`)).days).toEqual([]);
  });

  it('filters the feed by from, as the Overview week and the Activity time filter ask', async () => {
    const from = new Date(Date.now() - 24 * 3600_000).toISOString();
    const body = await readDemoBody(`/api/eks/automation/activity/prod-eks-cluster?${q}&limit=50&from=${encodeURIComponent(from)}`);
    expect(body.entries.map((e: any) => e.target.name)).toEqual(['api-server', 'api-staging', 'frontend', 'worker-hpa']);
  });

  it('filters the feed by an exact namespace, kind and name, as a workload detail asks', async () => {
    const exact = (ns: string, kind: string, name: string) =>
      readDemoBody(`/api/eks/automation/activity/prod-eks-cluster?${q}&limit=20&feature=VERTICAL&namespace=${ns}&kind=${kind}&name=${name}`);
    const grafana = await exact('monitoring', 'Deployment', 'grafana');
    expect(grafana.entries.map((e: any) => `${e.target.namespace}/${e.target.kind}/${e.target.name}`)).toEqual(['monitoring/Deployment/grafana']);
    // An exact name is not a part of one.
    expect((await exact('production', 'Deployment', 'api')).entries).toEqual([]);
    expect((await exact('staging', 'Deployment', 'api-server')).entries).toEqual([]);
  });

  it('answers the HPA list\'s one read as each HPA\'s own read, and lists one the cluster lacks as missing', async () => {
    const one = (name: string) => readDemoBody(`/api/eks/automation/hpa/prod-eks-cluster/production/${name}/history?${q}&window=72h`);
    const batch = await readDemoBody(`/api/eks/automation/hpa/prod-eks-cluster/history?${q}&window=72h&hpas=${encodeURIComponent('production/worker-hpa,production/frontend-hpa,production/gone-hpa')}`);
    expect(batch.window).toBe('72h');
    expect(batch.histories.map((h: any) => h.name)).toEqual(['worker-hpa', 'frontend-hpa']);
    const worker = await one('worker-hpa');
    expect(batch.histories[0].changes).toEqual(worker.changes);
    expect(batch.histories[0].atMax.minutesAtMax).toBe(worker.atMax.minutesAtMax);
    expect(batch.missing).toEqual([{ namespace: 'production', name: 'gone-hpa' }]);
    expect(await answerOf(`/api/eks/automation/hpa/prod-eks-cluster/history?${q}&window=72h&hpas=production`)).toMatchObject({ status: 400, body: { field: 'hpas' } });
    expect(await answerOf(`/api/eks/automation/hpa/prod-eks-cluster/history?${q}&window=6h&hpas=production/worker-hpa`)).toMatchObject({ status: 400, body: { field: 'window' } });
    expect(await answerOf(`/api/eks/automation/hpa/nope-cluster/history?${q}&window=72h&hpas=production/worker-hpa`)).toMatchObject({ status: 404, body: { code: 'K8S_CLUSTER_NOT_REGISTERED' } });
  });

  it.each([
    ['POST', '/api/eks/automation/usage/prod-eks-cluster?scope=cluster&resource=cpu&window=72h'],
    ['PUT', '/api/eks/automation/usage/prod-eks-cluster/sparklines?resource=cpu&window=72h'],
    ['DELETE', '/api/eks/automation/hpa/prod-eks-cluster/production/worker-hpa/history?window=72h'],
    ['POST', '/api/eks/automation/hpa/prod-eks-cluster/history?window=72h&hpas=production/worker-hpa'],
    ['POST', '/api/eks/automation/activity/prod-eks-cluster/summary?days=7'],
    ['GET', '/api/eks/automation/usage/prod-eks-cluster/anything-else'],
    ['GET', '/api/eks/automation/hpa/prod-eks-cluster'],
  ])('refuses %s %s with 403 K8S_DEMO, never a read fixture and never the network', async (method, path) => {
    const response = resolveDemoResponse(path, method);
    expect(response?.status).toBe(403);
    expect((await response!.json()).code).toBe('K8S_DEMO');
  });
});

/** Automation › Node autoscaler reads the automation agent's three endpoints; none may reach the backend. */
describe('demo router — node automation reads (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const base = (cluster: string) => `/api/eks/autoscaling/${cluster}`;
  const q = '?accountId=demo-aws-001&region=us-east-1';

  it('answers prod with node automation on, in the backend shapes', async () => {
    const dashboard = await readDemoBody(`${base('prod-eks-cluster')}${q}`);
    expect(dashboard.autoscalingEnabled).toBe(true);
    expect(dashboard.agentStatus).toMatchObject({ mode: 'full', agentVersion: '1.0.0-autoscaler' });
    expect(typeof dashboard.agentStatus.lastSyncAt).toBe('string');
    // Fields nothing measures stay absent (types/k8sAutomation.ts §10.3).
    expect(JSON.stringify(dashboard)).not.toMatch(/costSavedThisWeek|nodesSavedToday|lastRebalanceSavingsPercent/);
    const fleet = await readDemoBody(`${base('prod-eks-cluster')}/node-fleet?accountId=demo-aws-001`);
    expect(fleet).toMatchObject({
      totalNodes: 6, onDemandNodes: 3, spotNodes: 3, idleNodes: [], interruptedNodes: [],
      interruptedNodesReported: true, interruptionNoticesReported: true, interruptionNotices: [], spotCandidates: [],
    });
    expect(typeof fleet.collectedAt).toBe('string');
    expect(await readDemoBody(`${base('prod-eks-cluster')}/rebalance-status?accountId=demo-aws-001`)).toEqual({
      status: 'idle',
      message: 'No rebalance has been run yet',
    });
  });

  it('answers eu with node automation off', async () => {
    const dashboard = await readDemoBody(`${base('eu-eks-cluster')}${q}`);
    expect(dashboard.autoscalingEnabled).toBe(false);
    expect(dashboard.agentStatus).toMatchObject({ mode: 'disabled', lastSyncAt: null });
  });

  it('answers the consolidation plan honestly: NO_PLAN, with no invented nodes or savings', async () => {
    for (const cluster of ['prod-eks-cluster', 'eu-eks-cluster']) {
      expect(await readDemoBody(`${base(cluster)}/consolidation-plan?accountId=demo-aws-001`)).toEqual({
        freshness: 'NO_PLAN',
        generatedAt: null,
        ageSeconds: null,
        plan: null,
        savings: null,
      });
    }
  });
  it('answers the spot interruption history honestly: no invented events, the demo fleet share, handling not reported', async () => {
    const prod = await readDemoBody(`${base('prod-eks-cluster')}/spot-interruptions?accountId=demo-aws-001&days=90&limit=200`);
    expect(prod).toMatchObject({
      windowDays: 90,
      truncated: false,
      retentionDays: 365,
      recordingEnabled: true,
      fleet: { totalNodes: 6, spotNodes: 3, spotSharePct: 50 },
      coverage: { handlingNow: null },
      events: [],
    });
    expect(typeof prod.coverage.agentSnapshotAt).toBe('string');
    expect(typeof prod.coverage.automationReportAt).toBe('string');
    const eu = await readDemoBody(`${base('eu-eks-cluster')}/spot-interruptions?accountId=demo-aws-001`);
    expect(eu).toMatchObject({ windowDays: 30, fleet: { totalNodes: 3, spotNodes: 0, spotSharePct: 0 }, events: [] });
  });

  it.each(['', '/node-fleet', '/rebalance-status', '/consolidation-plan', '/spot-interruptions'])('answers any other cluster 404 K8S_CLUSTER_NOT_REGISTERED (%s)', async (path) => {
    const response = resolveDemoResponse(`${base('staging-eks-cluster')}${path}${q}`);
    expect(response?.status).toBe(404);
    expect((await response!.json()).code).toBe('K8S_CLUSTER_NOT_REGISTERED');
  });

  it.each([
    ['PUT', '/enabled'],
    ['PUT', '/mode'],
    ['PUT', '/node-optimizer'],
    ['PUT', '/rebalancer'],
    ['PUT', '/consolidation-plan'],
    ['POST', '/consolidation-plan'],
    ['POST', '/spot-interruptions'],
    ['POST', '/rebalance'],
    ['DELETE', '/node-templates/t1'],
  ])('refuses the node automation write %s %s with 403 K8S_DEMO, never a read fixture', async (method, path) => {
    const response = resolveDemoResponse(`${base('prod-eks-cluster')}${path}${q}`, method);
    expect(response?.status).toBe(403);
    expect((await response!.json()).code).toBe('K8S_DEMO');
  });
});

/**
 * The Cost page's CUR sections have no Cost Explorer fallback, so an unmocked path 409s and the
 * section hides itself. That is the right behaviour for anything genuinely unavailable and the
 * wrong one for a section the demo is meant to show — these routes are what make the difference.
 */
describe('demo router — CUR insights (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  it('answers the charge-type ledger with rows that sum to the invoice total', async () => {
    const body = await readDemoBody('/api/xamops/cur-insights/charge-types/demo-aws-001');
    expect(body).toEqual(demoChargeTypes);
    // So the ledger shows "matches the total above" rather than the sub-cent explanation.
    const total = body.reduce((sum: number, row: { cost: number }) => sum + row.cost, 0);
    expect(Math.round(total * 100)).toBe(Math.round(18420.75 * 100));
  });

  it('answers one service with only that service\'s resources', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/service-resources/demo-aws-001'
        + '?service=Amazon%20Simple%20Storage%20Service&period=2026-08',
    );

    expect(body.resources.length).toBeGreaterThan(0);
    for (const row of body.resources) {
      expect(row.service).toBe('Amazon Simple Storage Service');
    }
    // The S3 figure in demoChargesByService, reached by derivation rather than by hand.
    expect(Math.round(body.totalCost * 100)).toBe(Math.round(1920.25 * 100));
    expect(body.groupCount).toBe(body.resources.length);
  });

  it('reports the amortized basis unavailable, with a reason', async () => {
    // On purpose: the router ignores the query string, so ?basis=amortized would be served the
    // unblended fixture — identical figures under a different label, which is the dishonesty the
    // real guard exists to prevent. Reporting it unavailable also puts the
    // disabled-with-an-explanation state in front of anyone looking at the demo.
    const body = await readDemoBody('/api/xamops/cur-insights/capabilities/demo-aws-001');

    expect(body.amortizedAvailable).toBe(false);
    expect(body.amortizedDetail).not.toBe('');
    // The unit column is fine, so its sentence stays empty and nothing renders for it.
    expect(body.unitAvailable).toBe(true);
    expect(body.unitDetail).toBe('');
  });

  it('introduces no cur-insights catch-all, so an unmocked CUR path answers nothing usable', async () => {
    /*
     * The three new branches are specific paths. A `cur-insights` catch-all would answer every
     * future endpoint under the prefix with one of these fixtures — so a section the demo has no
     * data for would render another section's figures instead of hiding itself.
     *
     * An unmocked one lands on the generic `/api/xamops/` fallback, whose empty object fails the
     * shape check in every CUR fetcher and therefore reads as "no data" rather than as a payload.
     */
    const body = await readDemoBody('/api/xamops/cur-insights/savings-rate/demo-aws-001');
    expect(body).toEqual({});
  });

  it('answers the connectivity tab\'s identity lookup, decoding the ids it was given', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-identities/demo-aws-001'
        + '?ids=arn%3Aaws%3Ards%3Aap-south-1%3A605134400000%3Adb%3Axammer-prod%2Ci-0abc1001a2b3c4d5e',
    );

    // Decoded through the router's own `param` helper, so an ARN's colons survive.
    expect(body.identities.map((entry: { requestedId: string }) => entry.requestedId)).toEqual([
      'arn:aws:rds:ap-south-1:605134400000:db:xammer-prod',
      'i-0abc1001a2b3c4d5e',
    ]);
    // resource_id first, because that is the id the inventory-plane graph path emits.
    expect(body.identities[0].nodeIdCandidates[0]).toBe('xammer-prod');
    expect(body.resolved).toBe(2);
  });

  it('answers an id it has never seen as present and unresolved', async () => {
    // What the real endpoint does, and what keeps the "not in the account topology" state
    // reachable in demo rather than unreachable.
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-identities/demo-aws-001?ids=snap-0deadbeef',
    );

    expect(body.identities).toHaveLength(1);
    expect(body.identities[0].resourceId).toBeNull();
    expect(body.identities[0].nodeIdCandidates).toEqual(['snap-0deadbeef']);
    expect(body.resolved).toBe(0);
  });

  it('prices a set of resources, and refuses the one name two live resources answer to', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-costs/demo-aws-001'
        + '?ids=i-0abc1001a2b3c4d5e%2Cvol-0aa1122334455667%2Cxammer-prod%2Cigw-001&period=2026-08',
    );

    const priced = Object.fromEntries(
      body.costs.map((entry: { resourceId: string; cost: number }) => [entry.resourceId, entry.cost]),
    );
    expect(priced['i-0abc1001a2b3c4d5e']).toBe(1621.35);
    expect(priced['vol-0aa1122334455667']).toBe(484.74);
    // The RDS instance and the EKS cluster both tail to `xammer-prod`, so the bare name prices
    // neither — the row renders "cost not attributable to this resource alone".
    expect(body.ambiguous).toEqual(['xammer-prod']);
    expect(priced['xammer-prod']).toBeUndefined();
    // No row in demoTopResources at all: absent, which renders "no cost attributable".
    expect(priced['igw-001']).toBeUndefined();
    expect(body.requestedCount).toBe(4);
    expect(body.pricedCount).toBe(2);
  });

  it('prices the database under its own ARN, which is single-claimant', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-costs/demo-aws-001'
        + '?ids=arn%3Aaws%3Ards%3Aap-south-1%3A605134400000%3Adb%3Axammer-prod',
    );

    expect(body.ambiguous).toEqual([]);
    expect(body.costs[0].cost).toBe(2540.40);
  });

  // The per-resource drill-down drawer (Change/Breakdown/Trend tabs). Without a fixture the
  // request fell through to the backend, 409'd, and the drawer showed "No detail available".
  it('answers the resource drill-down scoped to the row that opened it', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-change/demo-aws-001'
        + '?resourceId=i-0abc1001a2b3c4d5e'
        + '&service=Amazon%20Elastic%20Compute%20Cloud&region=ap-south-1&period=2026-08',
    );

    // The exact shape the fetcher validates with isRecordWith(['lines','daily'],['costChange']).
    expect(Array.isArray(body.lines)).toBe(true);
    expect(Array.isArray(body.daily)).toBe(true);
    expect(typeof body.costChange).toBe('number');

    expect(body.comparisonAvailable).toBe(true);
    // Reconciles to the demoTopResources row, so the panel matches the figure that opened it.
    expect(Math.round(body.currentCost * 100)).toBe(Math.round(1621.35 * 100));
    expect(body.service).toBe('Amazon Elastic Compute Cloud');
    expect(body.region).toBe('ap-south-1');
  });

  it('keeps priceEffect + volumeEffect === costChange at the resource level', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-change/demo-aws-001'
        + '?resourceId=arn%3Aaws%3Ards%3Aap-south-1%3A605134400000%3Adb%3Axammer-prod'
        + '&service=Amazon%20Relational%20Database%20Service&region=ap-south-1&period=2026-08',
    );

    expect(Math.round((body.priceEffect + body.volumeEffect) * 100))
      .toBe(Math.round(body.costChange * 100));
  });

  it('keeps the invariant per line and every line clears the min-change filter', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-change/demo-aws-001'
        + '?resourceId=arn%3Aaws%3As3%3A%3A%3Axammer-prod-assets'
        + '&service=Amazon%20Simple%20Storage%20Service&region=ap-south-1&period=2026-08',
    );

    expect(body.lines.length).toBeGreaterThanOrEqual(1);
    expect(body.lines.length).toBeLessThanOrEqual(3);
    for (const l of body.lines) {
      expect(Math.round((l.priceEffect + l.volumeEffect) * 100))
        .toBe(Math.round(l.costChange * 100));
      // MIN_LINE_CHANGE = 0.01 in the drawer — a smaller line would be hidden and desync the count.
      expect(Math.abs(l.costChange) >= 0.01 || Math.abs(l.currentCost) >= 0.01).toBe(true);
    }
  });

  it('builds a daily series that reconciles to the current cost', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-change/demo-aws-001'
        + '?resourceId=i-0abc1001a2b3c4d5e'
        + '&service=Amazon%20Elastic%20Compute%20Cloud&region=ap-south-1&period=2026-08',
    );

    expect(body.daily.length).toBeGreaterThanOrEqual(28);
    for (const d of body.daily) expect(d.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const sum = body.daily.reduce((s: number, d: { cost: number }) => s + d.cost, 0);
    expect(Math.round(sum * 100)).toBe(Math.round(body.currentCost * 100));
  });

  it('answers two identical requests identically, so re-opening the drawer is stable', async () => {
    const url = '/api/xamops/cur-insights/resource-change/demo-aws-001'
      + '?resourceId=i-0abc1001a2b3c4d5e'
      + '&service=Amazon%20Elastic%20Compute%20Cloud&region=ap-south-1&period=2026-08';
    expect(await readDemoBody(url)).toEqual(await readDemoBody(url));
  });

  it('reads an unknown resource id as no data, so the drawer shows "No detail available"', async () => {
    // A 200 body of "null" fails the fetcher's shape check → readShape returns null → empty state.
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-change/demo-aws-001?resourceId=i-doesnotexist&period=2026-08',
    );
    expect(body).toBeNull();
  });

  it('reads a blank (account-level) id as no data', async () => {
    const body = await readDemoBody(
      '/api/xamops/cur-insights/resource-change/demo-aws-001?resourceId=&period=2026-08',
    );
    expect(body).toBeNull();
  });
});

describe('demo router — DbOps console (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });
  const base = '/api/dbops/aws/demo-aws-001';

  it('serves the health view as an object with findings (not the [] catch-all)', async () => {
    const body = await readDemoBody(`${base}/console/health`);
    expect(Array.isArray(body)).toBe(false);
    expect(body).toEqual(demoDbHealthView);
    expect(body.findings.length).toBeGreaterThan(0);
  });

  it('serves lock-waits as an array', async () => {
    expect(await readDemoBody(`${base}/console/lock-waits`)).toEqual(demoDbLockWaits);
  });

  it('serves replication as an array', async () => {
    expect(await readDemoBody(`${base}/console/replication`)).toEqual(demoDbReplication);
  });

  it('serves the access graph as a nodes/edges document', async () => {
    const body = await readDemoBody(`${base}/console/access-graph`);
    expect(Array.isArray(body)).toBe(false);
    expect(body).toEqual(demoAccessGraph);
    expect(body.nodes.length).toBeGreaterThan(0);
  });

  it('serves the table graph as a nodes/edges document', async () => {
    const body = await readDemoBody(`${base}/console/table-graph`);
    expect(Array.isArray(body)).toBe(false);
    expect(body).toEqual(demoFkGraph);
    expect(body.edges.length).toBeGreaterThan(0);
  });
});

describe('demo router — XamLens Datastores page and drawer (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });
  const DS = '/api/dspm/datastores';
  const S3 = 'xammer-prod-customer-exports';
  const rowsOf = async (query: string): Promise<DatastoreRow[]> =>
    ((await readDemoBody(`${DS}?${query}&pageSize=100`)) as DspmPage<DatastoreRow>).data;
  const dimensionOf = async (query: string, key: string) =>
    ((await readDemoBody(`${DS}/filter-options?${query}`)) as DatastoreFilterOptions).dimensions.find(d => d.key === key);
  const sourceIdOf = async (name: string) => (await rowsOf('tab=all')).find(row => row.name === name)!.sourceId!;
  const resourceOf = (sourceId: string, path: string) => readDemoBody(`${DS}/${sourceId}/${path}`);

  it('answers every Datastores path with its data, never the {} catch-all', async () => {
    const s3 = await sourceIdOf(S3);
    const bodies: unknown[] = [
      await readDemoBody(`${DS}?tab=all`),
      await readDemoBody(`${DS}/filter-options?tab=cloud`),
      await readDemoBody(`${DS}/kpis?tab=all`),
    ];
    for (const path of [
      'overview', 'scan-status', 'sensitives', 'sensitives/files?page=1&pageSize=10',
      'sensitives/occurrences?entityName=PERSON%20NAME&skip=0&limit=10&showFalsePositives=false',
      'risks', 'access-graph', 'scan-history', 'data-flow/databases',
    ]) {
      bodies.push(await resourceOf(s3, path));
    }
    const databases = await resourceOf(await sourceIdOf('crm_production'), 'data-flow/databases') as DataFlowDatabases;
    const schemas = await readDemoBody(`/api/dspm/data-flow/databases/${databases.items[0].id}/schemas`) as DataFlowSchemas;
    const tables = await readDemoBody(
      `/api/dspm/data-flow/schemas/${schemas.items[0].id}/tables?page=1&pageSize=10`,
    ) as DataFlowTablePage;
    const contacts = tables.rows.find(table => table.name === 'contacts')!;
    const columns = await readDemoBody(
      `/api/dspm/data-flow/tables/${contacts.id}/columns?page=2&pageSize=10`,
    ) as DataFlowColumnPage;
    bodies.push(databases, schemas, tables, columns);
    for (const body of bodies) expect(body).not.toEqual({});
    expect(tables).toMatchObject({ total: 23, searchCapped: false });
    expect(columns).toMatchObject({ total: 14, page: 2 });
    expect(columns.rows).toHaveLength(4);
    const searched = await readDemoBody(
      `/api/dspm/data-flow/schemas/${schemas.items[0].id}/tables?page=1&pageSize=10&q=DEAL`,
    ) as DataFlowTablePage;
    expect(searched.rows.map(table => table.name)).toEqual(['deal_notes', 'deal_stages', 'deals']);
  });

  it('still serves the existing SaaS and endpoint drawers on /api/dspm/assets/{id}', async () => {
    for (const tab of ['saas', 'endpoint']) {
      const [row] = await rowsOf(`tab=${tab}`);
      const detail = await readDemoBody(`/api/dspm/assets/${row.id}`);
      expect(detail.id).toBe(row.id);
      expect(detail.objects.length).toBeGreaterThan(0);
    }
  });

  it('honours Include and Exclude', async () => {
    const cloud = await rowsOf('tab=cloud');
    const included = await rowsOf('tab=cloud&provider=S3');
    const excluded = await rowsOf('tab=cloud&provider_exclude=S3');
    expect(included.length).toBeGreaterThan(0);
    expect(included.every(row => row.source.provider === 'S3')).toBe(true);
    expect(excluded.some(row => row.source.provider === 'S3')).toBe(false);
    expect(included.length + excluded.length).toBe(cloud.length);
    expect((await rowsOf('tab=cloud&provider=AZURE_BLOB&scan_status=NOT_SCANNED')).map(row => row.name))
      .toEqual(['ledger-exports-weu']);
  });

  it('counts every dimension disjunctively and drops its own excluded values', async () => {
    const provider = await dimensionOf('tab=cloud', 'provider');
    // A dimension's own selection never shrinks its own options...
    expect(await dimensionOf('tab=cloud&provider=S3', 'provider')).toEqual(provider);
    // ...while every other dimension counts only the matching rows.
    const statuses = await dimensionOf('tab=cloud&provider=S3', 'scan_status');
    const s3Rows = await rowsOf('tab=cloud&provider=S3');
    expect(statuses!.options.reduce((sum, option) => sum + (option.count ?? 0), 0)).toBe(s3Rows.length);
    const withoutEbs = await dimensionOf('tab=cloud&provider_exclude=EBS', 'provider');
    expect(withoutEbs!.options.map(option => option.value)).not.toContain('EBS');
    expect(withoutEbs!.options).toHaveLength(provider!.options.length - 1);
    expect(await dimensionOf('tab=all', 'region')).toBeUndefined();
    const hostnames = await dimensionOf('tab=endpoint', 'hostname');
    expect(hostnames!.options.length).toBeGreaterThan(0);
    expect(hostnames!.options.every(option => option.count === null)).toBe(true);
  });

  it('pages the occurrences of an entity', async () => {
    const s3 = await sourceIdOf(S3);
    const page = (skip: number) => resourceOf(
      s3, `sensitives/occurrences?entityName=PERSON%20NAME&skip=${skip}&limit=10&showFalsePositives=false`,
    ) as Promise<DatastoreOccurrencePage>;
    const first = await page(0);
    expect(first).toMatchObject({ entityName: 'PERSON NAME', total: 72, skip: 0, limit: 10 });
    expect(first.rows).toHaveLength(10);
    expect(first.rows[0].samples).toHaveLength(5);
    expect(first.rows[0].samples[0]).toBe('<<Jane Doe>>');
    expect(first.rows.some(row => row.samples.length === 0)).toBe(true);
    const last = await page(70);
    expect(last).toMatchObject({ total: 72, skip: 70 });
    expect(last.rows).toHaveLength(2);
  });

  it('answers an unknown datastore or Data Flow id with 404 ASSET_NOT_FOUND', async () => {
    for (const url of [
      `${DS}/ffffffffffffffffffffffff/overview`,
      `${DS}/ffffffffffffffffffffffff/sensitives/occurrences?entityName=PERSON%20NAME`,
      '/api/dspm/data-flow/schemas/00000000-0000-4000-8000-000000000000/tables?page=1&pageSize=10',
    ]) {
      const response = resolveDemoResponse(url);
      expect(response?.status, url).toBe(404);
      expect((await response!.json()).code).toBe('ASSET_NOT_FOUND');
    }
  });

  it('agrees with the drawer on the records and types of every cloud and on-prem row', async () => {
    const datastores = [...await rowsOf('tab=cloud'), ...await rowsOf('tab=on_prem')];
    expect(datastores).toHaveLength(23);
    for (const row of datastores) {
      const name = row.name!;
      const sensitives = await resourceOf(row.sourceId!, 'sensitives') as DatastoreSensitives;
      const overview = await resourceOf(row.sourceId!, 'overview') as DatastoreOverview;
      expect(sensitives.entities.reduce((sum, entity) => sum + entity.count, 0), name).toBe(row.sensitives.recordCount);
      // A never-scanned row has no total, and the list counts it as 0 ("-").
      if (row.scan.status === 'NOT_SCANNED') {
        expect(overview.sensitiveRecords.total, name).toBeNull();
        expect(row.sensitives.recordCount, name).toBe(0);
      } else {
        expect(overview.sensitiveRecords.total, name).toBe(row.sensitives.recordCount);
      }
      expect(overview.sensitiveRecords.groups.map(group => group.label), name).toEqual(row.sensitives.typeLabels);
    }
    const byName = (name: string) => datastores.find(row => row.name === name)!.sensitives;
    expect(byName(S3)).toEqual({ recordCount: 2156, typeLabels: ['Personal', 'IT & Security'] });
    expect(byName('crm_production')).toEqual({ recordCount: 8591, typeLabels: ['IT & Security', 'Personal'] });
  });

  it.each([
    ['all', '+1%', '+6'],
    ['cloud', '+10%', '+4'],
    ['endpoint', '+25%', null],
    ['saas', null, null],
    ['on_prem', null, null],
  ])('ends both %s KPI trends, 14 readings each, at the card values', async (tab, recordsBadge, assetsBadge) => {
    const kpis = await readDemoBody(`${DS}/kpis?tab=${tab}`) as DatastoreKpis;
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    expect(kpis.tab).toBe(tab);
    for (const metric of [kpis.totalSensitiveRecords, kpis.assetCount]) {
      expect(metric.trend).toHaveLength(14);
      expect(metric.trend[13]).toEqual({ date: today, value: metric.value });
    }
    expect(trendBadge(kpis.totalSensitiveRecords.trend, 'percent')?.text ?? null).toBe(recordsBadge);
    expect(trendBadge(kpis.assetCount.trend, 'delta')?.text ?? null).toBe(assetsBadge);
    // The tab strip's count, the asset card and the list agree.
    expect(kpis.tabCounts[tab]).toBe(kpis.assetCount.value);
    expect(((await readDemoBody(`${DS}?tab=${tab}`)) as DspmPage<DatastoreRow>).total).toBe(kpis.assetCount.value);
  });
});

describe('demo router — XamLens Misconfigurations page (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  const MC = '/api/dspm/misconfigurations';
  const RANK: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
  const datastoresOf = async () => [
    ...((await readDemoBody('/api/dspm/datastores?tab=cloud&pageSize=100')) as DspmPage<DatastoreRow>).data,
    ...((await readDemoBody('/api/dspm/datastores?tab=on_prem&pageSize=100')) as DspmPage<DatastoreRow>).data,
  ];

  it('answers the list with rows in row order and cards that match them, never {}', async () => {
    const list = await readDemoBody(MC) as MisconfigurationList;
    expect(list).not.toEqual({});
    expect(list.capped).toBe(false);
    expect(list.datastoresRead).toBe(23);
    expect(list.rows.length).toBeGreaterThanOrEqual(8);
    for (let i = 1; i < list.rows.length; i++) {
      const [a, b] = [list.rows[i - 1], list.rows[i]];
      const order = (RANK[a.severity ?? ''] ?? 0) - (RANK[b.severity ?? ''] ?? 0) || a.affectedResources - b.affectedResources;
      expect(order, `${a.title} before ${b.title}`).toBeGreaterThanOrEqual(0);
    }
    expect(list.rows[0].severity).toBe('HIGH');
    expect(list.open.total).toBe(list.rows.length);
    expect(list.open.high + list.open.medium + list.open.critical + list.open.low).toBe(list.rows.length);
    expect(new Set(list.rows.map(row => row.source))).toEqual(new Set(['UPWIND', 'MATTERS']));
    const details = await Promise.all(list.rows.map(row =>
      readDemoBody(`${MC}/policies/${encodeURIComponent(row.policyId)}`) as Promise<MisconfigurationDetail>));
    expect(list.totalAffectedResources)
      .toBe(new Set(details.flatMap(detail => detail.affected.map(item => item.datastore.sourceId))).size);
    expect(details.some(detail => detail.learnMore?.startsWith('https://'))).toBe(true);
    expect(details.some(detail => detail.cli !== null)).toBe(true);
  });

  it("agrees with every cloud and on-prem datastore's /risks list", async () => {
    const list = await readDemoBody(MC) as MisconfigurationList;
    const datastores = await datastoresOf();
    const risksOf = new Map<string, string[]>();
    for (const row of datastores) {
      const body = await readDemoBody(`/api/dspm/datastores/${row.sourceId}/risks`) as DatastoreRisks;
      risksOf.set(row.sourceId!, body.items.map(item => item.title!));
      // A derived datastore reports findings once scanned; the never-scanned Azure showcase keeps its own.
      if (row.scan.status === 'NOT_SCANNED' && row.name !== 'ledger-exports-weu') expect(body.items, row.name!).toEqual([]);
    }
    for (const row of list.rows) {
      const detail = await readDemoBody(`${MC}/policies/${encodeURIComponent(row.policyId)}`) as MisconfigurationDetail;
      expect(detail.policyId).toBe(row.policyId);
      expect(detail.title).toBe(row.title);
      const expected = datastores.filter(d => risksOf.get(d.sourceId!)!.includes(row.title!)).map(d => d.sourceId);
      expect(detail.affected.map(item => item.datastore.sourceId), row.title!).toEqual(expected);
      expect(row.affectedResources).toBe(expected.length);
      expect(row.sensitiveRecords).toBe(detail.affected.reduce((sum, item) => sum + item.datastore.sensitives.recordCount, 0));
    }
    // Every finding on every datastore belongs to one row.
    const titles = new Set(list.rows.map(row => row.title));
    for (const found of risksOf.values()) for (const title of found) expect(titles.has(title), title).toBe(true);
  });

  it('answers an unknown or malformed policy with 404 ASSET_NOT_FOUND, never {}', async () => {
    for (const url of [
      `${MC}/policies/p-ffffffffffffffff`,
      `${MC}/policies/-leading-dash`,
      `${MC}/policies/a%2Fb`,
      `${MC}/somewhere-else`,
    ]) {
      const response = resolveDemoResponse(url);
      expect(response?.status, url).toBe(404);
      expect((await response!.json()).code, url).toBe('ASSET_NOT_FOUND');
    }
  });
});

describe('demo router — XamLens SaaS, Endpoint and Graph View drawers (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });
  const DS = '/api/dspm/datastores';
  const FLOW = '/api/dspm/data-flow';
  const rowsOf = async (tab: string): Promise<DatastoreRow[]> =>
    ((await readDemoBody(`${DS}?tab=${tab}&pageSize=100`)) as DspmPage<DatastoreRow>).data;
  const rowOf = async (tab: string, name: string, subtype?: string) =>
    (await rowsOf(tab)).find(row => row.name === name && (!subtype || row.subtype === subtype))!;
  const answerOf = async (url: string) => {
    const response = resolveDemoResponse(url);
    expect(response, url).not.toBeNull();
    return { status: response!.status, body: await response!.json() };
  };
  const filesOf = (sourceId: string, query: string) => readDemoBody(`${DS}/${sourceId}/saas/files?${query}`) as Promise<SaasFilePage>;
  const crmSchemas = async () => {
    const crm = await rowOf('on_prem', 'crm_production');
    const databases = await readDemoBody(`${DS}/${crm.sourceId}/data-flow/databases`) as DataFlowDatabases;
    const schemas = await readDemoBody(`${FLOW}/databases/${databases.items[0].id}/schemas`) as DataFlowSchemas;
    return { database: databases.items[0].id, schemas: schemas.items };
  };
  const tablesOf = async (schemaId: string) =>
    (await readDemoBody(`${FLOW}/schemas/${schemaId}/tables?page=1&pageSize=100`) as DataFlowTablePage).rows;

  it('answers every new path with its DTO, never the {} catch-all', async () => {
    const gmail = await rowOf('saas', 'Priya Nair', 'GMAIL');
    const mac = await rowOf('endpoint', 'Priya’s MacBook Pro');
    const files = await filesOf(gmail.sourceId!, 'dataElement=PERSON%20NAME&limit=5&skip=0');
    expect(files).toMatchObject({ skip: 0, limit: 5, total: 6 });
    const { database, schemas } = await crmSchemas();
    const [table] = await tablesOf(schemas[0].id!);
    const urls = [
      ...['detail', 'records?view=data_elements', 'records?view=document_classes', 'scan-summary']
        .map(path => `${DS}/${gmail.sourceId}/saas/${path}`),
      ...['overview', 'activity', 'lineage', 'copies'].map(path => `/api/dspm/saas-files/${files.rows[0].id}/${path}`),
      ...['overview', 'filter-options', 'summary', 'sensitives?showFalsePositives=false',
        'occurrences?entityName=EMAIL%20ADDRESS&skip=0&limit=10&showFalsePositives=false', 'files?skip=0&limit=10',
        'scans?skip=0&limit=10'].map(path => `${DS}/${mac.sourceId}/endpoint/${path}`),
      `${FLOW}/schemas/${schemas[0].id}/erd`, `${FLOW}/databases/${database}/erd`, `${FLOW}/tables/${table.id}/relations`,
    ];
    for (const url of urls) {
      const { status, body } = await answerOf(url);
      expect(status, url).toBe(200);
      expect(body, url).not.toEqual({});
    }
  });

  it('answers unknown datastore, file and table ids with 404 ASSET_NOT_FOUND', async () => {
    const cloud = await rowOf('cloud', 'xammer-prod-customer-exports');
    for (const url of [
      `${DS}/ffffffffffffffffffffffff/saas/detail`,
      // A cloud row is no SaaS row.
      `${DS}/${cloud.sourceId}/saas/records?view=data_elements`,
      `${DS}/ffffffffffffffffffffffff/endpoint/overview`,
      '/api/dspm/saas-files/ffffffffffffffffffffffff/overview',
      '/api/dspm/saas-files/de5003999999999999999999/lineage',
      `${FLOW}/schemas/00000000-0000-4000-8000-000000000000/erd`,
      `${FLOW}/tables/00000000-0000-4000-8000-000000000000/relations`,
    ]) {
      const { status, body } = await answerOf(url);
      expect(status, url).toBe(404);
      expect(body.code, url).toBe('ASSET_NOT_FOUND');
    }
  });

  it('refuses a files filter that is no row of its view with 400 INVALID_REQUEST, as the backend does', async () => {
    const gmail = (await rowOf('saas', 'Priya Nair', 'GMAIL')).sourceId!;
    const drive = (await rowOf('saas', 'Priya Nair', 'GOOGLE_WORKSPACE')).sourceId!;
    for (const [sourceId, query, parameter] of [
      [gmail, 'dataElement=CREDIT%20CARD%20NUMBERS', 'dataElement'],
      [gmail, 'documentClass=Budgets', 'documentClass'],
      [drive, 'documentClass=Invoices%20%26%20Receipts', 'documentClass'],
      [gmail, 'dataElement=PERSON%20NAME&documentClass=Legal', 'dataElement'],
      [gmail, 'limit=5', 'dataElement'],
    ]) {
      const { status, body } = await answerOf(`${DS}/${sourceId}/saas/files?${query}`);
      expect(status, query).toBe(400);
      expect(body, query).toEqual({ code: 'INVALID_REQUEST', message: `${parameter} is not a row of this datastore` });
    }
  });

  it('agrees with the list on the record count of every SaaS and endpoint row (FR-DEMO-3)', async () => {
    for (const row of await rowsOf('saas')) {
      const records = await readDemoBody(`${DS}/${row.sourceId}/saas/records?view=data_elements`) as SaasRecords;
      expect(records.rows.reduce((sum, r) => sum + r.count, 0), row.name!).toBe(row.sensitives.recordCount);
      for (const element of records.rows) {
        const files = await filesOf(row.sourceId!, `dataElement=${encodeURIComponent(element.name)}&limit=100&skip=0`);
        expect(files.rows.reduce((sum, file) => sum + file.records, 0), `${row.name} ${element.name}`).toBe(element.count);
      }
    }
    for (const row of await rowsOf('endpoint')) {
      const metrics = await readDemoBody(`${DS}/${row.sourceId}/endpoint/sensitives?showFalsePositives=false`) as EndpointSensitiveMetrics;
      const summary = await readDemoBody(`${DS}/${row.sourceId}/endpoint/summary`) as EndpointSummary;
      const entities = metrics.metrics.filter(metric => metric.kind === 'ENTITY').reduce((sum, metric) => sum + metric.count, 0);
      expect(entities, row.name!).toBe(row.sensitives.recordCount);
      expect(summary.totalSensitives ?? 0, row.name!).toBe(row.sensitives.recordCount);
    }
  });

  it('draws each Table View foreign key in Graph View, from a column marked foreignKey', async () => {
    const { schemas } = await crmSchemas();
    let keys = 0;
    for (const schema of schemas) {
      const erd = await readDemoBody(`${FLOW}/schemas/${schema.id}/erd`) as DataFlowErd;
      const tables = await tablesOf(schema.id!);
      expect(erd.nodes.map(node => node.id)).toEqual(tables.map(table => table.id));
      for (const table of tables) {
        const name = table.name!;
        const outgoing = erd.edges.filter(edge => edge.sourceTableId === table.id);
        expect(outgoing, name).toHaveLength(table.foreignKeyCount ?? 0);
        keys += outgoing.length;
        const relations = await readDemoBody(`${FLOW}/tables/${table.id}/relations`) as DataFlowRelations;
        const columns = (await readDemoBody(`${FLOW}/tables/${table.id}/columns?page=1&pageSize=100`) as DataFlowColumnPage).rows;
        const sources = relations.outgoing.flatMap(relation => relation.columns.map(pair => pair.sourceColumn));
        expect(sources, name).toHaveLength(table.foreignKeyCount ?? 0);
        expect(columns.filter(column => column.foreignKey).map(column => column.name).sort(), name).toEqual([...sources].sort());
        expect(columns.filter(column => column.primaryKey).map(column => column.name), name).toEqual(['id']);
        const node = erd.nodes.find(n => n.id === table.id)!;
        expect(node.keyColumns.every(key => columns.some(column => column.name === key)), name).toBe(true);
      }
    }
    expect(keys).toBe(31);
  });

  it('draws audit.service_accounts as the public diagram’s one external card, with three isolated tables', async () => {
    const { schemas } = await crmSchemas();
    const erd = await readDemoBody(`${FLOW}/schemas/${schemas.find(s => s.name === 'public')!.id}/erd`) as DataFlowErd;
    expect(erd.externalTables).toEqual([{ key: 'ext:audit.service_accounts', schemaName: 'audit', tableName: 'service_accounts' }]);
    expect(erd.edges.filter(edge => edge.external).map(edge => edge.targetKey)).toEqual(Array(5).fill('ext:audit.service_accounts'));
    const linked = new Set(erd.edges.flatMap(edge => [edge.sourceTableId, edge.targetKey]));
    expect(erd.nodes.filter(node => !linked.has(node.id)).map(node => node.name)).toEqual(['email_templates', 'job_queue', 'territories']);
    // deals.parent_deal_id: the self-reference.
    expect(erd.edges.filter(edge => edge.sourceTableId === edge.targetKey)).toHaveLength(1);
  });

  it('lists the relations of contacts: its two keys and the eight that point at it', async () => {
    const { schemas } = await crmSchemas();
    const contacts = (await tablesOf(schemas.find(s => s.name === 'public')!.id!)).find(table => table.name === 'contacts')!;
    const relations = await readDemoBody(`${FLOW}/tables/${contacts.id}/relations`) as DataFlowRelations;
    expect(relations.outgoing.map(r => `${r.schemaName}.${r.tableName}`)).toEqual(['public.accounts', 'audit.service_accounts']);
    expect(relations.incoming).toHaveLength(8);
    expect(relations.incoming.every(r => r.columns[0].sourceColumn === 'contact_id' && r.columns[0].targetColumn === 'id')).toBe(true);
  });

  it('serves the SaaS file and Endpoint showcases', async () => {
    const gmail = (await rowOf('saas', 'Priya Nair', 'GMAIL')).sourceId!;
    const drive = (await rowOf('saas', 'Priya Nair', 'GOOGLE_WORKSPACE')).sourceId!;
    const idOf = async (sourceId: string, name: string, element: string) =>
      (await filesOf(sourceId, `dataElement=${encodeURIComponent(element)}&limit=100&skip=0`)).rows.find(f => f.name === name)!.id!;
    const fileAnswer = (id: string, resource: string) => readDemoBody(`/api/dspm/saas-files/${id}/${resource}`);

    const offerPdf = await idOf(gmail, 'Offer Letter - Priya Nair.pdf', 'PERSON NAME');
    const lineage = await fileAnswer(offerPdf, 'lineage') as SaasFileLineage;
    expect(lineage.lanes.map(lane => [lane.channelLabel, lane.self])).toEqual([['Gmail', true], ['Google Drive', false], ['Google Drive', false]]);
    expect((await fileAnswer(offerPdf, 'copies') as SaasFileCopies).copies.map(copy => copy.ownerEmail))
      .toEqual(['priya@xammer.in', 'priya@xammer.in']);
    expect((await fileAnswer(offerPdf, 'activity') as SaasFileActivity).entries).toHaveLength(2);

    const offerDocx = await idOf(drive, 'Offer Letter - Priya Nair.docx', 'ADDRESS');
    expect((await fileAnswer(offerDocx, 'overview') as SaasFileOverview).sharing)
      .toMatchObject({ externalShare: true, externalEmails: ['talent.partner@example.com'] });
    const docxCopies = await fileAnswer(offerDocx, 'copies') as SaasFileCopies;
    expect(docxCopies.locations).toBe(3);
    expect(docxCopies.copies.map(copy => copy.locationLabel)).toEqual(['SaaS · Gmail', 'SaaS · Google Drive', 'Endpoint · macOS']);
    const roster = await fileAnswer(await idOf(drive, 'Team Roster.xlsx', 'PHONE NUMBER'), 'overview') as SaasFileOverview;
    expect(roster.sharing).toEqual({
      internalEmails: [], externalEmails: [], internalGroups: [], externalGroups: [],
      privateShare: true, publicShare: false, orgWide: false, externalShare: false,
    });

    const desktop = (await rowOf('endpoint', 'HR-DESKTOP-02')).sourceId!;
    const scans = await readDemoBody(`${DS}/${desktop}/endpoint/scans?skip=0&limit=10`) as EndpointScanPage;
    expect(scans.rows.map(run => [run.status, run.endTime === null, run.totalFiles])).toEqual([['IN_PROGRESS', true, null], ['TIMED_OUT', false, 1240]]);
  });

  it('keeps no fixture-derived file name in the demo data', () => {
    // jsdom gives import.meta.url no file scheme; vitest runs from the app's root folder.
    const dir = join(process.cwd(), 'src', 'demo');
    const renamed = ['people', 'list'].join('-');
    const files = readdirSync(dir).filter(name => /\.tsx?$/.test(name));
    expect(files.length).toBeGreaterThan(5);
    for (const name of files) expect(readFileSync(join(dir, name), 'utf8').includes(renamed), name).toBe(false);
  });
});
/**
 * AC-UI-06 (AWS): every v2 path the AutoSpotting shell calls has a demo response, so a demo session
 * never reaches the backend and never gets the generic `{}` where a list or document is expected.
 */
describe('demo router — AutoSpotting shell (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
    resetAutoSpottingDemo();
  });

  const call = async (url: string, method = 'GET', body?: unknown) => {
    const response = resolveDemoResponse(url, method, body === undefined ? undefined : JSON.stringify(body));
    expect(response, `expected a demo response for ${method} ${url}`).not.toBeNull();
    return { status: response!.status, body: JSON.parse(await response!.text()) };
  };
  const V2 = '/api/autospotting/v2';
  const A = `${V2}/accounts/9001`;

  it('gives the demo AWS accounts a XamOps account id, which the shell needs', () => {
    expect(demoAccounts.filter(a => a.provider === 'AWS').every(a => typeof a.dbId === 'number')).toBe(true);
  });

  it('answers config with the shell on and conversions paused', async () => {
    const { body } = await call(`${V2}/config`);
    expect(body.shellEnabled).toBe(true);
    expect(body.providers.AWS.conversionsEnabled).toBe(false);
    expect(body.providers.AWS.v2MutationsEnabled).toBe(false);
  });

  it('answers the group list, its potential and one detail', async () => {
    const list = (await call(`${A}/groups`)).body;
    expect(list.items.length).toBeGreaterThan(0);
    expect(list.items.some((r: { savingsUnknown: boolean }) => r.savingsUnknown)).toBe(true);
    const potential = (await call(`${A}/groups/potential`)).body;
    expect(potential.pricedGroups).toBeGreaterThan(0);
    expect(potential.unpricedGroups).toBe(1);
    expect(potential.ineligibleGroups).toBe(1);
    const detail = await call(`${A}/groups/demo-grp-api`);
    expect(detail.status).toBe(200);
    expect(detail.body.group.state).toBe('ACTIVE');
    expect((await call(`${A}/groups/nope`)).status).toBe(404);
  });

  it('plans with all three profiles priced, then a confirm runs a scripted operation to ACTIVE', async () => {
    const created = await call(`${A}/plans`, 'POST', { kind: 'ENABLE', groupRef: 'us-east-1/web-asg' });
    expect(created.status).toBe(201);
    const estimates = JSON.parse(created.body.body).estimates.map((e: { profile: string }) => e.profile);
    expect(estimates).toEqual(['conservative', 'balanced', 'aggressive']);
    expect((await call(`${A}/plans/${created.body.planId}`)).body.planId).toBe(created.body.planId);

    const confirmed = await call(`${A}/plans/${created.body.planId}/confirm`, 'POST', { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false });
    expect(confirmed.status).toBe(202);
    const statuses: string[] = [];
    for (let i = 0; i < 6; i++) statuses.push((await call(`${A}/operations/${confirmed.body.operationId}`)).body.status);
    expect(statuses[0]).toBe('QUEUED');
    expect(statuses).toContain('RUNNING');
    expect(statuses[statuses.length - 1]).toBe('SUCCEEDED');
    const web = (await call(`${A}/groups`)).body.items.find((r: { name: string }) => r.name === 'web-asg');
    expect(web.state).toBe('ACTIVE');
  });

  it('reverts a managed group', async () => {
    const created = await call(`${A}/plans`, 'POST', { kind: 'REVERT', groupId: 'demo-grp-api' });
    const confirmed = await call(`${A}/plans/${created.body.planId}/confirm`, 'POST', { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false });
    for (let i = 0; i < 6; i++) await call(`${A}/operations/${confirmed.body.operationId}`);
    const api = (await call(`${A}/groups`)).body.items.find((r: { name: string }) => r.name === 'api-asg');
    expect(api.state).toBe('REVERTED');
  });

  it('answers analytics and activity, with a WOULD_DO dry run in the activity', async () => {
    const analytics = (await call(`${A}/analytics?from=2026-10-22&to=2026-11-20`)).body;
    expect(analytics.notTracked).toEqual([]);
    expect(analytics.groups.length).toBeGreaterThan(0);
    const audit = (await call(`${A}/audit?from=2026-10-22&to=2026-11-20&limit=100`)).body;
    expect(audit.some((e: { outcome: string }) => e.outcome === 'WOULD_DO')).toBe(true);
  });

  it('answers commitments, and a saved setting reads back', async () => {
    expect((await call(`${A}/commitments/coverage`)).body.ri_coverages.length).toBeGreaterThan(0);
    expect((await call(`${A}/commitments/settings`)).body).toEqual({ respectRiSpCommitments: true, riSpUtilizationThreshold: 75 });
    await call(`${A}/commitments/settings/risp-respect`, 'PUT', { respectRiSpCommitments: false, riSpUtilizationThreshold: 60 });
    expect((await call(`${A}/commitments/settings`)).body).toEqual({ respectRiSpCommitments: false, riSpUtilizationThreshold: 60 });
  });

  it('refuses an unknown v2 path with a 404 problem, not the generic empty object', async () => {
    const r = await call(`${V2}/accounts/9001/not-a-path`);
    expect(r.status).toBe(404);
    expect(r.body.code).toBe('NOT_FOUND');
  });
});

/** AC-UI-06 (GCP): the demo GCP project answers every path the shell calls with GCP data. */
describe('demo router — AutoSpotting shell for GCP (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
    resetAutoSpottingDemo();
  });

  const call = async (url: string, method = 'GET', body?: unknown) => {
    const response = resolveDemoResponse(url, method, body === undefined ? undefined : JSON.stringify(body));
    expect(response, `expected a demo response for ${method} ${url}`).not.toBeNull();
    return { status: response!.status, body: JSON.parse(await response!.text()) };
  };
  const V2 = '/api/autospotting/v2';
  const G = `${V2}/accounts/9101`;

  it('gives the demo GCP project a XamOps account id and lists GCP in the config', async () => {
    expect(demoAccounts.find(a => a.provider === 'GCP')?.dbId).toBe(9101);
    const { body } = await call(`${V2}/config`);
    expect(body.providers.GCP.conversionsEnabled).toBe(false);
    expect(body.providers.GCP.v2MutationsEnabled).toBe(false);
  });

  it('answers the MIG list with GKE excluded from the potential, and one detail', async () => {
    const list = (await call(`${G}/groups`)).body;
    expect(list.items.every((r: { provider: string }) => r.provider === 'GCP')).toBe(true);
    expect(list.items.some((r: { groupKey: string }) => r.groupKey.split('/').length === 3)).toBe(true);
    expect(list.blockedCounts.GCP_GKE_NODE_POOL).toBe(1);
    expect(list.blockedCounts.GCP_NO_READINESS_GATE).toBe(1);
    const potential = (await call(`${G}/groups/potential`)).body;
    expect(potential.ineligibleGroups).toBe(2);
    expect(potential.unpricedGroups).toBe(1);
    const detail = await call(`${G}/groups/demo-grp-gcp-api`);
    expect(detail.status).toBe(200);
    expect(detail.body.group.provider).toBe('GCP');
  });

  it('plans with the two-version floor and confirms through a scripted operation', async () => {
    const created = await call(`${G}/plans`, 'POST', { kind: 'ENABLE', groupRef: 'gcp-analytics-demo/us-central1/web-mig' });
    expect(created.status).toBe(201);
    const body = JSON.parse(created.body.body);
    expect(body.target.floorMechanism).toBe('TWO_VERSION_MIG');
    expect(body.onDemandFloor.floorN).toBe(3);
    expect(body.estimates.map((e: { profile: string }) => e.profile)).toEqual(['conservative', 'balanced', 'aggressive']);
    const confirmed = await call(`${G}/plans/${created.body.planId}/confirm`, 'POST', { planVersion: 1, acknowledgedWarningIds: [], fullSpotOptIn: false });
    for (let i = 0; i < 6; i++) await call(`${G}/operations/${confirmed.body.operationId}`);
    const web = (await call(`${G}/groups`)).body.items.find((r: { name: string }) => r.name === 'web-mig');
    expect(web.state).toBe('ACTIVE');
  });

  it('answers analytics, activity and commitments for the GCP project', async () => {
    const analytics = (await call(`${G}/analytics?from=2026-10-22&to=2026-11-20`)).body;
    expect(analytics.groups[0].byType[0].instanceType).toBe('c2-standard-8');
    const audit = (await call(`${G}/audit?from=2026-10-22&to=2026-11-20&limit=100`)).body;
    expect(audit.every((e: { groupKey: string | null }) => e.groupKey === null || e.groupKey.startsWith('gcp-analytics-demo/'))).toBe(true);
    expect((await call(`${G}/commitments/settings`)).status).toBe(200);
  });
});

describe.each(['ROLE_XAMOPS', 'ROLE_BILLOPS_ADMIN'])('demo router — AutoSpotting operator views (%s)', role => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, role);
    resetAutoSpottingDemo();
  });
  const PREFIX = '/api/xamops/autospotting-admin';

  it('answers switches, stuck operations, a switch change, and pause or resume', async () => {
    const switches = await readDemoBody(`${PREFIX}/switches`);
    expect(Array.isArray(switches)).toBe(true);
    const ops = await readDemoBody(`${PREFIX}/operations`);
    expect(ops.operations[0].deadlineAt).toBeTruthy();
    const changed = JSON.parse(
      await resolveDemoResponse(`${PREFIX}/switches/GLOBAL`, 'PUT', JSON.stringify({ engaged: true, expectedVersion: 0, reason: 'x' }))!.text(),
    );
    expect(changed.engaged).toBe(true);
    expect(changed.version).toBe(1);
    const paused = JSON.parse(await resolveDemoResponse(`${PREFIX}/groups/g-1/pause`, 'POST', JSON.stringify({ reason: 'x' }))!.text());
    expect(paused.state).toBe('PAUSED');
  });
});

/** Build 4 design 7 and AC22: every Data Lineage path is answered in the browser, off and on. */
describe('demo router — XamLens Data Lineage page (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });
  afterEach(() => {
    sessionStorage.removeItem(DEMO_LINEAGE_STEPS_KEY);
  });
  const LN = '/api/dspm/lineage';
  const POLICY = '5e1f0c00-0000-4000-8000-000000000001';
  const answerOf = async (url: string) => {
    const response = resolveDemoResponse(url);
    expect(response, url).not.toBeNull();
    return { status: response!.status, body: await response!.json() };
  };
  const stepsOn = () => sessionStorage.setItem(DEMO_LINEAGE_STEPS_KEY, 'on');
  const policies = (query: string) => readDemoBody(`${LN}/policies?category=SENSITIVE_FINDINGS&${query}`) as Promise<LineagePolicyPage>;
  const datastores = (query = '') => readDemoBody(`${LN}/policies/${POLICY}/datastores?${query}`) as Promise<LineageDatastorePage>;
  const filesOf = (id: string, query = '') => readDemoBody(`${LN}/datastores/${id}/files?${query}`) as Promise<LineageFilePage>;

  it('answers as the switch-off backend by default', async () => {
    expect(DEMO_LINEAGE_STEPS_DEFAULT).toBe(false);
    const categories = await readDemoBody(`${LN}/categories`) as LineageCategories;
    expect(categories.available).toBe(false);
    expect(categories.categories.map(c => c.name)).toEqual([
      'Sensitive Findings', 'Excessive Exposure', 'High Volume Access', 'Stale Data', 'Risky User', 'Public Exposure',
    ]);
    expect(categories.categories.every(c => c.exposures === null)).toBe(true);
    expect(await readDemoBody(`${LN}/filter-options?level=policy`)).toEqual({ dimensions: [] });
    const off = { available: false, rows: [], total: 0, skip: 0, limit: 7 };
    expect(await policies('skip=0&limit=7')).toEqual(off);
    expect(await datastores('skip=0&limit=7')).toEqual(off);
    expect(await filesOf('exp-01-studiomac', 'skip=0&limit=7')).toEqual(off);
    expect(await readDemoBody(`${LN}/files/f11e0000-0000-4000-8000-000000000001/lineage`))
      .toEqual({ available: false, coverageNote: null, lanes: [], events: [] });
  });

  it('answers every pattern off and on with its DTO, never the {} catch-all', async () => {
    for (const steps of ['off', 'on']) {
      sessionStorage.setItem(DEMO_LINEAGE_STEPS_KEY, steps);
      for (const url of [
        `${LN}/categories`, `${LN}/filter-options?level=policy`, `${LN}/filter-options?level=datastore`,
        `${LN}/policies?category=SENSITIVE_FINDINGS&skip=0&limit=7`, `${LN}/policies/${POLICY}/datastores?skip=0&limit=7`,
        `${LN}/datastores/exp-01-studiomac/files?skip=0&limit=7`, `${LN}/files/f11e0000-0000-4000-8000-000000000001/lineage`,
      ]) {
        const { status, body } = await answerOf(url);
        expect(status, `${steps} ${url}`).toBe(200);
        expect(body, `${steps} ${url}`).not.toEqual({});
      }
    }
  });

  it('switched on: counts agree with the lists, 9 policies page as 7 + 2, one datastore has 9 files', async () => {
    stepsOn();
    const categories = await readDemoBody(`${LN}/categories`) as LineageCategories;
    expect(categories.available).toBe(true);
    expect(categories.categories.map(c => c.exposures)).toEqual([9, 1, 0, 2, 0, 0]);
    for (const category of categories.categories) {
      const rows = await readDemoBody(`${LN}/policies?category=${category.id}&limit=50`) as LineagePolicyPage;
      expect(rows.total, category.id).toBe(category.exposures);
      expect(rows.rows).toHaveLength(category.exposures!);
    }
    const first = await policies('skip=0&limit=7');
    expect(first).toMatchObject({ available: true, total: 9, skip: 0, limit: 7 });
    expect(first.rows).toHaveLength(7);
    expect((await policies('skip=7&limit=7')).rows).toHaveLength(2);
    for (const policy of first.rows) {
      const page = await readDemoBody(`${LN}/policies/${policy.id}/datastores?limit=50`) as LineageDatastorePage;
      expect(page.total, policy.name!).toBe(policy.datastores);
      for (const datastore of page.rows) {
        const files = await filesOf(datastore.id!, 'limit=100');
        expect(files.total, datastore.name!).toBe(datastore.files);
        expect(files.rows).toHaveLength(datastore.files!);
      }
    }
    const studio = (await datastores()).rows.find(row => row.files === 9)!;
    expect(studio).toBeDefined();
    const pageOne = await filesOf(studio.id!, 'skip=0&limit=7');
    expect(pageOne).toMatchObject({ total: 9, skip: 0, limit: 7 });
    expect(pageOne.rows).toHaveLength(7);
    expect((await filesOf(studio.id!, 'skip=7&limit=7')).rows).toHaveLength(2);
  });

  it('switched on: filter options, search, Include and Exclude as the backend applies them', async () => {
    stepsOn();
    const options = await readDemoBody(`${LN}/filter-options?level=policy`) as DatastoreFilterOptions;
    expect(options.dimensions.map(d => d.key)).toEqual(['severity', 'datastoreName', 'label']);
    expect(options.dimensions[0].options.map(o => o.label)).toEqual(['Critical', 'High', 'Medium']);
    const names = options.dimensions[1].options.map(o => o.label);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    expect(options.dimensions[2].options.map(o => o.value)).toEqual(['PII', 'PCI', 'PHI', 'SECRETS']);
    const datastoreOptions = await readDemoBody(`${LN}/filter-options?level=datastore`) as DatastoreFilterOptions;
    expect(datastoreOptions.dimensions.map(d => d.key)).toEqual(['severity']);

    const all = (await policies('limit=50')).rows;
    const critical = await policies('limit=50&severity=CRITICAL');
    expect(critical.rows.length).toBeGreaterThan(0);
    expect(critical.rows.every(row => row.severity === 'CRITICAL')).toBe(true);
    const notCritical = await policies('limit=50&severity_exclude=CRITICAL');
    expect(notCritical.total + critical.total).toBe(all.length);
    expect(notCritical.rows.some(row => row.severity === 'CRITICAL')).toBe(false);
    expect(await policies('limit=50&severity_exclude=CRITICAL,HIGH,MEDIUM'))
      .toEqual({ available: true, rows: [], total: 0, skip: 0, limit: 50 });
    const secrets = await policies('limit=50&label=SECRETS');
    expect(secrets.total).toBeGreaterThan(0);
    expect(secrets.total).toBeLessThan(all.length);
    const legal = await policies('limit=50&datastoreName=Legal%20SharePoint');
    expect(legal.rows.every(row => row.service === 'SHARE_POINT')).toBe(true);
    const searched = await policies('limit=50&q=ONEDRIVE');
    expect(searched.total).toBeGreaterThan(0);
    expect(searched.rows.every(row => row.name!.toLowerCase().includes('onedrive'))).toBe(true);

    const policyDatastores = await datastores();
    const high = await datastores('severity=HIGH');
    const notHigh = await datastores('severity_exclude=HIGH');
    expect(high.total + notHigh.total).toBe(policyDatastores.total);
    expect((await datastores('q=ops')).rows.map(row => row.name)).toEqual(['Ops MacBook Air']);
  });

  it('switched on: lineage for most files, available:false for one', async () => {
    stepsOn();
    const files = (await Promise.all((await datastores()).rows.map(row => filesOf(row.id!, 'limit=100')))).flatMap(p => p.rows);
    const answers = await Promise.all(files.map(file => readDemoBody(`${LN}/files/${file.id}/lineage`) as Promise<SaasFileLineage>));
    expect(answers.filter(a => !a.available)).toHaveLength(1);
    const lineage = answers.find(a => a.available)!;
    expect(lineage.lanes.filter(lane => lane.self)).toHaveLength(1);
    expect(lineage.events.map(e => e.typeLabel)).toEqual(['File Created', 'File Modified', 'File Copied']);
  });

  it('refuses a value that is no option, a bad category and a bad level with 400 INVALID_REQUEST', async () => {
    stepsOn();
    for (const [url, message] of [
      [`${LN}/policies?category=SENSITIVE_FINDINGS&severity=LOW`, 'severity is not an option of this project'],
      [`${LN}/policies?category=SENSITIVE_FINDINGS&label_exclude=OTHER`, 'label_exclude is not an option of this project'],
      [`${LN}/policies/${POLICY}/datastores?severity_exclude=LOW`, 'severity_exclude is not an option of this project'],
      [`${LN}/policies?category=NOT_A_CATEGORY`, 'category must be one of the exposure categories'],
      [`${LN}/filter-options?level=file`, 'level must be policy or datastore'],
    ]) {
      const { status, body } = await answerOf(url);
      expect(status, url).toBe(400);
      expect(body, url).toEqual({ code: 'INVALID_REQUEST', message });
    }
  });

  it('answers unknown ids and other lineage paths with 404 ASSET_NOT_FOUND', async () => {
    stepsOn();
    for (const url of [
      `${LN}/policies/00000000-0000-4000-8000-000000000000/datastores`,
      `${LN}/datastores/exp-unknown/files`,
      `${LN}/files/ffffffffffffffffffffffff/lineage`,
      `${LN}/policies/not-a-uuid/datastores`,
      `${LN}/summary`,
      `${LN}/`,
    ]) {
      const { status, body } = await answerOf(url);
      expect(status, url).toBe(404);
      expect(body.code, url).toBe('ASSET_NOT_FOUND');
    }
    sessionStorage.setItem(DEMO_LINEAGE_STEPS_KEY, 'off');
    expect((await answerOf(`${LN}/summary`)).status).toBe(404);
  });
});
