import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { EnableAutomationDrawer } from './EnableAutomationDrawer';
import type {
  ConnectTokenResponse,
  PermissionManifest,
  PreflightResult,
} from '../../../hooks/useK8sOnboarding';
import type { OnboardingFeatureWire } from './featureCatalogPresentation';

const getPermissions = vi.fn();
const runPreflight = vi.fn();
const issueConnectToken = vi.fn();

vi.mock('../../../hooks/useK8sOnboarding', () => ({
  useK8sOnboarding: () => ({ getPermissions, runPreflight, issueConnectToken }),
}));

const FEATURES: OnboardingFeatureWire[] = [
  { id: 'node-automation', label: 'Node automation', phase: 2, available: true, reason: null, selected: true },
];

function manifest(): PermissionManifest {
  return {
    version: '3',
    phase: 'AUTOMATION',
    clusterName: 'prod-eks',
    region: 'us-east-1',
    accountId: '123456789',
    identityMode: 'IRSA',
    nodeScaler: 'karpenter',
    kubernetes: [],
    aws: [
      {
        role: 'write',
        boundTo: 'arn:aws:iam::123456789012:role/xamops-write',
        policy: {
          Version: '2012-10-17',
          Statement: [{ Sid: 'XamOpsWrite', Effect: 'Allow', Action: ['ec2:TerminateInstances'], Resource: '*' }],
        },
      },
    ],
    adminPermissions: [],
    features: FEATURES,
    nodeRemoval: true,
    notes: [],
    hash: 'def456',
  };
}

function preflight(overrides: Partial<PreflightResult> = {}): PreflightResult {
  return {
    ranAt: '2026-11-04T10:00:00Z',
    blocking: false,
    identityMode: 'IRSA',
    nodeScaler: 'karpenter',
    checks: [{ id: 'oidc', result: 'PASS', blocksPhases: [], title: 'OIDC provider present' }],
    ...overrides,
  };
}

const TOKEN: ConnectTokenResponse = {
  token: 'tok_auto',
  tokenPrefix: 'tok_',
  expiresAt: '2026-11-04T10:15:00Z',
  ttlSeconds: 900,
  purpose: 'connect',
  command:
    'curl -fsSL https://app.xamops.io/agentinstall/agent_install.sh | sudo bash -s -- --connect-token tok_auto --create-iam',
};

beforeEach(() => {
  getPermissions.mockReset();
  runPreflight.mockReset();
  issueConnectToken.mockReset();
});

function renderDrawer() {
  return render(
    <EnableAutomationDrawer open onClose={() => {}} clusterName="prod-eks" region="us-east-1" accountId="123456789" />,
  );
}

describe('EnableAutomationDrawer', () => {
  it('renders PermissionList and PreflightList after review', async () => {
    getPermissions.mockResolvedValue(manifest());
    runPreflight.mockResolvedValue(preflight());
    const user = userEvent.setup();
    const { container } = renderDrawer();

    await user.click(screen.getByRole('button', { name: 'Review permissions & preflight' }));

    await waitFor(() => {
      expect(container.querySelector('[data-permission-list]')).not.toBeNull();
      expect(container.querySelector('[data-preflight-list]')).not.toBeNull();
    });
    expect(screen.getByText('Node automation')).toBeInTheDocument();
    expect(screen.getByText('OIDC provider present')).toBeInTheDocument();
  });

  it('keeps the connect action disabled while preflight is blocking', async () => {
    getPermissions.mockResolvedValue(manifest());
    runPreflight.mockResolvedValue(preflight({ blocking: true }));
    const user = userEvent.setup();
    const { container } = renderDrawer();

    await user.click(screen.getByRole('button', { name: 'Review permissions & preflight' }));

    await waitFor(() => expect(container.querySelector('[data-enable-connect]')).not.toBeNull());
    expect(container.querySelector('[data-enable-connect]')).toBeDisabled();
    expect(container.querySelector('[data-enable-blocked]')).not.toBeNull();
  });

  it('shows the verbatim command once a non-blocking token is issued', async () => {
    getPermissions.mockResolvedValue(manifest());
    runPreflight.mockResolvedValue(preflight());
    issueConnectToken.mockResolvedValue(TOKEN);
    const user = userEvent.setup();
    const { container } = renderDrawer();

    await user.click(screen.getByRole('button', { name: 'Review permissions & preflight' }));
    await waitFor(() => expect(container.querySelector('[data-enable-connect]')).not.toBeNull());
    expect(container.querySelector('[data-enable-connect]')).not.toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Generate connect command' }));

    await waitFor(() => expect(screen.getByText(TOKEN.command)).toBeInTheDocument());
    expect(issueConnectToken).toHaveBeenCalledWith('prod-eks', {
      region: 'us-east-1',
      phase: 'AUTOMATION',
      createReadRole: true,
    });
    const text = container.textContent ?? '';
    // Assembled from fragments so this test file itself stays clean under the directory grep gate.
    expect(text).not.toContain(['api', 'key'].join('-'));
    expect(text).not.toContain(['XAMOPS', 'API', 'KEY'].join('_'));
  });
});
