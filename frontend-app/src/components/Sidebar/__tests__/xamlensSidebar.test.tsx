/**
 * XamLens's part of the Sidebar: under SecOps, XamLens and, on its routes, the Matters console's
 * "RISKS" section label with Misconfigurations beneath it, then the "DATA INVENTORY" label with
 * Datastores beneath it, each active on its own routes. The entries
 * follow XamLens: they fold away off its routes, like other nested entries, and a user whose tab rules
 * hide XamLens sees none of them.
 */
import React, { useEffect } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
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
import { ThemeProvider, useTheme } from '../../../contexts/ThemeContext';
import { XamTabAccessProvider } from '../../../contexts/XamTabAccessContext';
import { DATASTORE_TABS, XAMLENS_FILES_HOME, XAMLENS_HOME, XAMLENS_LINEAGE_HOME } from '../../../constants/xamlensTabs';

let allowedTabs: string[] = [];

/** Switches the real ThemeProvider to dark, as a theme toggle would. */
const DarkTheme: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { setTheme } = useTheme();
  useEffect(() => setTheme('dark'), [setTheme]);
  return <>{children}</>;
};

function renderAt(url: string, dark = false) {
  const sidebar = (
    <MemoryRouter initialEntries={[url]}>
      <XamTabAccessProvider>
        <Sidebar />
      </XamTabAccessProvider>
    </MemoryRouter>
  );
  return render(<ThemeProvider>{dark ? <DarkTheme>{sidebar}</DarkTheme> : sidebar}</ThemeProvider>);
}

const label = () => screen.getByText('Data Inventory');
// A section label is a <p>; SOC 2's own "Risks" entry, also in the sidebar, is not.
const risksLabel = () => screen.getByText('Risks', { selector: 'p' });
const follows = (a: Node, b: Node) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
/** XamLens's Dashboard entry; other sections have Dashboard links of their own. */
const xamLensDashboard = () => {
  const links = screen.getAllByRole('link', { name: 'Dashboard' }).filter(link => link.getAttribute('href') === '/xamlens/dashboard');
  expect(links).toHaveLength(1);
  return links[0];
};

beforeEach(() => {
  allowedTabs = [];
  vi.spyOn(window, 'fetch').mockImplementation(async () =>
    new Response(JSON.stringify({ allowedTabs, mattersDspmEnabled: true }), { status: 200 }));
});

afterEach(() => vi.restoreAllMocks());

