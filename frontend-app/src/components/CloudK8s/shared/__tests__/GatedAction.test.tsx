import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { GatedAction } from '../GatedAction';
import { reasonCopy } from '../reasonCopy';
import {
  AGENT_NOT_INSTALLED_DETAILS,
  NO_USER_PERMISSION_DETAILS,
  REASON_CODES,
  type Capability,
  type Gate,
} from '../../../../types/k8sAutomation';
import type { ReasonPlacement } from '../ReasonText';

const blocked = (gate: Gate, also: Gate[] = []): Capability => ({ allowed: false, editable: false, gate, also });
const ALLOWED: Capability = { allowed: true, editable: true, gate: null, also: [] };
const DEMO_ONLY: Capability = { allowed: false, editable: true, gate: { reason: 'DEMO' }, also: [] };

/** Every gate code, every detail of the two codes that have one, and a code this UI doesn't know. */
const GATES: Array<[string, Gate]> = [
  ...REASON_CODES.map((reason): [string, Gate] => [reason, { reason }]),
  ...NO_USER_PERMISSION_DETAILS.map((detail): [string, Gate] => [`NO_USER_PERMISSION/${detail}`, { reason: 'NO_USER_PERMISSION', detail }]),
  ...AGENT_NOT_INSTALLED_DETAILS.map((detail): [string, Gate] => [`AGENT_NOT_INSTALLED/${detail}`, { reason: 'AGENT_NOT_INSTALLED', detail }]),
  ['unknown code', { reason: 'UNKNOWN', raw: 'K8S_SOMETHING_NEW', message: 'The engine refused this change.' }],
];

interface RenderOptions {
  placement?: ReasonPlacement;
  kind?: 'commit' | 'draft';
  label?: string;
  initialEntry?: string;
}

function renderAction(capability: Capability, { placement = 'inline', kind = 'commit', label = 'Save', initialEntry = '/' }: RenderOptions = {}) {
  const onClick = vi.fn();
  const result = render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <GatedAction capability={capability} kind={kind} label={label} reasonPlacement={placement}>
        {(p) => (
          <button
            type="button"
            aria-disabled={p['aria-disabled']}
            aria-describedby={p['aria-describedby']}
            onClick={p.onActivate(onClick)}
            className={p.blockedClassName}
          >
            {label}
          </button>
        )}
      </GatedAction>
    </MemoryRouter>,
  );
  return { ...result, button: screen.getByRole('button', { name: label }), onClick };
}

function descriptionOf(element: HTMLElement): HTMLElement {
  const id = element.getAttribute('aria-describedby');
  expect(id, 'aria-describedby is set').toBeTruthy();
  const description = document.getElementById(id!);
  expect(description, `aria-describedby="${id}" resolves`).not.toBeNull();
  return description!;
}

