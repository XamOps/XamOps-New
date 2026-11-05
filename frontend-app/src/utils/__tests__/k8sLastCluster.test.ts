import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetLastCluster, readLastCluster, writeLastCluster } from '../k8sLastCluster';

const PREFIX = 'xamops.cloudk8s.lastCluster.v1';

describe('k8sLastCluster', () => {
  beforeEach(() => {
    localStorage.clear();
    try {
      sessionStorage.clear();
    } catch {
      /* ignore */
    }
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('round-trips a cluster for an identity on an account', () => {
    writeLastCluster('jane@corp.example', '111', { name: 'prod-eks', region: 'us-east-1' });
    expect(readLastCluster('jane@corp.example', '111')).toEqual({ name: 'prod-eks', region: 'us-east-1' });
  });

  it('keys by lowercased email, impersonation id and account', () => {
    writeLastCluster('Jane@Corp.Example', '111', { name: 'prod-eks', region: 'us-east-1' });
    expect(localStorage.getItem(`${PREFIX}:jane@corp.example:111`)).toBe(
      JSON.stringify({ name: 'prod-eks', region: 'us-east-1' }),
    );

    sessionStorage.setItem('impersonateUserId', '77');
    writeLastCluster('jane@corp.example', '111', { name: 'as-eks', region: 'eu-west-1' });
    expect(localStorage.getItem(`${PREFIX}:jane@corp.example#as:77:111`)).toBe(
      JSON.stringify({ name: 'as-eks', region: 'eu-west-1' }),
    );
  });

  it('isolates two users and two accounts', () => {
    writeLastCluster('a@x.com', '111', { name: 'ca', region: 'us-east-1' });
    writeLastCluster('b@x.com', '111', { name: 'cb', region: 'us-east-1' });
    writeLastCluster('a@x.com', '222', { name: 'ca2', region: 'eu-west-1' });

    expect(readLastCluster('a@x.com', '111')).toEqual({ name: 'ca', region: 'us-east-1' });
    expect(readLastCluster('b@x.com', '111')).toEqual({ name: 'cb', region: 'us-east-1' });
    expect(readLastCluster('a@x.com', '222')).toEqual({ name: 'ca2', region: 'eu-west-1' });
    expect(readLastCluster('b@x.com', '222')).toBeNull();
  });

  it('returns null and removes the key for malformed JSON', () => {
    const key = `${PREFIX}:jane@corp.example:111`;
    localStorage.setItem(key, '{not json');
    expect(readLastCluster('jane@corp.example', '111')).toBeNull();
    expect(localStorage.getItem(key)).toBeNull();
  });

  it('returns null and removes the key for wrong types', () => {
    const key = `${PREFIX}:jane@corp.example:111`;
    localStorage.setItem(key, JSON.stringify({ name: 5, region: 'us-east-1' }));
    expect(readLastCluster('jane@corp.example', '111')).toBeNull();
    expect(localStorage.getItem(key)).toBeNull();
  });

  it('returns null and removes the key for an invalid name/region', () => {
    const key = `${PREFIX}:jane@corp.example:111`;
    localStorage.setItem(key, JSON.stringify({ name: '-bad', region: 'nowhere' }));
    expect(readLastCluster('jane@corp.example', '111')).toBeNull();
    expect(localStorage.getItem(key)).toBeNull();
  });

  it('returns null and removes the key for a value over 512 chars', () => {
    const key = `${PREFIX}:jane@corp.example:111`;
    localStorage.setItem(key, JSON.stringify({ name: 'prod', region: 'us-east-1', junk: 'x'.repeat(600) }));
    expect(readLastCluster('jane@corp.example', '111')).toBeNull();
    expect(localStorage.getItem(key)).toBeNull();
  });

  it('returns null on a throwing getItem', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readLastCluster('jane@corp.example', '111')).toBeNull();
  });

  it('is a no-op on a throwing setItem', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    expect(() => writeLastCluster('jane@corp.example', '111', { name: 'prod', region: 'us-east-1' })).not.toThrow();
  });

  it('forgets a remembered cluster', () => {
    writeLastCluster('jane@corp.example', '111', { name: 'prod-eks', region: 'us-east-1' });
    forgetLastCluster('jane@corp.example', '111');
    expect(readLastCluster('jane@corp.example', '111')).toBeNull();
  });
});
