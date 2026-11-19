import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SaasIntegrationList } from '../../../../types/dspmScans';
vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));
import apiClient from '../../../../utils/apiClient';
import SaasScanConfiguration from '../SaasScanConfiguration';
import { formatListDate } from '../../datastores/datastoreFormat';
import {
  CHANNELS, GOOGLE_ID, SLACK_ID, T0, expectConsoleOnly, googleConfig, page, slackConfig, users,
} from './scanTestData';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const BASE = '/api/dspm/scan-management/saas-integrations';
const ALL_USERS = users(31);

const BOTH: SaasIntegrationList = {
  integrations: [
    { id: GOOGLE_ID, provider: 'GOOGLE_WORKSPACE', accountLabel: 'sample-workspace', driveTypes: ['GMAIL', 'GOOGLE_WORKSPACE'] },
    { id: SLACK_ID, provider: 'SLACK', accountLabel: 'sample-team', driveTypes: ['SLACK'] },
  ],
  partial: false,
};

let integrations: SaasIntegrationList;
let googleAnswer: () => unknown;

const requests = (suffix: string) => get.mock.calls
  .map(([url]) => new URL(String(url), 'http://localhost'))
  .filter(url => url.pathname.endsWith(suffix));
const last = (suffix: string) => requests(suffix)[requests(suffix).length - 1];

