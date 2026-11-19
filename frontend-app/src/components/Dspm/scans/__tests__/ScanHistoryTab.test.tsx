import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ScanRun } from '../../../../types/dspmScans';
vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../utils/apiClient';
import ScanHistoryTab from '../ScanHistoryTab';
import { POLL_INTERVAL_MS } from '../../../../hooks/useServerList';
import { expectConsoleOnly, page, run, runDatastore, runDetail } from './scanTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const BASE = '/api/dspm/scan-management/scan-history';
// AC13: 8 runs, 6 Endpoint and 2 Cloud, all Success / Immediate.
const RUNS: ScanRun[] = [
  ...Array.from({ length: 6 }, (_, i) => run(i + 1)),
  run(7, { platform: 'cloud', platformLabel: 'Cloud', coverage: 'SELECTED_DATASTORES', coverageLabel: 'Selected datastores' }),
  run(8, { platform: 'cloud', platformLabel: 'Cloud', coverage: 'SELECTED_DATASTORES', coverageLabel: 'Selected datastores' }),
];

let listAnswer: (url: URL) => unknown;
const listRequests = () => get.mock.calls
  .map(([url]) => new URL(String(url), 'http://localhost'))
  .filter(url => url.pathname === BASE);
const last = () => listRequests()[listRequests().length - 1];

