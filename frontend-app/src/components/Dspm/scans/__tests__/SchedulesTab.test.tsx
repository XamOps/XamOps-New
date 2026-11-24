import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ScanSchedule } from '../../../../types/dspmScans';
vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../utils/apiClient';
import SchedulesTab from '../SchedulesTab';
import { formatListDate } from '../../datastores/datastoreFormat';
import { T0, expectConsoleOnly, page, schedule, targets } from './scanTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const BASE = '/api/dspm/scan-management/schedules';
const LEGACY = schedule();
const WEEKLY = schedule({
  id: '0a0a0a0a0a0a0a0a0a0a0a02', name: 'Weekly sweep', legacy: false, state: 'PAUSED', platform: 'endpoint',
  platformLabel: 'Endpoint', coverage: 'ALL_DATASTORES', coverageLabel: 'All datastores', totalDatastores: 8,
  totalIsEstimate: true,
  timing: { type: 'recurring', triggerTime: T0, recurrenceType: 'WEEKLY', repeatOn: ['MONDAY', 'WEDNESDAY'], dayOfMonth: null, endDate: null, timezone: 'UTC' },
  lastRunAt: T0, lastRunStatus: 'FAILED', lastRunStatusLabel: 'Failed',
});

let rows: ScanSchedule[];
let detailAnswer: () => unknown;
const listRequests = () => get.mock.calls
  .map(([url]) => new URL(String(url), 'http://localhost'))
  .filter(url => url.pathname === BASE);
const last = () => listRequests()[listRequests().length - 1];

beforeEach(() => {
  rows = [LEGACY];
  detailAnswer = () => ({ schedule: LEGACY, scopeMode: 'STATIC', scopeNames: ['Finance scope'], targets: targets(10), targetsTotal: 40 });
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    if (url.pathname === BASE) return page(rows);
    if (url.pathname === `${BASE}/${LEGACY.id}`) return detailAnswer();
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('SchedulesTab', () => {
  it('shows the legacy one-time schedule as the console does (AC12)', async () => {
    render(<SchedulesTab />);
    const row = (await screen.findByText('Untitled schedule')).closest('tr') as HTMLTableRowElement;
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual([
      'Schedule Name', 'Environment', 'Coverage', 'Targets', 'Frequency', 'Scheduled Time', 'Scan Status', 'Actions',
    ]);
    expect(within(row).getByText('Legacy')).toBeInTheDocument();
    expect(within(row).getByText('Cloud')).toBeInTheDocument();
    expect(within(row).getByText('Selected datastores')).toBeInTheDocument();
    expect(within(row).getByText('40')).toBeInTheDocument();
    expect(within(row).getByText('One-time')).toBeInTheDocument();
    expect(within(row).getByText(formatListDate(T0))).toBeInTheDocument();
    expect(within(row).getByText('Not run yet')).toBeInTheDocument();
    expect(last().search).toBe('?page=1&pageSize=10');
  });

  it('shows a paused recurring schedule with its repeat, "Up to" and the last run status', async () => {
    rows = [WEEKLY];
    render(<SchedulesTab />);
    const row = (await screen.findByText('Weekly sweep')).closest('tr') as HTMLTableRowElement;
    expect(within(row).getByText('Paused')).toBeInTheDocument();
    expect(within(row).getByText('Up to 8')).toBeInTheDocument();
    expect(within(row).getByText('Recurring')).toBeInTheDocument();
    expect(within(row).getByText('Every Mon, Wed')).toBeInTheDocument();
    expect(within(row).getByText('Failed')).toBeInTheDocument();
  });

  it('opens "Schedule Details" with the returned targets and "Showing 10 of 40 targets" (AC12)', async () => {
    const user = userEvent.setup();
    render(<SchedulesTab />);
    const row = (await screen.findByText('Untitled schedule')).closest('tr') as HTMLTableRowElement;
    row.focus();
    await user.keyboard('{Enter}');
    const detail = await screen.findByRole('dialog', { name: 'Schedule Details' });
    expect(await within(detail).findByText('Showing 10 of 40 targets')).toBeInTheDocument();
    expect(within(detail).getAllByText(/^target-store-/)).toHaveLength(10);
    expect(within(detail).getByText('Finance scope')).toBeInTheDocument();
    expect(within(detail).getByText('Static')).toBeInTheDocument();
    expect(within(detail).getByRole('button', { name: 'Close' })).toHaveFocus();
    await user.click(within(detail).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'Schedule Details' })).toBeNull();
    expect(row).toHaveFocus();
  });

  it("shows the item's not-found text when the detail answers 404", async () => {
    detailAnswer = () => { throw new Error(`HTTP 404: ${JSON.stringify({ code: 'ASSET_NOT_FOUND', message: 'gone' })}`); };
    render(<SchedulesTab />);
    fireEvent.click(await screen.findByText('Untitled schedule'));
    const detail = await screen.findByRole('dialog', { name: 'Schedule Details' });
    expect(await within(detail).findByText('This item could not be found. It may have been removed.')).toBeInTheDocument();
  });

  it('sends each filter and the search, back on page 1', async () => {
    const user = userEvent.setup();
    rows = Array.from({ length: 10 }, (_, i) => schedule({ id: `0a0a0a0a0a0a0a0a0a0a0b${String(i).padStart(2, '0')}`, name: `S${i}` }));
    get.mockImplementation(async (raw: string) => {
      const url = new URL(raw, 'http://localhost');
      if (url.pathname === BASE) return page(rows, { total: 25, totalPages: 3, page: Number(url.searchParams.get('page')) });
      throw new Error(`HTTP 404: unexpected ${raw}`);
    });
    render(<SchedulesTab />);
    await screen.findByText('S0');
    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(last().searchParams.get('page')).toBe('2'));
    await user.selectOptions(screen.getByLabelText('Environment'), 'on_prem');
    await waitFor(() => expect(last().searchParams.get('platform')).toBe('on_prem'));
    expect(last().searchParams.get('page')).toBe('1');
    await user.selectOptions(screen.getByLabelText('Scan Status'), 'NOT_STARTED');
    await user.selectOptions(screen.getByLabelText('Coverage'), 'SELECTED_DATASTORES');
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search schedules' }), { target: { value: 'nightly' } });
    await waitFor(() => expect(last().searchParams.get('q')).toBe('nightly'));
    expect(last().searchParams.get('status')).toBe('NOT_STARTED');
    expect(last().searchParams.get('coverage')).toBe('SELECTED_DATASTORES');
    expect(last().searchParams.get('page')).toBe('1');
  });

  it('greys Export, Schedule Scan and each ⋮, which send nothing and do not open the row (AC21)', async () => {
    const user = userEvent.setup();
    render(<SchedulesTab />);
    await screen.findByText('Untitled schedule');
    const controls = [
      screen.getByRole('button', { name: 'Export' }),
      screen.getByRole('button', { name: 'Schedule Scan' }),
      ...screen.getAllByRole('button', { name: 'Schedule actions' }),
    ];
    const before = get.mock.calls.length;
    for (const control of controls) {
      expectConsoleOnly(control);
      await user.click(control);
    }
    expect(get.mock.calls.length).toBe(before);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows the empty states', async () => {
    const user = userEvent.setup();
    rows = [];
    render(<SchedulesTab />);
    expect(await screen.findByText('No scan schedules yet')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Environment'), 'cloud');
    expect(await screen.findByText('No schedules match these filters')).toBeInTheDocument();
  });
});
