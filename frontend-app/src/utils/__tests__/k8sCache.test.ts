import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const auth = vi.hoisted(() => ({ email: 'jane@corp.example' as string | null }));
const accounts = vi.hoisted(() => ({ ids: ['111111111111'] as string[] }));
vi.mock('../../contexts/AuthContext', () => ({ useAuth: () => ({ userProfile: auth.email ? { email: auth.email } : null }) }));
vi.mock('../../contexts/AccountContext', () => ({ useAccounts: () => ({ selectedAccountIds: accounts.ids }) }));

import {
  DEFAULT_K8S_CACHE_TTL_MS,
  IMPERSONATE_STORAGE_KEY,
  clearK8sCaches,
  createK8sCache,
  k8sCacheKey,
  resetK8sCacheIdentityForTests,
  syncK8sCacheIdentity,
  userKey,
} from '../k8sCache';
import { useK8sCacheIdentity } from '../../hooks/useK8sCacheIdentity';

const scope = { accountId: '111111111111', region: 'us-east-1', clusterName: 'prod-eks' };

describe('k8sCache keys', () => {
  beforeEach(() => {
    sessionStorage.clear();
    resetK8sCacheIdentityForTests();
    syncK8sCacheIdentity({ email: 'Jane@Corp.example', accountKey: '111111111111' });
  });

  it('keys by user, account, region and cluster', () => {
    expect(k8sCacheKey(scope)).toBe('jane@corp.example|111111111111|us-east-1|prod-eks');
    expect(k8sCacheKey({ accountId: '111111111111' })).toBe('jane@corp.example|111111111111||');
  });

  it('adds the impersonated user id to the user key', () => {
    sessionStorage.setItem(IMPERSONATE_STORAGE_KEY, '77');
    expect(userKey()).toBe('jane@corp.example#as:77');
    expect(k8sCacheKey(scope)).toBe('jane@corp.example#as:77|111111111111|us-east-1|prod-eks');
    sessionStorage.setItem(IMPERSONATE_STORAGE_KEY, 'null');
    expect(userKey()).toBe('jane@corp.example');
  });

  it('separates entries by every part of the key', () => {
    const cache = createK8sCache<string>('test-parts');
    cache.set(scope, 'prod');
    expect(cache.get(scope)).toBe('prod');
    expect(cache.get({ ...scope, clusterName: 'eu-eks' })).toBeUndefined();
    expect(cache.get({ ...scope, region: 'eu-west-1' })).toBeUndefined();
    expect(cache.get({ ...scope, accountId: '222222222222' })).toBeUndefined();
  });
});

describe('k8sCache entries', () => {
  beforeEach(() => {
    sessionStorage.clear();
    resetK8sCacheIdentityForTests();
    syncK8sCacheIdentity({ email: 'jane@corp.example', accountKey: '111111111111' });
  });
  afterEach(() => vi.useRealTimers());

  it('expires after the TTL, 60 s by default', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-10T09:00:00Z'));
    const cache = createK8sCache<number>('test-ttl');
    expect(DEFAULT_K8S_CACHE_TTL_MS).toBe(60_000);
    cache.set(scope, 1);
    vi.advanceTimersByTime(59_999);
    expect(cache.get(scope)).toBe(1);
    vi.advanceTimersByTime(1);
    expect(cache.get(scope)).toBeUndefined();

    cache.set(scope, 2, 5_000);
    vi.advanceTimersByTime(5_000);
    expect(cache.get(scope)).toBeUndefined();
  });

  it('never serves an entry cached under one impersonation id under another', () => {
    const cache = createK8sCache<string>('test-impersonation');
    sessionStorage.setItem(IMPERSONATE_STORAGE_KEY, '77');
    cache.set(scope, 'as user 77');
    sessionStorage.setItem(IMPERSONATE_STORAGE_KEY, '78');
    expect(cache.get(scope)).toBeUndefined();
    sessionStorage.removeItem(IMPERSONATE_STORAGE_KEY);
    expect(cache.get(scope)).toBeUndefined();
    sessionStorage.setItem(IMPERSONATE_STORAGE_KEY, '77');
    expect(cache.get(scope)).toBe('as user 77');
  });

  it('clearK8sCaches empties every cache', () => {
    const a = createK8sCache<number>('test-a');
    const b = createK8sCache<number>('test-b');
    a.set(scope, 1);
    b.set(scope, 2);
    clearK8sCaches();
    expect(a.size()).toBe(0);
    expect(b.size()).toBe(0);
  });
});

describe('syncK8sCacheIdentity', () => {
  beforeEach(() => {
    sessionStorage.clear();
    resetK8sCacheIdentityForTests();
  });

  it('only records on the first call', () => {
    const cache = createK8sCache<number>('test-first');
    cache.set(scope, 1);
    expect(syncK8sCacheIdentity({ email: 'jane@corp.example', accountKey: '1' })).toBe(false);
    expect(cache.size()).toBe(1);
  });

  it.each([
    ['the account', { email: 'jane@corp.example', accountKey: '2', impersonateUserId: null }],
    ['the profile email', { email: 'john@corp.example', accountKey: '1', impersonateUserId: null }],
    ['the impersonation id', { email: 'jane@corp.example', accountKey: '1', impersonateUserId: '77' }],
  ])('clears every cache when %s changes', (_what, next) => {
    syncK8sCacheIdentity({ email: 'jane@corp.example', accountKey: '1', impersonateUserId: null });
    const cache = createK8sCache<number>('test-change');
    cache.set({ accountId: '1' }, 1);
    expect(syncK8sCacheIdentity(next)).toBe(true);
    expect(cache.size()).toBe(0);
  });

  it('keeps the caches when nothing changed', () => {
    syncK8sCacheIdentity({ email: 'jane@corp.example', accountKey: '1', impersonateUserId: null });
    const cache = createK8sCache<number>('test-same');
    cache.set({ accountId: '1' }, 1);
    expect(syncK8sCacheIdentity({ email: 'JANE@corp.example ', accountKey: '1', impersonateUserId: null })).toBe(false);
    expect(cache.size()).toBe(1);
  });
});

describe('useK8sCacheIdentity', () => {
  beforeEach(() => {
    sessionStorage.clear();
    resetK8sCacheIdentityForTests();
    auth.email = 'jane@corp.example';
    accounts.ids = ['111111111111'];
  });

  it('clears the caches when the account, the email or the impersonation id changes', () => {
    const cache = createK8sCache<number>('test-hook');
    const { rerender } = renderHook(() => useK8sCacheIdentity());

    cache.set(scope, 1);
    rerender();
    expect(cache.size()).toBe(1);

    accounts.ids = ['222222222222'];
    rerender();
    expect(cache.size()).toBe(0);

    cache.set({ ...scope, accountId: '222222222222' }, 2);
    auth.email = 'john@corp.example';
    rerender();
    expect(cache.size()).toBe(0);

    cache.set({ ...scope, accountId: '222222222222' }, 3);
    sessionStorage.setItem(IMPERSONATE_STORAGE_KEY, '77');
    rerender();
    expect(cache.size()).toBe(0);
  });
});
