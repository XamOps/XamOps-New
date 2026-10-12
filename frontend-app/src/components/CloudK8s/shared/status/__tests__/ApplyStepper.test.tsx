import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ApplyStepper, { buildApplySteps } from '../ApplyStepper';
import type { ApplyOutcomeWire } from '../../../../../types/workloadRightsizing';

const pod = (name: string, ready: boolean): NonNullable<ApplyOutcomeWire['pods']>[number] => ({
  podName: name,
  uidBefore: 'u1',
  uidAfter: 'u1',
  samePod: true,
  ready,
  containers: [{
    name: 'app', containerIdBefore: 'c1', containerIdAfter: 'c1',
    restartCountBefore: 0, restartCountAfter: 0, observedBefore: null, observedAfter: null,
  }],
});

const outcome = (terminal: string, pods: ApplyOutcomeWire['pods']): ApplyOutcomeWire => ({
  method: 'DIRECT', terminal, detail: 'detail', restartFree: true, containers: null, pods,
});

const last = <T,>(a: T[]): T => a[a.length - 1];

describe('buildApplySteps (glossary §6)', () => {
  it('waits for the cluster when there is no outcome yet', () => {
    const steps = buildApplySteps(null);
    expect(steps).toHaveLength(1);
    expect(steps[0].title).toBe('Waiting for the cluster…');
  });

  // Today's idle-batch-listener success.
  it('APPLIED: checks pass, pods confirmed, done banded', () => {
    const steps = buildApplySteps(outcome('APPLIED', [pod('idle-batch-listener-b6d6898cd-2bddb', true)]));
    expect(steps[0].title).toBe('Checked the safety rules: all passed');
    expect(last(steps)).toMatchObject({ key: 'done', tone: 'good', banded: true });
  });

  // Today's throttled-encoder deferral: honest interim copy, no "put back" claim.
  it('DEFERRED: a deferred step and a warn result with interim copy, no revert claim', () => {
    const steps = buildApplySteps(outcome('DEFERRED', [pod('throttled-encoder-8b999cc87-mcw8q', false)]));
    const result = steps.find((s) => s.key === 'result')!;
    expect(result.tone).toBe('warn');
    expect(result.detail).toBe('Kubernetes deferred this. The new size may still be applied later.');
    expect(steps.some((s) => /put the pod back/i.test(s.title))).toBe(false);
  });

  it('REFUSED: safety checks fail and nothing is sent', () => {
    const steps = buildApplySteps(outcome('REFUSED', null));
    expect(steps[0]).toMatchObject({ key: 'checks', tone: 'bad', title: 'Stopped by a safety rule' });
    expect(last(steps)).toMatchObject({ key: 'result', tone: 'bad', banded: true });
    expect(steps.some((s) => s.key.startsWith('pod-'))).toBe(false);
  });

  it('INFEASIBLE is a bad terminal path', () => {
    const steps = buildApplySteps(outcome('INFEASIBLE', [pod('p-1', false)]));
    expect(steps.some((s) => s.key === 'infeasible' && s.tone === 'bad')).toBe(true);
    expect(last(steps)).toMatchObject({ tone: 'bad', banded: true });
  });

  it('DRY_RUN renders as a test run, never a success', () => {
    const steps = buildApplySteps(outcome('DRY_RUN', [pod('p-1', true)]));
    expect(last(steps)).toMatchObject({ key: 'done', tone: 'info' });
  });
});

describe('ApplyStepper render', () => {
  it('renders each step and the live subline', () => {
    render(<ApplyStepper outcome={outcome('APPLIED', [pod('idle-batch-listener-x', true)])} live />);
    expect(screen.getByText('Apply progress')).toBeTruthy();
    expect(screen.getByText('Live: updates every 2 seconds.')).toBeTruthy();
    expect(screen.getByText('Done: applied in every pod, no restart')).toBeTruthy();
  });

  it('renders the deferred interim copy', () => {
    render(<ApplyStepper outcome={outcome('DEFERRED', [pod('throttled-encoder-x', false)])} />);
    expect(screen.getByText('Kubernetes deferred this. The new size may still be applied later.')).toBeTruthy();
  });
});
