import { describe, expect, it } from 'vitest';
import {
  ENVIRONMENT_NAMES, TOP_RISKY_TABS, dashboardBarLink, datastoresSlug, formatCompactCount,
} from '../dashboardModel';

describe('dashboardBarLink', () => {
  it('opens the environment tab filtered by the bar', () => {
    expect(dashboardBarLink('cloud', 'provider', 'S3')).toBe('/xamlens/datastores/cloud?provider=S3');
    expect(dashboardBarLink('endpoint', 'os', 'MAC')).toBe('/xamlens/datastores/endpoint?os=MAC');
  });

  it('uses the on-prem slug for on_prem', () => {
    expect(dashboardBarLink('on_prem', 'provider', 'SELF_HOSTED_POSTGRES'))
      .toBe('/xamlens/datastores/on-prem?provider=SELF_HOSTED_POSTGRES');
  });

  it('encodes the key', () => {
    expect(dashboardBarLink('cloud', 'provider', 'A&B C')).toBe('/xamlens/datastores/cloud?provider=A%26B%20C');
  });
});

describe('datastoresSlug', () => {
  it.each([
    ['all', 'all'], ['cloud', 'cloud'], ['saas', 'saas'], ['endpoint', 'endpoint'], ['on_prem', 'on-prem'],
  ] as const)('maps %s to %s', (tab, slug) => {
    expect(datastoresSlug(tab)).toBe(slug);
  });
});

describe('formatCompactCount', () => {
  it.each([
    [0, '0'], [512, '512'], [999, '999'], [4_210, '4.2K'], [37_480, '37.5K'], [50_000, '50K'], [1_234_567, '1.2M'],
  ])('prints %d as %s', (n, text) => {
    expect(formatCompactCount(n)).toBe(text);
  });
});

describe('names', () => {
  it('titles the cards as the console does', () => {
    expect(Object.values(ENVIRONMENT_NAMES)).toEqual(['Cloud', 'SaaS', 'Endpoint', 'On Prem']);
  });

  it('lists the Top Risky tabs in the console order', () => {
    expect(TOP_RISKY_TABS.map(t => t.label)).toEqual(['All', 'Cloud', 'SaaS', 'Endpoint', 'On-Prem']);
  });
});
