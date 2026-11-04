import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, setAutomationScope } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  setAutomationScope: vi.fn(),
}));
vi.mock('../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities, setAutomationScope };
});

import { K8sApiError } from '../../api/k8sAutomationApi';
import { readPauseMarker, writePauseMarker } from '../../utils/k8sPauseMarker';
import { K8sCapabilitiesProvider, useK8sCapabilities } from '../useK8sCapabilities';
import * as hookModule from '../useNodeAutomationControl';
import { STATE_CHANGED_MESSAGE, useNodeAutomationControl } from '../useNodeAutomationControl';
import hookSource from '../useNodeAutomationControl.ts?raw';
import { ACCOUNT, CLUSTER, capsDto, type CapsOptions } from './nodeControlFixtures';

const SCOPE = { accountId: ACCOUNT, clusterName: CLUSTER };
const wrapper = ({ children }: { children: ReactNode }) => (
  <K8sCapabilitiesProvider accountId={ACCOUNT} demo={false}>{children}</K8sCapabilitiesProvider>
);

async function setup(initial: CapsOptions = {}) {
  getCapabilities.mockResolvedValue(capsDto(initial));
  const hook = renderHook(
    () => ({ caps: useK8sCapabilities(), ctl: useNodeAutomationControl({ clusterName: CLUSTER, accountId: ACCOUNT }) }),
    { wrapper },
  );
  await waitFor(() => expect(hook.result.current.ctl.view.kind).not.toBe('loading'));
  return hook;
}

