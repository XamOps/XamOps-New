import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../utils/apiClient';
import ScanManagementDrawer from '../ScanManagementDrawer';
import {
  GOOGLE_ID, endpointConfig, expectConsoleOnly, googleConfig, page, schedule, scope, targets, users,
} from './scanTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const BASE = '/api/dspm/scan-management';
const SCHEDULE = schedule();

const paths = () => get.mock.calls.map(([url]) => new URL(String(url), 'http://localhost').pathname);

beforeEach(() => {
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    switch (url.pathname) {
      case `${BASE}/saas-integrations`:
        return { integrations: [{ id: GOOGLE_ID, provider: 'GOOGLE_WORKSPACE', accountLabel: 'sample-workspace', driveTypes: ['GMAIL'] }], partial: false };
      case `${BASE}/saas-integrations/${GOOGLE_ID}/configuration`: return googleConfig();
      case `${BASE}/saas-integrations/${GOOGLE_ID}/users`: return page(users(3));
      case `${BASE}/endpoint-configuration`: return endpointConfig();
      case `${BASE}/scan-scopes`: return page([scope()]);
      case `${BASE}/schedules`: return page([SCHEDULE]);
      case `${BASE}/schedules/${SCHEDULE.id}`:
        return { schedule: SCHEDULE, scopeMode: 'STATIC', scopeNames: ['Sample scope'], targets: targets(2), targetsTotal: 2 };
      case `${BASE}/scan-history`: return page([]);
    }
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

const renderDrawer = (initialPlatform: 'saas' | 'endpoint' = 'saas') => {
  const onClose = vi.fn();
  render(<ScanManagementDrawer initialPlatform={initialPlatform} onClose={onClose} />);
  return { onClose, drawer: screen.getByRole('dialog', { name: 'Scan Management' }) };
};

describe('ScanManagementDrawer', () => {
  it('shows the title, the subtitle and the nav in the console order, Scan Configurations first', async () => {
    const { drawer } = renderDrawer();
    expect(within(drawer).getByRole('heading', { name: 'Scan Management' })).toBeInTheDocument();
    expect(within(drawer).getByText('Schedule, run and track scans across Cloud, Endpoint and On-Prem.')).toBeInTheDocument();
    const nav = within(drawer).getByRole('tablist', { name: 'Scan Management' });
    expect(nav).toHaveAttribute('aria-orientation', 'vertical');
    expect(within(nav).getAllByRole('tab').map(tab => tab.textContent)).toEqual(['Scan Configurations', 'Scan Scopes', 'Schedules', 'Scan History']);
    // The divider sits between the primary and the secondary pair.
    const separator = within(nav).getByRole('separator');
    expect(separator.previousElementSibling).toHaveTextContent('Scan Scopes');
    expect(separator.nextElementSibling).toHaveTextContent('Schedules');
    expect(within(nav).getByRole('tab', { name: 'Scan Configurations' })).toHaveAttribute('aria-selected', 'true');
    expect(within(drawer).getAllByRole('button', { name: 'Close' })[0]).toHaveFocus();
    await screen.findByText('Scan settings for SaaS apps and endpoint agents.');
  });

  it('moves focus and the selection with ArrowDown and ArrowUp, wrapping', async () => {
    const { drawer } = renderDrawer();
    const tab = (name: string) => within(drawer).getByRole('tab', { name });
    tab('Scan Configurations').focus();
    fireEvent.keyDown(tab('Scan Configurations'), { key: 'ArrowDown' });
    expect(tab('Scan Scopes')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Scan Scopes')).toHaveFocus();
    fireEvent.keyDown(tab('Scan Scopes'), { key: 'ArrowDown' });
    expect(tab('Schedules')).toHaveFocus();
    fireEvent.keyDown(tab('Schedules'), { key: 'ArrowUp' });
    fireEvent.keyDown(tab('Scan Scopes'), { key: 'ArrowUp' });
    fireEvent.keyDown(tab('Scan Configurations'), { key: 'ArrowUp' });
    expect(tab('Scan History')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Scan History')).toHaveFocus();
    expect(tab('Scan Configurations')).toHaveAttribute('tabindex', '-1');
    expect(tab('Scan History')).toHaveAttribute('tabindex', '0');
    await screen.findByText('No scans have run yet');
  });

  it('keeps a visited tab mounted, with its filters, and loads it once', async () => {
    const user = userEvent.setup();
    const { drawer } = renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Schedules' }));
    await screen.findByText('Untitled schedule');
    await user.selectOptions(within(drawer).getByLabelText('Coverage'), 'ALL_DATASTORES');
    await waitFor(() => expect(paths().filter(p => p === `${BASE}/schedules`)).toHaveLength(2));
    await user.click(within(drawer).getByRole('tab', { name: 'Scan Scopes' }));
    await screen.findByText('Sample scope');
    await user.click(within(drawer).getByRole('tab', { name: 'Schedules' }));
    expect(within(drawer).getByLabelText('Coverage')).toHaveValue('ALL_DATASTORES');
    expect(paths().filter(p => p === `${BASE}/schedules`)).toHaveLength(2);
  });

  it('starts Scan Configurations on SaaS, or on Endpoint from the Endpoint tab', async () => {
    renderDrawer('endpoint');
    expect(screen.getByRole('button', { name: 'Endpoint', pressed: true })).toBeInTheDocument();
    await screen.findByText('Scan Location');
    expect(paths()).toEqual([`${BASE}/endpoint-configuration`]);
  });

  it('starts on SaaS by default', async () => {
    renderDrawer();
    expect(screen.getByRole('button', { name: 'SaaS', pressed: true })).toBeInTheDocument();
    await screen.findByText('Scan Type');
    expect(paths()).not.toContain(`${BASE}/endpoint-configuration`);
  });

  it('greys Save, shown only on Scan Configurations, and Save sends nothing', async () => {
    const user = userEvent.setup();
    const { drawer } = renderDrawer();
    await screen.findByText('Scan Type');
    const save = within(drawer).getByRole('button', { name: 'Save' });
    expectConsoleOnly(save);
    const before = get.mock.calls.length;
    await user.click(save);
    expect(get.mock.calls.length).toBe(before);
    await user.click(within(drawer).getByRole('tab', { name: 'Scan Scopes' }));
    expect(within(drawer).queryByRole('button', { name: 'Save' })).toBeNull();
    expect(within(drawer).getAllByRole('button', { name: 'Close' })).toHaveLength(2);
  });

  it('closes a detail layer on Escape before the drawer, returning focus to its row', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = renderDrawer();
    await user.click(within(drawer).getByRole('tab', { name: 'Schedules' }));
    const row = (await screen.findByText('Untitled schedule')).closest('tr') as HTMLTableRowElement;
    await user.click(row);
    const detail = await screen.findByRole('dialog', { name: 'Schedule Details' });
    await within(detail).findByText('target-store-1');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Schedule Details' })).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(row).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes from the footer Close and from the X', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = renderDrawer();
    const [x, footer] = within(drawer).getAllByRole('button', { name: 'Close' });
    await user.click(footer);
    await user.click(x);
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
