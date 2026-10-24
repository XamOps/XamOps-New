import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ getSpotInterruptions: vi.fn() }));
vi.mock('../../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../../api/k8sAutomationApi')>();
  return { ...actual, ...api };
});

import { K8sApiError } from '../../../../../../api/k8sAutomationApi';
import type { SpotInterruptionsDto } from '../../../../../../types/k8sAutomation';
import SpotInterruptionsPage from '../SpotInterruptionsPage';
import { MIXED, dto, event } from './spotInterruptionsFixtures';

const DASH = '—';

function renderPage() {
  return render(<SpotInterruptionsPage clusterName="xamops-test-1" accountId="102227938259" region="ap-south-1" />);
}

const answer = (body: SpotInterruptionsDto) => api.getSpotInterruptions.mockResolvedValue(body);
const banner = () => document.querySelector<HTMLElement>('[data-now-banner]')!;
const strip = () => document.querySelector<HTMLElement>('[data-spot-kpis]');
const cell = (label: string) => within(strip()!).getByText(label).closest<HTMLElement>('[data-metric-cell]')!;
const table = () => screen.getByRole('table', { name: 'Spot interruption events' });
/** The body rows (each is role=button: it opens the drawer). */
const bodyRows = () => [...table().querySelectorAll<HTMLElement>('tbody tr')];

