import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { getCapabilities, setAutomationScope, setAutomationEnabled } = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  setAutomationScope: vi.fn(),
  setAutomationEnabled: vi.fn(),
}));
vi.mock('../../../api/k8sAutomationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/k8sAutomationApi')>();
  return { ...actual, getCapabilities, setAutomationScope, setAutomationEnabled };
});

import { K8sApiError } from '../../../api/k8sAutomationApi';
import { K8sCapabilitiesProvider } from '../../../hooks/useK8sCapabilities';
import { ACCOUNT, CLUSTER, capsDto, type CapsOptions } from '../../../hooks/__tests__/nodeControlFixtures';
import { PAUSE_SETTLE_MS } from '../../../utils/k8sAutomationControl';
import { pauseMarkerKey, readPauseMarker, writePauseMarker } from '../../../utils/k8sPauseMarker';
import {
  AutomationControlBar,
  LAST_RUN_NOTE,
  MANUAL_COMING_TEXT,
  OFF_NOTE,
  PAUSE_DIALOG_TITLE,
  PAUSE_LABEL,
  PAUSE_SCOPE_TEXT,
  PAUSE_SENT_NOTE,
} from '../AutomationControlBar';
import barSource from '../AutomationControlBar.tsx?raw';
import hookSource from '../../../hooks/useNodeAutomationControl.ts?raw';
import controlSource from '../../../utils/k8sAutomationControl.ts?raw';

const SCOPE = { accountId: ACCOUNT, clusterName: CLUSTER };
const WORKLOADS = '/eks-details?tab=automation&sub=workload-autoscaler';
const NODES = '/eks-details?tab=automation&sub=node-autoscaler';

async function renderBar(opts: CapsOptions = {}, { demo = false }: { demo?: boolean } = {}) {
  getCapabilities.mockResolvedValue(capsDto(opts));
  const view = render(
    <MemoryRouter>
      <K8sCapabilitiesProvider accountId={ACCOUNT} demo={demo}>
        <AutomationControlBar clusterName={CLUSTER} accountId={ACCOUNT} workloadsHref={WORKLOADS} nodeAutoscalerHref={NODES} />
      </K8sCapabilitiesProvider>
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getByTestId('automation-control-bar').getAttribute('aria-busy')).toBeNull());
  return view;
}

const bar = () => screen.getByTestId('automation-control-bar');
const kind = () => bar().getAttribute('data-control-kind');
const T0 = Date.parse('2026-05-10T12:00:00Z');
const markerAt = (ms: number, priorMode: 'full' | 'scoped' = 'full') => ({ priorMode, pausedAt: new Date(ms).toISOString() });

