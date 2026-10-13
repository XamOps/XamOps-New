import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent, waitFor, act } from '@testing-library/react';
import type { TrialAdminRow } from '../../../types/billopsAdminTrials';

/**
 * The hook is mocked, but statefully: the mocked `clearMutationState` nulls `mutationError`
 * exactly as the real one does, so the tests can see whether a dismissed error can come back.
 * The mutation functions live on the shared state too, so they are the same spies across
 * renders and their call counts mean something.
 */
const hookState = vi.hoisted(() => ({
  rows: [] as TrialAdminRow[],
  loading: false,
  error: null as string | null,
  mutatingClientId: null as number | null,
  mutationError: null as string | null,
  refresh: vi.fn(),
  extendTrial: vi.fn(),
  convertToPaid: vi.fn(),
  clearMutationState: vi.fn(),
}));

vi.mock('../../../hooks/useBillOpsAdminTrials', () => ({
  useBillOpsAdminTrials: () => ({
    rows: hookState.rows,
    loading: hookState.loading,
    error: hookState.error,
    mutatingClientId: hookState.mutatingClientId,
    mutationError: hookState.mutationError,
    lastResult: null,
    refresh: hookState.refresh,
    extendTrial: hookState.extendTrial,
    convertToPaid: hookState.convertToPaid,
    clearMutationState: hookState.clearMutationState,
  }),
}));

import TrialManagementSection from '../TrialManagementSection';

const daysFromNow = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString().slice(0, 19);

const expiredRow: TrialAdminRow = {
  clientId: 4, companyName: 'CloudBase Ltd', email: 'billing@cloudbase.example',
  planType: 'TRIAL', trialStatus: 'EXPIRED', trialEndAt: daysFromNow(-20), daysRemaining: 0,
};

const lapsedButActiveRow: TrialAdminRow = {
  clientId: 3, companyName: 'DataVault Inc', email: 'billing@datavault.example',
  planType: 'TRIAL', trialStatus: 'ACTIVE', trialEndAt: daysFromNow(-2), daysRemaining: 0,
};

const liveTrialRow: TrialAdminRow = {
  clientId: 2, companyName: 'Northwind Co', email: 'billing@northwind.example',
  planType: 'TRIAL', trialStatus: 'ACTIVE', trialEndAt: daysFromNow(9), daysRemaining: 9,
};

// Converted, yet the endpoint still reports a positive daysRemaining.
const convertedRow: TrialAdminRow = {
  clientId: 5, companyName: 'Sigma Tech', email: 'billing@sigmatech.example',
  planType: 'PAID', trialStatus: 'CONVERTED', trialEndAt: daysFromNow(12), daysRemaining: 12,
};

const rowFor = (name: string) => screen.getByText(name).closest('tr')!;

