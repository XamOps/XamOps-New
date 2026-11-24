import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { installFetch, renderWithProviders } from '../../__tests__/helpers';
import { awsAdapter } from '../../adapters/awsAdapter';
import ProgressTab from '../ProgressTab';
import { MONEY_LABELS, MoneyFigure } from '../MoneyLabel';

afterEach(() => vi.unstubAllGlobals());

const op = (over: Record<string, unknown> = {}) => ({
  operationId: 'op-1',
  groupId: 'g-1',
  kind: 'ENABLE',
  status: 'RUNNING',
  phase: 'FORWARD',
  step: 4,
  waitReason: null,
  progress: JSON.stringify({
    replaced: 9,
    total: 20,
    percent: 45,
    spot: 9,
    onDemand: 11,
    desired: 20,
    inService: 20,
    failed: 1,
    failures: [{ zone: 'us-east-1c', code: 'InsufficientInstanceCapacity', retrying: true }],
  }),
  errorCode: null,
  errorMessage: null,
  dryRun: false,
  deadlineAt: null,
  finishedAt: null,
  ...over,
});

const mount = (o: unknown) => {
  const fx = installFetch([{ match: /\/operations\/op-1$/, responses: [{ body: o }] }]);
  renderWithProviders(<ProgressTab accountId={7} operationId="op-1" adapter={awsAdapter} onViewAudit={() => {}} />);
  return fx;
};

describe('ProgressTab', () => {
  it('shows the progress bar, in-service vs desired and the retried failures', async () => {
    mount(op());
    const bar = await screen.findByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '45');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
    expect(screen.getByText('9 of 20 instances replaced')).toBeInTheDocument();
    expect(screen.getByText('Spot 9 On-Demand 11 Desired 20 In service 20')).toBeInTheDocument();
    expect(screen.getByText('us-east-1c InsufficientInstanceCapacity retried by AWS')).toBeInTheDocument();
  });

  it('labels a dry run', async () => {
    mount(op({ dryRun: true }));
    expect(await screen.findByText('Dry run')).toBeInTheDocument();
  });

  it('maps wait reasons to plain text', async () => {
    mount(op({ status: 'WAITING', waitReason: 'STUCK' }));
    expect(await screen.findByText(/Taking longer than expected/)).toBeInTheDocument();
  });

  it('says the original is being restored while compensating', async () => {
    mount(op({ phase: 'COMPENSATING' }));
    expect(await screen.findByText('Apply failed. Restoring the original configuration.')).toBeInTheDocument();
  });

  it('shows the error panel with the stable code and copyable ids for a failed operation', async () => {
    mount(op({ status: 'FAILED', errorCode: 'PERMISSION', finishedAt: '2026-11-17T10:00:00Z' }));
    expect(await screen.findByText('Needs attention')).toBeInTheDocument();
    expect(screen.getByText('Code: PERMISSION')).toBeInTheDocument();
    expect(screen.getByText(/Operation id op-1/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy operation id' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Update stack' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View audit trail' })).toBeInTheDocument();
  });

  it('stops polling once the operation is terminal', async () => {
    const { calls } = mount(op({ status: 'SUCCEEDED', finishedAt: '2026-11-17T10:00:00Z' }));
    await screen.findByText('Completed');
    await new Promise(r => setTimeout(r, 150));
    expect(calls.filter(c => /operations/.test(c.url))).toHaveLength(1);
  });

  it('says so when there is no operation', () => {
    renderWithProviders(<ProgressTab accountId={7} operationId={null} adapter={awsAdapter} />);
    expect(screen.getByText('No operation is running for this group.')).toBeInTheDocument();
  });
});

describe('MoneyLabel', () => {
  it('has exactly the five labels', () => {
    expect([...MONEY_LABELS]).toEqual(['Estimate', 'Billable', 'Not invoiced', 'Legacy (unverified)', 'Accruing (not final)']);
  });

  it('renders a figure with its label, and Not priced without one', () => {
    const { rerender } = renderWithProviders(<MoneyFigure amount={820} label="Estimate" perMonth />);
    expect(screen.getByText('USD 820/mo')).toBeInTheDocument();
    expect(screen.getByText('Estimate')).toBeInTheDocument();
    rerender(<MoneyFigure amount={null} label="Estimate" />);
    expect(screen.getByText('Not priced')).toBeInTheDocument();
    expect(screen.queryByText('Estimate')).toBeNull();
  });
});
