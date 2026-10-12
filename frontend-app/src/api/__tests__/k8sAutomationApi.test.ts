import { beforeEach, describe, expect, it, vi } from 'vitest';

const { apiFetch } = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import {
  K8sApiError,
  MAX_MESSAGE_LENGTH,
  getAutomationDashboard,
  getCapabilities,
  hasK8sCode,
  k8sRequest,
  saveSafetyGuards,
  setAutomationEnabled,
  triggerRebalance,
} from '../k8sAutomationApi';

const respond = (status: number, body: unknown, raw = false) =>
  new Response(raw ? (body as string) : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': raw ? 'text/html' : 'application/json' },
  });

async function failure(promise: Promise<unknown>): Promise<K8sApiError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(K8sApiError);
    return error as K8sApiError;
  }
  throw new Error('expected a K8sApiError');
}

describe('k8sRequest', () => {
  beforeEach(() => apiFetch.mockReset());

  it('returns the parsed JSON body, or null for an empty one', async () => {
    apiFetch.mockResolvedValueOnce(respond(200, { schemaVersion: 1 }));
    await expect(k8sRequest('/x')).resolves.toEqual({ schemaVersion: 1 });
    apiFetch.mockResolvedValueOnce(new Response('', { status: 200 }));
    await expect(k8sRequest('/x')).resolves.toBeNull();
  });

  it('reads the refusal body into the error', async () => {
    apiFetch.mockResolvedValueOnce(respond(403, {
      success: false, code: 'K8S_NO_USER_PERMISSION', message: "You don't have permission to change automation.",
      field: null, detail: 'TAB_GRANT',
    }));
    const e = await failure(k8sRequest('/x'));
    expect(e.status).toBe(403);
    expect(e.code).toBe('K8S_NO_USER_PERMISSION');
    expect(e.reason).toBe('NO_USER_PERMISSION');
    expect(e.detail).toBe('TAB_GRANT');
    expect(e.field).toBeNull();
    expect(e.message).toBe("You don't have permission to change automation.");
    expect(hasK8sCode(e)).toBe(true);
  });

  it('keeps the field of a validation refusal', async () => {
    apiFetch.mockResolvedValueOnce(respond(400, {
      success: false, code: 'K8S_INVALID_POLICY', message: 'Drain timeout must be between 5 and 60.',
      field: 'drainTimeoutMinutes', detail: null,
    }));
    const e = await failure(k8sRequest('/x'));
    expect(e.field).toBe('drainTimeoutMinutes');
  });

  it('caps the message at 300 characters of plain text', async () => {
    apiFetch.mockResolvedValueOnce(respond(403, { code: 'K8S_SOMETHING', message: `line one\n\n${'x'.repeat(500)}` }));
    const e = await failure(k8sRequest('/x'));
    expect(e.message.length).toBe(MAX_MESSAGE_LENGTH);
    expect(e.message.startsWith('line one x')).toBe(true);
    expect(e.message.endsWith('…')).toBe(true);
  });

  it('never shows a body without a code, such as an HTML error page', async () => {
    apiFetch.mockResolvedValueOnce(respond(502, '<html><body>Bad gateway nginx/1.25</body></html>', true));
    const e = await failure(k8sRequest('/x'));
    expect(e.status).toBe(502);
    expect(e.code).toBeNull();
    expect(e.message).not.toContain('nginx');
    expect(hasK8sCode(e)).toBe(false);
  });

  it('turns network failures and timeouts into status-0 errors, and lets the caller abort', async () => {
    apiFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect((await failure(k8sRequest('/x'))).status).toBe(0);
    apiFetch.mockRejectedValueOnce(new Error('Request timed out after 45000ms: /x'));
    expect((await failure(k8sRequest('/x'))).message).toBe('The request timed out. Try again.');
    apiFetch.mockRejectedValueOnce(new DOMException('aborted', 'AbortError'));
    await expect(k8sRequest('/x')).rejects.toMatchObject({ name: 'AbortError' });
  });
});

describe('endpoints', () => {
  beforeEach(() => {
    apiFetch.mockReset();
    apiFetch.mockResolvedValue(respond(200, { success: true, message: 'ok' }));
  });

  it('asks for capabilities by AWS account number', async () => {
    await getCapabilities('605134457560');
    expect(apiFetch.mock.calls[0][0]).toBe('/api/xamops/k8s/capabilities?accountId=605134457560');
  });

  it('encodes the cluster name and sends complete bodies', async () => {
    await getAutomationDashboard({ cluster: 'prod eks', accountId: '111111111111', region: 'us-east-1' });
    expect(apiFetch.mock.calls[0][0]).toBe('/api/eks/autoscaling/prod%20eks?accountId=111111111111&region=us-east-1');

    await setAutomationEnabled({ cluster: 'prod-eks', accountId: '111111111111' }, true);
    expect(apiFetch.mock.calls[1]).toEqual(['/api/eks/autoscaling/prod-eks/enabled?accountId=111111111111',
      { method: 'PUT', body: JSON.stringify({ enabled: true }) }]);

    await saveSafetyGuards({ cluster: 'prod-eks', accountId: '1' }, {
      respectPodDisruptionBudgets: true, protectSingleReplicas: true, protectStatefulSets: true,
      protectCriticalNamespaces: true, criticalNamespaces: null,
    });
    expect(JSON.parse(apiFetch.mock.calls[2][1].body)).toHaveProperty('criticalNamespaces', null);

    await triggerRebalance({ cluster: 'prod-eks', accountId: '1' });
    expect(apiFetch.mock.calls[3][1]).toEqual({ method: 'POST' });
  });
});
