import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { GatedSwitch } from '../GatedSwitch';
import type { Capability } from '../../../../types/k8sAutomation';

const ALLOWED: Capability = { allowed: true, editable: true, gate: null, also: [] };
const READ_ONLY: Capability = { allowed: false, editable: false, gate: { reason: 'READ_ONLY_CONNECTION' }, also: [] };
const DEMO_ONLY: Capability = { allowed: false, editable: true, gate: { reason: 'DEMO' }, also: [] };

function renderSwitch(capability: Capability, props: { isSelected?: boolean; pending?: boolean; placement?: 'inline' | 'popover' } = {}) {
  const onChange = vi.fn();
  const result = render(
    <MemoryRouter>
      <GatedSwitch
        capability={capability}
        label="Node automation"
        isSelected={props.isSelected ?? false}
        onChange={onChange}
        pending={props.pending}
        reasonPlacement={props.placement}
      />
    </MemoryRouter>,
  );
  return { ...result, onChange, control: screen.getByRole('switch', { name: 'Node automation' }) };
}

function descriptionOf(element: HTMLElement): HTMLElement {
  const id = element.getAttribute('aria-describedby');
  expect(id).toBeTruthy();
  const description = document.getElementById(id!);
  expect(description, 'aria-describedby resolves').not.toBeNull();
  return description!;
}

describe('GatedSwitch', () => {
  it('renders the react-aria Switch when allowed, and toggles', async () => {
    const user = userEvent.setup();
    const { control, onChange, container } = renderSwitch(ALLOWED);
    expect(control.tagName).toBe('INPUT');
    expect(control).not.toHaveAttribute('aria-disabled');
    await user.click(control);
    expect(onChange).toHaveBeenCalledWith(true);
    expect(container.querySelector('[data-gated="blocked"]')).toBeNull();
  });

  it.each([false, true])('renders a blocked replica (selected: %s) that does nothing', async (isSelected) => {
    const user = userEvent.setup();
    const { control, onChange, container } = renderSwitch(READ_ONLY, { isSelected });

    expect(control.tagName).toBe('BUTTON');
    expect(control).toHaveAttribute('role', 'switch');
    expect(control).toHaveAttribute('aria-checked', String(isSelected));
    expect(control).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(control).textContent).toContain('Read-only connection. Automation changes need read-write access.');
    expect(container.querySelector('[data-gated-lock]')).not.toBeNull();

    await user.click(control);
    control.focus();
    expect(control).toHaveFocus();
    await user.keyboard(' ');
    await user.keyboard('{Enter}');
    expect(onChange).not.toHaveBeenCalled();
    expect(control).toHaveAttribute('aria-checked', String(isSelected));

    expect(container.querySelector('[aria-readonly]')).toBeNull();
    expect(container.querySelector('[disabled]')).toBeNull();
  });

  it('blocks a DEMO-only capability: a switch saves, so it is a commit', () => {
    const { control } = renderSwitch(DEMO_ONLY);
    expect(control).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(control).textContent).toContain("Demo workspace. Changes aren't saved.");
  });

  it('is blocked and described by "Saving…" while pending', async () => {
    const user = userEvent.setup();
    const { control, onChange } = renderSwitch(ALLOWED, { isSelected: true, pending: true });
    expect(control).toHaveAttribute('aria-disabled', 'true');
    expect(control).toHaveAttribute('aria-checked', 'true');
    expect(descriptionOf(control).textContent).toBe('Saving…');
    await user.click(control);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('puts the reason behind a lock button in popover placement', () => {
    const { control } = renderSwitch(READ_ONLY, { placement: 'popover' });
    expect(descriptionOf(control)).toHaveClass('sr-only');
    expect(screen.getByRole('button', { name: 'Why is Node automation unavailable?' })).toBeInTheDocument();
  });
});
