import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ApplyStepper, { buildApplySteps } from '../ApplyStepper';
import type { ApplyOutcomeWire } from '../../../../../types/workloadRightsizing';
import type { ApplyOutcomeWire as VerticalOutcome } from '../../../../../types/verticalRightsizer';

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

describe('buildApplySteps: the put-back proof (pods[].revert, fix S7)', () => {
  const declined = (terminal: string, state: string, reverted: boolean, detail: string | null = null): VerticalOutcome => ({
    method: 'DIRECT',
    terminal,
    detail: 'OutOfcpu on ip-172-31-9-125',
    restartFree: true,
    containers: null,
    reverted,
    insufficient: 'cpu',
    pods: [{
      podName: 'throttled-encoder-8b999cc87-mcw8q', uidBefore: 'u1', uidAfter: 'u1', samePod: true, ready: true,
      containers: [{
        name: 'encoder', containerIdBefore: 'c1', containerIdAfter: 'c1', restartCountBefore: 0, restartCountAfter: 0,
        observedBefore: { cpuRequestM: 10, memRequestMi: 64, cpuLimitM: 50, memLimitMi: 128 },
        observedAfter: { cpuRequestM: 10, memRequestMi: 64, cpuLimitM: 50, memLimitMi: 128 },
      }],
      changed: false,
      revert: { state, detail },
    }],
  });

  // Today's throttled-encoder: deferred for CPU, put back and proved back.
  it('REVERTED: "So XamOps put the pod back", and nothing changed', () => {
    const steps = buildApplySteps(declined('DEFERRED', 'REVERTED', true));
    expect(steps.find((s) => s.key === 'deferred')!.title).toBe('Kubernetes deferred this: the node has no free CPU');
    const back = steps.find((s) => s.key === 'revert-0')!;
    expect(back).toMatchObject({ tone: 'warn', title: 'So XamOps put the pod back' });
    expect(back.detail).toBe('Back to 10m CPU, 64Mi memory, and the pod confirmed it: nothing is pending. No restart.');
    expect(last(steps)).toMatchObject({ key: 'result', title: 'Result: not applied. Nothing changed.', banded: true });
    expect(last(steps).detail).toContain('Make room on its node, then apply again.');
  });

  it.each([
    ['NOT_CONFIRMED', 'warn', "XamOps sent the pod back, but it didn't confirm"],
    ['FAILED', 'bad', "XamOps couldn't put the pod back"],
  ])('%s: the new size may still be applied later, and "Nothing changed" is never said', (state, tone, title) => {
    const steps = buildApplySteps(declined('DEFERRED', state, false, 'the API server refused the patch'));
    const back = steps.find((s) => s.key === 'revert-0')!;
    expect(back).toMatchObject({ tone, title });
    expect(back.detail).toContain('may still be applied later');
    expect(last(steps)).toMatchObject({ key: 'result', title: 'Result: not applied yet.' });
    expect(steps.some((s) => s.title.includes('Nothing changed'))).toBe(false);
  });

  it('an infeasible size that was put back still closes as not applied', () => {
    const steps = buildApplySteps(declined('INFEASIBLE', 'REVERTED', true));
    expect(steps.map((s) => s.key)).toEqual(['checks', 'pod-0', 'infeasible', 'revert-0', 'result']);
    expect(last(steps)).toMatchObject({ tone: 'bad', title: 'Result: not applied. Nothing changed.' });
  });

  it('says "Resizing" for a pod Kubernetes has not confirmed, never "Resized"', () => {
    const steps = buildApplySteps(outcome('DEFERRED', [pod('throttled-encoder-x', false)]));
    expect(steps.find((s) => s.key === 'pod-0')!.title).toBe('Resizing pod 1 of 1, the test pod');
  });
});

describe('ApplyStepper render', () => {
  it('labels the result chip, so the outcome is never an icon alone', () => {
    render(<ApplyStepper outcome={outcome('DEFERRED', [pod('throttled-encoder-x', false)])} />);
    const chip = document.querySelector('[data-result-tone] [data-tone]')!;
    expect(chip.textContent).toBe('Not applied: Kubernetes deferred it');
    expect(chip.querySelector('svg[aria-hidden="true"]')).toBeTruthy();
  });

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
