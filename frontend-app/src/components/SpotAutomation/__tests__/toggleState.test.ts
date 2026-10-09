import { describe, it, expect } from 'vitest';
import { awsToggleState, gcpToggleState, GKE_LOCK_REASON, PAUSED_TOOLTIP } from '../toggleState';

describe('awsToggleState', () => {
  it('locks EKS-managed groups whatever the pause state', () => {
    expect(awsToggleState({ isEnabled: false, eksManaged: true }, false)).toEqual({ mode: 'locked' });
    expect(awsToggleState({ isEnabled: true, eksManaged: true }, true)).toEqual({ mode: 'locked' });
  });

  it('disables Enable while paused', () => {
    expect(awsToggleState({ isEnabled: false, eksManaged: false }, true)).toEqual({ mode: 'disabled', reason: PAUSED_TOOLTIP });
  });

  it('keeps Disable available while paused', () => {
    expect(awsToggleState({ isEnabled: true, eksManaged: false }, true)).toEqual({ mode: 'switch' });
  });

  it('is a normal switch when not paused', () => {
    expect(awsToggleState({ isEnabled: false, eksManaged: false }, false)).toEqual({ mode: 'switch' });
    expect(awsToggleState({ isEnabled: true, eksManaged: false }, false)).toEqual({ mode: 'switch' });
  });
});

describe('gcpToggleState', () => {
  it('locks GKE pools that are not on Spot', () => {
    expect(gcpToggleState({ isEnabled: false, gkeManagedPool: true }, false)).toEqual({ mode: 'locked', reason: GKE_LOCK_REASON });
    expect(gcpToggleState({ isEnabled: false, gkeManagedPool: true, xamopsManagedTemplate: true }, true)).toEqual({ mode: 'locked', reason: GKE_LOCK_REASON });
  });

  it('locks GKE Spot pools XamOps did not convert', () => {
    expect(gcpToggleState({ isEnabled: true, gkeManagedPool: true, xamopsManagedTemplate: false }, false)).toEqual({ mode: 'locked', reason: GKE_LOCK_REASON });
    expect(gcpToggleState({ isEnabled: true, gkeManagedPool: true }, true)).toEqual({ mode: 'locked', reason: GKE_LOCK_REASON });
  });

  it('lets XamOps-converted GKE pools be disabled, even while paused', () => {
    expect(gcpToggleState({ isEnabled: true, gkeManagedPool: true, xamopsManagedTemplate: true }, true)).toEqual({ mode: 'switch' });
  });

  it('disables Enable while paused', () => {
    expect(gcpToggleState({ isEnabled: false, gkeManagedPool: false }, true)).toEqual({ mode: 'disabled', reason: PAUSED_TOOLTIP });
    expect(gcpToggleState({ isEnabled: false }, true)).toEqual({ mode: 'disabled', reason: PAUSED_TOOLTIP });
  });

  it('keeps Disable available while paused', () => {
    expect(gcpToggleState({ isEnabled: true, gkeManagedPool: false }, true)).toEqual({ mode: 'switch' });
  });

  it('is a normal switch when not paused', () => {
    expect(gcpToggleState({ isEnabled: false, gkeManagedPool: false }, false)).toEqual({ mode: 'switch' });
    expect(gcpToggleState({ isEnabled: true }, false)).toEqual({ mode: 'switch' });
  });
});
