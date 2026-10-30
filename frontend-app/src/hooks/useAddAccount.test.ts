import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

// Mock the api client so launchStack's generate-stack-url call returns a controlled shape.
// The hook imports { apiFetch } from '../utils/apiClient'; stubbing it here keeps the test
// focused on the launch wiring rather than fetch/CSRF plumbing.
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

describe('useAddAccount — stack launch', () => {
  let openSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    apiFetch.mockReset();
    openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);
  });

  it('opens only the connection stack; the CUR stack is created by XamOps on connect', async () => {
    const connectionUrl = 'https://console.aws.amazon.com/cloudformation/home?region=ap-south-1#/stacks';
    apiFetch.mockResolvedValue(
      jsonResponse({
        url: connectionUrl,
        externalId: 'ext-123',
        eventBridgeEnabled: 'false',
        roleName: 'XamOpsReadOnlyRole',
        // An older backend may still send this; the hook must ignore it.
        curUrl: 'https://console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/create/review',
      }),
    );

    const { result } = renderHook(() => useAddAccount());

    act(() => result.current.setAccountName('Prod'));
    await act(async () => {
      await result.current.launchStack();
    });

    await waitFor(() => expect(result.current.isVerifying).toBe(true));
    expect(openSpy).toHaveBeenCalledTimes(1);
    expect(openSpy).toHaveBeenCalledWith(connectionUrl, '_blank', 'noopener,noreferrer');
  });
});
