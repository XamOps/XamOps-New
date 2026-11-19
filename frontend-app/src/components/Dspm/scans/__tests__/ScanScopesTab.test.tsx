import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ScanScope } from '../../../../types/dspmScans';
vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../utils/apiClient';
import ScanScopesTab from '../ScanScopesTab';
import { formatListDate } from '../../datastores/datastoreFormat';
import { T0, expectConsoleOnly, page, scope, targets } from './scanTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const BASE = '/api/dspm/scan-management/scan-scopes';
const SCOPE = scope();

let rows: ScanScope[];
const listRequests = () => get.mock.calls
  .map(([url]) => new URL(String(url), 'http://localhost'))
  .filter(url => url.pathname === BASE);

beforeEach(() => {
  rows = [];
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === BASE) return page(rows);
    if (url.pathname === `${BASE}/${SCOPE.id}`) return { scope: SCOPE, targets: targets(2), targetsTotal: 3 };
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('ScanScopesTab', () => {
  it('says "No scan scopes yet" and greys Create Scope and Export (AC14)', async () => {
    const user = userEvent.setup();
    render(<ScanScopesTab />);
    expect(await screen.findByText('No scan scopes yet')).toBeInTheDocument();
    expect(screen.getByText('Create a scope to choose which datastores or devices a scan covers, then reuse it in any schedule.')).toBeInTheDocument();
    const before = get.mock.calls.length;
    for (const name of ['Create Scope', 'Export']) {
      const control = screen.getByRole('button', { name });
      expectConsoleOnly(control);
      await user.click(control);
    }
    expect(get.mock.calls.length).toBe(before);
  });

  it('lists a scope with its mode and greys every ⋮ (AC14)', async () => {
    const user = userEvent.setup();
    rows = [SCOPE, scope({ id: '0d0d0d0d0d0d0d0d0d0d0d02', name: 'Laptops', platform: 'endpoint', platformLabel: 'Endpoint', scopeMode: 'DYNAMIC_RULES', totalDatastores: null, updatedAt: null })];
    render(<ScanScopesTab />);
    const row = (await screen.findByText('Sample scope')).closest('tr') as HTMLTableRowElement;
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual(['Scope Name', 'Environment', 'Targets', 'Last Modified', 'Actions']);
    expect(within(row).getByText('Static')).toBeInTheDocument();
    expect(within(row).getByText('Cloud')).toBeInTheDocument();
    expect(within(row).getByText('3')).toBeInTheDocument();
    expect(within(row).getByText(formatListDate(T0))).toBeInTheDocument();
    expect(screen.getByText('Dynamic Rules')).toBeInTheDocument();
    const menus = screen.getAllByRole('button', { name: 'Scope actions' });
    expect(menus).toHaveLength(2);
    const before = get.mock.calls.length;
    for (const menu of menus) {
      expectConsoleOnly(menu);
      await user.click(menu);
    }
    expect(get.mock.calls.length).toBe(before);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('filters by Environment and search, and shows the no-match state', async () => {
    const user = userEvent.setup();
    render(<ScanScopesTab />);
    await screen.findByText('No scan scopes yet');
    await user.selectOptions(screen.getByLabelText('Environment'), 'endpoint');
    await waitFor(() => expect(listRequests()[listRequests().length - 1].searchParams.get('platform')).toBe('endpoint'));
    expect(await screen.findByText('No scopes match these filters')).toBeInTheDocument();
    expect(within(screen.getByLabelText('Environment')).getAllByRole('option').map(o => o.textContent)).toEqual(['All', 'Cloud', 'On-Prem', 'Endpoint']);
  });

  it('opens the scope detail with its targets', async () => {
    const user = userEvent.setup();
    rows = [SCOPE];
    render(<ScanScopesTab />);
    await user.click(await screen.findByText('Sample scope'));
    const detail = await screen.findByRole('dialog', { name: 'Sample scope' });
    expect(await within(detail).findByText('target-store-1')).toBeInTheDocument();
    expect(within(detail).getByText('Showing 2 of 3 targets')).toBeInTheDocument();
  });
});