describe('AutomationControlBar', () => {
  beforeEach(() => {
    getCapabilities.mockReset();
    setAutomationScope.mockReset().mockResolvedValue({ success: true, message: 'ok' });
    setAutomationEnabled.mockReset();
    localStorage.clear();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  describe('Pause', () => {
    it('opens a confirm with focus on Cancel; Cancel sends nothing', async () => {
      const user = userEvent.setup();
      await renderBar({ mode: 'full' });
      await user.click(screen.getByRole('button', { name: PAUSE_LABEL }));
      const dialog = screen.getByRole('alertdialog', { name: PAUSE_DIALOG_TITLE(CLUSTER) });
      expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
      await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('alertdialog')).toBeNull();
      expect(setAutomationScope).not.toHaveBeenCalled();
    });

    it('confirm sends exactly one mode write with disabled, stores the prior mode and reads Paused', async () => {
      const user = userEvent.setup();
      await renderBar({ mode: 'scoped' });
      getCapabilities.mockResolvedValue(capsDto({ mode: 'disabled', derivedState: 'OFF' }));
      await user.click(screen.getByRole('button', { name: PAUSE_LABEL }));
      await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: PAUSE_LABEL }));
      await waitFor(() => expect(kind()).toBe('paused'));
      expect(setAutomationScope).toHaveBeenCalledTimes(1);
      expect(setAutomationScope).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'disabled');
      expect(setAutomationEnabled).not.toHaveBeenCalled();
      expect(readPauseMarker(SCOPE)?.priorMode).toBe('scoped');
      expect(screen.getByRole('button', { name: 'Resume' })).toBeTruthy();
      expect(screen.queryByRole('alertdialog')).toBeNull();
    });

    it('the dialog states what is and is not paused, and the browser-only 7-day limit', async () => {
      const user = userEvent.setup();
      await renderBar({ mode: 'full' });
      await user.click(screen.getByRole('button', { name: PAUSE_LABEL }));
      const text = screen.getByRole('alertdialog').textContent ?? '';
      expect(text).toContain('Workload auto-apply and changes you apply yourself are not paused.');
      expect(text).toContain('Resume puts the mode back to Full.');
      expect(text).toContain('this browser only');
      expect(text).toContain('7 days');
      expect(text).not.toContain("can't keep a record");
    });

    it('warns in the dialog when this browser cannot keep a record', async () => {
      const user = userEvent.setup();
      await renderBar({ mode: 'full' });
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
      await user.click(screen.getByRole('button', { name: PAUSE_LABEL }));
      expect(screen.getByRole('alertdialog').textContent).toContain(
        "This browser can't keep a record, so Resume will only work until you reload this page.",
      );
    });

    it.each([
      ['403 gate', new K8sApiError({ status: 403, code: 'K8S_READ_ONLY_CONNECTION', message: 'Read-only connection.' })],
      ['network', new K8sApiError({ status: 0 })],
      ['5xx', new K8sApiError({ status: 500 })],
    ])('a failed write (%s) keeps the state, shows the error in the dialog, and can be cancelled', async (_l, err) => {
      const user = userEvent.setup();
      setAutomationScope.mockRejectedValue(err);
      await renderBar({ mode: 'full' });
      await user.click(screen.getByRole('button', { name: PAUSE_LABEL }));
      await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: PAUSE_LABEL }));
      await waitFor(() => expect(within(screen.getByRole('alertdialog')).getByRole('alert').textContent).toBe(err.message));
      expect(kind()).toBe('autonomous');
      expect(readPauseMarker(SCOPE)).toBeNull();
      await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
      expect(screen.queryByRole('alertdialog')).toBeNull();
    });

    it('Escape does not close the confirm while the request is pending', async () => {
      const user = userEvent.setup();
      let release!: () => void;
      setAutomationScope.mockImplementation(() => new Promise((resolve) => { release = () => resolve({ success: true, message: '' }); }));
      await renderBar({ mode: 'full' });
      await user.click(screen.getByRole('button', { name: PAUSE_LABEL }));
      await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: PAUSE_LABEL }));
      await waitFor(() => expect(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Working…' })).toBeTruthy());
      await user.keyboard('{Escape}');
      expect(screen.getByRole('alertdialog')).toBeTruthy();
      await act(async () => release());
      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    });

    it.each([
      ['read-only connection', { writeReasons: ['READ_ONLY_CONNECTION'] }, false],
      ['no permission', { writeReasons: ['NO_USER_PERMISSION'] }, false],
      ['agent not installed', { agent: 'NOT_INSTALLED' as const }, false],
      ['demo', {}, true],
    ])('%s: Pause is blocked with a reason and sends nothing', async (_l, opts, demo) => {
      const user = userEvent.setup();
      await renderBar({ mode: 'full', ...opts }, { demo });
      const pause = screen.getByRole('button', { name: PAUSE_LABEL });
      expect(pause).toHaveAttribute('aria-disabled', 'true');
      const reason = document.getElementById(pause.getAttribute('aria-describedby')!);
      expect(reason?.textContent).toBeTruthy();
      await user.click(pause);
      expect(screen.queryByRole('alertdialog')).toBeNull();
      expect(setAutomationScope).not.toHaveBeenCalled();
    });

    it('agent NOT_INSTALLED with automationAgent CONNECTED still reads the autonomous state; Pause is blocked by the capability gate', async () => {
      await renderBar({ mode: 'full', agent: 'NOT_INSTALLED' });
      expect(kind()).toBe('autonomous');
      expect(within(bar()).getByText('Acting')).toBeTruthy();
      const pause = screen.getByRole('button', { name: PAUSE_LABEL });
      expect(pause).toHaveAttribute('aria-disabled', 'true');
      expect(document.getElementById(pause.getAttribute('aria-describedby')!)?.textContent).toContain('Install the XamOps agent first');
    });
  });

  describe('Resume', () => {
    it('shows the date, resumes to the held mode and clears the marker', async () => {
      const user = userEvent.setup();
      const pausedAt = Date.now() - 3600_000;
      writePauseMarker(SCOPE, markerAt(pausedAt, 'scoped'));
      await renderBar({ mode: 'disabled', derivedState: 'OFF', effectiveActing: 'NOT_ACTING' });
      expect(kind()).toBe('paused');
      const date = new Date(pausedAt).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
      expect(bar().textContent).toContain(`Paused on ${date} from this browser. Resume returns it to Scoped.`);

      getCapabilities.mockResolvedValue(capsDto({ mode: 'scoped', derivedState: 'AUTOMATED', effectiveActing: 'NOT_ACTING' }));
      await user.click(screen.getByRole('button', { name: 'Resume' }));
      const dialog = screen.getByRole('alertdialog');
      expect(dialog.textContent).toContain(new Date(pausedAt).toLocaleString());
      expect(dialog.textContent).toContain('sets the mode to Scoped');
      expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
      await user.click(within(dialog).getByRole('button', { name: 'Resume' }));
      await waitFor(() => expect(kind()).toBe('autonomous'));
      expect(setAutomationScope).toHaveBeenCalledTimes(1);
      expect(setAutomationScope).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'scoped');
      expect(readPauseMarker(SCOPE)).toBeNull();
    });

    it('right after Resume the snapshot still says OFF, so it never claims Acting', async () => {
      const user = userEvent.setup();
      writePauseMarker(SCOPE, markerAt(Date.now() - 1000, 'full'));
      await renderBar({ mode: 'disabled', derivedState: 'OFF', effectiveActing: 'ACTING' });
      // The reload after the write never lands: the bar runs off the override and the stale snapshot.
      getCapabilities.mockImplementation(() => new Promise(() => {}));
      await user.click(screen.getByRole('button', { name: 'Resume' }));
      await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Resume' }));
      await waitFor(() => expect(kind()).toBe('autonomous'));
      expect(within(bar()).getByText('Not confirmed')).toBeTruthy();
      expect(within(bar()).queryByText('Acting')).toBeNull();
      expect(within(bar()).queryByText('Analysis only')).toBeNull();
    });

    it('with no marker there is no Resume: the state reads Off with OFF_NOTE and a link', async () => {
      await renderBar({ mode: 'disabled', derivedState: 'OFF' });
      expect(kind()).toBe('off');
      expect(screen.queryByRole('button', { name: 'Resume' })).toBeNull();
      expect(screen.getByText(OFF_NOTE)).toBeTruthy();
      expect(screen.getByRole('link', { name: 'View node automation' })).toHaveAttribute('href', NODES);
      expect(screen.getByRole('button', { name: PAUSE_LABEL })).toHaveAttribute('aria-disabled', 'true');
      expect(screen.getByText('Already off')).toBeTruthy();
    });

    it('a marker older than 7 days is ignored and removed; one just under 7 days still works', async () => {
      writePauseMarker(SCOPE, markerAt(Date.now() - 8 * 24 * 3600_000));
      const first = await renderBar({ mode: 'disabled', derivedState: 'OFF' });
      expect(kind()).toBe('off');
      expect(screen.queryByRole('button', { name: 'Resume' })).toBeNull();
      expect(localStorage.getItem(pauseMarkerKey(SCOPE))).toBeNull();
      first.unmount();

      writePauseMarker(SCOPE, markerAt(Date.now() - (6 * 24 + 23) * 3600_000));
      await renderBar({ mode: 'disabled', derivedState: 'OFF' });
      expect(kind()).toBe('paused');
      expect(screen.getByRole('button', { name: 'Resume' })).toBeTruthy();
    });
  });

  describe('honest state words', () => {
    it.each([
      [{ derivedState: 'AUTOMATED', effectiveActing: 'ACTING' }, 'Acting'],
      [{ derivedState: 'AUTOMATED', effectiveActing: 'NOT_ACTING' }, 'Ready to enable'],
      [{ derivedState: 'AUTOMATED', effectiveActing: 'UNKNOWN' }, 'Not confirmed'],
      [{ derivedState: 'AUTOMATED', effectiveActing: null }, 'Not confirmed'],
      [{ derivedState: 'ANALYSIS', effectiveActing: 'ACTING' }, 'Analysis only'],
    ] as const)('%j reads %s', async (opts, word) => {
      await renderBar({ mode: 'full', ...opts });
      expect(within(bar()).getByText(word)).toBeTruthy();
      if (word !== 'Acting') expect(within(bar()).queryByText('Acting')).toBeNull();
    });

    it('not-installed, not-enabled and unknown show a word, no Pause, and the Autonomous segment disabled', async () => {
      for (const [opts, word] of [
        [{ automationAgent: 'NOT_INSTALLED' as const }, 'Not installed'],
        [{ enabled: false }, 'Off'],
        [{ mode: null }, 'Unknown'],
      ] as const) {
        const { unmount } = await renderBar(opts);
        expect(within(bar()).getByText(word)).toBeTruthy();
        expect(screen.queryByRole('button', { name: PAUSE_LABEL })).toBeNull();
        expect(screen.getByRole('button', { name: 'Autonomous' })).toHaveAttribute('aria-disabled', 'true');
        unmount();
      }
    });

    it('while capabilities load it shows a skeleton and no controls', () => {
      getCapabilities.mockImplementation(() => new Promise(() => {}));
      render(
        <MemoryRouter>
          <K8sCapabilitiesProvider accountId={ACCOUNT} demo={false}>
            <AutomationControlBar clusterName={CLUSTER} accountId={ACCOUNT} workloadsHref={WORKLOADS} nodeAutoscalerHref={NODES} />
          </K8sCapabilitiesProvider>
        </MemoryRouter>,
      );
      expect(bar().getAttribute('aria-busy')).toBe('true');
      expect(screen.queryByRole('button')).toBeNull();
    });
  });

  describe('time-bound "Pause sent"', () => {
    it('reads Pause sent inside the window and flips to plain Paused when the window passes, without a reload', async () => {
      vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
      vi.setSystemTime(T0);
      writePauseMarker(SCOPE, markerAt(T0));
      await renderBar({ mode: 'disabled', derivedState: 'AUTOMATED', effectiveActing: 'ACTING' });
      expect(within(bar()).getByText('Pause sent')).toBeTruthy();
      expect(bar().textContent).toContain(PAUSE_SENT_NOTE);
      expect(bar().textContent).not.toContain(LAST_RUN_NOTE);

      await act(async () => { vi.setSystemTime(T0 + PAUSE_SETTLE_MS + 1000); vi.advanceTimersByTime(30_000); });
      expect(within(bar()).queryByText('Pause sent')).toBeNull();
      expect(within(bar()).getByText('Paused')).toBeTruthy();
      expect(bar().textContent).toContain(LAST_RUN_NOTE);
      expect(bar().textContent).not.toContain(PAUSE_SENT_NOTE);
      expect(bar().textContent).toContain('Paused on');
    });

    it('an hour after the pause it is plain Paused from the start', async () => {
      vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
      vi.setSystemTime(T0 + 3600_000);
      writePauseMarker(SCOPE, markerAt(T0));
      await renderBar({ mode: 'disabled', derivedState: 'AUTOMATED', effectiveActing: 'ACTING' });
      expect(within(bar()).getByText('Paused')).toBeTruthy();
      expect(within(bar()).queryByText('Pause sent')).toBeNull();
    });

    it('NOT_ACTING or absent never shows either note', async () => {
      writePauseMarker(SCOPE, markerAt(Date.now()));
      await renderBar({ mode: 'disabled', derivedState: 'AUTOMATED', effectiveActing: 'NOT_ACTING' });
      expect(bar().textContent).not.toContain(PAUSE_SENT_NOTE);
      expect(bar().textContent).not.toContain(LAST_RUN_NOTE);
    });

    it('with no marker (another browser) and no write, neither claim is made', async () => {
      await renderBar({ mode: 'disabled', derivedState: 'AUTOMATED', effectiveActing: 'ACTING' });
      expect(kind()).toBe('off');
      expect(bar().textContent).not.toContain(PAUSE_SENT_NOTE);
      expect(bar().textContent).not.toContain(LAST_RUN_NOTE);
      expect(bar().textContent).toContain(OFF_NOTE);
    });
  });

  describe('scope copy', () => {
    it('the scope line says workload auto-apply is separate and links to Workloads', async () => {
      await renderBar({ mode: 'full' });
      expect(screen.getByTestId('pause-scope').textContent).toBe(PAUSE_SCOPE_TEXT);
      expect(within(screen.getByTestId('pause-scope')).getByRole('link', { name: 'Workloads' })).toHaveAttribute('href', WORKLOADS);
    });
  });

  describe('Autonomous | Manual toggle', () => {
    it('Manual is aria-disabled, never pressed, labelled Coming, and activating it explains and sends nothing', async () => {
      const user = userEvent.setup();
      await renderBar({ mode: 'full' });
      const manual = screen.getByRole('button', { name: /Manual/ });
      expect(manual).toHaveAttribute('aria-disabled', 'true');
      expect(manual).not.toHaveAttribute('aria-pressed', 'true');
      expect(manual.textContent).toContain('Coming');
      expect(document.getElementById(manual.getAttribute('aria-describedby')!)?.textContent).toBe(MANUAL_COMING_TEXT);

      await user.click(manual);
      expect(within(screen.getByTestId('manual-note')).getByText(MANUAL_COMING_TEXT)).toBeTruthy();
      await user.click(within(screen.getByTestId('manual-note')).getByRole('button', { name: 'Dismiss' }));
      expect(screen.queryByTestId('manual-note')).toBeNull();

      manual.focus();
      await user.keyboard('{Enter}');
      expect(screen.getByTestId('manual-note')).toBeTruthy();
      await user.click(within(screen.getByTestId('manual-note')).getByRole('button', { name: 'Dismiss' }));
      manual.focus();
      await user.keyboard(' ');
      expect(screen.getByTestId('manual-note')).toBeTruthy();
      expect(setAutomationScope).not.toHaveBeenCalled();
      expect(setAutomationEnabled).not.toHaveBeenCalled();
      expect(manual).not.toHaveAttribute('aria-pressed', 'true');
    });

    it.each(['full', 'scoped'])('Autonomous is pressed for %s and pressing it is a no-op', async (mode) => {
      const user = userEvent.setup();
      await renderBar({ mode });
      const autonomous = screen.getByRole('button', { name: 'Autonomous' });
      expect(autonomous).toHaveAttribute('aria-pressed', 'true');
      await user.click(autonomous);
      expect(screen.queryByRole('alertdialog')).toBeNull();
      expect(setAutomationScope).not.toHaveBeenCalled();
    });

    it('from paused, Autonomous opens the Resume confirm and sends the held prior mode', async () => {
      const user = userEvent.setup();
      writePauseMarker(SCOPE, markerAt(Date.now() - 1000, 'scoped'));
      await renderBar({ mode: 'disabled', derivedState: 'OFF' });
      const autonomous = screen.getByRole('button', { name: 'Autonomous' });
      expect(autonomous).toHaveAttribute('aria-pressed', 'false');
      await user.click(autonomous);
      const dialog = screen.getByRole('alertdialog', { name: /Resume node automation/ });
      await user.click(within(dialog).getByRole('button', { name: 'Resume' }));
      await waitFor(() => expect(setAutomationScope).toHaveBeenCalledWith({ cluster: CLUSTER, accountId: ACCOUNT }, 'scoped'));
      expect(setAutomationScope).toHaveBeenCalledTimes(1);
    });

    it('from off, Autonomous is aria-disabled with OFF_NOTE as its description and sends nothing', async () => {
      const user = userEvent.setup();
      await renderBar({ mode: 'disabled', derivedState: 'OFF' });
      const autonomous = screen.getByRole('button', { name: 'Autonomous' });
      expect(autonomous).toHaveAttribute('aria-disabled', 'true');
      expect(document.getElementById(autonomous.getAttribute('aria-describedby')!)?.textContent).toContain(OFF_NOTE);
      await user.click(autonomous);
      expect(screen.queryByRole('alertdialog')).toBeNull();
      expect(setAutomationScope).not.toHaveBeenCalled();
      expect(setAutomationEnabled).not.toHaveBeenCalled();
    });
  });

  it('never offers to turn node automation on, and the hook exposes no enable path', () => {
    for (const source of [barSource, hookSource, controlSource]) {
      expect(source).not.toMatch(/Turn node automation on/);
      expect(source).not.toMatch(/setAutomationEnabled/);
    }
  });
});
