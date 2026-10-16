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

describe('buildApplySteps: only what the proof says (glossary v2 §8.2, §11.3; ResizeProof)', () => {
  const resized = (name: string, restartsAfter = 0): NonNullable<VerticalOutcome['pods']>[number] => ({
    podName: name, uidBefore: `${name}-uid`, uidAfter: `${name}-uid`, samePod: true, ready: true, changed: true,
    containers: [{
      name: 'api', containerIdBefore: 'c1', containerIdAfter: restartsAfter > 0 ? 'c2' : 'c1', restartCountBefore: 0, restartCountAfter: restartsAfter,
      observedBefore: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
      observedAfter: { cpuRequestM: 25, memRequestMi: 256, cpuLimitM: 25, memLimitMi: 256 },
    }],
  });
  const applied = (over: Partial<VerticalOutcome>): VerticalOutcome => ({
    method: 'IN_PLACE', terminal: 'APPLIED', detail: null, restartFree: true,
    containers: [{ name: 'api', state: 'APPLIED', detail: null, requested: null, restartExpected: false }],
    pods: [resized('api-6f7d8-2bddb'), resized('api-6f7d8-jm7jv')],
    ...over,
  });
  const titles = (steps: ReturnType<typeof buildApplySteps>) => steps.map((s) => s.title);

  const CAVEAT = (kind: string) =>
    `The ${kind} itself wasn't changed, so a pod created later (a rollout, a rescheduled pod) starts at the size in its template, not the new one.`;

  it('restart-free and every pod: "Done: applied in every pod, no restart", with the template caveat', () => {
    const steps = buildApplySteps(applied({}), { kind: 'Deployment' });
    expect(titles(steps)).toEqual([
      'Checked the safety rules: all passed',
      'Resized pod 1 of 2, the test pod',
      'Resized pod 2 of 2',
      'Done: applied in every pod, no restart',
    ]);
    expect(last(steps).detail).toBe(CAVEAT('Deployment'));
  });

  // In-place resize never writes the template (ClusterWorkloadInventory), so after a first in-place
  // change the running pods' size before this apply is not what a new pod starts at.
  it('names no size in the caveat when the pods were already resized in place (observedBefore is not the template)', () => {
    const again = (name: string): NonNullable<VerticalOutcome['pods']>[number] => ({
      ...resized(name),
      containers: [{
        name: 'api', containerIdBefore: 'c1', containerIdAfter: 'c1', restartCountBefore: 0, restartCountAfter: 0,
        // Running at 25m / 250Mi from an earlier in-place change, on a 1 CPU / 1 GiB template.
        observedBefore: { cpuRequestM: 25, memRequestMi: 250, cpuLimitM: 25, memLimitMi: 250 },
        observedAfter: { cpuRequestM: 50, memRequestMi: 256, cpuLimitM: 50, memLimitMi: 256 },
      }],
    });
    const steps = buildApplySteps(applied({ pods: [again('api-6f7d8-2bddb'), again('api-6f7d8-jm7jv')] }), { kind: 'Deployment' });
    expect(last(steps).detail).toBe(CAVEAT('Deployment'));
    expect(last(steps).detail).not.toMatch(/\d+m CPU|Mi memory|GiB/);
  });

  it('names no container\'s size in the caveat for a pod with two containers', () => {
    const twoContainers = (name: string): NonNullable<VerticalOutcome['pods']>[number] => ({
      ...resized(name),
      containers: [
        {
          name: 'proxy', containerIdBefore: 'p1', containerIdAfter: 'p1', restartCountBefore: 0, restartCountAfter: 0,
          observedBefore: { cpuRequestM: 50, memRequestMi: 64, cpuLimitM: 50, memLimitMi: 64 },
          observedAfter: { cpuRequestM: 50, memRequestMi: 64, cpuLimitM: 50, memLimitMi: 64 },
        },
        {
          name: 'api', containerIdBefore: 'c1', containerIdAfter: 'c1', restartCountBefore: 0, restartCountAfter: 0,
          observedBefore: { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 },
          observedAfter: { cpuRequestM: 25, memRequestMi: 256, cpuLimitM: 25, memLimitMi: 256 },
        },
      ],
    });
    const steps = buildApplySteps(applied({ pods: [twoContainers('api-6f7d8-2bddb')] }), { kind: 'StatefulSet' });
    expect(last(steps).detail).toBe(CAVEAT('StatefulSet'));
    expect(last(steps).detail).not.toMatch(/50m|1000m/);
  });

  // ResizeProof: restartFree false means "not proved", and skippedPods means "never every pod".
  it('restartFree false and a skipped pod: every running pod (2 of 3), no restart-free proof, pods numbered of 3', () => {
    const steps = buildApplySteps(
      applied({ restartFree: false, skippedPods: [{ podName: 'api-6f7d8-x9k2p', phase: 'Pending', reason: 'NOT_RUNNING' }] }),
      { kind: 'Deployment' },
    );
    expect(titles(steps)).toEqual([
      'Checked the safety rules: all passed',
      'Resized pod 1 of 3, the test pod',
      'Resized pod 2 of 3',
      'Pod 3 of 3 not touched',
      'Done: applied in every running pod (2 of 3), no restart-free proof',
    ]);
    expect(steps.find((s) => s.key === 'skipped')).toMatchObject({
      tone: 'off',
      detail: "api-6f7d8-x9k2p: not running (Pending). It runs at the workload's own size.",
    });
    // Nothing claims a restart that no restart count shows, or "every pod".
    expect(steps.some((s) => s.key.startsWith('restart-'))).toBe(false);
    expect(titles(steps).join(' ')).not.toMatch(/every pod|no restart$|no restart,/);
  });

  it('names why each pod was left out, in main\'s words', () => {
    const steps = buildApplySteps(applied({
      skippedPods: [
        { podName: 'api-old', phase: 'Running', reason: 'OLD_REVISION' },
        { podName: 'api-going', phase: 'Running', reason: 'TERMINATING' },
      ],
    }));
    expect(steps.find((s) => s.key === 'skipped')).toMatchObject({
      title: 'Pods 3 and 4 of 4 not touched',
      detail: "api-old: from the previous version of the workload; api-going: being deleted. They run at the workload's own size.",
    });
    expect(last(steps).title).toBe('Done: applied in every running pod (2 of 4), no restart');
  });

  it('a container whose restart count rose gets the Restart row, and Done never says "no restart"', () => {
    const steps = buildApplySteps(applied({
      restartFree: false,
      containers: [{ name: 'api', state: 'APPLIED', detail: null, requested: null, restartExpected: true }],
      pods: [resized('api-6f7d8-2bddb', 1), resized('api-6f7d8-jm7jv')],
    }));
    const restart = steps.find((s) => s.key.startsWith('restart-'))!;
    expect(restart).toMatchObject({ key: 'restart-0-api', tone: 'bad', title: 'A container restarted during the resize' });
    expect(restart.detail).toBe('2bddb, container api: restart count 0 → 1. This was not a restart-free change. The container restarts by its own setting.');
    expect(steps.filter((s) => s.key.startsWith('restart-'))).toHaveLength(1);
    expect(last(steps).title).toBe('Done: applied in every pod, no restart-free proof');
  });

  it('says the template caveat only for IN_PLACE, the method that leaves the template alone', () => {
    expect(last(buildApplySteps(applied({}))).detail).toBe(CAVEAT('workload'));
    expect(last(buildApplySteps(applied({ method: 'IN_PLACE_AND_PERSIST', restartFree: false }), { kind: 'Deployment' })).detail).toBeUndefined();
  });

  it('never closes with "Nothing changed" while a pod the reply lists did change', () => {
    const partly = buildApplySteps({
      method: 'IN_PLACE', terminal: 'PARTIALLY_APPLIED', detail: 'The canary pod was resized and confirmed, but pod api-2 was not.',
      restartFree: false, containers: null, reverted: true,
      pods: [resized('api-1'), { ...resized('api-2'), changed: false, revert: { state: 'REVERTED', detail: null } }],
    });
    expect(last(partly)).toMatchObject({ key: 'result', tone: 'warn', title: 'Result: partly applied.' });
    expect(last(partly).detail).toBe('It stopped at the first pod that failed. The pods above say which changed.');
    expect(titles(partly).join(' ')).not.toContain('Nothing changed');

    const restarted = buildApplySteps({
      method: 'IN_PLACE', terminal: 'RESTART_DETECTED', detail: 'restart detected: container api of pod team-a/api-1 went from restartCount 0 to 1',
      restartFree: false, containers: null, pods: [resized('api-1', 1)],
    });
    expect(titles(restarted)).toEqual([
      'Checked the safety rules: all passed',
      'Resized pod 1 of 1, the test pod',
      'A container restarted during the resize',
      'Result: failed.',
    ]);
    expect(last(restarted).detail).toBe('A container restarted during the resize. This was not a restart-free change.');

    // A failure that changed nothing still says so.
    const failed = buildApplySteps({ ...outcome('FAILED', [pod('p-1', false)]), restartFree: false });
    expect(last(failed).title).toBe('Result: not applied. Nothing changed.');
  });
});

