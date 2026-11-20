import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GcpConfigTab, { policyPlanKind } from '../GcpConfigTab';
import type { GroupDetail, GroupRow } from '../../../../api/autospottingApi';

const row = (state: string): GroupRow => ({
  id: null,
  provider: 'GCP',
  groupKey: 'p/us-central1/web',
  location: 'us-central1',
  name: 'web',
  state,
  managed: false,
  externalSpot: false,
  desired: 6,
  min: null,
  max: null,
  instanceType: null,
  enableAllowed: true,
  enableBlockedBy: null,
  blockers: [],
  warnings: [],
  deferred: [],
  monthlySavings: null,
  savingsUnknown: false,
  savingsLabel: null,
});
const detail = (state: string): GroupDetail => ({
  group: row(state),
  configMode: null,
  driftStatus: null,
  lastErrorCode: null,
  transitions: [],
  currentOperation: null,
});
const current = { updatePolicy: { type: 'OPPORTUNISTIC', maxSurge: { fixed: 3 }, maxUnavailable: { fixed: 3 } } };

describe('GcpConfigTab', () => {
  it('picks MIG_POLICY_UPDATE for an unmanaged MIG and CONFIG_APPLY for an active one', () => {
    expect(policyPlanKind('UNMANAGED')).toEqual({ kind: 'MIG_POLICY_UPDATE' });
    expect(policyPlanKind('REVERTED')).toEqual({ kind: 'MIG_POLICY_UPDATE' });
    expect(policyPlanKind('LEGACY_UNCONFIRMED')).toEqual({ kind: 'MIG_POLICY_UPDATE' });
    expect(policyPlanKind('ACTIVE')).toEqual({ kind: 'CONFIG_APPLY' });
  });

  it('shows a state reason, and no review button, for any other state', () => {
    render(<GcpConfigTab detail={detail('APPLYING')} current={current} onReview={() => {}} />);
    expect(screen.getByText(/AutoSpotting is changing this group/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Review plan' })).toBeNull();
    expect(policyPlanKind('FALLBACK')).toHaveProperty('reason');
    expect(policyPlanKind('PAUSED')).toEqual({ reason: 'Resolve the drift first, then change the settings.' });
  });

  it('sends only the edited fields and the plan kind for the state', () => {
    const onReview = vi.fn();
    render(<GcpConfigTab detail={detail('UNMANAGED')} current={current} onReview={onReview} />);
    expect(screen.getByRole('button', { name: 'Review plan' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Max surge'), { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    expect(onReview).toHaveBeenCalledWith('MIG_POLICY_UPDATE', { updatePolicy: { maxSurge: { fixed: 6 } } });
  });

  it('does not send a value equal to the current one, and never sends the type unless it was edited', () => {
    const onReview = vi.fn();
    render(<GcpConfigTab detail={detail('ACTIVE')} current={current} onReview={onReview} />);
    fireEvent.change(screen.getByLabelText('Max surge'), { target: { value: '3' } });
    expect(screen.getByRole('button', { name: 'Review plan' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Update type'), { target: { value: 'PROACTIVE' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    expect(onReview).toHaveBeenCalledWith('CONFIG_APPLY', { updatePolicy: { type: 'PROACTIVE' } });
  });

  it('refuses a negative value', () => {
    render(<GcpConfigTab detail={detail('UNMANAGED')} current={current} onReview={() => {}} />);
    fireEvent.change(screen.getByLabelText('Max unavailable'), { target: { value: '-1' } });
    expect(screen.getByRole('button', { name: 'Review plan' })).toBeDisabled();
  });
});
