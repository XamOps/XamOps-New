import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities } = vi.hoisted(() => ({ getCapabilities: vi.fn() }));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities };
});

import { K8sApiError } from '../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../useK8sCapabilities';
import { usePublishClusterNavAccess } from '../useK8sNavAccess';
import { publishClusterNavAccess, useClusterNavAccess } from '../../utils/k8sNavAccess';
import { demoCapabilities } from '../../demo/k8sAutomationMockData';

function Publisher({ cluster, account }: { cluster: string; account: string }) {
  usePublishClusterNavAccess(cluster, account);
  return null;
}

function Reader({ cluster, account }: { cluster: string; account: string }) {
  const access = useClusterNavAccess(cluster, account);
  return <output data-testid="access">{access ? JSON.stringify({ viewOnly: access.viewOnly, reason: access.reason }) : 'none'}</output>;
}

function renderWith(account: string, cluster: string, demo: boolean) {
  return render(
    <>
      <K8sCapabilitiesProvider accountId={account} demo={demo}>
        <Publisher cluster={cluster} account={account} />
      </K8sCapabilitiesProvider>
      <Reader cluster={cluster} account={account} />
    </>,
  );
}

const access = () => screen.getByTestId('access').textContent;

beforeEach(() => {
  getCapabilities.mockReset();
  publishClusterNavAccess(null);
});

afterEach(() => vi.restoreAllMocks());

describe('usePublishClusterNavAccess (Addendum 1, J-5, A4)', () => {
  it('publishes nothing while capabilities load', () => {
    getCapabilities.mockReturnValue(new Promise(() => {}));
    renderWith('demo-aws-001', 'prod-eks-cluster', true);
    expect(access()).toBe('none');
  });

  it.each([
    ['the demo read-write account', 'demo-aws-001', "Demo workspace. Changes aren't saved."],
    ['the demo read-only account', 'demo-aws-002', 'Read-only connection. Automation changes need read-write access.'],
  ])('reads "View only" for %s in a demo session, with the primary reason', async (_label, account, reason) => {
    getCapabilities.mockResolvedValue(demoCapabilities(account));
    renderWith(account, 'prod-eks-cluster', true);
    await waitFor(() => expect(access()).toBe(JSON.stringify({ viewOnly: true, reason })));
  });

  it('is not view-only when a change is allowed (read-write, permitted, automation agent connected)', async () => {
    getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
    renderWith('demo-aws-001', 'prod-eks-cluster', false);
    await waitFor(() => expect(access()).toBe(JSON.stringify({ viewOnly: false, reason: null })));
  });

  it('explains a cluster capabilities do not list', async () => {
    getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-001'));
    renderWith('demo-aws-001', 'staging-eks-cluster', false);
    await waitFor(() => expect(access()).toBe(JSON.stringify({
      viewOnly: true, reason: "This cluster isn't monitored yet. Install the XamOps agent first.",
    })));
  });

  it('fails closed when capabilities fail', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    getCapabilities.mockRejectedValue(new K8sApiError({ status: 500, code: 'K8S_INTERNAL' }));
    renderWith('111111111111', 'prod-eks', false);
    await waitFor(() => expect(access()).toBe(JSON.stringify({ viewOnly: true, reason: "Couldn't check automation access." })));
  });

  it('clears the value when the page leaves', async () => {
    getCapabilities.mockResolvedValue(demoCapabilities('demo-aws-002'));
    const { rerender } = renderWith('demo-aws-002', 'prod-eks-cluster', true);
    await waitFor(() => expect(access()).not.toBe('none'));
    rerender(<Reader cluster="prod-eks-cluster" account="demo-aws-002" />);
    expect(access()).toBe('none');
  });
});