beforeEach(() => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  listAnswer = url => {
    const platform = url.searchParams.get('platform');
    return page(platform ? RUNS.filter(r => r.platform === platform) : RUNS);
  };
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === BASE) return listAnswer(url);
    const cloudRun = RUNS[6];
    if (url.pathname === `${BASE}/${cloudRun.id}`) return runDetail(cloudRun, { accounts: [{ value: 'sample-account', count: 1 }, { value: null, count: 2 }] });
    if (url.pathname === `${BASE}/${cloudRun.id}/datastores`) {
      return page([runDatastore({
        name: 'sample-bucket', subtype: 'S3', subtypeLabel: 'S3', accountName: 'sample-account', region: 'eu-west-1',
        status: 'FAILED', statusLabel: 'Failed', failureReason: 'Access was denied.',
      })]);
    }
    if (url.pathname === `${BASE}/${RUNS[0].id}`) return runDetail(RUNS[0], { accounts: [] });
    if (url.pathname === `${BASE}/${RUNS[0].id}/datastores`) return page([runDatastore()]);
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('ScanHistoryTab', () => {
  it('lists the 8 runs, and Environment Cloud sends platform=cloud and shows 2 (AC13)', async () => {
    const user = userEvent.setup();
    render(<ScanHistoryTab />);
    await screen.findByText('Sample scan 1');
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual([
      'Name', 'Coverage', 'Environment', 'Trigger', 'Scheduled Time', 'Scan Status', 'Progress',
    ]);
    const body = screen.getAllByRole('rowgroup')[1];
    expect(within(body).getAllByRole('row')).toHaveLength(8);
    expect(within(body).getAllByText('Endpoint')).toHaveLength(6);
    expect(within(body).getAllByText('Cloud')).toHaveLength(2);
    expect(within(body).getAllByText('Success')).toHaveLength(8);
    expect(within(body).getAllByText('Immediate')).toHaveLength(8);
    expect(within(body).getAllByText('1 / 1')).toHaveLength(8);

    await user.selectOptions(screen.getByLabelText('Environment'), 'cloud');
    await waitFor(() => expect(within(screen.getAllByRole('rowgroup')[1]).getAllByRole('row')).toHaveLength(2));
    expect(last().searchParams.get('platform')).toBe('cloud');
    expect(last().searchParams.get('page')).toBe('1');
  });

  it('sends Coverage, Scan Status, Trigger and the search', async () => {
    const user = userEvent.setup();
    render(<ScanHistoryTab />);
    await screen.findByText('Sample scan 1');
    await user.selectOptions(screen.getByLabelText('Coverage'), 'ALL_DATASTORES');
    await user.selectOptions(screen.getByLabelText('Scan Status'), 'FAILED');
    await user.selectOptions(screen.getByLabelText('Trigger'), 'SCHEDULED');
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search scans' }), { target: { value: 'sample' } });
    await waitFor(() => expect(last().searchParams.get('q')).toBe('sample'));
    expect(last().searchParams.get('coverage')).toBe('ALL_DATASTORES');
    expect(last().searchParams.get('status')).toBe('FAILED');
    expect(last().searchParams.get('trigger')).toBe('SCHEDULED');
  });

  it('shows the failed count, the phase and the skipped count', async () => {
    listAnswer = () => page([run(1, {
      status: 'IN_PROGRESS', statusLabel: 'Progress', phase: 'FILE_DISCOVERY', phaseLabel: 'File Discovery',
      statusCounts: { FAILED: 2, SUCCESS: 1 }, completedDatastores: 3, totalDatastores: 9, skippedCount: 4,
    })]);
    render(<ScanHistoryTab />);
    const row = (await screen.findByText('Sample scan 1')).closest('tr') as HTMLTableRowElement;
    const chip = within(row).getByText('Progress');
    expect(chip).toHaveAttribute('title', '2 of 9 failed');
    expect(within(row).getByText('3 / 9')).toBeInTheDocument();
    expect(within(row).getByText('File Discovery')).toBeInTheDocument();
    expect(within(row).getByText('4 skipped')).toBeInTheDocument();
  });

  it('refreshes every minute only while a shown run is in progress, and stops on unmount', async () => {
    vi.useFakeTimers();
    const settled = render(<ScanHistoryTab />);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2); });
    expect(listRequests()).toHaveLength(1);
    settled.unmount();
    get.mockClear();

    listAnswer = () => page([run(1, { status: 'IN_PROGRESS', statusLabel: 'Progress' })]);
    const pending = render(<ScanHistoryTab />);
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(listRequests()).toHaveLength(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS); });
    expect(listRequests()).toHaveLength(2);
    pending.unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3); });
    expect(listRequests()).toHaveLength(2);
  });

  it('opens "Scan History Details" with its 1 datastore and filters (AC13)', async () => {
    const user = userEvent.setup();
    render(<ScanHistoryTab />);
    await user.click(await screen.findByText('Sample scan 7'));
    const detail = await screen.findByRole('dialog', { name: 'Scan History Details' });
    const table = (await within(detail).findByText('sample-bucket')).closest('table') as HTMLTableElement;
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toEqual(['Datastore', 'Source / Account', 'Scan Status']);
    expect(within(table).getByText('eu-west-1')).toBeInTheDocument();
    expect(within(table).getByText('Access was denied.')).toHaveClass('text-rose-600');
    expect(within(detail).getByText('Sample scope')).toBeInTheDocument();
    expect(within(detail).getByText('2m 5s')).toBeInTheDocument();

    const account = within(detail).getByLabelText('Account');
    expect(within(account).getAllByRole('option').map(o => o.textContent)).toEqual(['All', 'sample-account']);
    await user.selectOptions(account, 'sample-account');
    await user.selectOptions(within(detail).getByLabelText('Scan Status'), 'FAILED');
    const datastoreRequests = () => get.mock.calls.map(([url]) => new URL(String(url), 'http://localhost')).filter(u => u.pathname.endsWith('/datastores'));
    await waitFor(() => expect(datastoreRequests()[datastoreRequests().length - 1].searchParams.get('status')).toBe('FAILED'));
    expect(datastoreRequests()[datastoreRequests().length - 1].searchParams.get('account')).toBe('sample-account');

    const before = get.mock.calls.length;
    const exportButton = within(detail).getByRole('button', { name: 'Export' });
    expectConsoleOnly(exportButton);
    await user.click(exportButton);
    expect(get.mock.calls.length).toBe(before);
  });

  it('heads the table "Device" for an endpoint run', async () => {
    const user = userEvent.setup();
    render(<ScanHistoryTab />);
    await user.click(await screen.findByText('Sample scan 1'));
    const detail = await screen.findByRole('dialog', { name: 'Scan History Details' });
    await within(detail).findByText('sample-laptop-01');
    expect(within(detail).getByRole('columnheader', { name: 'Device' })).toBeInTheDocument();
  });

  it('greys Export, which sends nothing (AC21)', async () => {
    const user = userEvent.setup();
    render(<ScanHistoryTab />);
    await screen.findByText('Sample scan 1');
    const exportButton = screen.getByRole('button', { name: 'Export' });
    expectConsoleOnly(exportButton);
    const before = get.mock.calls.length;
    await user.click(exportButton);
    expect(get.mock.calls.length).toBe(before);
  });
});
