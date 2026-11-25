import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { DspmStatus } from '../../../../types/dspm';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import type { DashboardEnvironment, DspmDashboard } from '../../../../types/dspmPages';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
// The world map fetches its outline from a CDN; its own test covers it.
vi.mock('../RegionMap', () => ({ default: () => <div data-testid="region-map" /> }));

import apiClient from '../../../../utils/apiClient';
import DashboardPage from '../DashboardPage';
import { ENVIRONMENT_TOOLTIPS, TOP_RISKY_TOOLTIP } from '../dashboardModel';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

// ─── synthetic data ─────────────────────────────────────────────────────────────────────────────

function row(over: Partial<DatastoreRow> & Pick<DatastoreRow, 'id' | 'name' | 'category'>): DatastoreRow {
  return {
    sourceId: null, subtype: null, subtypeLabel: null,
    risk: { score: null, level: 'HIGH', levelLabel: 'High' },
    sensitives: { recordCount: 1, typeLabels: [] },
    source: { provider: null, providerLabel: null, accountLabel: null, region: null },
    scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: null },
    agent: null,
    ...over,
  };
}

const CLOUD_ROW = row({
  id: 'cloud-1', sourceId: '0123456789abcdef01234567', name: 'invoices-bucket', category: 'cloud', subtype: 'S3',
  sensitives: { recordCount: 3_900, typeLabels: ['Financial', 'Personal'] },
  source: { provider: 'S3', providerLabel: 'S3', accountLabel: 'example-prod', region: 'eu-west-1' },
});
const SAAS_ROW = row({
  id: 'saas-1', sourceId: 'abcdefabcdefabcdefabcdef', name: 'Sam Example', category: 'saas', subtype: 'GMAIL',
  sensitives: { recordCount: 2_400, typeLabels: ['Personal'] },
  source: { provider: 'GOOGLE_WORKSPACE', providerLabel: 'Google Workspace', accountLabel: null, region: null },
});
const ENDPOINT_ROW = row({
  id: 'endpoint-1', sourceId: '0a1b2c3d4e5f0a1b2c3d4e5f', name: 'design-laptop-02', category: 'endpoint', subtype: 'MAC',
  sensitives: { recordCount: 12, typeLabels: [] },
});
const BAD_ID_ROW = row({ id: 'cloud-2', sourceId: 'not-a-source-id', name: 'orphan-volume', category: 'cloud' });

const env = (over: Partial<DashboardEnvironment> & Pick<DashboardEnvironment, 'id'>): DashboardEnvironment => ({
  datastores: 3, sensitiveRecords: 0, recordsTrend: [], connected: true, filter: 'provider', subCategories: [],
  partial: false, ...over,
});

const DASHBOARD: DspmDashboard = {
  environments: [
    env({
      id: 'cloud', sensitiveRecords: 4_210,
      subCategories: [
        { key: 'S3', label: 'S3', sensitiveRecords: 3_900, datastores: 2 },
        { key: 'RDS', label: 'RDS', sensitiveRecords: 310, datastores: 1 },
        { key: 'BIGQUERY', label: 'BigQuery', sensitiveRecords: 0, datastores: 1 },
      ],
    }),
    env({
      id: 'saas', sensitiveRecords: 52_400, filter: null,
      subCategories: [
        { key: 'GOOGLE_WORKSPACE', label: 'Google Drive', sensitiveRecords: 50_000, datastores: 4 },
        { key: 'GMAIL', label: 'Gmail', sensitiveRecords: 2_400, datastores: 4 },
      ],
    }),
    env({ id: 'endpoint', datastores: 0, connected: false, filter: 'os' }),
    env({
      id: 'on_prem', sensitiveRecords: 1_250, partial: true,
      subCategories: [{ key: 'SELF_HOSTED_POSTGRES', label: 'PostgreSQL', sensitiveRecords: 1_250, datastores: 1 }],
    }),
  ],
  topRisky: {
    all: [CLOUD_ROW, SAAS_ROW, ENDPOINT_ROW, BAD_ID_ROW],
    cloud: [CLOUD_ROW, BAD_ID_ROW],
    saas: [SAAS_ROW],
    endpoint: [],
    on_prem: [],
  },
  regions: [
    { region: 'eu-west-1', datastores: 3, scannedDatastores: 2, sensitiveLabels: ['Financial', 'Personal'] },
  ],
};

let dashboardAnswer: () => Promise<DspmDashboard>;
const DRAWER_PATH = /^\/api\/dspm\/datastores\/[0-9a-f]{24}\//;
const drawerRequests = () => get.mock.calls.map(([url]) => String(url)).filter(url => DRAWER_PATH.test(url));