beforeEach(() => {
  integrations = BOTH;
  googleAnswer = () => googleConfig();
  get.mockImplementation(async (raw: string) => {
    const url = new URL(raw, 'http://localhost');
    switch (url.pathname) {
      case BASE: return integrations;
      case `${BASE}/${GOOGLE_ID}/configuration`: return googleAnswer();
      case `${BASE}/${SLACK_ID}/configuration`: return slackConfig();
      case `${BASE}/${GOOGLE_ID}/users`: {
        const p = Number(url.searchParams.get('page'));
        const size = Number(url.searchParams.get('pageSize'));
        return page(ALL_USERS.slice((p - 1) * size, p * size), { page: p, pageSize: size, total: ALL_USERS.length, totalPages: 4 });
      }
      case `${BASE}/${SLACK_ID}/channels`: return page(CHANNELS);
    }
    throw new Error(`HTTP 404: unexpected ${raw}`);
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('SaasScanConfiguration', () => {
  it('marks Google Workspace and Slack connected and the other four "Not connected" (AC10)', async () => {
    render(<SaasScanConfiguration />);
    const apps = await screen.findByRole('list', { name: 'SaaS apps' });
    const items = within(apps).getAllByRole('listitem');
    expect(items.map(item => item.querySelector('.font-semibold')?.textContent))
      .toEqual(['Microsoft 365', 'Google Workspace', 'Jira', 'Confluence', 'GitLab', 'Slack']);
    for (const name of ['Microsoft 365', 'Jira', 'Confluence', 'GitLab']) {
      const item = items.find(i => i.textContent?.startsWith(name)) as HTMLElement;
      expect(item).toHaveTextContent('Not connected');
      expect(within(item).queryByRole('button')).toBeNull();
    }
    expect(within(apps).getByRole('button', { name: /Google Workspace/ })).toHaveAttribute('aria-pressed', 'true');
    expect(within(apps).getByRole('button', { name: /Slack/ })).toHaveAttribute('aria-pressed', 'false');
  });

  it('shows the Google Workspace settings greyed, with its drive tabs and the users paged by 10 (AC10)', async () => {
    const user = userEvent.setup();
    render(<SaasScanConfiguration />);
    expect(await screen.findByText(`Last saved by owner@example.com · ${formatListDate(T0)}`)).toBeInTheDocument();
    const realtime = screen.getByRole('switch', { name: 'Real-time scanning' });
    const backfill = screen.getByRole('switch', { name: 'Backfill existing data' });
    expect(realtime).toHaveAttribute('aria-checked', 'false');
    expect(backfill).toHaveAttribute('aria-checked', 'true');
    const sampling = screen.getByRole('textbox', { name: 'Sampling Coverage' });
    expect(sampling).toHaveTextContent('100%');
    for (const control of [realtime, backfill, sampling]) expectConsoleOnly(control);

    const drives = screen.getByRole('tablist', { name: 'Drives' });
    expect(within(drives).getAllByRole('tab').map(t => t.textContent)).toEqual(['Google Drive', 'Gmail']);
    expect(screen.getByRole('radio', { name: 'Selected users' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'All users' })).toHaveAttribute('aria-checked', 'false');
    expectConsoleOnly(screen.getByRole('radio', { name: 'Selected users' }));
    expect(screen.getByText('Changes in this table save immediately.')).toBeInTheDocument();

    await screen.findByText('person1@example.com');
    expect(screen.getByText('Showing 1 - 10 of 31')).toBeInTheDocument();
    const first = last('/users');
    expect(first.searchParams.get('driveType')).toBe('GOOGLE_WORKSPACE');
    expect(first.searchParams.get('page')).toBe('1');
    expect(first.searchParams.get('pageSize')).toBe('10');
    const scanSwitches = screen.getAllByRole('switch', { name: /^Scan person/ });
    expect(scanSwitches).toHaveLength(10);
    expect(scanSwitches[0]).toHaveAttribute('aria-checked', 'true');
    expect(scanSwitches[1]).toHaveAttribute('aria-checked', 'false');
    expectConsoleOnly(scanSwitches[0]);

    const before = get.mock.calls.length;
    await user.click(scanSwitches[1]);
    await user.click(realtime);
    expect(get.mock.calls.length).toBe(before);

    await user.click(screen.getByRole('button', { name: '2' }));
    await screen.findByText('person11@example.com');
    expect(last('/users').searchParams.get('page')).toBe('2');

    await user.click(within(drives).getByRole('tab', { name: 'Gmail' }));
    expect(screen.getByText('31 users will be scanned.')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'All users' })).toHaveAttribute('aria-checked', 'true');
  });

  it('filters the users by search, status and domain, back on page 1 each time', async () => {
    const user = userEvent.setup();
    render(<SaasScanConfiguration />);
    await screen.findByText('person1@example.com');
    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(last('/users').searchParams.get('page')).toBe('2'));

    await user.selectOptions(screen.getByLabelText('User Status'), 'SUSPENDED');
    await waitFor(() => expect(last('/users').searchParams.get('status')).toBe('SUSPENDED'));
    expect(last('/users').searchParams.get('page')).toBe('1');

    const domain = screen.getByLabelText('Domain');
    expect(within(domain).getAllByRole('option').map(o => o.textContent)).toEqual(['All', 'example.com', 'example.net']);
    await user.selectOptions(domain, 'example.net');
    await waitFor(() => expect(last('/users').searchParams.get('domain')).toBe('example.net'));

    fireEvent.change(screen.getByRole('searchbox', { name: 'Search users' }), { target: { value: '  person3 ' } });
    await waitFor(() => expect(last('/users').searchParams.get('q')).toBe('person3'));
  });

  it("shows Slack's channels table with the bundle's filter codes", async () => {
    const user = userEvent.setup();
    render(<SaasScanConfiguration />);
    await screen.findByText('person1@example.com');
    await user.click(screen.getByRole('button', { name: /Slack/ }));
    expect(await screen.findByText('Discovery is still in progress...')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Sampling Coverage' })).toBeNull();   // backfilling off

    const board = (await screen.findByText('board-prep')).closest('tr') as HTMLTableRowElement;
    expect(screen.getAllByRole('row')).toHaveLength(4);
    expect(within(board).getByRole('img', { name: 'Private channel' })).toBeInTheDocument();
    expect(within(board).getByText('New')).toBeInTheDocument();
    expect(within(board).getByText('External')).toBeInTheDocument();
    expect(within(board).getByText('Private')).toBeInTheDocument();
    const team = screen.getByText('team-room').closest('tr') as HTMLTableRowElement;
    expect(within(team).getByRole('img', { name: 'Public channel' })).toBeInTheDocument();
    expect(within(team).getByText('Daily chatter')).toBeInTheDocument();
    expect(within(team).getByText('12')).toBeInTheDocument();
    expect(within(team).getByRole('switch', { name: 'Scan team-room' })).toHaveAttribute('aria-checked', 'true');
    const retired = screen.getByText('retired-project').closest('tr') as HTMLTableRowElement;
    expect(within(retired).getByText('1,204')).toBeInTheDocument();
    expect(within(retired).getByText('Archived')).toBeInTheDocument();
    expectConsoleOnly(within(board).getByRole('switch', { name: 'Scan board-prep' }));

    await user.selectOptions(screen.getByLabelText('Channel Type'), 'PRIVATE');
    await user.selectOptions(screen.getByLabelText('Channel Status'), 'ARCHIVED');
    await user.selectOptions(screen.getByLabelText('Scan'), 'ENABLED');
    await user.selectOptions(screen.getByLabelText('New'), 'true');
    await waitFor(() => expect(last('/channels').searchParams.get('isNew')).toBe('true'));
    const query = last('/channels').searchParams;
    expect(query.get('channelType')).toBe('PRIVATE');
    expect(query.get('channelStatus')).toBe('ARCHIVED');
    expect(query.get('scanState')).toBe('ENABLED');
    expect(query.get('page')).toBe('1');
    expect(within(screen.getByLabelText('New')).getAllByRole('option').map(o => o.textContent)).toEqual(['All', 'New only']);
  });

  it('says so when no SaaS integration is connected', async () => {
    integrations = { integrations: [], partial: true };
    render(<SaasScanConfiguration />);
    expect(await screen.findByText('No SaaS integrations connected')).toBeInTheDocument();
    expect(screen.getAllByText('Not connected')).toHaveLength(6);
    expect(screen.getByText('Based on the first 1,000 SaaS datastores.')).toBeInTheDocument();
  });

  it("shows the item's not-found text when the configuration answers 404", async () => {
    googleAnswer = () => { throw new Error(`HTTP 404: ${JSON.stringify({ code: 'ASSET_NOT_FOUND', message: 'gone' })}`); };
    render(<SaasScanConfiguration />);
    expect(await screen.findByText('This item could not be found. It may have been removed.')).toBeInTheDocument();
  });

  it('hides "Last saved by" unless both the user and the time are set', async () => {
    googleAnswer = () => googleConfig({ lastSavedBy: null });
    render(<SaasScanConfiguration />);
    await screen.findByText('Scan Type');
    expect(screen.queryByText(/^Last saved/)).toBeNull();
  });
});