describe('useNodeAutomationControl', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    setAutomationScope.mockReset();
    localStorage.clear();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('pause sends exactly one mode write with disabled, stores the prior mode, and revalidates quietly', async () => {
    for (const mode of ['full', 'scoped'] as const) {
      localStorage.clear();
      getCapabilities.mockClear();
      setAutomationScope.mockReset().mockResolvedValue({ success: true, message: 'ok' });
      const { result, unmount } = await setup({ mode });
      expect(result.current.ctl.view).toMatchObject({ kind: 'autonomous', mode, acting: 'acting' });

      getCapabilities.mockResolvedValue(capsDto({ mode: 'disabled', derivedState: 'OFF' }));
      let ok = false;
      await act(async () => { ok = await result.current.ctl.pause(); });
      expect(ok).toBe(true);
      expect(setAutomationScope).toHaveBeenCalledTimes(1);
      expect(setAutomationScope).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'disabled');
      expect(readPauseMarker(SCOPE)?.priorMode).toBe(mode);
      expect(result.current.ctl.view).toMatchObject({ kind: 'paused', priorMode: mode });
      // The capabilities reload was quiet: ready the whole time.
      expect(result.current.caps.status).toBe('ready');
      await waitFor(() => expect(getCapabilities).toHaveBeenCalledTimes(2));
      unmount();
    }
  });

  it('resume writes the prior mode, clears the marker, and never claims Acting from the paused snapshot', async () => {
    writePauseMarker(SCOPE, { priorMode: 'scoped', pausedAt: new Date().toISOString() });
    setAutomationScope.mockResolvedValue({ success: true, message: 'ok' });
    const { result } = await setup({ mode: 'disabled', derivedState: 'OFF', effectiveActing: 'NOT_ACTING' });
    expect(result.current.ctl.view.kind).toBe('paused');

    getCapabilities.mockResolvedValue(capsDto({ mode: 'scoped', derivedState: 'AUTOMATED', effectiveActing: 'NOT_ACTING' }));
    await act(async () => { await result.current.ctl.resume(); });
    expect(setAutomationScope).toHaveBeenCalledTimes(1);
    expect(setAutomationScope).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'scoped');
    expect(readPauseMarker(SCOPE)).toBeNull();
    expect(result.current.ctl.view.kind).toBe('autonomous');
  });

  it('without a marker there is nothing to resume and the state reads off', async () => {
    const { result } = await setup({ mode: 'disabled', derivedState: 'OFF' });
    expect(result.current.ctl.view.kind).toBe('off');
    let ok = true;
    await act(async () => { ok = await result.current.ctl.resume(); });
    expect(ok).toBe(false);
    expect(setAutomationScope).not.toHaveBeenCalled();
    expect(result.current.ctl.error?.message).toBe(STATE_CHANGED_MESSAGE);
  });

  it('pause from a state that is not autonomous is refused without a request', async () => {
    const { result } = await setup({ mode: 'disabled' });
    await act(async () => { await result.current.ctl.pause(); });
    expect(setAutomationScope).not.toHaveBeenCalled();
    expect(result.current.ctl.error?.message).toBe(STATE_CHANGED_MESSAGE);
  });

  describe('reload with a held marker', () => {
    it('keeps it while the server says disabled', async () => {
      writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: new Date().toISOString() });
      const { result } = await setup({ mode: 'disabled', derivedState: 'OFF' });
      expect(result.current.ctl.view).toMatchObject({ kind: 'paused', priorMode: 'full' });
    });

    it('clears it when the server says full or scoped (someone resumed elsewhere)', async () => {
      writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: new Date().toISOString() });
      const { result } = await setup({ mode: 'full' });
      await waitFor(() => expect(readPauseMarker(SCOPE)).toBeNull());
      expect(result.current.ctl.view.kind).toBe('autonomous');
    });

    it.each([
      ['corrupt JSON', '{x'],
      ['bad priorMode', JSON.stringify({ priorMode: 'disabled', pausedAt: new Date().toISOString() })],
    ])('treats %s as no marker', async (_l, raw) => {
      localStorage.setItem(`xamops:k8s:pause:v1:anonymous|${ACCOUNT}|${CLUSTER}`, raw);
      const { result } = await setup({ mode: 'disabled', derivedState: 'OFF' });
      expect(result.current.ctl.view.kind).toBe('off');
    });

    it('treats a throwing localStorage as no marker', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
      const { result } = await setup({ mode: 'disabled', derivedState: 'OFF' });
      expect(result.current.ctl.view.kind).toBe('off');
    });
  });

  describe('failures leave state unchanged and write no marker', () => {
    it.each([
      ['403 gate refusal', new K8sApiError({ status: 403, code: 'K8S_READ_ONLY_CONNECTION', message: 'Read-only connection.' })],
      ['400 invalid policy', new K8sApiError({ status: 400, code: 'K8S_INVALID_POLICY', message: 'Bad mode.' })],
      ['404 not registered', new K8sApiError({ status: 404, code: 'K8S_CLUSTER_NOT_REGISTERED', message: 'Not registered.' })],
      ['network', new K8sApiError({ status: 0 })],
      ['5xx', new K8sApiError({ status: 500 })],
    ])('%s', async (_label, err) => {
      setAutomationScope.mockRejectedValue(err);
      const { result } = await setup({ mode: 'full' });
      const calls = getCapabilities.mock.calls.length;
      let ok = true;
      await act(async () => { ok = await result.current.ctl.pause(); });
      expect(ok).toBe(false);
      expect(result.current.ctl.view.kind).toBe('autonomous');
      expect(readPauseMarker(SCOPE)).toBeNull();
      expect(result.current.ctl.error?.message).toBe(err.message);
      expect(result.current.ctl.pending).toBeNull();
      // A gate refusal refetches capabilities (onRefusal); other errors do not.
      if (err.status === 403) await waitFor(() => expect(getCapabilities.mock.calls.length).toBeGreaterThan(calls));
      else expect(getCapabilities.mock.calls.length).toBe(calls);
      act(() => result.current.ctl.clearError());
      expect(result.current.ctl.error).toBeNull();
    });
  });

  it('a double click sends one request', async () => {
    let release!: () => void;
    setAutomationScope.mockImplementation(() => new Promise((resolve) => { release = () => resolve({ success: true, message: '' }); }));
    const { result } = await setup({ mode: 'full' });
    let first!: Promise<boolean>;
    let second!: Promise<boolean>;
    act(() => {
      first = result.current.ctl.pause();
      second = result.current.ctl.pause();
    });
    expect(result.current.ctl.pending).toBe('pause');
    await act(async () => { release(); await first; });
    expect(await second).toBe(false);
    expect(setAutomationScope).toHaveBeenCalledTimes(1);
  });

  it('a blocked capability sends nothing', async () => {
    const { result } = await setup({ mode: 'full', writeReasons: ['READ_ONLY_CONNECTION'] });
    expect(result.current.ctl.capability.allowed).toBe(false);
    let ok = true;
    await act(async () => { ok = await result.current.ctl.pause(); });
    expect(ok).toBe(false);
    expect(setAutomationScope).not.toHaveBeenCalled();
  });

  it('agent NOT_INSTALLED with automationAgent CONNECTED keeps the view autonomous but blocks the write', async () => {
    const { result } = await setup({ mode: 'full', agent: 'NOT_INSTALLED' });
    expect(result.current.ctl.view.kind).toBe('autonomous');
    expect(result.current.ctl.capability.gate?.reason).toBe('AGENT_NOT_INSTALLED');
    await act(async () => { await result.current.ctl.pause(); });
    expect(setAutomationScope).not.toHaveBeenCalled();
  });

  describe('marker race (Date frozen so fetchedAt and lastWriteAt are comparable)', () => {
    const T0 = Date.parse('2026-05-10T12:00:00Z');

    it('survives while the post-write reload is still in flight', async () => {
      setAutomationScope.mockResolvedValue({ success: true, message: '' });
      const { result } = await setup({ mode: 'full' });
      getCapabilities.mockImplementation(() => new Promise(() => {}));
      await act(async () => { await result.current.ctl.pause(); });
      expect(readPauseMarker(SCOPE)).not.toBeNull();
      expect(result.current.ctl.view.kind).toBe('paused');
    });

    it('an equal-stamp snapshot keeps the override and the marker; a later one reporting full clears both', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(T0);
      setAutomationScope.mockResolvedValue({ success: true, message: '' });
      const { result } = await setup({ mode: 'full' });

      getCapabilities.mockResolvedValue(capsDto({ mode: 'full' }));
      await act(async () => { await result.current.ctl.pause(); });
      await act(async () => { await Promise.resolve(); await Promise.resolve(); });
      // The reload landed in the same millisecond as the write: not newer, so the override holds.
      expect(result.current.ctl.view.kind).toBe('paused');
      expect(readPauseMarker(SCOPE)).not.toBeNull();

      vi.setSystemTime(T0 + 1);
      await act(async () => { result.current.caps.refetch(); });
      await waitFor(() => expect(result.current.ctl.view.kind).toBe('autonomous'));
      await waitFor(() => expect(readPauseMarker(SCOPE)).toBeNull());
    });

    it('a later snapshot reporting disabled drops the override and keeps the marker', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(T0);
      setAutomationScope.mockResolvedValue({ success: true, message: '' });
      const { result } = await setup({ mode: 'full' });
      getCapabilities.mockResolvedValue(capsDto({ mode: 'disabled', derivedState: 'OFF' }));
      await act(async () => { await result.current.ctl.pause(); });
      vi.setSystemTime(T0 + 1);
      await act(async () => { result.current.caps.refetch(); });
      await waitFor(() => expect(result.current.caps.fetchedAt).toBe(T0 + 1));
      expect(result.current.ctl.view.kind).toBe('paused');
      expect(readPauseMarker(SCOPE)).not.toBeNull();
    });

    it('after resume, a snapshot that still says disabled does not re-create the marker', async () => {
      writePauseMarker(SCOPE, { priorMode: 'full', pausedAt: new Date(T0 - 1000).toISOString() });
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(T0);
      setAutomationScope.mockResolvedValue({ success: true, message: '' });
      const { result } = await setup({ mode: 'disabled', derivedState: 'OFF' });
      await act(async () => { await result.current.ctl.resume(); });
      expect(readPauseMarker(SCOPE)).toBeNull();
      expect(result.current.ctl.view.kind).toBe('autonomous');
    });
  });

  it('a revalidate failure after a successful pause keeps the bar paused and other gates enabled', async () => {
    setAutomationScope.mockResolvedValue({ success: true, message: '' });
    const { result } = await setup({ mode: 'full' });
    getCapabilities.mockRejectedValue(new K8sApiError({ status: 500 }));
    await act(async () => { await result.current.ctl.pause(); });
    await waitFor(() => expect(getCapabilities.mock.calls.length).toBeGreaterThan(1));
    expect(result.current.caps.status).toBe('ready');
    expect(result.current.ctl.view.kind).toBe('paused');
    expect(result.current.caps.can('automation.scope', { cluster: CLUSTER }).allowed).toBe(true);
  });

  it('exports no enable path: only pause and resume mutate', () => {
    expect(Object.keys(hookModule).sort()).toEqual(['STATE_CHANGED_MESSAGE', 'default', 'useNodeAutomationControl']);
    expect(hookSource).not.toMatch(/setAutomationEnabled|goAutonomous|enable\(/);
    expect(hookSource.match(/setAutomationScope\(/g)).toHaveLength(1);
  });
});