beforeEach(() => {
  dashboardAnswer = async () => DASHBOARD;
  get.mockImplementation(async (url: string) => {
    if (url === '/api/dspm/dashboard') return dashboardAnswer();
    // The drawers stay in their loading state: their content is not under test here.
    if (DRAWER_PATH.test(url)) return new Promise(() => {});
    throw new Error(`HTTP 404: unexpected ${url}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

const Probe = () => {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}{location.search}</div>;
};

function renderPage(status: DspmStatus = READY) {
  return render(
    <MemoryRouter initialEntries={['/xamlens/dashboard']}>
      <Routes>
        <Route path="/xamlens/dashboard" element={<DashboardPage status={status} onRetry={() => {}} />} />
        <Route path="/xamlens/datastores/:datastoreTab" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  );
}

const card = (name: string) => screen.getByRole('article', { name });
const topRisky = () => screen.getByText('Top Risky Datastores').closest('div.rounded-2xl') as HTMLElement;

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('DashboardPage', () => {
  it('shows the header with Classification pressed, and DAM and the gear greyed', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument();
    const view = screen.getByRole('group', { name: 'Dashboard view' });
    expect(within(view).getByRole('button', { name: 'Classification' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(view).getByRole('button', { name: 'DAM' })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('button', { name: 'Manage dashboard' })).toHaveAttribute('aria-disabled', 'true');
    await screen.findByRole('article', { name: 'Cloud' });
  });

  it('renders one card per environment in response order, with the big number and the bars', async () => {
    renderPage();
    await screen.findByRole('article', { name: 'Cloud' });
    expect(screen.getAllByRole('article').map(a => within(a).getByRole('heading', { level: 3 }).textContent))
      .toEqual(['Cloud', 'SaaS', 'Endpoint', 'On Prem']);
    const cloud = card('Cloud');
    const total = within(cloud).getByText('4.2K');
    expect(total).toHaveAttribute('title', '4,210');
    expect(within(cloud).getByText('Sensitive Records')).toBeInTheDocument();
    const bars = within(within(cloud).getByRole('list', { name: 'Cloud breakdown' })).getAllByRole('button');
    expect(bars.map(b => b.getAttribute('aria-label'))).toEqual([
      'S3: 3,900 sensitive records. Show these datastores',
      'RDS: 310 sensitive records. Show these datastores',
      'BigQuery: 0 sensitive records. Show these datastores',
    ]);
    expect(within(card('SaaS')).getByText('52.4K')).toBeInTheDocument();
    expect(within(card('SaaS')).getByText('Google Drive')).toBeInTheDocument();
  });

  it('gives each card the console tooltip on its info icon', async () => {
    renderPage();
    await screen.findByRole('article', { name: 'Cloud' });
    expect(within(card('Cloud')).getByRole('img', { name: ENVIRONMENT_TOOLTIPS.cloud })).toHaveAttribute('title', ENVIRONMENT_TOOLTIPS.cloud);
    expect(within(card('On Prem')).getByRole('img', { name: ENVIRONMENT_TOOLTIPS.on_prem })).toBeInTheDocument();
    expect(within(topRisky()).getByRole('img', { name: TOP_RISKY_TOOLTIP })).toBeInTheDocument();
  });

  it('opens the filtered Datastores tab from a bar', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: /^RDS:/ }));
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/datastores/cloud?provider=RDS');
  });

  it('uses the on-prem slug for an On Prem bar', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: /^PostgreSQL:/ }));
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/datastores/on-prem?provider=SELF_HOSTED_POSTGRES');
  });

  it('draws SaaS bars without a link: drive types open no filter', async () => {
    renderPage();
    await screen.findByRole('article', { name: 'SaaS' });
    const saas = within(card('SaaS')).getByRole('list', { name: 'SaaS breakdown' });
    expect(within(saas).getAllByRole('listitem')).toHaveLength(2);
    expect(within(saas).queryAllByRole('button')).toHaveLength(0);
  });

  it('shows the Not Connected card with a greyed Connect', async () => {
    renderPage();
    const endpoint = await screen.findByRole('article', { name: 'Endpoint' });
    expect(within(endpoint).getByText('Endpoint Not Connected')).toBeInTheDocument();
    expect(within(endpoint).getByText('Connect to see Endpoint Data')).toBeInTheDocument();
    expect(within(endpoint).getByRole('button', { name: 'Connect' })).toHaveAttribute('aria-disabled', 'true');
    expect(within(endpoint).queryByText('Sensitive Records')).toBeNull();
    expect(get.mock.calls.map(([url]) => url)).toEqual(['/api/dspm/dashboard']);
  });

  it('notes a partial environment', async () => {
    renderPage();
    await screen.findByRole('article', { name: 'On Prem' });
    expect(within(card('On Prem')).getByText('Based on the first 1,000 datastores.')).toBeInTheDocument();
    expect(within(card('Cloud')).queryByText('Based on the first 1,000 datastores.')).toBeNull();
  });

  it('shows Active Sensitive Regions under the cards, and its View All opens the region-filtered Cloud tab', async () => {
    const user = userEvent.setup();
    renderPage();
    const widget = await screen.findByRole('region', { name: 'Active Sensitive Regions' });
    expect(within(widget).getByText('(Showing 1 Regions)')).toBeInTheDocument();
    expect(within(widget).getByRole('tab', { name: 'Ireland' })).toHaveAttribute('aria-selected', 'true');
    expect(within(widget).getByTestId('region-map')).toBeInTheDocument();
    await user.click(within(widget).getByRole('link', { name: 'View All datastores in Ireland' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/xamlens/datastores/cloud?region=eu-west-1');
  });

  it('lists the Top Risky rows of the selected tab, with "View All" only off All', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('invoices-bucket');
    const widget = topRisky();
    expect(within(widget).getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true');
    expect(within(widget).queryByRole('link', { name: /View All/ })).toBeNull();
    const rows = within(widget).getAllByRole('row').slice(1);
    expect(rows.map(r => within(r).getAllByRole('cell')[0].textContent)).toEqual([
      'invoices-bucket', 'Sam Example', 'design-laptop-02', 'orphan-volume',
    ]);
    expect(within(rows[0]).getByText('3,900')).toBeInTheDocument();
    expect(within(rows[0]).getByText('Financial')).toBeInTheDocument();
    expect(within(rows[0]).getByText('+1')).toBeInTheDocument();

    await user.click(within(widget).getByRole('tab', { name: 'SaaS' }));
    expect(within(widget).getByRole('link', { name: /View All/ })).toHaveAttribute('href', '/xamlens/datastores/saas');
    expect(within(widget).getAllByRole('row')).toHaveLength(2);

    await user.click(within(widget).getByRole('tab', { name: 'On-Prem' }));
    expect(within(widget).getByRole('link', { name: /View All/ })).toHaveAttribute('href', '/xamlens/datastores/on-prem');
    expect(within(widget).getByText('No risky datastores found')).toBeInTheDocument();
  });

  it.each([
    ['invoices-bucket', 'datastore', '0123456789abcdef01234567'],
    ['Sam Example', 'saas', 'abcdefabcdefabcdefabcdef'],
    ['design-laptop-02', 'endpoint', '0a1b2c3d4e5f0a1b2c3d4e5f'],
  ])('opens %s in the %s drawer', async (name, kind, sourceId) => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByText(name));
    expect(await screen.findByRole('dialog', { name })).toBeInTheDocument();
    await waitFor(() => expect(drawerRequests().length).toBeGreaterThan(0));
    const kindOf = (url: string) => (url.includes('/saas/') ? 'saas' : url.includes('/endpoint/') ? 'endpoint' : 'datastore');
    expect(new Set(drawerRequests().map(kindOf))).toEqual(new Set([kind]));
    expect(drawerRequests().every(url => url.startsWith(`/api/dspm/datastores/${sourceId}/`))).toBe(true);
  });

  it('opens a focused row on Enter, and never a row without a valid source id', async () => {
    const user = userEvent.setup();
    renderPage();
    const orphan = (await screen.findByText('orphan-volume')).closest('tr')!;
    expect(orphan).not.toHaveAttribute('tabindex');
    await user.click(orphan);
    expect(screen.queryByRole('dialog')).toBeNull();
    const cloud = screen.getByText('invoices-bucket').closest('tr')!;
    expect(cloud).toHaveAttribute('tabindex', '0');
    cloud.focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: 'invoices-bucket' })).toBeInTheDocument();
  });

  it('shows the error in each section with Retry, which loads again', async () => {
    const user = userEvent.setup();
    dashboardAnswer = async () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); };
    renderPage();
    const alerts = await screen.findAllByRole('alert');
    expect(alerts).toHaveLength(2);
    expect(alerts[0]).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    dashboardAnswer = async () => DASHBOARD;
    await user.click(within(alerts[0]).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('article', { name: 'Cloud' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('explains a not-ready workspace and loads nothing', () => {
    renderPage({ ...READY, ready: false, configured: false, reason: 'NOT_CONFIGURED' });
    expect(screen.getByText('Your data security workspace is being set up')).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });
});
