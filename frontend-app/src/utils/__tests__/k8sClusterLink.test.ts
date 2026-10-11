import { describe, expect, it } from 'vitest';
import { DEFAULT_REGION, parseClusterLink } from '../k8sClusterLink';
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