describe('TrialManagementSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hookState.rows = [];
    hookState.loading = false;
    hookState.error = null;
    hookState.mutatingClientId = null;
    hookState.mutationError = null;
    hookState.extendTrial.mockImplementation(async () => true);
    hookState.convertToPaid.mockImplementation(async () => true);
    hookState.clearMutationState.mockImplementation(() => { hookState.mutationError = null; });
  });

  it('flags a blocked tenant with the word EXPIRED', () => {
    hookState.rows = [expiredRow];
    render(<TrialManagementSection />);

    expect(within(rowFor('CloudBase Ltd')).getByText(/EXPIRED — tenant blocked \(402\)/)).toBeInTheDocument();
  });

  it('reads a lapsed end date on an ACTIVE row as EXPIRED', () => {
    hookState.rows = [lapsedButActiveRow];
    render(<TrialManagementSection />);

    expect(within(rowFor('DataVault Inc')).getByText(/EXPIRED — lapsed 2 days ago/)).toBeInTheDocument();
  });

  it('shows a converted tenant as paid and never claims days left', () => {
    hookState.rows = [convertedRow];
    render(<TrialManagementSection />);

    const row = rowFor('Sigma Tech');
    expect(within(row).getByText('Paid — converted from trial')).toBeInTheDocument();
    expect(within(row).queryByText(/days left/)).not.toBeInTheDocument();
    expect(within(row).queryByText(/12 days/)).not.toBeInTheDocument();
  });

  it('warns in the convert dialog that no invoice is written and it cannot be undone here', () => {
    hookState.rows = [expiredRow];
    render(<TrialManagementSection />);

    fireEvent.click(within(rowFor('CloudBase Ltd')).getByRole('button', { name: /convert to paid/i }));

    const dialog = screen.getByRole('alertdialog');
    expect(dialog).toHaveTextContent(/no subscription, no invoice and no entitlement record/i);
    expect(dialog).toHaveTextContent(/cannot be undone from this screen/i);
    expect(dialog).toHaveTextContent(/client #4/);
  });

  it('disables that row’s actions while its mutation is in flight', () => {
    hookState.rows = [expiredRow, lapsedButActiveRow];
    hookState.mutatingClientId = 4;
    render(<TrialManagementSection />);

    const busyRow = rowFor('CloudBase Ltd');
    expect(busyRow).toHaveAttribute('aria-busy', 'true');
    within(busyRow).getAllByRole('button').forEach(button => expect(button).toBeDisabled());

    // The other tenant stays operable.
    const otherRow = rowFor('DataVault Inc');
    expect(otherRow).toHaveAttribute('aria-busy', 'false');
    expect(within(otherRow).getByRole('button', { name: /extend trial/i })).toBeEnabled();
  });

  it('renders the hook error inline rather than blanking the section', () => {
    hookState.error = 'You need BillOps admin rights to manage trials.';
    render(<TrialManagementSection />);

    expect(screen.getByText('You need BillOps admin rights to manage trials.')).toBeInTheDocument();
    expect(screen.getByText('Trial Management')).toBeInTheDocument();
  });

  it('does not re-raise a dismissed extend failure as a modal', async () => {
    const message = 'The change could not be saved.';
    hookState.rows = [lapsedButActiveRow];
    hookState.extendTrial.mockImplementation(async () => false);

    const { rerender } = render(<TrialManagementSection />);
    fireEvent.click(within(rowFor('DataVault Inc')).getByRole('button', { name: /extend trial/i }));

    // Submit, then let the hook report the failure the way it would.
    const dialog = () => screen.getByRole('dialog');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Extend trial' }));
    await waitFor(() => expect(hookState.extendTrial).toHaveBeenCalledWith(3, 30));
    hookState.mutationError = message;
    rerender(<TrialManagementSection />);

    // Inline in the dialog that submitted it — not as a modal on top of it.
    expect(within(dialog()).getByText(message)).toBeInTheDocument();
    expect(screen.queryByText('Change not saved')).not.toBeInTheDocument();

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Cancel' }));

    expect(hookState.clearMutationState).toHaveBeenCalled();
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    expect(screen.queryByText('Change not saved')).not.toBeInTheDocument();
  });

  it('ignores a second convert confirmation while the first is in flight', async () => {
    hookState.rows = [expiredRow];
    // The POST stays pending, which is the whole window this test is about.
    let settle: ((ok: boolean) => void) | undefined;
    hookState.convertToPaid.mockImplementation(
      () => new Promise<boolean>(resolve => { settle = resolve; }));

    const { rerender } = render(<TrialManagementSection />);
    fireEvent.click(within(rowFor('CloudBase Ltd')).getByRole('button', { name: /convert to paid/i }));

    const confirm = within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Convert to paid' });
    fireEvent.click(confirm);
    await waitFor(() => expect(hookState.convertToPaid).toHaveBeenCalledTimes(1));

    // The POST is away: the hook now reports the tenant as mutating.
    hookState.mutatingClientId = 4;
    rerender(<TrialManagementSection />);

    const inFlight = within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Converting…' });
    expect(inFlight).toBeDisabled();
    fireEvent.click(inFlight);

    expect(hookState.convertToPaid).toHaveBeenCalledTimes(1);

    // Let the POST finish so the dialog closes the way it would in production.
    await act(async () => { settle!(true); });
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('states in the extend dialog that the extension runs from the later of today and the end date', () => {
    hookState.rows = [lapsedButActiveRow];
    render(<TrialManagementSection />);

    fireEvent.click(within(rowFor('DataVault Inc')).getByRole('button', { name: /extend trial/i }));

    expect(screen.getByText(/later of today and the current end date/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '30 days' })).toBeInTheDocument();
  });

  it('warns that extending a paid tenant puts it back on a trial plan and under 402 gating', () => {
    hookState.rows = [convertedRow];
    render(<TrialManagementSection />);

    fireEvent.click(within(rowFor('Sigma Tech')).getByRole('button', { name: /extend trial/i }));

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent(/not trial-gated today/i);
    expect(dialog).toHaveTextContent(/puts it back on a TRIAL plan/i);
    expect(dialog).toHaveTextContent(/blocked with 402 once the new end date passes/i);
  });

  it('does not warn about re-gating when the tenant is still on a live trial', () => {
    hookState.rows = [liveTrialRow];
    render(<TrialManagementSection />);

    fireEvent.click(within(rowFor('Northwind Co')).getByRole('button', { name: /extend trial/i }));

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent(/later of today and the current end date/i);
    expect(dialog).not.toHaveTextContent(/puts it back on a/i);
    expect(dialog).not.toHaveTextContent(/not trial-gated today/i);
  });

  it('shows a failed convert inside its own confirmation, without a second overlay', async () => {
    const message = 'That tenant no longer exists.';
    hookState.rows = [expiredRow];
    hookState.convertToPaid.mockImplementation(async () => false);

    const { rerender } = render(<TrialManagementSection />);
    fireEvent.click(within(rowFor('CloudBase Ltd')).getByRole('button', { name: /convert to paid/i }));

    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Convert to paid' }));
    await waitFor(() => expect(hookState.convertToPaid).toHaveBeenCalledWith(4));
    hookState.mutationError = message;
    rerender(<TrialManagementSection />);

    // One overlay, and the failure is in it.
    const dialogs = screen.getAllByRole('alertdialog');
    expect(dialogs).toHaveLength(1);
    expect(within(dialogs[0]).getByText(message)).toBeInTheDocument();
    expect(screen.queryByText('Change not saved')).not.toBeInTheDocument();

    // Dismissing the confirmation drops the message rather than re-raising it as a modal.
    fireEvent.click(within(dialogs[0]).getByRole('button', { name: 'Cancel' }));

    expect(hookState.clearMutationState).toHaveBeenCalled();
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    expect(screen.queryByText('Change not saved')).not.toBeInTheDocument();
  });
});
