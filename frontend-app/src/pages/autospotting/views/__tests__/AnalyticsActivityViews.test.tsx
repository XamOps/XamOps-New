import { describe, it, expect, afterEach, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installFetch, renderWithProviders } from '../../__tests__/helpers';
import AnalyticsView from '../AnalyticsView';
import ActivityView from '../ActivityView';

afterEach(() => vi.unstubAllGlobals());

const fig = (over: Record<string, unknown> = {}) => ({
  spotLaunches: 9,
  launchFailures: 1,
  failuresByCause: { SPOT_CAPACITY: 1 },
  interruptions: 2,
  rebalanceRecommendations: 3,
  successRate: 90,
  ...over,
});
const NULLS = { spotLaunches: null, launchFailures: null, failuresByCause: {}, interruptions: null, rebalanceRecommendations: null, successRate: null };
const analytics = (over: Record<string, unknown> = {}) => ({
  from: '2026-10-22T00:00:00Z',
  to: '2026-11-21T00:00:00Z',
  notTracked: [],
  totals: fig(),
  groups: [
    {
      groupId: 'g-1', groupKey: 'us-east-1/web-asg', name: 'web-asg', location: 'us-east-1', figures: fig(),
      byType: [{ instanceType: 'm5.large', figures: fig() }],
    },
  ],
  ...over,
});
const audit = (id: number, outcome = 'OK') => ({
  id, occurredAt: '2026-11-19T10:00:00Z', eventType: 'DRY_RUN', actorType: 'USER', actorRole: 'ROLE_USER',
  operationId: null, planId: null, groupId: null, groupKey: 'us-east-1/web-asg', action: 'ENABLE', outcome, errorCode: null,
});

describe('AnalyticsView', () => {
  it('shows "Not tracked" instead of 0 when the account has no event source', async () => {
    installFetch([
      {
        match: /\/accounts\/7\/analytics/,
        responses: [{
          body: analytics({
            notTracked: ['spotLaunches', 'launchFailures', 'interruptions', 'rebalanceRecommendations', 'successRate'],
            totals: NULLS,
            groups: [],
          }),
        }],
      },
    ]);
    renderWithProviders(<AnalyticsView accountId={7} />);
    const row = (await screen.findByText('All groups')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('Not tracked')).toHaveLength(5);
    expect(within(row).queryByText('0')).not.toBeInTheDocument();
    expect(screen.getByText(/none of these figures is tracked/)).toBeInTheDocument();
  });

  it('renders figures per group and per instance type with the classified cause', async () => {
    installFetch([{ match: /\/accounts\/7\/analytics/, responses: [{ body: analytics() }] }]);
    renderWithProviders(<AnalyticsView accountId={7} />);
    await screen.findByText('web-asg');
    expect(screen.getByText('m5.large')).toBeInTheDocument();
    expect(screen.getAllByText(/SPOT_CAPACITY 1/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('90%').length).toBeGreaterThan(0);
  });

  it('says so when a tracked account has no events in the range', async () => {
    installFetch([
      { match: /\/accounts\/7\/analytics/, responses: [{ body: analytics({ groups: [], totals: fig({ spotLaunches: 0, launchFailures: 0, failuresByCause: {}, interruptions: 0, rebalanceRecommendations: 0, successRate: null }) }) }] },
    ]);
    renderWithProviders(<AnalyticsView accountId={7} />);
    expect(await screen.findByText('No Spot events in this range.')).toBeInTheDocument();
    expect(screen.getByText('No launches')).toBeInTheDocument();
  });

  it('names the metrics that are not tracked when only some are missing (partial)', async () => {
    installFetch([
      { match: /\/accounts\/7\/analytics/, responses: [{ body: analytics({ notTracked: ['rebalanceRecommendations'], totals: fig({ rebalanceRecommendations: null }) }) }] },
    ]);
    renderWithProviders(<AnalyticsView accountId={7} />);
    expect(await screen.findByText(/Not tracked for this account: Rebalance recommendations/)).toBeInTheDocument();
  });

  it('shows an error with a retry, and requests the chosen range', async () => {
    const fx = installFetch([
      { match: /\/accounts\/7\/analytics/, responses: [{ status: 500, body: { code: 'INTERNAL', requestId: 'req-1' } }, { body: analytics() }] },
    ]);
    renderWithProviders(<AnalyticsView accountId={7} />);
    expect(await screen.findByText("Couldn't load analytics.")).toBeInTheDocument();
    expect(screen.getByText('Request id req-1')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('web-asg')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '7 days' }));
    await screen.findByText('web-asg');
    expect(fx.calls.some(c => /from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/.test(c.url))).toBe(true);
  });
});

describe('ActivityView', () => {
  it('lists the audit rows with their outcome', async () => {
    installFetch([{ match: /\/accounts\/7\/audit/, responses: [{ body: [audit(2, 'WOULD_DO'), audit(1)] }] }]);
    renderWithProviders(<ActivityView accountId={7} />);
    expect(await screen.findByText('WOULD_DO')).toBeInTheDocument();
    expect(screen.getAllByText('ENABLE')).toHaveLength(2);
  });

  it('shows the empty state', async () => {
    installFetch([{ match: /\/accounts\/7\/audit/, responses: [{ body: [] }] }]);
    renderWithProviders(<ActivityView accountId={7} />);
    expect(await screen.findByText('No activity in this range.')).toBeInTheDocument();
  });

  it('shows an error with a retry', async () => {
    installFetch([{ match: /\/accounts\/7\/audit/, responses: [{ status: 500, body: {} }, { body: [audit(1)] }] }]);
    renderWithProviders(<ActivityView accountId={7} />);
    expect(await screen.findByText("Couldn't load activity.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('ENABLE')).toBeInTheDocument();
  });

  it('says the list is cut off when it reaches the limit (partial)', async () => {
    const many = Array.from({ length: 100 }, (_, i) => audit(i + 1));
    installFetch([{ match: /\/accounts\/7\/audit/, responses: [{ body: many }] }]);
    renderWithProviders(<ActivityView accountId={7} />);
    expect(await screen.findByText(/Showing the latest 100 entries/)).toBeInTheDocument();
  });

  it('scopes the read to a group when one is given', async () => {
    const fx = installFetch([{ match: /\/accounts\/7\/audit/, responses: [{ body: [] }] }]);
    renderWithProviders(<ActivityView accountId={7} groupId="g-1" />);
    await screen.findByText('No activity in this range.');
    expect(fx.calls[0].url).toContain('groupId=g-1');
  });
});
