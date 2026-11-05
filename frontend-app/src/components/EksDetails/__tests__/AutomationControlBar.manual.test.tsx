import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, setAutomationScope, setApprovalMode } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  setAutomationScope: vi.fn(),
  setApprovalMode: vi.fn(),
}));
vi.mock('../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities, setAutomationScope, setApprovalMode };
});

import { K8sCapabilitiesProvider } from '../../../hooks/useK8sCapabilities';
import { ACCOUNT, CLUSTER, capsDto, type CapsOptions } from '../../../hooks/__tests__/nodeControlFixtures';
import type { CapabilitiesManual } from '../../../types/k8sAutomation';
import { AutomationControlBar, MANUAL_COMING_TEXT } from '../AutomationControlBar';

const PENDING = '/eks-details?tab=automation&sub=pending-actions';
const manual = (o: Partial<CapabilitiesManual> = {}): CapabilitiesManual => ({
  mode: 'AUTONOMOUS', effective: 'active', pending: 0, available: true, reason: null, workloadProposals: true, ...o,
});

async function renderBar(opts: CapsOptions = {}) {
  getCapabilities.mockResolvedValue(capsDto(opts));
  render(
    <MemoryRouter>
      <K8sCapabilitiesProvider accountId={ACCOUNT} demo={false}>
        <AutomationControlBar clusterName={CLUSTER} accountId={ACCOUNT} workloadsHref="/w" nodeAutoscalerHref="/n" pendingActionsHref={PENDING} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getByTestId('automation-control-bar').getAttribute('aria-busy')).toBeNull());
}
const manualButton = () => screen.getByRole('button', { name: /^Manual/ });

describe('AutomationControlBar: Manual segment (Phase B)', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    setAutomationScope.mockReset();
    setApprovalMode.mockReset().mockResolvedValue({ mode: 'MANUAL', effective: 'switching', changedAt: null });
    localStorage.clear();
  });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('stays the Phase A shell when the backend reports no manual block', async () => {
    await renderBar({ mode: 'full' });
    expect(manualButton()).toHaveAttribute('aria-disabled', 'true');
    expect(manualButton().textContent).toContain('Coming');
    expect(document.getElementById(manualButton().getAttribute('aria-describedby')!)?.textContent).toBe(MANUAL_COMING_TEXT);
  });

  it('says why when Manual is reported but unavailable', async () => {
    await renderBar({ mode: 'full', manual: manual({ available: false, reason: 'AGENT_TOO_OLD' }) });
    expect(manualButton()).toHaveAttribute('aria-disabled', 'true');
    expect(document.getElementById(manualButton().getAttribute('aria-describedby')!)?.textContent).toContain('too old');
  });

  it('is live when available: no Coming label, no aria-disabled, and the confirm names the consequence', async () => {
    const user = userEvent.setup();
    await renderBar({ mode: 'full', manual: manual() });
    expect(manualButton()).not.toHaveAttribute('aria-disabled');
    expect(manualButton().textContent).not.toContain('Coming');
    expect(manualButton()).toHaveAttribute('aria-pressed', 'false');
    await user.click(manualButton());
    const dialog = screen.getByRole('alertdialog');
    expect(dialog.textContent).toContain('propose each action and wait for your approval');
    expect(dialog.textContent).toContain('usually within a minute');
    expect(dialog.textContent).toContain('Spot-interruption handling is paused while a cluster is in Manual.');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    expect(setApprovalMode).not.toHaveBeenCalled();
  });

  it('confirm sends one approval-mode write and reads "Switching to Manual" until the agent confirms', async () => {
    const user = userEvent.setup();
    await renderBar({ mode: 'full', manual: manual() });
    getCapabilities.mockResolvedValue(capsDto({ mode: 'full', manual: manual({ mode: 'MANUAL', effective: 'switching', pending: 0 }) }));
    await user.click(manualButton());
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Switch to Manual' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(setApprovalMode).toHaveBeenCalledTimes(1);
    expect(setApprovalMode).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'MANUAL');
    expect(setAutomationScope).not.toHaveBeenCalled();
    expect(screen.getByText('Switching to Manual')).toBeTruthy();
    expect(manualButton()).toHaveAttribute('aria-pressed', 'true');
  });

  it('re-reads capabilities every 5 s while switching and stops after 2 minutes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await renderBar({ mode: 'full', manual: manual({ mode: 'MANUAL', effective: 'switching' }) });
    const before = getCapabilities.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000); });
    expect(getCapabilities.mock.calls.length).toBeGreaterThan(before);
    await act(async () => { await vi.advanceTimersByTimeAsync(2 * 60_000); });
    const settled = getCapabilities.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(getCapabilities.mock.calls.length).toBe(settled);
  });

  it('shows Manual once the agent confirms, with the pending count link; unknown count is an em dash', async () => {
    await renderBar({ mode: 'full', manual: manual({ mode: 'MANUAL', pending: 3 }) });
    expect(screen.getByText('Manual', { selector: 'span span' })).toBeTruthy();
    expect(manualButton()).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Autonomous' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('link', { name: 'Pending actions (3)' })).toHaveAttribute('href', PENDING);
  });

  it('does not claim Manual when the agent has not checked in', async () => {
    await renderBar({ mode: 'full', manual: manual({ mode: 'MANUAL', effective: 'unknown', pending: null }) });
    expect(screen.getByText('Manual, not confirmed')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Pending actions (—)' })).toBeTruthy();
  });

  it('the reverse switch says how many waiting proposals will be marked stale', async () => {
    const user = userEvent.setup();
    setApprovalMode.mockResolvedValue({ mode: 'AUTONOMOUS', effective: 'active', changedAt: null });
    await renderBar({ mode: 'full', manual: manual({ mode: 'MANUAL', pending: 2 }) });
    await user.click(screen.getByRole('button', { name: 'Autonomous' }));
    expect(screen.getByRole('alertdialog').textContent).toContain('2 waiting proposals will be marked stale');
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Switch to Autonomous' }));
    await waitFor(() => expect(setApprovalMode).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'AUTONOMOUS'));
  });

  it('a read-only connection cannot switch: no dialog, no request', async () => {
    const user = userEvent.setup();
    await renderBar({ mode: 'full', manual: manual(), writeReasons: ['READ_ONLY_CONNECTION'] });
    await user.click(manualButton());
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(setApprovalMode).not.toHaveBeenCalled();
  });
});
