/**
 * The XamLens (DSPM) admin item in the BillOps admin sidebar follows the roles its API admits
 * (DSPM_ADMIN_ROLES): shown to a BillOps admin, hidden from a BillOps sub-admin, even one whose
 * section list names it.
 */
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let roles: string[] = [];
vi.mock('../../../contexts/AuthContext', () => ({
  useAuth: () => ({
    hasAnyRole: (wanted: string | string[]) => (Array.isArray(wanted) ? wanted : [wanted]).some(r => roles.includes(r)),
  }),
}));
vi.mock('../../../contexts/AccountContext', () => ({
  useAccounts: () => ({ selectedAccountDisplay: { provider: 'AWS' }, accounts: [], selectedAccountIds: [] }),
}));
vi.mock('../AccountSelector', () => ({ default: () => <div>Account selector</div> }));

import Sidebar from '../Sidebar';
import { ThemeProvider } from '../../../contexts/ThemeContext';
import { XamTabAccessProvider } from '../../../contexts/XamTabAccessContext';
import { DSPM_ADMIN_ROLES } from '../../../constants/billopsAdminTabs';

let allowedTabs: string[] = [];

function renderSidebar() {
  return render(
    <ThemeProvider>
      <MemoryRouter initialEntries={['/billops-admin-dashboard']}>
        <XamTabAccessProvider>
          <Sidebar />
        </XamTabAccessProvider>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  allowedTabs = [];
  vi.spyOn(window, 'fetch').mockImplementation(async () =>
    new Response(JSON.stringify({ isPrimaryUser: true, allowedTabs }), { status: 200 }));
});

afterEach(() => vi.restoreAllMocks());

describe('Sidebar XamLens (DSPM) admin item', () => {
  it('uses the roles DspmAdminController admits', () => {
    expect(DSPM_ADMIN_ROLES).toEqual(['ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN']);
  });

  it('is shown to a BillOps admin', async () => {
    roles = ['ROLE_BILLOPS_ADMIN'];
    renderSidebar();
    expect(await screen.findByRole('link', { name: 'XamLens' })).toHaveAttribute('href', '/dspm-admin');
  });

  it('is hidden from a BillOps sub-admin', async () => {
    roles = ['ROLE_BILLOPS_ADMIN_SUB'];
    renderSidebar();
    await screen.findByRole('link', { name: 'Accounts' });
    expect(screen.queryByRole('link', { name: 'XamLens' })).toBeNull();
  });

  it('stays hidden from a sub-admin whose section list includes it', async () => {
    roles = ['ROLE_BILLOPS_ADMIN_SUB'];
    allowedTabs = ['client-details', 'dspm-admin'];
    renderSidebar();
    await screen.findByRole('link', { name: 'Accounts' });
    expect(screen.queryByRole('link', { name: 'XamLens' })).toBeNull();
  });
});