// DirectInPlaceResizeExecutor returns only the canary's proof when the canary fails, and only the
// attempted pods after a mid-rollout failure: the pods it never reached are in neither list.
describe('buildApplySteps: a rollout that stopped, numbered against the workload (glossary §6 "Not touched")', () => {
  type Pod = NonNullable<VerticalOutcome['pods']>[number];
  const NOT_REACHED = "XamOps stops at the first pod that doesn't take the change.";
  const before = { cpuRequestM: 1000, memRequestMi: 1024, cpuLimitM: 1000, memLimitMi: 1024 };
  const after = { cpuRequestM: 25, memRequestMi: 256, cpuLimitM: 25, memLimitMi: 256 };
  const podOf = (name: string, changed: boolean, revert: Pod['revert'] = null): Pod => ({
    podName: name, uidBefore: `${name}-uid`, uidAfter: `${name}-uid`, samePod: true, ready: true, changed,
    containers: [{
      name: 'api', containerIdBefore: 'c1', containerIdAfter: 'c1', restartCountBefore: 0, restartCountAfter: 0,
      observedBefore: before, observedAfter: changed ? after : before,
    }],
    revert,
  });
  const deferredCanary = (over: Partial<VerticalOutcome> = {}): VerticalOutcome => ({
    method: 'IN_PLACE', terminal: 'DEFERRED', detail: 'OutOfcpu: requested 25, used 1900, capacity 1930', restartFree: false,
    containers: null, reverted: true, insufficient: 'cpu',
    pods: [podOf('api-6f7d8-mcw8q', false, { state: 'REVERTED', detail: null })],
    ...over,
  });
  const titles = (steps: ReturnType<typeof buildApplySteps>) => steps.map((s) => s.title);

  it('a deferred test pod on a three-replica workload: "Pods 2 and 3 of 3 not touched", after the put-back', () => {
    const steps = buildApplySteps(deferredCanary(), { kind: 'Deployment', replicas: 3 });
    expect(titles(steps)).toEqual([
      'Checked the safety rules: all passed',
      'Resizing pod 1 of 3, the test pod',
      'Kubernetes deferred this: the node has no free CPU',
      'So XamOps put the pod back',
      'Pods 2 and 3 of 3 not touched',
      'Result: not applied. Nothing changed.',
    ]);
    expect(steps.find((s) => s.key === 'not-reached')).toMatchObject({ tone: 'off', detail: NOT_REACHED });
  });

  it('a rollout that stopped part-way names the pods it never reached', () => {
    const steps = buildApplySteps(
      {
        method: 'IN_PLACE',
        terminal: 'PARTIALLY_APPLIED',
        detail: 'The canary pod was resized and confirmed, but pod api-3 was not (DEFERRED). The rollout stopped there.',
        restartFree: false,
        containers: null,
        reverted: true,
        pods: [podOf('api-1', true), podOf('api-2', true), podOf('api-3', false, { state: 'REVERTED', detail: null })],
      },
      { replicas: 5 },
    );
    expect(titles(steps)).toEqual([
      'Checked the safety rules: all passed',
      'Resized pod 1 of 5, the test pod',
      'Resized pod 2 of 5',
      'Resizing pod 3 of 5',
      'So XamOps put the pod back',
      'Pods 4 and 5 of 5 not touched',
      'Result: partly applied.',
    ]);
  });

  it('numbers skipped pods after the ones it reached, and the pods never reached last', () => {
    const steps = buildApplySteps(
      deferredCanary({ skippedPods: [{ podName: 'api-6f7d8-x9k2p', phase: 'Pending', reason: 'NOT_RUNNING' }] }),
      { replicas: 4 },
    );
    expect(titles(steps)).toEqual([
      'Checked the safety rules: all passed',
      'Resizing pod 1 of 4, the test pod',
      'Pod 2 of 4 not touched',
      'Kubernetes deferred this: the node has no free CPU',
      'So XamOps put the pod back',
      'Pods 3 and 4 of 4 not touched',
      'Result: not applied. Nothing changed.',
    ]);
    expect(steps.find((s) => s.key === 'skipped')!.detail).toBe("api-6f7d8-x9k2p: not running (Pending). It runs at the workload's own size.");
    expect(steps.find((s) => s.key === 'not-reached')!.detail).toBe(NOT_REACHED);
  });

  it('an applied reply covered every running pod, so it numbers against its own pods whatever the replica count says', () => {
    const steps = buildApplySteps(
      { method: 'IN_PLACE', terminal: 'APPLIED', detail: null, restartFree: true, containers: null, pods: [podOf('api-1', true), podOf('api-2', true)] },
      { replicas: 3 },
    );
    expect(titles(steps)).toEqual(['Checked the safety rules: all passed', 'Resized pod 1 of 2, the test pod', 'Resized pod 2 of 2', 'Done: applied in every pod, no restart']);
    expect(steps.some((s) => s.key === 'not-reached')).toBe(false);
  });

  it('claims no pod it can\'t count: no replica count, or a reply that lists no pod', () => {
    expect(titles(buildApplySteps(deferredCanary()))).toContain('Resizing pod 1 of 1, the test pod');
    expect(buildApplySteps(deferredCanary()).some((s) => s.key === 'not-reached')).toBe(false);
    expect(buildApplySteps(deferredCanary({ pods: [] }), { replicas: 3 }).some((s) => s.key === 'not-reached')).toBe(false);
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
