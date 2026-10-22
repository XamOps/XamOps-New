import { describe, it, expect, beforeEach } from 'vitest';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';
import { demoAccounts } from './xamopsMockData';

// Guards the DevOps-in-the-Box catalogue's provider scoping: each cloud's shelf shows its own
// tooling (and the multi-cloud script), never another cloud's. The `matchesProvider` helper here
// is a copy of the predicate in useDevOpsScripts, so the fixture is checked against the exact
// filter the page applies. Regression cover for the all-AWS catalogue that showed EC2/S3/RDS
// scripts under GCP and Azure accounts.
const matchesProvider = (script: any, provider?: string): boolean => {
  if (!provider) return true;
  const providers = script.cloudProviders ?? [];
  if (providers.length === 0) return true;
  if (providers.some((p: string) => p?.toLowerCase() === 'multi-cloud')) return true;
  return providers.some((p: string) => p?.toLowerCase() === provider.toLowerCase());
};

const read = async (url: string) => {
  const r = resolveDemoResponse(url);
  expect(r, `expected a demo response for ${url}`).not.toBeNull();
  return JSON.parse(await r!.text());
};

describe('DevOps-in-the-Box provider scoping', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  it('every script declares a provider', async () => {
    const scripts = await read('/api/dev_ops_script');
    expect(scripts.length).toBeGreaterThan(0);
    for (const s of scripts) {
      expect(Array.isArray(s.cloudProviders), `${s.name} has no cloudProviders`).toBe(true);
      expect(s.cloudProviders.length).toBeGreaterThan(0);
      for (const p of s.cloudProviders) {
        expect(['aws', 'azure', 'gcp', 'multi-cloud']).toContain(p);
      }
    }
  });

  it('each provider gets its own scripts, none from another cloud', async () => {
    const scripts = await read('/api/dev_ops_script');

    for (const provider of ['AWS', 'GCP', 'Azure']) {
      const shelf = scripts.filter((s: any) => matchesProvider(s, provider));
      expect(shelf.length, `${provider} shelf is empty`).toBeGreaterThan(0);

      for (const s of shelf) {
        const ok = s.cloudProviders.includes('multi-cloud')
          || s.cloudProviders.includes(provider.toLowerCase());
        expect(ok, `${s.name} leaked onto the ${provider} shelf`).toBe(true);
      }
    }
  });

  it('GCP shows GCP tooling and never the AWS scripts from the screenshot', async () => {
    const scripts = await read('/api/dev_ops_script');
    const names = scripts.filter((s: any) => matchesProvider(s, 'GCP')).map((s: any) => s.name);

    expect(names).toContain('GCE Idle VM Shutdown');
    expect(names).toContain('Cloud Storage Lifecycle');
    expect(names).toContain('Cloud SQL Backup Validator');
    expect(names).toContain('IAM Role Auditor');

    expect(names).not.toContain('EC2 Auto Shutdown');
    expect(names).not.toContain('S3 Lifecycle Policy');
    expect(names).not.toContain('RDS Backup Validator');
    expect(names).not.toContain('IAM Policy Auditor');
  });

  it('Azure shows Azure tooling and no AWS or GCP scripts', async () => {
    const scripts = await read('/api/dev_ops_script');
    const names = scripts.filter((s: any) => matchesProvider(s, 'Azure')).map((s: any) => s.name);

    expect(names).toContain('VM Auto Deallocate');
    expect(names).toContain('Blob Lifecycle Policy');
    expect(names).toContain('SQL Backup Validator');
    expect(names).toContain('RBAC Role Auditor');

    expect(names).not.toContain('EC2 Auto Shutdown');
    expect(names).not.toContain('GCE Idle VM Shutdown');
  });

  it('the multi-cloud script appears on all three shelves', async () => {
    const scripts = await read('/api/dev_ops_script');
    for (const provider of ['AWS', 'GCP', 'Azure']) {
      const names = scripts.filter((s: any) => matchesProvider(s, provider)).map((s: any) => s.name);
      expect(names, provider).toContain('K8s Resource Limits');
    }
  });

  it('each shelf still covers every category pill the page renders', async () => {
    const scripts = await read('/api/dev_ops_script');
    for (const provider of ['AWS', 'GCP', 'Azure']) {
      const cats = new Set(
        scripts.filter((s: any) => matchesProvider(s, provider)).map((s: any) => s.category),
      );
      for (const pill of ['terraform', 'bash', 'python', 'ansible']) {
        expect(cats, `${provider} has no ${pill} script`).toContain(pill);
      }
    }
  });

  it('shows the whole catalogue when no account is selected', async () => {
    const scripts = await read('/api/dev_ops_script');
    expect(scripts.filter((s: any) => matchesProvider(s, undefined))).toHaveLength(scripts.length);
  });

  it('covers every provider present in the demo account list', async () => {
    const scripts = await read('/api/dev_ops_script');
    for (const acc of demoAccounts) {
      const shelf = scripts.filter((s: any) => matchesProvider(s, acc.provider));
      expect(shelf.length, `${acc.name} (${acc.provider}) has no scripts`).toBeGreaterThan(0);
    }
  });
});