describe('Sidebar XamLens entries', () => {
  it.each(DATASTORE_TABS.map(t => t.slug))(
    'puts the DATA INVENTORY label and an active Datastores entry under XamLens on /xamlens/datastores/%s',
    async slug => {
      renderAt(`/xamlens/datastores/${slug}`);
      const datastores = await screen.findByRole('link', { name: 'Datastores' });
      expect(screen.getByRole('button', { name: 'SecOps' })).toHaveAttribute('aria-expanded', 'true');
      expect(datastores).toHaveAttribute('href', XAMLENS_HOME);
      expect(datastores.className).toContain('font-semibold');
      expect(screen.getAllByText('Data Inventory')).toHaveLength(1);
      expect(label()).toHaveClass('uppercase', 'text-[10px]', 'tracking-widest');
      const xamLens = screen.getByRole('link', { name: 'XamLens' });
      expect(xamLens.compareDocumentPosition(label()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(label().compareDocumentPosition(datastores) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    },
  );

  it.each(['/xamlens/files/saas', '/xamlens/files/endpoint'])(
    'lists Datastores then an active Files entry under one DATA INVENTORY label on %s',
    async url => {
      renderAt(url);
      const files = await screen.findByRole('link', { name: 'Files' });
      const datastores = screen.getByRole('link', { name: 'Datastores' });
      expect(files).toHaveAttribute('href', XAMLENS_FILES_HOME);
      expect(files).toHaveAttribute('href', '/xamlens/files/saas');
      expect(files.className).toContain('font-semibold');
      expect(datastores.className).not.toContain('font-semibold');
      expect(screen.getAllByText('Data Inventory')).toHaveLength(1);
      expect(label().compareDocumentPosition(datastores) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(datastores.compareDocumentPosition(files) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    },
  );

  it('lists Files, not active, on a Datastores route', async () => {
    renderAt(XAMLENS_HOME);
    const files = await screen.findByRole('link', { name: 'Files' });
    expect(files.className).not.toContain('font-semibold');
    expect(screen.getByRole('link', { name: 'Datastores' }).className).toContain('font-semibold');
  });

  it.each([
    ['light', false, 'text-[rgba(0,0,0,0.55)]'],
    ['dark', true, 'text-[rgba(255,255,255,0.5)]'],
  ])('gives the label the %s theme\'s muted colour', async (_theme, dark, colour) => {
    renderAt(XAMLENS_HOME, dark);
    await screen.findByRole('link', { name: 'Datastores' });
    await waitFor(() => expect(label()).toHaveClass(colour));
  });

  it('heads Misconfigurations with the RISKS label above DATA INVENTORY, with the same label classes', async () => {
    renderAt(XAMLENS_HOME);
    const datastores = await screen.findByRole('link', { name: 'Datastores' });
    const misconfigurations = screen.getByRole('link', { name: 'Misconfigurations' });
    expect(misconfigurations).toHaveAttribute('href', '/xamlens/misconfigurations');
    expect(screen.getAllByText('Risks', { selector: 'p' })).toHaveLength(1);
    expect(risksLabel().className).toBe(label().className);
    expect(risksLabel()).toHaveClass('uppercase', 'text-[10px]', 'tracking-widest');
    expect(follows(screen.getByRole('link', { name: 'XamLens' }), risksLabel())).toBe(true);
    expect(follows(risksLabel(), misconfigurations)).toBe(true);
    expect(follows(misconfigurations, label())).toBe(true);
    expect(follows(label(), datastores)).toBe(true);
    // Only Datastores is active on a Datastores route.
    expect(datastores.className).toContain('font-semibold');
    expect(misconfigurations.className).not.toContain('font-semibold');
  });

  it('makes Misconfigurations the active entry on /xamlens/misconfigurations', async () => {
    renderAt('/xamlens/misconfigurations');
    const misconfigurations = await screen.findByRole('link', { name: 'Misconfigurations' });
    expect(screen.getByRole('button', { name: 'SecOps' })).toHaveAttribute('aria-expanded', 'true');
    expect(misconfigurations.className).toContain('font-semibold');
    expect(screen.getByRole('link', { name: 'Datastores' }).className).not.toContain('font-semibold');
    expect(risksLabel()).toBeInTheDocument();
  });

  it('puts Dashboard first under XamLens with no section label, then DATA INVENTORY once', async () => {
    renderAt('/xamlens/datastores/all');
    const datastores = await screen.findByRole('link', { name: 'Datastores' });
    const dashboard = xamLensDashboard();
    expect(dashboard).toHaveAttribute('href', '/xamlens/dashboard');
    const xamLens = screen.getByRole('link', { name: 'XamLens' });
    expect(xamLens.compareDocumentPosition(dashboard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(dashboard.compareDocumentPosition(label()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(label().compareDocumentPosition(datastores) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getAllByText('Data Inventory')).toHaveLength(1);
    // No section label between XamLens and Dashboard: Dashboard sits right after the XamLens entry's list item.
    const subNav = dashboard.closest('a')!.parentElement!;
    expect(subNav.firstElementChild).toBe(dashboard.closest('a'));
    expect(dashboard.className).not.toContain('font-semibold');
  });

  it('marks Dashboard active on /xamlens/dashboard, and Datastores not', async () => {
    renderAt('/xamlens/dashboard');
    const datastores = await screen.findByRole('link', { name: 'Datastores' });
    expect(screen.getByRole('button', { name: 'SecOps' })).toHaveAttribute('aria-expanded', 'true');
    expect(xamLensDashboard().className).toContain('font-semibold');
    expect(datastores.className).not.toContain('font-semibold');
  });

  it('folds the label and Datastores away off the XamLens routes', async () => {
    renderAt('/xamsecure/overview');
    await screen.findByRole('link', { name: 'XamLens' });
    expect(screen.queryByRole('link', { name: 'Datastores' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Misconfigurations' })).toBeNull();
    expect(label().closest('[aria-hidden="true"]')).not.toBeNull();
    expect(risksLabel().closest('[aria-hidden="true"]')).not.toBeNull();
  });

  it('puts an active Data Lineage entry directly after Datastores and Files under the one DATA INVENTORY label', async () => {
    renderAt(XAMLENS_LINEAGE_HOME);
    const lineage = await screen.findByRole('link', { name: 'Data Lineage' });
    const datastores = screen.getByRole('link', { name: 'Datastores' });
    expect(lineage).toHaveAttribute('href', '/xamlens/data-lineage');
    expect(lineage.className).toContain('font-semibold');
    expect(datastores.className).not.toContain('font-semibold');
    expect(screen.getAllByText('Data Inventory')).toHaveLength(1);
    expect(label().compareDocumentPosition(datastores) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(datastores.compareDocumentPosition(lineage) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const links = screen.getAllByRole('link');
    expect(links[links.indexOf(datastores) + 1]).toBe(screen.getByRole('link', { name: 'Files' }));
    expect(links[links.indexOf(datastores) + 2]).toBe(lineage);
  });

  it('folds Data Lineage away off the XamLens routes', async () => {
    renderAt('/xamsecure/overview');
    await screen.findByRole('link', { name: 'XamLens' });
    expect(screen.queryByRole('link', { name: 'Data Lineage' })).toBeNull();
  });

  it('shows none of them to a user whose tab rules hide XamLens', async () => {
    allowedTabs = ['secops-xamsecure'];
    renderAt('/xamsecure/overview');
    await screen.findByRole('link', { name: 'XamSecure' });
    expect(screen.queryByRole('link', { name: 'XamLens', hidden: true })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Datastores', hidden: true })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Misconfigurations', hidden: true })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Data Lineage', hidden: true })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Files', hidden: true })).toBeNull();
    expect(screen.queryByText('Data Inventory')).toBeNull();
    expect(screen.queryByText('Risks', { selector: 'p' })).toBeNull();
    expect(screen.queryAllByRole('link', { name: 'Dashboard', hidden: true })
      .filter(link => link.getAttribute('href') === '/xamlens/dashboard')).toHaveLength(0);
  });
});
