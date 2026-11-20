import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DspmStatus } from '../../../../types/dspm';
import type { MisconfigurationList } from '../../../../types/dspmMisconfigurations';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
// The export's save is stubbed; the CSV text itself is built with the real toCsv.
vi.mock('../../../../utils/csvExport', async importOriginal => ({
  ...(await importOriginal<typeof import('../../../../utils/csvExport')>()),
  downloadCsv: vi.fn(),
}));

import apiClient from '../../../../utils/apiClient';
import { downloadCsv } from '../../../../utils/csvExport';
import { ThemeProvider } from '../../../../contexts/ThemeContext';
import MisconfigurationsPage from '../MisconfigurationsPage';
import { LIST, policy } from './fixtures';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const download = downloadCsv as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

let listAnswer: () => Promise<MisconfigurationList>;

beforeEach(() => {
  listAnswer = async () => LIST;
  get.mockImplementation(async (url: string) => {
    if (url === '/api/dspm/misconfigurations') return listAnswer();
    throw new Error(`HTTP 404: unexpected ${url}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

function renderPage(status: DspmStatus = READY) {
  return render(<ThemeProvider><MisconfigurationsPage status={status} onRetry={() => {}} /></ThemeProvider>);
}

const table = () => screen.getByRole('table');
const bodyRows = () => within(table()).getAllByRole('row').slice(1);
const titles = () => bodyRows().map(row => within(row).queryAllByRole('cell')[0]?.textContent ?? '');

async function loaded() {
  renderPage();
  await screen.findByText('Bucket allows anonymous public read');
}

describe('MisconfigurationsPage', () => {
  it('renders every console element in its place, with one GET', async () => {
    await loaded();
    expect(screen.getByRole('heading', { level: 1, name: 'Misconfigurations' })).toBeInTheDocument();
    expect(screen.getByText('Last Scan:')).toBeInTheDocument();
    expect(screen.getByText('Auto-Scan runs every 24 hours')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Group by' })).toHaveDisplayValue('Select group by');
    expect(screen.getByRole('button', { name: /^Severity/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Status/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'More filters' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show analytics' })).toHaveAttribute('aria-pressed', 'true');
    for (const name of [
      'Total Affected Resources', 'Open Misconfigurations', 'Resolved Misconfigurations', 'Open Misconfigured Resources', 'Category',
    ]) {
      expect(screen.getByRole('group', { name }), name).toHaveAttribute('aria-disabled', 'true');
    }
    expect(within(table()).getAllByRole('columnheader').map(th => th.textContent)).toEqual([
      'Misconfiguration', 'Severity', 'Status', 'Affected Resources', 'Sensitives', 'Labels', 'Frameworks', 'Category',
    ]);
    expect(screen.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 8');
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument();
    expect(screen.getByText('Showing 1 - 3 of 3')).toBeInTheDocument();
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/api/dspm/misconfigurations');
  });

  it('lists the rows in the backend order with the Upwind badge on Upwind policies only', async () => {
    await loaded();
    expect(titles().map(text => text.replace(/Powered by Upwind$/, ''))).toEqual([
      'Bucket allows anonymous public read', 'Object access logging is turned off', 'Backups are not encrypted',
    ]);
    expect(bodyRows().map(row => within(row).queryByText('Powered by Upwind') !== null)).toEqual([true, true, false]);
    const first = within(bodyRows()[0]);
    const cells = first.getAllByRole('cell');
    expect(first.getByText('High')).toBeInTheDocument();
    expect(cells[2]).toHaveTextContent(/^Open$/);
    expect(first.getByText('4')).toBeInTheDocument();
    expect(first.getByText('4.7K')).toHaveAttribute('title', '4,700');
    expect(first.getByText('Personal')).toBeInTheDocument();
    expect(first.getByText('SOC2')).toHaveAttribute('title', 'SOC2');
    expect(first.getByText('NIST')).toBeInTheDocument();
    expect(cells[7]).toHaveTextContent(/^-$/);
    // One "+ 1" after the first label, one after the first two frameworks.
    expect(first.getAllByText('+ 1').map(badge => badge.getAttribute('title'))).toEqual(['IT & Security', 'GDPR']);
    // Every row is Open; no row has a category.
    expect(bodyRows().map(row => within(row).getAllByRole('cell')[2].textContent)).toEqual(['Open', 'Open', 'Open']);
    expect(bodyRows().map(row => within(row).getAllByRole('cell')[7].textContent)).toEqual(['-', '-', '-']);
  });

  it('greys the cards, the chart and Category with "Disabled by admin" and shows none of the list totals', async () => {
    await loaded();
    const greyed = [
      'Total Affected Resources', 'Open Misconfigurations', 'Resolved Misconfigurations', 'Open Misconfigured Resources', 'Category',
    ].map(name => screen.getByRole('group', { name }));
    for (const region of greyed) {
      expect(region).toHaveAttribute('aria-disabled', 'true');
      // No figure anywhere; the chart's range name is the only text with a digit.
      expect(region.textContent?.replace('Last 30 days', '')).not.toMatch(/\d/);
      expect(within(region).queryByRole('button')).toBeNull();
      fireEvent.mouseEnter(region);
      expect(screen.getByRole('tooltip', { hidden: false })).toHaveTextContent('Disabled by admin');
      fireEvent.mouseLeave(region);
    }
    expect(within(screen.getByRole('group', { name: 'Open Misconfigured Resources' })).getByText('Last 30 days')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Last Scan time' })).toHaveTextContent(/^-$/);
    for (const figure of ['4242', '4,242', '7171', '7,171', '1313', '2626', '3939', '5252', '88']) {
      expect(screen.queryByText(figure), figure).toBeNull();
    }
  });

  it('hides and shows the cards with the chart button', async () => {
    const user = userEvent.setup();
    await loaded();
    const toggle = screen.getByRole('button', { name: 'Show analytics' });
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    for (const name of ['Total Affected Resources', 'Open Misconfigured Resources', 'Category']) {
      expect(screen.queryByRole('group', { name }), name).toBeNull();
    }
    await user.click(toggle);
    expect(screen.getByRole('group', { name: 'Open Misconfigurations' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Category' })).toBeInTheDocument();
  });

  it('greys Run Scan with "Disabled by admin" and sends nothing', async () => {
    await loaded();
    const runScan = screen.getByRole('button', { name: 'Run Scan' });
    expect(runScan).toHaveAttribute('aria-disabled', 'true');
    fireEvent.mouseEnter(runScan);
    expect(screen.getByRole('tooltip')).toHaveTextContent('Disabled by admin');
    fireEvent.click(runScan);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('searches titles and filters by Severity and the More filters', async () => {
    const user = userEvent.setup();
    await loaded();
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'LOGGING');
    expect(titles()).toHaveLength(1);
    expect(titles()[0]).toContain('Object access logging is turned off');
    await user.clear(screen.getByRole('searchbox', { name: 'Search' }));

    await user.click(screen.getByRole('button', { name: /^Severity/ }));
    const severity = screen.getByRole('dialog', { name: 'Severity' });
    expect(within(severity).getAllByRole('checkbox').length).toBe(3);   // Select all, High, Medium
    await user.click(within(severity).getByRole('checkbox', { name: /High/ }));
    expect(titles()).toHaveLength(1);
    expect(titles()[0]).toContain('Bucket allows anonymous public read');
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: /^Severity/ }));
    await user.click(within(screen.getByRole('dialog', { name: 'Severity' })).getByRole('button', { name: 'Clear' }));
    await user.keyboard('{Escape}');
    expect(titles()).toHaveLength(3);

    await user.click(screen.getByRole('button', { name: /^Status/ }));
    const status = screen.getByRole('dialog', { name: 'Status' });
    expect(within(status).getAllByRole('checkbox').map(box => box.closest('label')?.textContent)).toEqual(['Select all', 'Open 3']);
    await user.click(within(status).getByRole('checkbox', { name: /Open/ }));
    expect(titles()).toHaveLength(3);
    await user.keyboard('{Escape}');

    await user.click(screen.getByRole('button', { name: 'More filters' }));
    const more = screen.getByRole('dialog', { name: 'More filters' });
    expect(within(within(more).getByRole('list', { name: 'Filters' })).getAllByRole('button').map(b => b.textContent?.trim()))
      .toEqual(['Source', 'Category', 'Framework', 'Cloud Account', 'SaaS Account']);
    for (const name of ['Category', 'SaaS Account']) {
      const greyed = within(more).getByRole('button', { name });
      expect(greyed).toHaveAttribute('aria-disabled', 'true');
      fireEvent.mouseEnter(greyed);
      expect(within(more).getByRole('tooltip')).toHaveTextContent('Disabled by admin');
      fireEvent.mouseLeave(greyed);
    }
    await user.click(within(more).getByRole('button', { name: 'Cloud Account' }));
    await user.click(within(more).getByRole('checkbox', { name: /example-stg/ }));
    expect(titles()).toHaveLength(1);
    expect(titles()[0]).toContain('Object access logging is turned off');
    await user.click(within(more).getByRole('button', { name: 'Source' }));
    await user.click(within(more).getByRole('checkbox', { name: /Matters/ }));
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('draws a header per group with Group By', async () => {
    const user = userEvent.setup();
    await loaded();
    const groupBy = screen.getByRole('combobox', { name: 'Group by' });
    expect(within(groupBy).getAllByRole('option').map(o => o.textContent))
      .toEqual(['Select group by', 'Severity', 'Status', 'Category', 'Frameworks']);
    await user.selectOptions(groupBy, 'severity');
    const headers = () => within(table()).getAllByRole('columnheader').slice(8).map(th => th.textContent);
    expect(headers()).toEqual(['High 1', 'Medium 2']);
    await user.selectOptions(groupBy, 'status');
    expect(headers()).toEqual(['Open 3']);
    await user.selectOptions(groupBy, 'category');
    expect(headers()).toEqual(['- 3']);
    await user.selectOptions(groupBy, 'framework');
    expect(headers()).toEqual(['GDPR 1', 'NIST 2', 'SOC2 1', '- 1']);
    expect(screen.getByText('Showing 1 - 5 of 5')).toBeInTheDocument();
  });

  it('hides a column through Columns and exports the filtered rows with the visible columns', async () => {
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByRole('button', { name: /Columns/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Frameworks' }));
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 7');
    expect(within(table()).queryByRole('columnheader', { name: 'Frameworks' })).toBeNull();
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'bucket');
    await user.click(screen.getByRole('button', { name: 'Export' }));
    expect(download).toHaveBeenCalledTimes(1);
    const [fileName, csv] = download.mock.calls[0];
    expect(fileName).toMatch(/^misconfigurations-\d{4}-\d{2}-\d{2}\.csv$/);
    expect(csv.split('\r\n')).toEqual([
      'Misconfiguration,Severity,Status,Affected Resources,Sensitives,Labels,Category',
      'Bucket allows anonymous public read,High,Open,4,4700,"Personal, IT & Security",',
    ]);
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('opens a row on click and on Enter', async () => {
    get.mockImplementation(async (url: string) => {
      if (url === '/api/dspm/misconfigurations') return LIST;
      return new Promise(() => {});   // the drawer stays loading; MisconfigurationDrawer.test.tsx covers it
    });
    const user = userEvent.setup();
    await loaded();
    bodyRows()[1].focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: 'Object access logging is turned off' })).toBeInTheDocument();
    expect(get).toHaveBeenLastCalledWith('/api/dspm/misconfigurations/policies/p-00000000000000b2');
  });

  it('shows the error copy with Retry, and loads again on Retry', async () => {
    let fail = true;
    listAnswer = async () => {
      if (fail) throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}');
      return LIST;
    };
    const user = userEvent.setup();
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Matters is not responding right now. Try again shortly.');
    expect(screen.queryByText('No misconfigurations found.')).toBeNull();
    fail = false;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Bucket allows anonymous public read')).toBeInTheDocument();
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('says when the backend stopped at its datastore cap', async () => {
    listAnswer = async () => ({ ...LIST, capped: true, datastoresRead: 500 });
    await loaded();
    expect(screen.getByRole('status')).toHaveTextContent('Showing misconfigurations of the first 500 datastores.');
  });

  it('says so when the project has no misconfiguration', async () => {
    listAnswer = async () => ({ ...LIST, rows: [] });
    renderPage();
    expect(await screen.findByText('No misconfigurations found.')).toBeInTheDocument();
  });

  it('pages 10 rows at a time', async () => {
    listAnswer = async () => ({
      ...LIST,
      rows: Array.from({ length: 12 }, (_, i) => policy({ policyId: `rule_${i}`, title: `Rule ${String(i).padStart(2, '0')}` })),
    });
    renderPage();
    await screen.findByText('Rule 00');
    expect(bodyRows()).toHaveLength(10);
    expect(screen.getByText('Showing 1 - 10 of 12')).toBeInTheDocument();
  });

  it('explains a workspace that is not ready instead of loading', async () => {
    renderPage({ ...READY, ready: false, configured: false, reason: 'NOT_CONFIGURED' });
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(get).not.toHaveBeenCalled();
  });
});
