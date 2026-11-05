import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}));

vi.mock('../utils/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/apiClient')>();
  return { ...actual, default: { ...actual.default, get: apiGet, post: apiPost, delete: apiDelete } };
});

import { useK8sOnboarding } from './useK8sOnboarding';

const ACCOUNT_ID = '12';
const BASE = '/api/xamops/k8s/onboarding';

function setup(accountId = ACCOUNT_ID) {
  const { result } = renderHook(() => useK8sOnboarding({ accountId }));
  return result;
}

describe('useK8sOnboarding', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('getStatus GETs the cluster status with the accountId', async () => {
    apiGet.mockResolvedValue({ clusterName: 'prod-eks', registered: true });
    const result = setup();

    await result.current.getStatus('prod-eks');

    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(apiGet).toHaveBeenCalledWith(`${BASE}/clusters/prod-eks?accountId=12`);
  });

  it('runPreflight POSTs the region and phase', async () => {
    apiPost.mockResolvedValue({ ranAt: '2026-11-04T00:00:00Z', checks: [], blocking: false });
    const result = setup();

    await result.current.runPreflight('prod-eks', 'us-east-1', 'READ_ONLY');

    expect(apiPost).toHaveBeenCalledWith(
      `${BASE}/clusters/prod-eks/preflight?accountId=12`,
      { region: 'us-east-1', phase: 'READ_ONLY' }
    );
  });

  it('getPermissions GETs the manifest with every query param', async () => {
    apiGet.mockResolvedValue({ hash: 'abc' });
    const result = setup();

    await result.current.getPermissions({
      clusterName: 'prod-eks',
      region: 'us-east-1',
      phase: 'AUTOMATION',
      createReadRole: true,
      features: ['cost', 'node-removal'],
    });

    expect(apiGet).toHaveBeenCalledWith(
      `${BASE}/permissions?accountId=12&clusterName=prod-eks&region=us-east-1&phase=AUTOMATION&createReadRole=true&features=cost%2Cnode-removal`
    );
  });

  it('getPermissions omits the features param when none are given', async () => {
    apiGet.mockResolvedValue({ hash: 'abc' });
    const result = setup();

    await result.current.getPermissions({
      clusterName: 'prod-eks',
      region: 'us-east-1',
      phase: 'READ_ONLY',
      createReadRole: false,
    });

    expect(apiGet).toHaveBeenCalledWith(
      `${BASE}/permissions?accountId=12&clusterName=prod-eks&region=us-east-1&phase=READ_ONLY&createReadRole=false`
    );
  });

  it('issueConnectToken POSTs { region, phase, createReadRole } and passes the command through unchanged', async () => {
    const command = 'xamops-agent install --connect-token tok_123 --create-iam';
    apiPost.mockResolvedValue({
      token: 'tok_123',
      tokenPrefix: 'tok',
      expiresAt: '2026-11-04T01:00:00Z',
      ttlSeconds: 3600,
      purpose: 'connect',
      command,
    });
    const result = setup();

    const res = await result.current.issueConnectToken('prod-eks', {
      region: 'us-east-1',
      phase: 'READ_ONLY',
      createReadRole: true,
    });

    expect(apiPost).toHaveBeenCalledWith(
      `${BASE}/clusters/prod-eks/connect-token?accountId=12`,
      { region: 'us-east-1', phase: 'READ_ONLY', createReadRole: true }
    );
    expect(res.command).toBe(command);
  });

  it('issueConnectToken forwards selected features when provided', async () => {
    apiPost.mockResolvedValue({ token: 't', command: 'c' });
    const result = setup();

    await result.current.issueConnectToken('prod-eks', {
      region: 'us-east-1',
      phase: 'AUTOMATION',
      createReadRole: false,
      features: ['node-removal'],
    });

    expect(apiPost).toHaveBeenCalledWith(
      `${BASE}/clusters/prod-eks/connect-token?accountId=12`,
      { region: 'us-east-1', phase: 'AUTOMATION', createReadRole: false, features: ['node-removal'] }
    );
  });

  it('revokeKey DELETEs the agent key', async () => {
    apiDelete.mockResolvedValue(undefined);
    const result = setup();

    await result.current.revokeKey('prod-eks', 'automation');

    expect(apiDelete).toHaveBeenCalledWith(`${BASE}/clusters/prod-eks/keys/automation?accountId=12`);
  });

  it('disableAutomation POSTs to the disable-automation endpoint', async () => {
    apiPost.mockResolvedValue(undefined);
    const result = setup();

    await result.current.disableAutomation('prod-eks');

    expect(apiPost).toHaveBeenCalledWith(`${BASE}/clusters/prod-eks/disable-automation?accountId=12`);
  });

  it('encodes interpolated values that need escaping', async () => {
    apiGet.mockResolvedValue({ clusterName: 'a/b', registered: false });
    const result = setup('99');

    await result.current.getStatus('a/b');

    expect(apiGet).toHaveBeenCalledWith(`${BASE}/clusters/a%2Fb?accountId=99`);
  });
});
