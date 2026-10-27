import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

// Mock the api client so launchStack's generate-stack-url call returns a controlled shape.
// The hook imports { apiFetch } from '../utils/apiClient'; stubbing it here keeps the test
// focused on the CUR-URL wiring rather than fetch/CSRF plumbing.
const apiFetch = vi.fn();
vi.mock('../utils/apiClient', () => ({
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}));

import { useAddAccount } from './useAddAccount';

/** Builds a Response-like object whose json() yields the given body. */
const jsonResponse = (body: unknown) =>
  ({
    ok: true,
    status: 200,
    headers: new Headers(),
    text: async () => JSON.stringify(body),
    json: async () => body,
  }) as unknown as Response;

describe('useAddAccount — CUR stack wiring', () => {
  let openSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    apiFetch.mockReset();
    // window.open is called for the connection stack (always) and for the CUR stack (on demand).
    openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
  });

  it('sets curStackUrl from the response and opens it via launchCurStack when curUrl is present', async () => {
    const curUrl =
      'https://console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/create/review?stackName=XamOps-CUR-Prod';
    apiFetch.mockResolvedValue(
      jsonResponse({
        url: 'https://console.aws.amazon.com/cloudformation/home?region=ap-south-1#/stacks',
        externalId: 'ext-123',
        eventBridgeEnabled: 'false',
        roleName: 'XamOpsReadOnlyRole',
        curUrl,
        curStackName: 'XamOps-CUR-Prod',
        curRegion: 'us-east-1',
      }),
    );

    const { result } = renderHook(() => useAddAccount());

    act(() => result.current.setAccountName('Prod'));
    await act(async () => {
      await result.current.launchStack();
    });

    await waitFor(() => expect(result.current.curStackUrl).toBe(curUrl));

    // The connection stack opened automatically; the CUR stack has not yet.
    expect(openSpy).toHaveBeenCalledTimes(1);

    act(() => result.current.launchCurStack());
    expect(openSpy).toHaveBeenCalledWith(curUrl, '_blank', 'noopener,noreferrer');
    expect(openSpy).toHaveBeenCalledTimes(2);
  });

  it('leaves curStackUrl null and launchCurStack a no-op when curUrl is absent', async () => {
    apiFetch.mockResolvedValue(
      jsonResponse({
        url: 'https://console.aws.amazon.com/cloudformation/home?region=ap-south-1#/stacks',
        externalId: 'ext-456',
        eventBridgeEnabled: 'false',
        roleName: 'XamOpsReadOnlyRole',
      }),
    );

    const { result } = renderHook(() => useAddAccount());

    act(() => result.current.setAccountName('NoCur'));
    await act(async () => {
      await result.current.launchStack();
    });

    await waitFor(() => expect(result.current.isVerifying).toBe(true));
    expect(result.current.curStackUrl).toBeNull();

    // Only the connection stack opened; launchCurStack must not open anything.
    expect(openSpy).toHaveBeenCalledTimes(1);
    act(() => result.current.launchCurStack());
    expect(openSpy).toHaveBeenCalledTimes(1);
  });
});