beforeEach(() => {
  api.getSpotInterruptions.mockReset();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('Spot interruptions page states (AC11)', () => {
  it('reads the window it shows: 30 days by default, then the pressed pill', async () => {
    const user = userEvent.setup();
    answer(dto());
    renderPage();
    await screen.findAllByText('No interruptions recorded in the last 30 days');
    expect(api.getSpotInterruptions).toHaveBeenLastCalledWith(
      { cluster: 'xamops-test-1', accountId: '102227938259', region: 'ap-south-1' },
      { days: 30, limit: 200 },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    const group = screen.getByRole('group', { name: 'Window' });
    expect(within(group).getByRole('button', { name: '30 d' })).toHaveAttribute('aria-pressed', 'true');
    answer(dto({ windowDays: 90 }));
    await user.click(within(group).getByRole('button', { name: '90 d' }));
    await screen.findAllByText('No interruptions recorded in the last 90 days');
    expect(within(group).getByRole('button', { name: '90 d' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(group).getByRole('button', { name: '30 d' })).toHaveAttribute('aria-pressed', 'false');
    expect(api.getSpotInterruptions).toHaveBeenLastCalledWith(expect.anything(), { days: 90, limit: 200 }, expect.anything());
  });

  it('none: no events with Spot nodes, and 0 only because recording looked', async () => {
    answer(dto());
    renderPage();
    await screen.findAllByText('No interruptions recorded in the last 30 days');
    expect(document.querySelector('[data-spot-state="none"]')).not.toBeNull();
    expect(cell('Spot events')).toHaveTextContent('0');
    expect(cell('Spot events')).toHaveTextContent('None in the window');
    expect(cell('Recovery')).toHaveTextContent(DASH);
    expect(cell('Recovery')).toHaveTextContent('No recovery measured yet');
  });

  it('no-spot: the latest snapshot has no Spot nodes', async () => {
    answer(dto({ fleet: { totalNodes: 3, spotNodes: 0, spotSharePct: 0, collectedAt: new Date().toISOString() } }));
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-spot-state="no-spot"]')).not.toBeNull());
    expect(banner()).toHaveTextContent('No spot nodes in this cluster');
    expect(cell('Spot share now')).toHaveTextContent('0%');
  });

  it('not-monitored: neither agent has ever reported; no strip, no 0 or "None"', async () => {
    answer(dto({ fleet: null, coverage: { agentSnapshotAt: null, automationReportAt: null, handlingNow: null } }));
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-spot-state="not-monitored"]')).not.toBeNull());
    expect(banner()).toHaveTextContent("XamOps isn't receiving node data from this cluster yet.");
    expect(strip()).toBeNull();
    const page = document.querySelector('[data-spot-interruptions-page]')!;
    expect(page.textContent).not.toMatch(/\bNone\b|(^|\s)0(\s|$)/);
  });

  it('error: the read message with Retry, which reads again', async () => {
    const user = userEvent.setup();
    api.getSpotInterruptions.mockRejectedValueOnce(new K8sApiError({ status: 500, message: 'Server error' }));
    answer(dto());
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-spot-state="error"]')).not.toBeNull());
    expect(screen.getByText(/Couldn't read the Spot interruption history: Server error/)).toBeInTheDocument();
    expect(strip()).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findAllByText('No interruptions recorded in the last 30 days');
    expect(api.getSpotInterruptions).toHaveBeenCalledTimes(2);
  });

  it('recording off: a dash, not 0, and the faint line', async () => {
    answer(dto({ recordingEnabled: false }));
    renderPage();
    await screen.findByText('Recording is turned off on this XamOps deployment.');
    expect(cell('Spot events')).toHaveTextContent(DASH);
    expect(cell('Spot events')).toHaveTextContent('Recording is off');
    expect(cell('Spot events')).not.toHaveTextContent(/(^|\s)0(\s|$)/);
  });

  it('handling now not reported: "Not reported" with the report age, never a guessed mode', async () => {
    answer(dto({ coverage: { agentSnapshotAt: new Date().toISOString(), automationReportAt: new Date(Date.now() - 12 * 60_000).toISOString(), handlingNow: null } }));
    renderPage();
    await screen.findAllByText('No interruptions recorded in the last 30 days');
    expect(cell('XamOps handling now')).toHaveTextContent('Not reported');
    expect(cell('XamOps handling now')).toHaveTextContent('Automation agent report 12 min old');
  });
});

describe('Spot interruptions events table', () => {
  it('shows each row with dot+word marks (AC12) and the real cells', async () => {
    answer(dto({ events: MIXED }));
    renderPage();
    await waitFor(() => expect(table()).toBeInTheDocument());
    const rows = bodyRows();
    expect(rows).toHaveLength(3);
    const text = rows.map((r) => r.textContent ?? '');
    const interruption = rows[text.findIndex((t) => t.includes('2-min notice'))];
    expect(interruption).toHaveTextContent('3–8 min');
    expect(interruption).toHaveTextContent('Simulated (dry run)');
    expect(interruption).toHaveTextContent('Simulated drain');
    const rebalance = rows[text.findIndex((t) => t.includes('Rebalance recommendation'))];
    expect(rebalance).toHaveTextContent('Not handled by XamOps');
    expect(rebalance).toHaveTextContent('Tracking');
    const removed = rows[text.findIndex((t) => t.includes('Removed · cause unconfirmed'))];
    expect(removed).toHaveTextContent('Not measurable');
    expect(removed).not.toHaveTextContent(/\bmin\b/);
    // Every status is a StatusMark (dot + word), never a coloured pill.
    expect(table().querySelectorAll('[data-tone]').length).toBeGreaterThanOrEqual(6);
    expect(document.querySelector('[data-spot-interruptions-page] .rounded-full[data-tone]')).toBeNull();
    expect(banner()).toHaveTextContent(
      'In the last 30 days: 1 interruption notice · 1 rebalance recommendation · 1 Spot node removed · cause unconfirmed · Spot is 33% of nodes',
    );
  });

  it('unconfirmed-only rows never read as interruptions: header, Spot events cell, Notice column (AC27)', async () => {
    answer(dto({ events: [event({ noticeKind: 'unconfirmed' }), event({ noticeKind: 'unconfirmed', nodeName: 'ip-10-0-9-9.x' })] }));
    renderPage();
    await waitFor(() => expect(table()).toBeInTheDocument());
    expect(banner()).toHaveTextContent('In the last 30 days: 2 Spot nodes removed · cause unconfirmed · Spot is 33% of nodes');
    expect(banner().textContent).not.toMatch(/interruption/i);
    expect(cell('Spot events').textContent).not.toMatch(/interruption/i);
    const notices = [...table().querySelectorAll('[data-notice-cell]')].map((n) => n.textContent ?? '');
    expect(notices).toEqual(['Removed · cause unconfirmed', 'Removed · cause unconfirmed']);
    for (const n of notices) expect(n).not.toMatch(/interruption/i);
  });

  it('dashes uncaptured workloads, and filters by Notice and Handling', async () => {
    const user = userEvent.setup();
    answer(dto({ events: [...MIXED, event({ workloads: null, podCount: null, workloadsTruncated: null, nodeName: 'ip-10-0-4-4.x' })] }));
    renderPage();
    await waitFor(() => expect(table()).toBeInTheDocument());
    expect(bodyRows()).toHaveLength(4);
    await user.click(within(screen.getByRole('group', { name: 'Handling' })).getByRole('button', { name: 'Not handled' }));
    expect(bodyRows()).toHaveLength(1);
    await user.click(within(screen.getByRole('group', { name: 'Handling' })).getByRole('button', { name: 'All' }));
    await user.click(within(screen.getByRole('group', { name: 'Notice' })).getByRole('button', { name: 'Cause unconfirmed' }));
    expect(bodyRows()).toHaveLength(2);
    const dashRow = bodyRows().find((r) => r.textContent?.includes('ip-10-0-4-4'))!;
    expect(within(dashRow).getByTitle("No snapshot listed this node's pods at the notice")).toHaveTextContent(DASH);
  });

  it('opens the drawer with every section, a dash with a reason for what was not recorded', async () => {
    const user = userEvent.setup();
    answer(dto({ events: [MIXED[0]] }));
    renderPage();
    await waitFor(() => expect(table()).toBeInTheDocument());
    await user.click(bodyRows()[0]);
    const dialog = await screen.findByRole('dialog');
    for (const h of ['Notice', 'Node', 'Affected workloads', 'Recovery', 'XamOps handling']) {
      expect(within(dialog).getByRole('heading', { name: h })).toBeInTheDocument();
    }
    expect(dialog).toHaveTextContent('NTH taint');
    expect(dialog).toHaveTextContent('team-a/Deployment/web · 2 pods on node');
    expect(dialog).toHaveTextContent('Recovered: workloads Ready elsewhere in 3–8 min');
    expect(dialog).toHaveTextContent('Simulated drain');
    expect(within(dialog).getByTitle('Not reported.')).toHaveTextContent(DASH); // instance id
    for (const d of within(dialog).getAllByTitle('No Pending pod was seen.')) expect(d).toHaveTextContent(DASH);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('says when the list is truncated, and when no Spot nodes remain now', async () => {
    answer(dto({ truncated: true, events: [MIXED[0]], fleet: { totalNodes: 3, spotNodes: 0, spotSharePct: 0, collectedAt: new Date().toISOString() } }));
    renderPage();
    await screen.findByText('Showing the newest 200. Narrow the window to see older events.');
    expect(screen.getByText('No spot nodes in this cluster now.')).toBeInTheDocument();
  });
});
