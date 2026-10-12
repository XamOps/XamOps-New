/**
 * The Sidebar with the CloudK8s cluster sections (implementation plan, Addendum 1, J-1, J-8, J-11):
 * on an EKS cluster page, CloudOps is open, CloudK8s is active and the cluster's sections are listed
 * under it; elsewhere nothing changes, and XamSecure's nested entries render as before.
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({ hasAnyRole: (roles: string | string[]) => (Array.isArray(roles) ? roles : [roles]).includes('ROLE_XAMOPS') }),
}));
vi.mock('../../../contexts/AccountContext', () => ({
  useAccounts: () => ({ selectedAccountDisplay: { provider: 'AWS' }, accounts: [], selectedAccountIds: [] }),
}));
vi.mock('../AccountSelector', () => ({ default: () => <div>Account selector</div> }));

import Sidebar from '../Sidebar';
import { ThemeProvider } from '../../../contexts/ThemeContext';
import { XAMSECURE_SIDEBAR_TABS } from '../../../constants/xamsecureTabs';
import { CLUSTER_SECTIONS_LABEL } from '../../CloudK8s/shared/ClusterSectionsNav';

const CLUSTER = '/eks-details?name=prod-eks&region=us-east-1&accountIds=111111111111&tab=workloads&sub=pods';

function renderAt(url: string) {
  return render(
    <ThemeProvider>
      <MemoryRouter initialEntries={[url]}>
        <Sidebar />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

const cloudOps = () => screen.getByRole('button', { name: 'CloudOps' });
const cloudK8s = () => screen.getByRole('link', { name: 'CloudK8s' });

beforeEach(() => {
  vi.spyOn(window, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ allowedTabs: [] }), { status: 200 }));
});

afterEach(() => vi.restoreAllMocks());

describe('Sidebar CloudK8s entry (Addendum 1)', () => {
  it('opens CloudOps, marks CloudK8s active and lists the cluster sections on a cluster page', async () => {
    renderAt(CLUSTER);
    await waitFor(() => expect(cloudOps()).toHaveAttribute('aria-expanded', 'true'));
    expect(cloudK8s().className).toContain('font-semibold');
    const nav = screen.getByRole('navigation', { name: CLUSTER_SECTIONS_LABEL });
    expect(within(nav).getByRole('link', { name: 'Pods' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Workloads', current: 'page' })).toBeInTheDocument();
    // The cluster page has no tablist of its own in the sidebar.
    expect(screen.queryByRole('tablist')).toBeNull();
  });

  it('lists no cluster sections on the fleet page', async () => {
    renderAt('/cloudk8s');
    await waitFor(() => expect(cloudOps()).toHaveAttribute('aria-expanded', 'true'));
    expect(cloudK8s().className).toContain('font-semibold');
    expect(screen.queryByRole('navigation', { name: CLUSTER_SECTIONS_LABEL })).toBeNull();
  });

  it('leaves XamSecure as it was', async () => {
    renderAt('/xamsecure/overview');
    await waitFor(() => expect(screen.getByRole('button', { name: 'SecOps' })).toHaveAttribute('aria-expanded', 'true'));
    for (const tab of XAMSECURE_SIDEBAR_TABS) {
      expect(screen.getByRole('link', { name: tab.label })).toHaveAttribute('href', `/xamsecure/${tab.id}`);
    }
    expect(cloudK8s().className).not.toContain('font-semibold');
    expect(screen.queryByRole('navigation', { name: CLUSTER_SECTIONS_LABEL })).toBeNull();
  });
});
