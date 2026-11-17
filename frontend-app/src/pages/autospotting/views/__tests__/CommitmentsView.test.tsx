import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders } from '../../__tests__/helpers';

vi.mock('../../../../contexts/TopbarContext', () => ({ useTopbar: () => ({ setActions: () => {}, clearActions: () => {} }) }));
vi.mock('goey-toast', () => ({ gooeyToast: { success: vi.fn(), error: vi.fn() } }));

import CommitmentsView from '../CommitmentsView';

afterEach(() => vi.unstubAllGlobals());

const coverage = {
  collected_at: '2026-11-17T10:00:00Z',
  total_ri_utilization_percent: 78.5,
  total_sp_utilization_percent: 85.2,
  ri_coverages: [{ instance_type: 'm5.large', region: 'us-east-1', availability_zone: 'us-east-1a', coverage_percent: 92 }],
  sp_coverages: [{ plan_type: 'Compute Savings Plan', coverage_percent: 87.5 }],
};

describe('CommitmentsView', () => {
  it('loads the saved RI/SP setting from the v2 endpoint', async () => {
    const fx = installFetch([
      { match: /\/accounts\/7\/commitments\/coverage$/, responses: [{ body: coverage }] },
      { match: /\/accounts\/7\/commitments\/settings$/, responses: [{ body: { respectRiSpCommitments: true, riSpUtilizationThreshold: 75 } }] },
    ]);
    renderWithProviders(<CommitmentsView accountId={7} />, '/autospotting?view=commitments');
    expect(await screen.findByText('75%')).toBeInTheDocument();
    expect(screen.getByRole('switch')).toBeChecked();
    const urls = fx.calls.map(c => c.url);
    expect(urls.some(u => u.includes('/api/autospotting/v2/accounts/7/commitments/coverage'))).toBe(true);
    // The shell never reads the legacy paths.
    expect(urls.some(u => u.includes('/api/autospotting/settings/') || u.includes('/analytics/risp-coverage/'))).toBe(false);
  });

  it('saves through the v2 PUT with the intent header and an idempotency key', async () => {
    const fx = installFetch([
      { match: /\/accounts\/7\/commitments\/coverage$/, responses: [{ body: coverage }] },
      { match: /\/accounts\/7\/commitments\/settings$/, responses: [{ body: { respectRiSpCommitments: false, riSpUtilizationThreshold: 90 } }] },
      {
        method: 'PUT',
        match: /\/accounts\/7\/commitments\/settings\/risp-respect$/,
        responses: [{ body: { respectRiSpCommitments: true, riSpUtilizationThreshold: 90 } }],
      },
    ]);
    renderWithProviders(<CommitmentsView accountId={7} />, '/autospotting?view=commitments');
    await screen.findByText('Respect RI/SP Commitments');
    await userEvent.click(screen.getByRole('switch'));
    await userEvent.click(screen.getByRole('button', { name: /Save Settings/ }));
    await waitFor(() => expect(fx.calls.some(c => c.method === 'PUT')).toBe(true));
    const put = fx.calls.find(c => c.method === 'PUT')!;
    expect(put.url).toContain('/api/autospotting/v2/accounts/7/commitments/settings/risp-respect');
    expect(put.headers['X-XamOps-Intent']).toBe('autospotting');
    expect(put.headers['Idempotency-Key']).toBeTruthy();
    expect(put.body).toEqual({ respectRiSpCommitments: true, riSpUtilizationThreshold: 90 });
  });

  it('asks for an account when none is selected', () => {
    renderWithProviders(<CommitmentsView accountId={null} />);
    expect(screen.getByText(/Select an AWS account/)).toBeInTheDocument();
  });
});
