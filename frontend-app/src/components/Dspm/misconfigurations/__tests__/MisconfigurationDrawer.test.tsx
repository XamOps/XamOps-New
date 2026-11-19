import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DspmStatus } from '../../../../types/dspm';
import type { MisconfigurationDetail } from '../../../../types/dspmMisconfigurations';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
vi.mock('../../../../utils/csvExport', async importOriginal => ({
  ...(await importOriginal<typeof import('../../../../utils/csvExport')>()),
  downloadCsv: vi.fn(),
}));

import apiClient from '../../../../utils/apiClient';
import { downloadCsv } from '../../../../utils/csvExport';
import { ThemeProvider } from '../../../../contexts/ThemeContext';
import { formatListDate } from '../../datastores/datastoreFormat';
import MisconfigurationsPage from '../MisconfigurationsPage';
import { DETAIL, LIST } from './fixtures';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const download = downloadCsv as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};

const DETAIL_URL = `/api/dspm/misconfigurations/policies/${DETAIL.policyId}`;
let detailAnswer: () => Promise<MisconfigurationDetail>;

const calls = () => get.mock.calls.map(([url]) => String(url));
const detailCalls = () => calls().filter(url => url === DETAIL_URL);

beforeEach(() => {
  detailAnswer = async () => DETAIL;
  get.mockImplementation(async (url: string) => {
    if (url === '/api/dspm/misconfigurations') return LIST;
    if (url === DETAIL_URL) return detailAnswer();
    // The datastore drawer stays loading: DatastoreDrawer.test.tsx covers its content.
    if (url.startsWith('/api/dspm/datastores/')) return new Promise(() => {});
    throw new Error(`HTTP 404: unexpected ${url}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

const NAME = 'Bucket allows anonymous public read';
const drawer = () => screen.getByRole('dialog', { name: NAME });

async function openDrawer() {
  const user = userEvent.setup();
  render(<ThemeProvider><MisconfigurationsPage status={READY} onRetry={() => {}} /></ThemeProvider>);
  await user.click(await screen.findByText(NAME));
  await screen.findByRole('dialog', { name: NAME });
  return user;
}

async function openAffected() {
  const user = await openDrawer();
  await user.click(within(drawer()).getByRole('tab', { name: 'Affected Datastores' }));
  await within(drawer()).findByText('orders-archive');
  return user;
}

describe('MisconfigurationDrawer', () => {
  it('opens on Details beside a vertical nav, with the close X focused and one detail request', async () => {
    await openDrawer();
    expect(within(drawer()).getByRole('button', { name: 'Close' })).toHaveFocus();
    const nav = within(drawer()).getByRole('tablist', { name: 'Misconfiguration details' });
    expect(nav).toHaveAttribute('aria-orientation', 'vertical');
    expect(within(nav).getAllByRole('tab').map(tab => [tab.textContent, tab.getAttribute('aria-selected')]))
      .toEqual([['Details', 'true'], ['Affected Datastores', 'false']]);
    await within(drawer()).findByText('Misconfiguration Details');
    expect(detailCalls()).toHaveLength(1);
    expect(within(drawer()).getByRole('heading', { level: 2 })).toHaveTextContent(NAME);
    expect(within(drawer()).getByAltText('Upwind')).toBeInTheDocument();
    expect(within(drawer()).getByText('High')).toBeInTheDocument();
  });

  it('shows the policy id, description, remediation, the Bash fix and Learn More, and no left-out field', async () => {
    await openDrawer();
    const d = within(drawer());
    await d.findByText('Policy ID');
    expect(d.getByText(DETAIL.policyId)).toBeInTheDocument();
    expect(d.getByRole('button', { name: 'Copy policy ID' })).toBeInTheDocument();
    expect(d.getByText('anonymous').tagName).toBe('STRONG');
    expect(d.getByText('Remove the public grant.')).toBeInTheDocument();
    expect(d.getByText('Bash')).toBeInTheDocument();
    expect(d.getByText(DETAIL.cli!)).toBeInTheDocument();
    const learnMore = d.getByRole('link', { name: /Learn More/ });
    expect(learnMore).toHaveAttribute('href', DETAIL.learnMore);
    expect(learnMore).toHaveAttribute('target', '_blank');
    expect(learnMore).toHaveAttribute('rel', 'noopener noreferrer');
    for (const text of ['Misconfiguration ID', 'Category', 'Status', 'Open']) expect(d.queryByText(text), text).toBeNull();
  });

  it('greys Push to SIEM with "Disabled by admin" and sends nothing', async () => {
    await openDrawer();
    await within(drawer()).findByText('Policy ID');
    const siem = within(drawer()).getByRole('button', { name: /Push to SIEM/ });
    expect(siem).toHaveAttribute('aria-disabled', 'true');
    fireEvent.mouseEnter(siem);
    expect(within(drawer()).getByRole('tooltip')).toHaveTextContent('Disabled by admin');
    fireEvent.click(siem);
    expect(calls()).toEqual(['/api/dspm/misconfigurations', DETAIL_URL]);
  });

  it('lists the affected datastores with search, Account Name, Columns 7, Export and Push to Jira', async () => {
    const user = await openAffected();
    const d = within(drawer());
    const table = d.getByRole('table');
    expect(within(table).getAllByRole('columnheader').map(th => th.textContent)).toEqual([
      'Affected Datastore', 'Datastore Type', 'Account Name', 'Sensitives', 'Labels', 'Last scanned time', 'Jira',
    ]);
    expect(d.getByText('Showing 1 - 3 of 3')).toBeInTheDocument();
    expect(d.getByText(formatListDate(1_790_000_000))).toBeInTheDocument();
    expect(d.getByText('1.56K')).toHaveAttribute('title', '1,559');
    // Only rows with a 24-hex source id are links.
    expect(d.getByRole('button', { name: 'orders-archive' })).toBeInTheDocument();
    expect(d.queryByRole('button', { name: 'ledger_db' })).toBeNull();
    const jira = d.getAllByRole('button', { name: 'Push to Jira' });
    expect(jira).toHaveLength(3);
    expect(jira[0]).toHaveAttribute('aria-disabled', 'true');
    for (const text of ['Status', 'Discovered time']) expect(d.queryByText(text), text).toBeNull();

    await user.type(d.getByRole('searchbox', { name: 'Search' }), 'EXPORTS');
    expect(d.getByText('Showing 1 - 1 of 1')).toBeInTheDocument();
    await user.clear(d.getByRole('searchbox', { name: 'Search' }));

    await user.click(d.getByRole('button', { name: /Account Name/ }));
    const filter = within(screen.getByRole('dialog', { name: 'Account Name' }));
    expect(filter.getAllByRole('checkbox').map(box => box.closest('label')?.textContent)).toEqual([
      'Select all', 'example-prod 1', 'example-stg 1', 'ledger_db 1',
    ]);
    await user.click(filter.getByRole('checkbox', { name: /example-prod/ }));
    await user.click(filter.getByRole('checkbox', { name: /ledger_db/ }));
    await user.keyboard('{Escape}');
    expect(d.getByText('Showing 1 - 2 of 2')).toBeInTheDocument();

    await user.click(d.getByRole('button', { name: /Columns/ }));
    expect(d.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 7');
    await user.click(d.getByRole('checkbox', { name: 'Labels' }));
    await user.keyboard('{Escape}');
    expect(d.getByRole('button', { name: /Columns/ })).toHaveTextContent('Columns 6');

    await user.click(d.getByRole('button', { name: 'Export' }));
    expect(download).toHaveBeenCalledTimes(1);
    const [fileName, csv] = download.mock.calls[0];
    expect(fileName).toMatch(new RegExp(`^misconfiguration-${DETAIL.policyId}-affected-datastores-\\d{4}-\\d{2}-\\d{2}\\.csv$`));
    expect(csv.split('\r\n')).toEqual([
      'Affected Datastore,Datastore Type,Account Name,Sensitives,Last scanned time',
      `orders-archive,S3,example-prod,1559,"${formatListDate(1_790_000_000)}"`,
      'ledger_db,PostgreSQL,ledger_db,0,',
    ]);
    expect(drawer()).toBeInTheDocument();
  });

  it('hands off to the datastore drawer and comes back on Affected Datastores', async () => {
    const user = await openAffected();
    await user.click(within(drawer()).getByRole('button', { name: 'orders-archive' }));
    const datastore = await screen.findByRole('dialog', { name: 'orders-archive' });
    expect(screen.queryByRole('dialog', { name: NAME })).toBeNull();
    expect(calls()).toContain('/api/dspm/datastores/0123456789abcdef01234567/overview');
    expect(screen.getAllByRole('dialog')).toHaveLength(1);

    await user.click(within(datastore).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'orders-archive' })).toBeNull();
    const back = await screen.findByRole('dialog', { name: NAME });
    expect(within(back).getByRole('tab', { name: 'Affected Datastores' })).toHaveAttribute('aria-selected', 'true');
    expect(await within(back).findByText('orders-archive')).toBeInTheDocument();
  });

  it('closes an open filter on the first Escape and the drawer on the second', async () => {
    const user = await openAffected();
    await user.click(within(drawer()).getByRole('button', { name: /Account Name/ }));
    expect(screen.getByRole('dialog', { name: 'Account Name' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Account Name' })).toBeNull();
    expect(drawer()).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: NAME })).toBeNull());
  });

  it('says a policy that is gone is no longer reported, without Retry', async () => {
    detailAnswer = async () => {
      throw new Error('HTTP 404: {"code":"ASSET_NOT_FOUND","message":"Misconfiguration not found"}');
    };
    await openDrawer();
    const alert = await within(drawer()).findByRole('alert');
    expect(alert).toHaveTextContent('This misconfiguration is no longer reported.');
    expect(within(alert).queryByRole('button', { name: 'Retry' })).toBeNull();
  });
});
