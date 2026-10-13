import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, getVerticalStatus } = vi.hoisted(() => ({ getCapabilities: vi.fn(), getVerticalStatus: vi.fn() }));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});
vi.mock('../../api/verticalRecommendationsApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/verticalRecommendationsApi')>();
  return { ...actual, getVerticalStatus };
});

import { K8sApiError } from '../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../useK8sCapabilities';
import { clearVerticalCacheForTests, useVerticalRecommendations } from '../useVerticalRecommendations';
import { demoCapabilities, demoVerticalStatus } from '../../demo/k8sAutomationMockData';
import type { CapabilitiesDto } from '../../types/k8sAutomation';

const ACCOUNT = 'demo-aws-001';
const PROD = 'prod-eks-cluster';

function caps(edit?: (dto: CapabilitiesDto) => void): CapabilitiesDto {
  const dto = demoCapabilities(ACCOUNT)!;
  edit?.(dto);
  return dto;
}

function renderRead(cluster = PROD) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <K8sCapabilitiesProvider accountId={ACCOUNT} demo>{children}</K8sCapabilitiesProvider>
  );
  return renderHook(() => useVerticalRecommendations(cluster, ACCOUNT, 'us-east-1'), { wrapper });
}

beforeEach(() => {
  getCapabilities.mockReset();
  getVerticalStatus.mockReset();
  clearVerticalCacheForTests();
});
afterEach(() => vi.restoreAllMocks());

describe('useVerticalRecommendations, reads (§11.2, NIT 3)', () => {
  it('makes no request while capabilities load', () => {
    getCapabilities.mockReturnValue(new Promise(() => {}));
    const { result } = renderRead();
    expect(result.current.state).toBe('loading');
    expect(getVerticalStatus).not.toHaveBeenCalled();
  });

  it('reads V7 with no request when the engine is switched off', async () => {
    getCapabilities.mockResolvedValue(caps((d) => { d.verticalEngine.enabled = false; }));
    const { result } = renderRead();
    await waitFor(() => expect(result.current.state).toBe('V7'));
    expect(getVerticalStatus).not.toHaveBeenCalled();
  });

  it('reads V8 with no request for a cluster capabilities do not list', async () => {
    getCapabilities.mockResolvedValue(caps());
    const { result } = renderRead('staging-eks-cluster');
    await waitFor(() => expect(result.current.state).toBe('V8'));
    expect(getVerticalStatus).not.toHaveBeenCalled();
  });

  it('reads V5 with no engine request when capabilities fail, and Retry reloads capabilities', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getCapabilities.mockRejectedValue(new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }));
    const { result } = renderRead();
    await waitFor(() => expect(result.current.state).toBe('V5'));
    expect(getVerticalStatus).not.toHaveBeenCalled();
    const calls = getCapabilities.mock.calls.length;
    act(() => result.current.retry());
    await waitFor(() => expect(getCapabilities.mock.calls.length).toBe(calls + 1));
    expect(getVerticalStatus).not.toHaveBeenCalled();
  });

  it('turns a 200 into the engine view, cached for the next reader', async () => {
    getCapabilities.mockResolvedValue(caps());
    getVerticalStatus.mockResolvedValue(demoVerticalStatus(PROD));
    const first = renderRead();
    await waitFor(() => expect(first.result.current.state).toBe('V4'));
    expect(first.result.current.view?.workloads).toHaveLength(9);
    expect(getVerticalStatus).toHaveBeenCalledWith({ cluster: PROD, accountId: ACCOUNT, region: 'us-east-1' }, expect.anything());
    first.unmount();
    const second = renderRead();
    await waitFor(() => expect(second.result.current.state).toBe('V4'));
    expect(getVerticalStatus).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['a codeless 404 as V6 (engine not deployed)', new K8sApiError({ status: 404 }), 'V6'],
    ['K8S_CLUSTER_NOT_REGISTERED as V8', new K8sApiError({ status: 404, code: 'K8S_CLUSTER_NOT_REGISTERED' }), 'V8'],
    ['a codeless 403 as no access', new K8sApiError({ status: 403 }), 'no-access'],
    ['K8S_NO_ACCOUNT_ACCESS as no access', new K8sApiError({ status: 403, code: 'K8S_NO_ACCOUNT_ACCESS' }), 'no-access'],
    ['a 500 as V5', new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }), 'V5'],
    ['a network failure as V5', new K8sApiError({ status: 0 }), 'V5'],
  ])('classifies %s, by code', async (_label, error, state) => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getCapabilities.mockResolvedValue(caps());
    getVerticalStatus.mockRejectedValue(error);
    const { result } = renderRead();
    await waitFor(() => expect(result.current.state).toBe(state));
    expect(result.current.view).toBeNull();
  });

  it('retries the engine read after a failure', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getCapabilities.mockResolvedValue(caps());
    getVerticalStatus.mockRejectedValueOnce(new K8sApiError({ status: 500 })).mockResolvedValueOnce(demoVerticalStatus('eu-eks-cluster'));
    const { result } = renderRead();
    await waitFor(() => expect(result.current.state).toBe('V5'));
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.state).toBe('V2'));
    expect(getVerticalStatus).toHaveBeenCalledTimes(2);
  });
});
