import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import type { DspmPage, DspmStatus } from '../../../../types/dspm';
import type { DatastoreFilterOptions, DatastoreKpis, DatastoreRow } from '../../../../types/dspmDatastores';
import type { DatastoreTabSlug } from '../../../../constants/xamlensTabs';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import DatastoresPage from '../DatastoresPage';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

// ─── synthetic data ─────────────────────────────────────────────────────────────────────────────

function row(over: Partial<DatastoreRow> & Pick<DatastoreRow, 'id' | 'name' | 'category'>): DatastoreRow {
  return {
    sourceId: null, subtype: null, subtypeLabel: null,
    risk: { score: null, level: null, levelLabel: null },
    sensitives: { recordCount: 0, typeLabels: [] },
    source: { provider: null, providerLabel: null, accountLabel: null, region: null },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

const CLOUD = row({
  id: 'cloud-asset-1', sourceId: '0123456789abcdef01234567', name: 'orders-archive', category: 'cloud',
  subtype: 'S3', subtypeLabel: 'S3',
  risk: { score: 8.44, level: 'CRITICAL', levelLabel: 'Critical' },
  sensitives: { recordCount: 1_559, typeLabels: ['Personal'] },
  source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
});
const SAAS = row({
  id: 'saas-asset-1', sourceId: 'abcdefabcdefabcdefabcdef', name: 'Pat Example', category: 'saas',
  subtype: 'GMAIL', subtypeLabel: 'Gmail',
  risk: { score: null, level: 'HIGH', levelLabel: 'High' },
  source: { provider: 'GOOGLE_WORKSPACE', providerLabel: 'Google Workspace', accountLabel: 'example-workspace', region: null },
});
const ENDPOINT = row({
  id: 'endpoint-asset-1', sourceId: null, name: 'build-laptop-07', category: 'endpoint',
  subtype: 'MAC', subtypeLabel: 'macOS',
  risk: { score: null, level: 'MEDIUM', levelLabel: 'Medium' },
  sensitives: { recordCount: 6, typeLabels: ['Personal'] },
  source: { provider: 'ENDPOINT_AGENT', providerLabel: 'Endpoint Agent', accountLabel: null, region: null },
  agent: { serialNumber: 'SN-TEST-0007', status: 'OFFLINE', statusLabel: 'Offline', version: '9.9.1' },
});
const ON_PREM = row({
  id: 'onprem-asset-1', sourceId: 'fedcba9876543210fedcba98', name: 'ledger_db', category: 'on_prem',
  subtype: 'SELF_HOSTED_POSTGRES', subtypeLabel: 'PostgreSQL',
  scan: { status: 'FAILED', statusLabel: 'Failed', displayAt: null },
  source: { provider: 'SELF_HOSTED_POSTGRES', providerLabel: 'PostgreSQL (self-hosted)', accountLabel: 'ledger_db', region: 'self-hosted' },
});
const ROWS = [CLOUD, SAAS, ENDPOINT, ON_PREM];

const page = (rows: DatastoreRow[], over: Partial<DspmPage<DatastoreRow>> = {}): DspmPage<DatastoreRow> =>
  ({ data: rows, page: 1, pageSize: 10, total: rows.length, totalPages: 1, ...over });

const kpis = (tab: string): DatastoreKpis => ({
  tab,
  tabCounts: { all: 40, cloud: 20, saas: 12, endpoint: 5, on_prem: 3 },
  totalSensitiveRecords: { value: 896_678, trend: [{ date: '2026-10-23', value: 896_676 }, { date: '2026-11-05', value: 896_678 }] },
  assetCount: { value: 40, trend: [{ date: '2026-10-23', value: 35 }, { date: '2026-11-05', value: 40 }] },
  classified: 30, scheduled: 0, failed: 2, stopped: 1, criticalHigh: 8,
});

const PROVIDER_OPTIONS = [{ value: 'S3', label: 'S3', count: 2 }, { value: 'EBS', label: 'EBS', count: 1 }];

/** Like the vendor: an excluded provider disappears from the provider options. */
function defaultOptions(url: URL): DatastoreFilterOptions {
  const excluded = (url.searchParams.get('provider_exclude') ?? '').split(',');
  return {
    dimensions: [
      { key: 'integration_id', options: [{ value: 'aaaaaaaaaaaaaaaaaaaaaaaa', label: 'example-prod', count: 3 }], hasMore: false },
      { key: 'provider', options: PROVIDER_OPTIONS.filter(o => !excluded.includes(o.value)), hasMore: false },
      { key: 'scan_status', options: [{ value: 'CLASSIFIED', label: 'Classified', count: 3 }], hasMore: false },
    ],
  };
}

let listAnswer: (url: URL) => DspmPage<DatastoreRow>;
let optionsAnswer: (url: URL) => DatastoreFilterOptions;
let kpiAnswer: (tab: string) => DatastoreKpis | Promise<DatastoreKpis>;

const requests = (path: string) =>
  get.mock.calls.map(([url]) => new URL(String(url), 'http://localhost')).filter(u => u.pathname === path);
const listRequests = () => requests('/api/dspm/datastores');
const optionRequests = () => requests('/api/dspm/datastores/filter-options');
const kpiRequests = () => requests('/api/dspm/datastores/kpis');
const assetRequests = () =>
  get.mock.calls.map(([url]) => String(url)).filter(url => url.startsWith('/api/dspm/assets/'));
const last = (urls: URL[]) => urls[urls.length - 1];

beforeEach(() => {
  listAnswer = () => page(ROWS);
  optionsAnswer = defaultOptions;
  kpiAnswer = kpis;
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === '/api/dspm/datastores/kpis') return kpiAnswer(url.searchParams.get('tab') ?? '');
    if (url.pathname === '/api/dspm/datastores/filter-options') return optionsAnswer(url);
    if (url.pathname === '/api/dspm/datastores') return listAnswer(url);
    // The drawers stay in their loading state: their content is not under test here.
    if (url.pathname.startsWith('/api/dspm/assets/')) return new Promise(() => {});
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

function Harness() {
  const { datastoreTab } = useParams<{ datastoreTab: string }>();
  return <DatastoresPage tab={datastoreTab as DatastoreTabSlug} status={READY} onRetry={() => {}} />;
}

function renderPage(path = '/xamlens/datastores/all') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/xamlens/datastores/:datastoreTab" element={<Harness />} />
      </Routes>
    </MemoryRouter>,
  );
}

const rowOf = (text: string) => screen.getByText(text).closest('tr') as HTMLTableRowElement;
const searchBox = () => screen.getByRole('searchbox', { name: 'Search by name' });

async function openProviderDropdown(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /^Provider/ }));
  return screen.getByRole('dialog', { name: 'Provider' });
}

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('DatastoresPage', () => {
  it('shows the tab counts from the KPIs, "–" until they arrive', async () => {
    let release: (value: DatastoreKpis) => void = () => {};
    kpiAnswer = () => new Promise(resolve => { release = resolve; });
    renderPage();
    expect(screen.getByRole('tab', { name: /^All/ })).toHaveTextContent('–');
    await act(async () => { release(kpis('all')); });
    expect(screen.getByRole('tab', { name: /^All/ })).toHaveTextContent('40');
    expect(screen.getByRole('tab', { name: /^Cloud/ })).toHaveTextContent('20');
    expect(screen.getByRole('tab', { name: /^SaaS/ })).toHaveTextContent('12');
    expect(screen.getByRole('tab', { name: /^Endpoint/ })).toHaveTextContent('5');
    expect(screen.getByRole('tab', { name: /^On-Prem/ })).toHaveTextContent('3');
    expect(screen.getByRole('tab', { name: /^All/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('renders the row cells', async () => {
    renderPage();
    const cloud = await screen.findByText('orders-archive').then(el => el.closest('tr') as HTMLTableRowElement);
    expect(within(cloud).getByText('8.4')).toBeInTheDocument();
    expect(within(cloud).getByText('Critical')).toBeInTheDocument();
    expect(within(cloud).getByText('1.56K')).toBeInTheDocument();
    expect(within(cloud).getByText('Personal')).toBeInTheDocument();
    expect(within(cloud).getByText('example-prod')).toBeInTheDocument();
    expect(within(cloud).getByText('eu-west-1')).toBeInTheDocument();
    const onPrem = rowOf('PostgreSQL').closest('tr') as HTMLTableRowElement;
    expect(within(onPrem).getByText('Failed')).toBeInTheDocument();
    expect(within(onPrem).getByText('0')).toBeInTheDocument();
    expect(within(onPrem).getByText('self-hosted')).toBeInTheDocument();
  });

  it('resets search, filters and page when the tab changes', async () => {
    const user = userEvent.setup();
    listAnswer = url => page(ROWS, { total: 25, totalPages: 3, page: Number(url.searchParams.get('page')) });
    renderPage();
    await screen.findByText('orders-archive');

    fireEvent.change(searchBox(), { target: { value: 'orders' } });
    await waitFor(() => expect(last(listRequests()).searchParams.get('q')).toBe('orders'));

    const dropdown = await openProviderDropdown(user);
    await user.click(await within(dropdown).findByRole('checkbox', { name: /^S3/ }));
    await waitFor(() => expect(last(listRequests()).searchParams.get('provider')).toBe('S3'));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Provider' })).toBeNull();

    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(last(listRequests()).searchParams.get('page')).toBe('2'));

    await user.click(screen.getByRole('tab', { name: /^Cloud/ }));
    await waitFor(() => expect(last(listRequests()).searchParams.get('tab')).toBe('cloud'));
    const first = last(listRequests());
    expect(first.searchParams.get('page')).toBe('1');
    expect(first.searchParams.get('sort')).toBe('-severity');
    expect(first.searchParams.has('q')).toBe(false);
    expect(first.searchParams.has('provider')).toBe(false);
    expect(searchBox()).toHaveValue('');
    expect(screen.getByRole('button', { name: /^Provider/ })).toHaveTextContent(/^Provider\s*$/);
    expect(screen.getByRole('tab', { name: /^Cloud/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('keeps the KPI strip independent of filters and search', async () => {
    const user = userEvent.setup();
    const { container } = renderPage();
    await screen.findByText('orders-archive');
    const card = (id: string) => container.querySelector(`[data-kpi="${id}"]`) as HTMLElement;
    await waitFor(() => expect(card('totalSensitiveRecords')).toHaveTextContent('896,678'));
    expect(card('totalSensitiveRecords')).toHaveTextContent('+1%');
    expect(card('assetCount')).toHaveTextContent('40');
    expect(card('assetCount')).toHaveTextContent('+5');
    expect(card('failedStopped')).toHaveTextContent('3');
    expect(card('criticalHigh')).toHaveTextContent('8');

    fireEvent.change(searchBox(), { target: { value: 'orders' } });
    const dropdown = await openProviderDropdown(user);
    await user.click(await within(dropdown).findByRole('checkbox', { name: /^S3/ }));
    await waitFor(() => expect(last(listRequests()).searchParams.get('provider')).toBe('S3'));
    await waitFor(() => expect(last(listRequests()).searchParams.get('q')).toBe('orders'));

    expect(kpiRequests()).toHaveLength(1);
    expect(kpiRequests()[0].search).toBe('?tab=all');
    expect(card('totalSensitiveRecords')).toHaveTextContent('896,678');
  });

  it('shows 6 chooser columns, disabled checkboxes and "⋯" on Endpoint', async () => {
    const user = userEvent.setup();
    listAnswer = () => page([ENDPOINT]);
    renderPage('/xamlens/datastores/endpoint');
    const device = await screen.findByText('build-laptop-07').then(el => el.closest('tr') as HTMLTableRowElement);
    expect(within(device).getByText('SN-TEST-0007')).toBeInTheDocument();
    expect(within(device).getByText('Offline')).toBeInTheDocument();
    expect(within(device).getByText('9.9.1')).toBeInTheDocument();

    const columns = screen.getByRole('button', { name: /^Columns/ });
    expect(columns).toHaveTextContent('6');
    for (const box of screen.getAllByRole('checkbox', { name: /^Select / })) {
      expect(box).toHaveAttribute('aria-disabled', 'true');
      expect(box).toHaveAttribute('aria-checked', 'false');
    }
    expect(screen.getAllByRole('checkbox', { name: /^Select / })).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'More actions' })).toHaveAttribute('aria-disabled', 'true');

    await user.click(columns);
    const menu = columns.parentElement as HTMLElement;
    const choices = within(menu).getAllByRole('checkbox');
    expect(choices).toHaveLength(6);
    expect(within(menu).getByRole('checkbox', { name: /Device/ })).toBeDisabled();
    expect(within(menu).getByRole('checkbox', { name: /Device/ })).toBeChecked();
    expect(within(menu).getByText('Always shown')).toBeInTheDocument();
    await user.click(within(menu).getByRole('checkbox', { name: /Serial Number/ }));
    expect(columns).toHaveTextContent('5');
    expect(screen.queryByText('SN-TEST-0007')).toBeNull();
  });

  it('has no "⋯" on All', async () => {
    renderPage();
    await screen.findByText('orders-archive');
    expect(screen.getByRole('button', { name: /^Columns/ })).toHaveTextContent('5');
    expect(screen.queryByRole('button', { name: 'More actions' })).toBeNull();
  });

  it('greys every console-only control, each with the Matters console tooltip', async () => {
    listAnswer = () => page([ENDPOINT]);
    renderPage('/xamlens/datastores/endpoint');
    await screen.findByText('build-laptop-07');
    const controls = [
      screen.getByRole('button', { name: 'Run Scan Now' }),
      screen.getByRole('button', { name: 'Manage Scans' }),
      screen.getByRole('button', { name: 'More actions' }),
      screen.getByRole('button', { name: 'Export' }),
      ...screen.getAllByRole('button', { name: 'Row actions' }),
      ...screen.getAllByRole('checkbox', { name: /^Select / }),
    ];
    expect(controls).toHaveLength(7);
    for (const control of controls) {
      expect(control).toHaveAttribute('aria-disabled', 'true');
      const tooltip = document.getElementById(control.getAttribute('aria-describedby') ?? '') as HTMLElement;
      expect(tooltip).not.toBeVisible();
      fireEvent.mouseEnter(control);
      expect(tooltip).toBeVisible();
      expect(tooltip).toHaveTextContent('Available in the Matters console');
      fireEvent.mouseLeave(control);
    }
    expect(assetRequests()).toHaveLength(0);
  });

  it('opens nothing for cloud and on-prem rows, which are not focusable', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('orders-archive');
    for (const name of ['orders-archive', 'ledger_db']) {
      const tr = screen.getAllByText(name)[0].closest('tr') as HTMLTableRowElement;
      expect(tr).not.toHaveAttribute('tabindex');
      await user.click(tr);
    }
    expect(assetRequests()).toHaveLength(0);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
  });

  it('opens DspmSaasDrawer for a SaaS row, with the account label', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Pat Example');
    expect(rowOf('Pat Example')).toHaveAttribute('tabindex', '0');
    await user.click(rowOf('Pat Example'));
    const drawer = await screen.findByRole('dialog', { name: 'Pat Example' });
    expect(within(drawer).getByText('example-workspace')).toBeInTheDocument();
    expect(assetRequests()).toEqual(['/api/dspm/assets/saas-asset-1']);
  });

  it('opens DspmAssetDrawer for an endpoint row', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('build-laptop-07');
    await user.click(rowOf('build-laptop-07'));
    expect(await screen.findByText('Loading asset…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
    expect(assetRequests()).toEqual(['/api/dspm/assets/endpoint-asset-1']);
  });

  it('opens a focused row on Enter, but not on Enter pressed on its ⋮', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Pat Example');
    const saas = rowOf('Pat Example');

    act(() => within(saas).getByRole('button', { name: 'Row actions' }).focus());
    await user.keyboard('{Enter}');
    expect(assetRequests()).toHaveLength(0);
    expect(screen.queryByRole('dialog')).toBeNull();

    act(() => saas.focus());
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: 'Pat Example' })).toBeInTheDocument();
    expect(assetRequests()).toEqual(['/api/dspm/assets/saas-asset-1']);
  });

  it('sends identical filter terms to the list and the filter options, Exclude included', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('orders-archive');
    const dropdown = await openProviderDropdown(user);
    await user.click(await within(dropdown).findByRole('checkbox', { name: /^S3/ }));
    await user.selectOptions(within(dropdown).getByRole('combobox', { name: 'Include or exclude' }), 'exclude');

    await waitFor(() => expect(last(listRequests()).searchParams.get('provider_exclude')).toBe('S3'));
    await waitFor(() => expect(last(optionRequests()).searchParams.get('provider_exclude')).toBe('S3'));
    const terms = (url: URL) => [...url.searchParams.entries()].filter(([key]) => !['page', 'pageSize', 'sort'].includes(key));
    expect(terms(last(listRequests()))).toEqual(terms(last(optionRequests())));
    expect(last(listRequests()).searchParams.has('provider')).toBe(false);
    expect(last(listRequests()).searchParams.get('page')).toBe('1');

    // The vendor no longer returns S3, but the excluded value stays listed and checked.
    await waitFor(() => expect(within(dropdown).getByRole('checkbox', { name: /^S3/ })).toBeChecked());
    expect(within(dropdown).getByRole('checkbox', { name: /^EBS/ })).not.toBeChecked();
  });

  it('debounces the search and normalises it', async () => {
    renderPage();
    await screen.findByText('orders-archive');
    const listBefore = listRequests().length;
    const optionsBefore = optionRequests().length;

    fireEvent.change(searchBox(), { target: { value: 'o' } });
    fireEvent.change(searchBox(), { target: { value: 'or' } });
    fireEvent.change(searchBox(), { target: { value: 'ord\t' } });
    await waitFor(() => expect(listRequests().length).toBeGreaterThan(listBefore));
    await waitFor(() => expect(optionRequests().length).toBeGreaterThan(optionsBefore));

    expect(listRequests().slice(listBefore).map(u => u.searchParams.get('q'))).toEqual(['ord']);
    expect(optionRequests().slice(optionsBefore).map(u => u.searchParams.get('q'))).toEqual(['ord']);
  });

  it('limits "Search by name" to 200 characters', async () => {
    renderPage();
    await screen.findByText('orders-archive');
    expect(searchBox()).toHaveAttribute('maxlength', '200');
  });

  it('selects at most 50 values with "Select all" and says so', async () => {
    const user = userEvent.setup();
    optionsAnswer = () => ({
      dimensions: [{
        key: 'provider',
        options: Array.from({ length: 60 }, (_, i) => ({
          value: `PROVIDER_${String(i).padStart(2, '0')}`, label: `Provider ${i}`, count: 1,
        })),
        hasMore: false,
      }],
    });
    renderPage();
    await screen.findByText('orders-archive');
    const dropdown = await openProviderDropdown(user);
    await user.click(await within(dropdown).findByRole('checkbox', { name: 'Select all' }));

    await waitFor(() => expect(last(listRequests()).searchParams.get('provider')?.split(',')).toHaveLength(50));
    expect(last(listRequests()).searchParams.get('provider')?.split(',')[0]).toBe('PROVIDER_00');
    expect(within(dropdown).getByText('Up to 50 values per filter')).toBeInTheDocument();
    expect(within(dropdown).getByRole('checkbox', { name: /^Provider 55/ })).toBeDisabled();
  });

  it('shows the error copy with Retry when the list fails', async () => {
    const user = userEvent.setup();
    listAnswer = () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); };
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    listAnswer = () => page(ROWS);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('orders-archive')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