describe('GatedAction blocked, for every reason code and detail', () => {
  it.each(GATES)('%s, inline: aria-disabled, visible reason, resolving description, no-op, focusable', async (_name, gate) => {
    const user = userEvent.setup();
    const { button, onClick, container } = renderAction(blocked(gate));

    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toHaveAttribute('disabled');
    expect(container.querySelector('[disabled]')).toBeNull();

    const description = descriptionOf(button);
    expect(description.textContent).toContain(reasonCopy(gate).text);
    expect(description).not.toHaveClass('sr-only');
    expect(container.querySelector('[data-gated-lock]')).not.toBeNull();

    await user.click(button);
    await user.keyboard('{Enter}');
    expect(onClick).not.toHaveBeenCalled();

    await user.tab();
    await user.tab({ shift: true });
    button.focus();
    expect(button).toHaveFocus();
  });

  it.each(GATES)('%s, popover: aria-disabled, sr-only reason, lock is its own tab stop and opens', async (_name, gate) => {
    const user = userEvent.setup();
    const { button, onClick, container } = renderAction(blocked(gate), { placement: 'popover' });

    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toHaveAttribute('disabled');
    expect(container.querySelector('[disabled]')).toBeNull();

    const description = descriptionOf(button);
    expect(description.textContent).toContain(reasonCopy(gate).text);
    expect(description).toHaveClass('sr-only');

    const lock = screen.getByRole('button', { name: 'Why is Save unavailable?' });
    await user.tab();
    expect(button).toHaveFocus();
    await user.tab();
    expect(lock).toHaveFocus();

    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain(reasonCopy(gate).text);

    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('GatedAction behaviour', () => {
  it('renders the bare control when allowed', async () => {
    const user = userEvent.setup();
    const { button, onClick, container } = renderAction(ALLOWED);
    expect(button).not.toHaveAttribute('aria-disabled');
    expect(button).not.toHaveAttribute('aria-describedby');
    expect(container.querySelector('[data-gated-lock]')).toBeNull();
    await user.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('lets a DEMO-only capability operate draft controls but not commit ones', async () => {
    const user = userEvent.setup();
    const draft = renderAction(DEMO_ONLY, { kind: 'draft', label: 'Add namespace' });
    expect(draft.button).not.toHaveAttribute('aria-disabled');
    await user.click(draft.button);
    expect(draft.onClick).toHaveBeenCalledTimes(1);
    draft.unmount();

    const commit = renderAction(DEMO_ONLY, { kind: 'commit', label: 'Save' });
    expect(commit.button).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(commit.button).textContent).toContain("Demo workspace. Changes aren't saved.");
    await user.click(commit.button);
    expect(commit.onClick).not.toHaveBeenCalled();
  });

  it('blocks a draft control when the capability is not editable', () => {
    const { button } = renderAction(blocked({ reason: 'READ_ONLY_CONNECTION' }), { kind: 'draft', label: 'Add namespace' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
  });

  it('opens the popover with Space too', async () => {
    const user = userEvent.setup();
    renderAction(blocked({ reason: 'ANALYSE_ONLY' }), { placement: 'popover', label: 'Live consolidation' });
    const lock = screen.getByRole('button', { name: 'Why is Live consolidation unavailable?' });
    lock.focus();
    await user.keyboard(' ');
    expect(await screen.findByRole('dialog')).toHaveTextContent('Live consolidation is disabled until pod-fit simulation is available.');
  });

  it('lists the other reasons by short label, once each, never DEMO', () => {
    const { button } = renderAction(blocked({ reason: 'CONTROLLER_NOT_READY', message: 'Rebalancing is blocked.' }, [
      { reason: 'READ_ONLY_CONNECTION' },
      { reason: 'ANALYSE_ONLY' },
      { reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH' },
      { reason: 'DEMO' },
    ]));
    const text = descriptionOf(button).textContent ?? '';
    expect(text).toContain('Rebalancing is blocked.');
    expect(text).toContain('Also needs: read-write access, safety validation, a connected XamOps agent');
    expect(text).not.toMatch(/demo/i);
  });

  it('shows "Not allowed" with the server message for a code it does not know', () => {
    const { button } = renderAction(blocked({ reason: 'UNKNOWN', raw: 'K8S_SOMETHING_NEW', message: 'The engine refused this change.' }));
    const description = descriptionOf(button);
    expect(description.textContent).toBe('Not allowed: The engine refused this change.');
    expect(description).toHaveAttribute('title', 'K8S_SOMETHING_NEW');
  });

  it('renders the remedy: read-write explainer with support and Account Manager, never Add Account', async () => {
    const user = userEvent.setup();
    const { container } = renderAction(blocked({ reason: 'READ_ONLY_CONNECTION' }));
    await user.click(screen.getByRole('button', { name: 'Get read-write access' }));
    expect(screen.getByText(/XamOps can't switch a read-only connection to read-write in place/)).toBeVisible();
    expect(screen.getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', '/tickets');
    expect(screen.getByRole('link', { name: 'Open Account Manager' })).toHaveAttribute('href', '/account-manager');
    for (const link of container.querySelectorAll('a')) {
      expect(link.getAttribute('href')).not.toMatch(/add-account/i);
    }
  });

  it('links setup remedies to the Data sources install cards on the same cluster page', () => {
    renderAction(blocked({ reason: 'AGENT_NOT_INSTALLED', detail: 'AUTOMATION_AGENT' }), {
      initialEntry: '/eks-details?name=prod-eks&accountIds=111111111111&region=us-east-1&tab=automation&sub=overview',
    });
    expect(screen.getByRole('link', { name: 'Install the node automation agent' })).toHaveAttribute(
      'href',
      '/eks-details?name=prod-eks&accountIds=111111111111&region=us-east-1&tab=data-sources&sub=install#automation-agent',
    );
  });

  it('adds the direct-access line and remedy under EXECUTION_PATH', () => {
    const { button } = renderAction(blocked({
      reason: 'AGENT_NOT_INSTALLED', detail: 'EXECUTION_PATH', directPath: 'CLUSTER_ACCESS_NOT_CONFIGURED',
    }));
    expect(descriptionOf(button).textContent).toBe(
      "The XamOps agent isn't connected, so XamOps can't send changes to this cluster. "
      + "Direct access isn't set up: no EKS access entry maps the XamOps role.",
    );
    expect(screen.getByRole('link', { name: 'Set up monitoring' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Or set up direct access' })).toBeInTheDocument();
  });

  it('describes a loading capability without a reason, still blocked and resolving', () => {
    const { button } = renderAction({ allowed: false, editable: false, gate: null, also: [] });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(button).textContent).toBe('Checking automation access…');
  });
});
