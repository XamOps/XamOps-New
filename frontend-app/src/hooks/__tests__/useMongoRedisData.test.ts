import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMongoData } from '../useMongoData';
import { useRedisData } from '../useRedisData';
import apiClient from '../../utils/apiClient';

vi.mock('../../utils/apiClient', () => ({
  default: { get: vi.fn(async () => []) },
}));

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

/**
 * PERF-19: the drawer supplies a Mongo/Redis host only when the engine matches and its mirror flag
 * is on. With no host the hooks must not fetch — a Postgres/MySQL drawer no longer fires the six
 * Mongo + Redis requests whose panels are hidden anyway.
 */
describe('PERF-19 Mongo/Redis hooks skip fetching without a host', () => {
  beforeEach(() => get.mockClear());

  it('useMongoData does not fetch when host is undefined', async () => {
    renderHook(() => useMongoData('acct', undefined));
    await Promise.resolve();
    expect(get).not.toHaveBeenCalled();
  });

  it('useRedisData does not fetch when host is undefined', async () => {
    renderHook(() => useRedisData('acct', undefined));
    await Promise.resolve();
    expect(get).not.toHaveBeenCalled();
  });

  it('useMongoData fetches its endpoints when a host is provided', async () => {
    renderHook(() => useMongoData('acct', 'mongo-host'));
    await waitFor(() => expect(get).toHaveBeenCalled());
    const urls = get.mock.calls.map((c) => c[0] as string);
    expect(urls.some((u) => u.includes('/agent-mongo-metrics'))).toBe(true);
  });

  it('useRedisData fetches its endpoints when a host is provided', async () => {
    renderHook(() => useRedisData('acct', 'redis-host'));
    await waitFor(() => expect(get).toHaveBeenCalled());
    const urls = get.mock.calls.map((c) => c[0] as string);
    expect(urls.some((u) => u.includes('/agent-redis-metrics'))).toBe(true);
  });
});
