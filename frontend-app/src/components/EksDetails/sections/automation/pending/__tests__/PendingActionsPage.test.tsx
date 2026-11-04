import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getProposals, approveProposal, rejectProposal, canMock } = vi.hoisted(() => ({
  getProposals: vi.fn(),
  approveProposal: vi.fn(),
  rejectProposal: vi.fn(),
  canMock: vi.fn(),
}));
vi.mock('../../../../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../../../../api/k8sAutomationApi')>();
  return { ...actual, getProposals, approveProposal, rejectProposal };
});
vi.mock('../../../../../../hooks/useK8sCapabilities', () => {
  const value = { can: canMock, onRefusal: vi.fn() };
  return { useK8sCapabilities: () => value, useOptionalK8sCapabilities: () => value };
});

import { K8sApiError } from '../../../../../../api/k8sAutomationApi';
import type { Capability } from '../../../../../../types/k8sAutomation';
import PendingActionsPage from '../PendingActionsPage';
import { NOW, iso, list, proposal, workloadProposal } from './proposalFixtures';

const ALLOWED: Capability = { allowed: true, editable: true, gate: null, also: [] };
const READ_ONLY: Capability = { allowed: false, editable: false, gate: { reason: 'READ_ONLY_CONNECTION' }, also: [] };
const TARGET = { cluster: 'prod-eks', accountId: '111', region: 'us-east-1' };

function renderPage() {
  return render(
    <MemoryRouter>
      <PendingActionsPage clusterName="prod-eks" accountId="111" region="us-east-1" />
    </MemoryRouter>,
  );
}
const row = (id: string) => document.querySelector<HTMLElement>(`[data-proposal="${id}"]`)!;

