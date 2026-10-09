import type { ComponentProps } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SpotAutomationASGTable from '../SpotAutomationASGTable';
import type { ASG, SpotCosts } from '../../../hooks/useSpotAutomationData';

const costs: SpotCosts = { currentHourly: 0, currentMonthlyCost: 0, onDemandHourly: 0, hourlyActualSavings: 0 };

const makeAsg = (overrides: Partial<ASG>): ASG => ({
  name: 'asg',
  region: 'us-east-1',
  isEnabled: false,
  eksManaged: false,
  instanceCount: 2,
  spotCount: 0,
  onDemandCount: 2,
  instanceTypes: 't3.micro',
  currentCost: 0.02,
  onDemandCost: 0.02,
  savings: 0,
  potentialSavings: 0.01,
  ...overrides,
});

type Props = ComponentProps<typeof SpotAutomationASGTable>;

const renderTable = (overrides: Partial<Props> = {}) => {
  const props: Props = {
    asgs: [],
    costs,
    totalInstances: 0,
    isLoading: false,
    selectedAccountId: '123456789012',
    onToggleASG: vi.fn().mockResolvedValue(undefined),
    onSyncASG: vi.fn().mockResolvedValue({ backfilled: 0, message: '' }),
    onRefresh: vi.fn(),
    ...overrides,
  };
  render(
    <MemoryRouter>
      <SpotAutomationASGTable {...props} />
    </MemoryRouter>,
  );
  return props;
};

describe('SpotAutomationASGTable', () => {
  it('shows an error state with Retry instead of the empty state when the fetch failed', () => {
    const onRetry = vi.fn();
    renderTable({ error: 'Access denied text', onRetry });

    expect(screen.getByText("Couldn't load Auto Scaling groups")).toBeInTheDocument();
    expect(screen.getByText('Access denied text')).toBeInTheDocument();
    expect(screen.queryByText('No Auto Scaling Groups Found')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('shows the empty state when there is no error', () => {
    renderTable({ error: null });
    expect(screen.getByText('No Auto Scaling Groups Found')).toBeInTheDocument();
    expect(screen.queryByText("Couldn't load Auto Scaling groups")).not.toBeInTheDocument();
  });

  it('disables Enable but keeps Disable while conversions are paused', () => {
    const asgs = [
      makeAsg({ name: 'asg-off', isEnabled: false }),
      makeAsg({ name: 'asg-on', isEnabled: true, spotCount: 2, onDemandCount: 0 }),
    ];
    const props = renderTable({ asgs, totalInstances: 4, conversionsPaused: true });

    const off = screen.getByRole('switch', { name: 'AutoSpotting for asg-off' });
    const on = screen.getByRole('switch', { name: 'AutoSpotting for asg-on' });
    expect(off).toBeDisabled();
    expect(on).toBeEnabled();
    expect(off).toHaveAccessibleDescription('New Spot conversions are paused while AutoSpotting is being upgraded.');

    fireEvent.click(off);
    expect(props.onToggleASG).not.toHaveBeenCalled();
  });

  it('keeps the rows and shows a banner when a refresh failed', () => {
    const asgs = [makeAsg({ name: 'asg-kept' })];
    const onRetry = vi.fn();
    renderTable({ asgs, totalInstances: 2, error: 'Request failed', onRetry });

    expect(screen.getByText("Couldn't refresh. Showing the last loaded data.")).toBeInTheDocument();
    expect(screen.getByText('asg-kept')).toBeInTheDocument();
    expect(screen.queryByText("Couldn't load Auto Scaling groups")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
