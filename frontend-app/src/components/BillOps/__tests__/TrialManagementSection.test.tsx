import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import type { TrialAdminRow } from '../../../types/billopsAdminTrials';

const hookState = vi.hoisted(() => ({
  rows: [] as TrialAdminRow[],
  loading: false,
  error: null as string | null,
  mutatingClientId: null as number | null,
}));

vi.mock('../../../hooks/useBillOpsAdminTrials', () => ({
  useBillOpsAdminTrials: () => ({
    rows: hookState.rows,
    loading: hookState.loading,
    error: hookState.error,
    mutatingClientId: hookState.mutatingClientId,
    mutationError: null,
    lastResult: null,
    refresh: vi.fn(),
    extendTrial: vi.fn(),
    convertToPaid: vi.fn(),
    clearMutationState: vi.fn(),
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

// Converted, yet the endpoint still reports a positive daysRemaining.
const convertedRow: TrialAdminRow = {
  clientId: 5, companyName: 'Sigma Tech', email: 'billing@sigmatech.example',
  planType: 'PAID', trialStatus: 'CONVERTED', trialEndAt: daysFromNow(12), daysRemaining: 12,
};

const rowFor = (name: string) => screen.getByText(name).closest('tr')!;

describe('TrialManagementSection', () => {
  beforeEach(() => {
    hookState.rows = [];
    hookState.loading = false;
    hookState.error = null;
    hookState.mutatingClientId = null;
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

  it('states in the extend dialog that the extension runs from the later of today and the end date', () => {
    hookState.rows = [lapsedButActiveRow];
    render(<TrialManagementSection />);

    fireEvent.click(within(rowFor('DataVault Inc')).getByRole('button', { name: /extend trial/i }));

    expect(screen.getByText(/later of today and the current end date/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '30 days' })).toBeInTheDocument();
  });
});