describe('PendingActionsPage', () => {
  beforeEach(() => {
    getProposals.mockReset();
    approveProposal.mockReset().mockResolvedValue({ id: 'p-1', status: 'APPROVED', alreadyDecided: false });
    rejectProposal.mockReset().mockResolvedValue({ id: 'p-1', status: 'REJECTED', alreadyDecided: false });
    canMock.mockReset().mockReturnValue(ALLOWED);
    vi.useFakeTimers({ shouldAdvanceTime: true, now: NOW });
  });
  afterEach(() => { vi.useRealTimers(); });

  it('AC-F1: renders a queue row with action, target, why, evidence, expiry, Approve and Reject, and history rows', async () => {
    getProposals.mockResolvedValue(list([
      proposal(),
      proposal({ id: 'h-1', status: 'EXECUTED', outcomeVerified: true, decidedByName: 'Asha', decidedAt: iso(-3600_000) }),
    ]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    const r = within(row('p-1'));
    expect(r.getByText('Drain node')).toBeTruthy();
    expect(r.getByText('ip-10-0-1-5')).toBeTruthy();
    expect(r.getByText('Node is idle: CPU usage 4%, memory usage 11%, 7 pods can move')).toBeTruthy();
    expect(r.getByText('Passes all checks')).toBeTruthy();
    expect(r.getByText('in 1 h 12 min').tagName).toBe('TIME');
    expect(r.getByRole('button', { name: /^Approve Drain node/ })).toBeTruthy();
    expect(r.getByRole('button', { name: /^Reject Drain node/ })).toBeTruthy();
    // evidence: PDB table, guards, planned moves
    expect(r.getByText('shop/api')).toBeTruthy();
    expect(r.getByText(/Guards in force/)).toBeTruthy();
    expect(r.getByText(/moves from ip-10-0-1-5 to ip-10-0-2-9/)).toBeTruthy();
    // history
    expect(within(row('h-1')).getByText('Executed and verified')).toBeTruthy();
    expect(within(row('h-1')).getByText('Asha')).toBeTruthy();
    expect(document.querySelector('[data-pending-page] table [class*="rounded-full"]')).toBeNull();
  });

  it('approve opens an inline confirm with the exact consequence, then sends version, fingerprint and note', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const p = proposal();
    getProposals.mockResolvedValue(list([p]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    await user.click(within(row('p-1')).getByRole('button', { name: /^Approve/ }));
    const confirm = document.querySelector<HTMLElement>('[data-confirm="approve"]')!;
    expect(within(confirm).getByText(/Drains ip-10-0-1-5 by evicting 7 pods through the Eviction API/)).toBeTruthy();
    expect(within(confirm).getByText(/left to KARPENTER to remove/)).toBeTruthy();
    expect(within(confirm).getByLabelText('Note (optional)')).toHaveFocus();
    expect(approveProposal).not.toHaveBeenCalled();
    await user.type(within(confirm).getByLabelText('Note (optional)'), 'ok to go');
    await user.click(within(confirm).getByRole('button', { name: 'Confirm approve' }));
    await waitFor(() => expect(approveProposal).toHaveBeenCalledTimes(1));
    expect(approveProposal).toHaveBeenCalledWith(TARGET, 'p-1', { expectedVersion: 3, planFingerprint: p.planFingerprint, note: 'ok to go' });
    await waitFor(() => expect(screen.getByText('Approved. Waiting for the automation agent.')).toBeTruthy());
  });

  it('Escape cancels the confirm and sends nothing', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    getProposals.mockResolvedValue(list([proposal()]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    await user.click(within(row('p-1')).getByRole('button', { name: /^Approve/ }));
    await user.keyboard('{Escape}');
    expect(document.querySelector('[data-confirm]')).toBeNull();
    expect(approveProposal).not.toHaveBeenCalled();
  });

  it('reject sends the version and an optional note only', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    getProposals.mockResolvedValue(list([proposal()]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    await user.click(within(row('p-1')).getByRole('button', { name: /^Reject/ }));
    await user.click(screen.getByRole('button', { name: 'Confirm reject' }));
    await waitFor(() => expect(rejectProposal).toHaveBeenCalledWith(TARGET, 'p-1', { expectedVersion: 3 }));
    expect(approveProposal).not.toHaveBeenCalled();
  });

  it.each([
    ['VERSION_CONFLICT', 'Someone else already decided this.'],
    ['FINGERPRINT_MISMATCH', 'This proposal changed just now. Review the updated details and approve again.'],
    ['STALE', 'This action went stale before approval.'],
    ['K8S_AGENT_TOO_OLD', 'The automation agent is too old for Manual mode. Upgrade it first.'],
    ['K8S_MODE_DISABLED', 'Automation is paused for this cluster. Resume it from the Automation mode control to approve.'],
  ])('approve error %s shows its words', async (code, words) => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    approveProposal.mockRejectedValue(new K8sApiError({ status: 409, code, message: 'server text' }));
    getProposals.mockResolvedValue(list([proposal()]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    await user.click(within(row('p-1')).getByRole('button', { name: /^Approve/ }));
    await user.click(screen.getByRole('button', { name: 'Confirm approve' }));
    await waitFor(() => expect(screen.getByText(words)).toBeTruthy());
  });

  it('a conflict re-reads the list so the row shows what changed', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    approveProposal.mockRejectedValue(new K8sApiError({ status: 409, code: 'VERSION_CONFLICT', message: 'x' }));
    getProposals.mockResolvedValueOnce(list([proposal()]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    getProposals.mockResolvedValue(list([proposal({ status: 'REJECTED', decidedByName: 'Bo', decidedAt: iso(-1000) })]));
    await user.click(within(row('p-1')).getByRole('button', { name: /^Approve/ }));
    await user.click(screen.getByRole('button', { name: 'Confirm approve' }));
    await waitFor(() => expect(getProposals).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(within(row('p-1')).getByText('Rejected')).toBeTruthy());
  });

  it('a consolidation row inside its dwell has no Approve and sends nothing', async () => {
    getProposals.mockResolvedValue(list([
      proposal({ actionType: 'NODE_CONSOLIDATE', approvableAfter: iso(10 * 60_000) }),
    ]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    expect(within(row('p-1')).queryByRole('button', { name: /Approve/ })).toBeNull();
    expect(within(row('p-1')).getByText(/Can be approved from .*: waiting out the consolidation dwell/)).toBeTruthy();
    expect(approveProposal).not.toHaveBeenCalled();
  });

  it('AC-F2: a read-only user sees the rows with the gate reason and no buttons, and no request is sent', async () => {
    canMock.mockReturnValue(READ_ONLY);
    getProposals.mockResolvedValue(list([proposal(), workloadProposal()]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    expect(within(row('p-1')).queryByRole('button', { name: /Approve|Reject/ })).toBeNull();
    expect(within(row('w-1')).queryByRole('button', { name: /Approve|Reject/ })).toBeNull();
    expect(within(row('p-1')).getByText('You can view this action but not approve it.')).toBeTruthy();
    expect(within(row('p-1')).getByText('Drain node')).toBeTruthy();
    expect(approveProposal).not.toHaveBeenCalled();
    expect(rejectProposal).not.toHaveBeenCalled();
  });

  it('uses the node action id for node rows and the workload action id for workload rows', async () => {
    getProposals.mockResolvedValue(list([proposal(), workloadProposal()]));
    renderPage();
    await waitFor(() => expect(row('w-1')).toBeTruthy());
    expect(canMock).toHaveBeenCalledWith('proposals.approve.node', { cluster: 'prod-eks' });
    expect(canMock).toHaveBeenCalledWith('proposals.approve.workload', { cluster: 'prod-eks' });
    expect(within(row('w-1')).getByText('CPU request 500m to 220m')).toBeTruthy();
  });

  it('a DRY_RUN row says Dry run and offers Approve simulation', async () => {
    getProposals.mockResolvedValue(list([proposal({ dryRun: true })]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    expect(within(row('p-1')).getByText('Dry run')).toBeTruthy();
    expect(within(row('p-1')).getByRole('button', { name: /Approve simulation/ })).toBeTruthy();
  });

  it('AC-F3: refused, in-progress and terminal rows each have their own words', async () => {
    getProposals.mockResolvedValue(list([
      proposal({ id: 'r', status: 'REFUSED', verdict: 'REFUSED', refusalCodes: ['pdb-no-disruptions:shop/api', 'db-primary'] }),
      proposal({ id: 'ap', status: 'APPROVED' }),
      proposal({ id: 'run', status: 'DISPATCHED' }),
      proposal({ id: 'st', status: 'STALE' }),
      proposal({ id: 'fa', status: 'FAILED', statusReason: 'node not found' }),
      proposal({ id: 'ex', status: 'EXPIRED' }),
      proposal({ id: 'si', status: 'EXECUTED', dryRun: true }),
      proposal({ id: 'un', status: 'EXECUTED', outcomeVerified: false }),
    ]));
    renderPage();
    await waitFor(() => expect(row('r')).toBeTruthy());
    expect(within(row('r')).getByText('A PodDisruptionBudget allows no disruptions (shop/api)')).toBeTruthy();
    expect(within(row('r')).getByText('Hosts a database primary')).toBeTruthy();
    expect(within(row('r')).queryByRole('button', { name: /Approve|Reject/ })).toBeNull();
    expect(within(row('ap')).getByText('Approved, waiting for the agent')).toBeTruthy();
    expect(within(row('run')).getByText('Running')).toBeTruthy();
    expect(within(row('st')).getByText('Stale, the cluster changed before it ran')).toBeTruthy();
    expect(within(row('fa')).getByText('Failed: node not found')).toBeTruthy();
    expect(within(row('ex')).getByText('Expired before anyone decided')).toBeTruthy();
    expect(within(row('si')).getByText('Simulated, nothing changed')).toBeTruthy();
    expect(within(row('un')).getByText('Executed, not verified')).toBeTruthy();
  });

  it('AC-F3: loading, empty, read error with Retry, and UNREADABLE (never partial rows)', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    getProposals.mockRejectedValueOnce(new K8sApiError({ status: 500 }));
    renderPage();
    expect(screen.getByText('Reading pending actions…')).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Couldn't read pending actions")).toBeTruthy());
    getProposals.mockResolvedValueOnce(list([], { state: 'UNREADABLE', counts: null }));
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByText("The stored proposals couldn't be read, so none are shown.")).toBeTruthy());
    expect(document.querySelector('[data-proposal]')).toBeNull();
  });

  it('empty queue: honest copy, the last check-in, and the workload-proposals note', async () => {
    getProposals.mockResolvedValue(list([], { manual: { available: true, reason: null, workloadProposals: false } }));
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-pending-empty]')).toBeTruthy());
    const empty = document.querySelector('[data-pending-empty]')!.textContent!;
    expect(empty).toContain('No actions are waiting. XamOps checks about every minute.');
    expect(empty).toContain('Last agent check-in');
    expect(empty).toContain('enable it in Node autoscaler');
    expect(empty).toContain("Workload proposals aren't enabled in this environment.");
  });

  it('an unknown last check-in is an em dash', async () => {
    getProposals.mockResolvedValue(list([], { lastAgentCheckIn: null }));
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-pending-empty]')).toBeTruthy());
    expect(document.querySelector('[data-pending-empty]')!.textContent).toContain('Last agent check-in —.');
  });

  it('not in Manual: one line and no queue; unavailable shows the capability reason', async () => {
    getProposals.mockResolvedValue(list([proposal()], { approvalMode: 'AUTONOMOUS' }));
    renderPage();
    await waitFor(() => expect(screen.getByText('Manual mode is off for this cluster')).toBeTruthy());
    expect(document.querySelector('[data-proposal]')).toBeNull();
  });

  it('not available in this environment', async () => {
    getProposals.mockResolvedValue(list([], {
      approvalMode: 'AUTONOMOUS', manual: { available: false, reason: 'CONTROLLER_NOT_READY', workloadProposals: false },
    }));
    renderPage();
    await waitFor(() => expect(screen.getByText("Manual mode isn't enabled in this environment.")).toBeTruthy());
  });

  it('switching and unknown effective states show their status lines', async () => {
    getProposals.mockResolvedValue(list([], { effective: 'switching' }));
    const view = renderPage();
    await waitFor(() => expect(document.querySelector('[data-effective="switching"]')).toBeTruthy());
    expect(document.querySelector('[data-effective="switching"]')!.textContent).toContain('Switching to Manual: confirming with the automation agent.');
    view.unmount();
    getProposals.mockResolvedValue(list([], { effective: 'unknown' }));
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-effective="unknown"]')).toBeTruthy());
    expect(document.querySelector('[data-effective="unknown"]')!.textContent).toContain("can't confirm the switch");
  });

  it('refreshes every 30 s while visible and keeps the rows when a refresh fails', async () => {
    getProposals.mockResolvedValue(list([proposal()]));
    renderPage();
    await waitFor(() => expect(row('p-1')).toBeTruthy());
    expect(getProposals).toHaveBeenCalledTimes(1);
    getProposals.mockRejectedValue(new K8sApiError({ status: 500 }));
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(getProposals).toHaveBeenCalledTimes(2);
    expect(row('p-1')).toBeTruthy();
  });

  it('says so when the list is capped', async () => {
    getProposals.mockResolvedValue(list([proposal()], { nextCursor: 'abc' }));
    renderPage();
    await waitFor(() => expect(document.querySelector('[data-capped]')).toBeTruthy());
  });
});
