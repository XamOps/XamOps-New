import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DspmStatus } from '../../../../types/dspm';
import type { RemediationActivity, RemediationItem } from '../../../../types/dspmPages';

vi.mock('../../../../utils/apiClient', () => ({ default: { get: vi.fn() } }));

import apiClient from '../../../../utils/apiClient';
import RemediationPage from '../RemediationPage';
import { RUN_NOT_FOUND_TEXT } from '../RemediationRunDrawer';
import { CONSOLE_ONLY_TOOLTIP } from '../../datastores/ui/ConsoleOnly';
import { POLL_INTERVAL_MS } from '../../../../hooks/useServerList';

const get = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const READY: DspmStatus = {
  ready: true, entitled: true, configured: true, reason: null, message: null,
  projectName: 'Example workspace', lastVerifiedAt: null,
};
const NOT_READY: DspmStatus = {
  ready: false, entitled: true, configured: false, reason: 'NOT_CONFIGURED', message: null,
  projectName: null, lastVerifiedAt: null,
};

const BASE = '/api/dspm/remediation/activity';
const FIRST_PAGE = `${BASE}?skip=0&limit=10`;
const T0 = Date.UTC(2026, 10, 19, 9, 30) / 1000;

// ─── synthetic data ─────────────────────────────────────────────────────────────────────────────

const run = (over: Partial<RemediationItem> & Pick<RemediationItem, 'id' | 'status'>): RemediationItem => ({
  actionType: 'MAKE_PRIVATE', actionLabel: 'Make Private', objectName: `object-${over.id}`, objectPath: null,
  datastoreType: 'GOOGLE_DRIVE', datastoreLabel: 'Google Drive', sourceType: 'SAAS',
  initiatedBy: { name: 'Sam Example', email: 'sam@example.com' }, triggerType: 'MANUAL', triggerLabel: 'Manual',
  createdAt: T0, statusLabel: over.status, approvers: [], scheduledAt: null, ...over,
});

const WAITING = run({
  id: 'run-wait', status: 'WAITING_APPROVAL', statusLabel: 'Awaiting Approval', objectName: 'pay-review.xlsx',
  objectPath: 'https://drive.example.com/file/d/pay-review', approvers: ['lead@example.com', 'cso@example.com'],
});
const SCHEDULED = run({ id: 'run-sched', status: 'SCHEDULED', statusLabel: 'Scheduled', objectName: 'agenda.docx', scheduledAt: T0 + 3600 });
const FAILED = run({
  id: 'run-fail', status: 'FAILED', statusLabel: 'Failed', objectName: 'bucket-export.csv',
  objectPath: 's3://example-bucket/bucket-export.csv', datastoreType: 'S3', datastoreLabel: 'S3', sourceType: 'CLOUD',
  initiatedBy: { name: null, email: 'bot@example.com' }, triggerType: 'POLICY', triggerLabel: 'Policy',
});
const DONE = run({ id: 'run-done', status: 'COMPLETED', statusLabel: 'Completed', objectName: 'roster.xlsx' });

const activity = (items: RemediationItem[], over: Partial<RemediationActivity> = {}): RemediationActivity => ({
  summary: { total: items.length, waitingApproval: 0, inProgressScheduled: 0, completed: 0, failed: 0 },
  statusCounts: {}, total: items.length, skip: 0, limit: 10, items, ...over,
});

const EMPTY = activity([]);
const MIXED = activity([WAITING, SCHEDULED, FAILED, DONE], {
  summary: { total: 4, waitingApproval: 1, inProgressScheduled: 1, completed: 1, failed: 1 },
  statusCounts: { ALL: 4, WAITING_APPROVAL: 1, SCHEDULED: 1, IN_PROGRESS: 0, COMPLETED: 1, FAILED: 1 },
});
const SETTLED = activity([DONE, FAILED], {
  summary: { total: 2, waitingApproval: 0, inProgressScheduled: 0, completed: 1, failed: 1 },
  statusCounts: { ALL: 2, COMPLETED: 1, FAILED: 1 },
});

let answer: (url: string) => Promise<unknown>;
const requests = () => get.mock.calls.map(([url]) => String(url)).filter(url => url.startsWith(BASE));
const lastRequest = () => requests()[requests().length - 1];

