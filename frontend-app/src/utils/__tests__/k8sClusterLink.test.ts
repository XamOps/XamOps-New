import { describe, expect, it } from 'vitest';
import { DEFAULT_REGION, FLEET_VIEW_ALL, FLEET_VIEW_PARAM, clusterSwitchHref, fleetHref, isSwitchableCluster, parseClusterLink, serializeClusterLink } from '../k8sClusterLink';
import { parseClusterLink as fromPage } from '../../pages/EksDetailsPage';

const parse = (search: string, demo = false) => parseClusterLink(new URLSearchParams(search), demo);

describe('parseClusterLink (§13)', () => {
  it('reads the cluster, account, region and version', () => {
    expect(parse('name=prod-eks&accountIds=111111111111&region=eu-west-1&version=1.36')).toEqual({
      clusterName: 'prod-eks', accountId: '111111111111', region: 'eu-west-1', version: '1.36',
    });
  });

  it('defaults a missing region and never guesses a version', () => {
    expect(parse('name=prod-eks&accountIds=111111111111')).toEqual({
      clusterName: 'prod-eks', accountId: '111111111111', region: DEFAULT_REGION, version: null,
    });
  });

  it.each([
    ['', false],
    ['name=prod-eks', false],
    ['accountIds=111111111111', false],
    ['name=-x&accountIds=111111111111', false],
    ['name=prod-eks&accountIds=1234567890123', false],
    ['name=prod-eks&accountIds=demo-aws-001', false],
    ['name=prod-eks&accountIds=111111111111&region=useast1', false],
  ])('rejects %j', (search, demo) => {
    expect(parse(search, demo)).toBeNull();
  });

  it('accepts a demo account only in a demo session', () => {
    expect(parse('name=prod-eks-cluster&accountIds=demo-aws-001', true)).toMatchObject({ accountId: 'demo-aws-001' });
  });

  it('is the parser the page uses', () => {
    expect(fromPage).toBe(parseClusterLink);
  });
});

describe('serializeClusterLink / isSwitchableCluster / clusterSwitchHref', () => {
  const CURRENT = '?name=a&region=us-east-1&accountIds=111&version=1.29&tab=automation&sub=activity&workload=web&hpa=x&phase=live&debug=true';

  it('serializes name, region, accountIds, then version', () => {
    expect(serializeClusterLink({ clusterName: 'a', region: 'eu-west-1', accountId: '111', version: '1.30' }))
      .toBe('/eks-details?name=a&region=eu-west-1&accountIds=111&version=1.30');
    expect(serializeClusterLink({ clusterName: 'a', region: 'eu-west-1', accountId: '111', version: null }))
      .toBe('/eks-details?name=a&region=eu-west-1&accountIds=111');
  });

  it('keeps tab, sub and debug, drops page params, and takes the target version', () => {
    expect(clusterSwitchHref(CURRENT, { name: 'b', region: 'eu-west-1', version: '1.30' }, '111'))
      .toBe('/eks-details?name=b&region=eu-west-1&accountIds=111&version=1.30&tab=automation&sub=activity&debug=true');
  });

  it('does not leak the old version onto a target that has none', () => {
    expect(clusterSwitchHref(CURRENT, { name: 'c', region: 'us-east-1' }, '111'))
      .toBe('/eks-details?name=c&region=us-east-1&accountIds=111&tab=automation&sub=activity&debug=true');
  });

  it('does not resolve tab/sub defaults', () => {
    expect(clusterSwitchHref('?name=a&accountIds=111', { name: 'b', region: 'us-east-1' }, '111'))
      .toBe('/eks-details?name=b&region=us-east-1&accountIds=111');
  });

  it('accepts only clusters the page itself would accept', () => {
    expect(isSwitchableCluster({ name: 'prod-eks', region: 'us-east-1' })).toBe(true);
    expect(isSwitchableCluster({ name: '-bad', region: 'us-east-1' })).toBe(false);
    expect(isSwitchableCluster({ name: 'ok', region: 'useast1' })).toBe(false);
    expect(isSwitchableCluster({ name: 5, region: 'us-east-1' })).toBe(false);
  });
});

describe('fleetHref', () => {
  it('is exactly /cloudk8s?view=all', () => {
    expect(fleetHref()).toBe('/cloudk8s?view=all');
    expect(fleetHref()).toBe(`/cloudk8s?${FLEET_VIEW_PARAM}=${FLEET_VIEW_ALL}`);
  });
});
