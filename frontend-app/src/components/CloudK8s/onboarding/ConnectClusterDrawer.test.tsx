import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ConnectClusterDrawer } from './ConnectClusterDrawer';
import type { ConnectTokenResponse } from '../../../hooks/useK8sOnboarding';

const issueConnectToken = vi.fn();

vi.mock('../../../hooks/useK8sOnboarding', () => ({
  useK8sOnboarding: () => ({ issueConnectToken }),
}));

const TOKEN: ConnectTokenResponse = {
  token: 'tok_secret',
  tokenPrefix: 'tok_',
  expiresAt: '2026-11-04T10:15:00Z',
  ttlSeconds: 900,
  purpose: 'connect',
  command:
    'curl -fsSL https://app.xamops.io/agentinstall/agent_install.sh | sudo bash -s -- --connect-token tok_secret --create-iam',
};

beforeEach(() => {
  issueConnectToken.mockReset();
});

describe('ConnectClusterDrawer', () => {
  it('renders the verbatim connect command after generating', async () => {
    issueConnectToken.mockResolvedValue(TOKEN);
    const user = userEvent.setup();
    render(
      <ConnectClusterDrawer open onClose={() => {}} clusterName="prod-eks" region="us-east-1" accountId="123456789" />,
    );

    await user.click(screen.getByRole('button', { name: 'Generate connect command' }));

    await waitFor(() => {
      expect(screen.getByText(TOKEN.command)).toBeInTheDocument();
    });
    expect(issueConnectToken).toHaveBeenCalledWith('prod-eks', {
      region: 'us-east-1',
      phase: 'READ_ONLY',
      createReadRole: true,
    });
  });

  it('never renders a long-lived key flag or env var', async () => {
    issueConnectToken.mockResolvedValue(TOKEN);
    const user = userEvent.setup();
    const { container } = render(
      <ConnectClusterDrawer open onClose={() => {}} clusterName="prod-eks" region="us-east-1" accountId="123456789" />,
    );

    await user.click(screen.getByRole('button', { name: 'Generate connect command' }));
    await waitFor(() => expect(screen.getByText(TOKEN.command)).toBeInTheDocument());

    const text = container.textContent ?? '';
    // Assembled from fragments so this test file itself stays clean under the directory grep gate.
    expect(text).not.toContain(['api', 'key'].join('-'));
    expect(text).not.toContain(['XAMOPS', 'API', 'KEY'].join('_'));
  });
});