beforeEach(() => {
  answer = async () => EMPTY;
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  get.mockImplementation(async (url: string) => {
    if (url.startsWith(BASE)) return answer(url);
    throw new Error(`HTTP 404: unexpected ${url}`);
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

const renderPage = (status: DspmStatus = READY) => render(<RemediationPage status={status} onRetry={() => {}} />);
const card = (name: string) => document.querySelector(`[data-card="${name}"]`) as HTMLElement;
const rowOf = (name: string) => screen.getByText(name).closest('tr') as HTMLElement;

// ─── tests ───────────────────────────────────────────────────────────────────────────────────────

describe('RemediationPage', () => {
  it('shows only the Activity tab, and with 0 runs every card at 0 and the empty table', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Remediation' })).toBeInTheDocument();
    const tabs = within(screen.getByRole('tablist')).getAllByRole('tab');
    expect(tabs.map(t => t.textContent)).toEqual(['Activity']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('No remediation activity yet')).toBeInTheDocument();
    expect(requests()).toEqual([FIRST_PAGE]);
    for (const name of ['waitingApproval', 'inProgressScheduled', 'completed', 'failed']) {
      expect(card(name), name).toHaveTextContent(/0$/);
    }
    expect(screen.getByText('Total events').closest('div.rounded-xl')).toHaveTextContent(/Total events.*0/);
    expect(within(screen.getByTestId('remediation-total-bar')).queryAllByRole('button')).toHaveLength(0);
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual([
      'Action', 'File / Object', 'Datastore', 'Initiated by', 'Trigger', 'Time', 'Status',
    ]);
  });

  it('carries the console tooltips on the cards', async () => {
    renderPage();
    await screen.findByText('No remediation activity yet');
    for (const text of [
      'All remediation events, across every status', 'Events awaiting reviewer approval',
      'Events currently running or scheduled to run', 'Events that finished successfully', 'Events that failed to complete',
    ]) expect(screen.getByRole('img', { name: text })).toHaveAttribute('title', text);
  });

  it('sends status=COMPLETED&skip=0 when Status "Completed" is chosen, from any page', async () => {
    const user = userEvent.setup();
    answer = async url => activity(Array.from({ length: 10 }, (_, i) => run({ id: `r${i}`, status: 'COMPLETED' })), {
      total: 25, skip: Number(new URL(url, 'http://x').searchParams.get('skip')),
    });
    renderPage();
    await screen.findByText('object-r0');
    await user.click(screen.getByRole('button', { name: '2' }));
    await waitFor(() => expect(requests()).toContain(`${BASE}?skip=10&limit=10`));
    await user.selectOptions(screen.getByLabelText('Status'), 'Completed');
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?status=COMPLETED&skip=0&limit=10`));
    expect(screen.getByLabelText('Status')).toHaveValue('COMPLETED');
  });

  it('sends the Action filter and the trimmed search as q, on page 1', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('No remediation activity yet');
    await user.selectOptions(screen.getByLabelText('Action'), 'Delete File');
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?actionType=DELETE_FILE&skip=0&limit=10`));
    await user.type(screen.getByRole('searchbox', { name: 'Search' }), ' roster ');
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?q=roster&actionType=DELETE_FILE&skip=0&limit=10`));
    // The debounce sends the search once, not per key.
    expect(requests().filter(url => url.includes('q='))).toHaveLength(1);
  });

  it('sets the Status filter from a card, and clears it on a second click', async () => {
    const user = userEvent.setup();
    answer = async () => MIXED;
    renderPage();
    await screen.findByText('pay-review.xlsx');
    await user.click(card('failed'));
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?status=FAILED&skip=0&limit=10`));
    expect(card('failed')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Status')).toHaveValue('FAILED');
    await user.click(card('failed'));
    await waitFor(() => expect(lastRequest()).toBe(FIRST_PAGE));
    await user.click(card('waitingApproval'));
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?status=WAITING_APPROVAL&skip=0&limit=10`));
  });

  it('filters In progress / scheduled by the one status that has runs, and not at all when both have', async () => {
    const user = userEvent.setup();
    answer = async () => MIXED;   // SCHEDULED 1, IN_PROGRESS 0
    const view = renderPage();
    await screen.findByText('pay-review.xlsx');
    await user.click(card('inProgressScheduled'));
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?status=SCHEDULED&skip=0&limit=10`));
    view.unmount();
    answer = async () => ({ ...MIXED, statusCounts: { ...MIXED.statusCounts, IN_PROGRESS: 2 } });
    renderPage();
    await screen.findByText('pay-review.xlsx');
    expect(card('inProgressScheduled').tagName).toBe('DIV');
  });

  it('filters by status from a segment of the Total bar', async () => {
    const user = userEvent.setup();
    answer = async () => MIXED;
    renderPage();
    await screen.findByText('pay-review.xlsx');
    const bar = screen.getByTestId('remediation-total-bar');
    await user.click(within(bar).getByRole('button', { name: 'Completed: 1' }));
    await waitFor(() => expect(lastRequest()).toBe(`${BASE}?status=COMPLETED&skip=0&limit=10`));
  });

  it('hides and shows the cards with the analytics toggle, on by default', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('No remediation activity yet');
    const toggle = screen.getByRole('button', { name: 'Show analytics' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByText('Total events')).toBeNull();
    await user.click(toggle);
    expect(screen.getByText('Total events')).toBeInTheDocument();
  });

  it('renders the row cells: approvers while waiting, the run time while scheduled', async () => {
    answer = async () => MIXED;
    renderPage();
    await screen.findByText('pay-review.xlsx');
    const waiting = rowOf('pay-review.xlsx');
    expect(within(waiting).getByText('Awaiting Approval')).toHaveClass('bg-amber-50');
    expect(within(waiting).getByText('Approver: lead@example.com +1')).toHaveAttribute('title', 'lead@example.com, cso@example.com');
    expect(within(waiting).getByText('https://drive.example.com/file/d/pay-review')).toBeInTheDocument();
    expect(within(rowOf('agenda.docx')).getByText(/^Scheduled \d{2} Nov 2026, /)).toBeInTheDocument();
    const failed = rowOf('bucket-export.csv');
    expect(within(failed).getByText('bot@example.com')).toBeInTheDocument();
    expect(within(failed).getByText('Policy')).toBeInTheDocument();
    expect(within(failed).getByText('CLOUD')).toBeInTheDocument();
  });

  it('opens the run drawer with Reject and Approve & Run greyed for a run awaiting approval', async () => {
    const user = userEvent.setup();
    answer = async () => MIXED;
    renderPage();
    await user.click(await screen.findByText('pay-review.xlsx'));
    const drawer = screen.getByRole('dialog', { name: 'Run detail' });
    expect(within(drawer).getByText('Run run-wait')).toBeInTheDocument();
    const link = within(drawer).getByRole('link', { name: 'https://drive.example.com/file/d/pay-review' });
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(drawer).getByText('lead@example.com, cso@example.com')).toBeInTheDocument();
    const calls = get.mock.calls.length;
    for (const name of ['Reject', 'Approve & Run']) {
      const button = within(drawer).getByRole('button', { name });
      expect(button).toHaveAttribute('aria-disabled', 'true');
      await user.hover(button);
      expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
      await user.click(button);
      await user.unhover(button);
    }
    expect(get.mock.calls.length).toBe(calls);
    expect(screen.getByRole('dialog', { name: 'Run detail' })).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'Run detail' })).toBeNull();
  });

  it('shows the footer by status: Cancel scheduled run, Retry action, or nothing', async () => {
    const user = userEvent.setup();
    answer = async () => MIXED;
    renderPage();
    const footerButtons = () => within(screen.getByRole('dialog', { name: 'Run detail' }))
      .queryAllByRole('button').filter(b => b.getAttribute('aria-disabled') === 'true').map(b => b.textContent);
    await user.click(await screen.findByText('agenda.docx'));
    expect(footerButtons()).toEqual(['Cancel scheduled run']);
    expect(within(screen.getByRole('dialog')).getByText('Scheduled for')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByText('bucket-export.csv'));
    expect(footerButtons()).toEqual(['Retry action']);
    // An s3 path is text, not a link.
    expect(within(screen.getByRole('dialog')).queryByRole('link')).toBeNull();
    expect(within(screen.getByRole('dialog')).getByText('s3://example-bucket/bucket-export.csv')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByText('roster.xlsx'));
    expect(footerButtons()).toEqual([]);
  });

  it('polls every minute only while a shown run is pending, and stops on unmount', async () => {
    vi.useFakeTimers();
    answer = async () => SETTLED;
    const settled = renderPage();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(screen.getByText('roster.xlsx')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2); });
    expect(requests()).toHaveLength(1);
    settled.unmount();

    get.mockClear();
    answer = async () => MIXED;
    const pending = renderPage();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(requests()).toHaveLength(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS); });
    expect(requests()).toEqual([FIRST_PAGE, FIRST_PAGE]);
    pending.unmount();
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3); });
    expect(requests()).toHaveLength(2);
  });

  it('keeps the open run and shows the not-found text once a refresh no longer holds it', async () => {
    vi.useFakeTimers();
    answer = async () => MIXED;
    renderPage();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    fireEvent.click(screen.getByText('pay-review.xlsx'));
    const drawer = screen.getByRole('dialog', { name: 'Run detail' });
    expect(within(drawer).queryByText(RUN_NOT_FOUND_TEXT)).toBeNull();
    answer = async () => activity([SCHEDULED, DONE], { statusCounts: { SCHEDULED: 1 } });
    await act(async () => { await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS); });
    expect(within(drawer).getByText(RUN_NOT_FOUND_TEXT)).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: 'Approve & Run' })).toBeNull();
    expect(within(drawer).getByText('Make Private')).toBeInTheDocument();
  });

  it('shows the error with Retry and keeps the filters', async () => {
    const user = userEvent.setup();
    answer = async () => { throw new Error('HTTP 502: {"code":"UPSTREAM_UNAVAILABLE","message":"down"}'); };
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(screen.getByLabelText('Status')).toBeInTheDocument();
    answer = async () => EMPTY;
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No remediation activity yet')).toBeInTheDocument();
  });

  it('explains a workspace that is not ready and asks for nothing', () => {
    renderPage(NOT_READY);
    expect(screen.queryByRole('heading', { level: 1, name: 'Remediation' })).toBeNull();
    expect(get).not.toHaveBeenCalled();
  });
});
