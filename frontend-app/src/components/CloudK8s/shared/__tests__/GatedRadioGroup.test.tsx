import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { GatedRadioGroup } from '../GatedRadioGroup';
import type { Capability } from '../../../../types/k8sAutomation';

type Mode = 'OFF' | 'RECOMMEND' | 'AUTO';

const EDITABLE: Capability = { allowed: true, editable: true, gate: null, also: [] };
const READ_ONLY: Capability = { allowed: false, editable: false, gate: { reason: 'READ_ONLY_CONNECTION' }, also: [] };
const AUTO_BLOCKED: Capability = {
  allowed: false, editable: false,
  gate: { reason: 'CONTROLLER_NOT_READY', message: "Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed." },
  also: [],
};

function renderMode(group: Capability, value: Mode = 'RECOMMEND') {
  const onChange = vi.fn();
  render(
    <MemoryRouter>
      <GatedRadioGroup<Mode>
        label="Mode"
        value={value}
        onChange={onChange}
        capability={group}
        options={[
          { value: 'OFF', label: 'Off' },
          { value: 'RECOMMEND', label: 'Recommend' },
          { value: 'AUTO', label: 'Auto', capability: AUTO_BLOCKED },
        ]}
      />
    </MemoryRouter>,
  );
  return {
    onChange,
    group: screen.getByRole('radiogroup', { name: 'Mode' }),
    off: screen.getByRole('radio', { name: 'Off' }),
    recommend: screen.getByRole('radio', { name: 'Recommend' }),
    auto: screen.getByRole('radio', { name: 'Auto' }),
  };
}

function descriptionOf(element: HTMLElement): HTMLElement {
  const id = element.getAttribute('aria-describedby');
  expect(id).toBeTruthy();
  const description = document.getElementById(id!);
  expect(description, 'aria-describedby resolves').not.toBeNull();
  return description!;
}

describe('GatedRadioGroup', () => {
  it('is a radiogroup with one tab stop on the checked option', () => {
    const { group, off, recommend, auto } = renderMode(EDITABLE);
    expect(group).not.toHaveAttribute('aria-disabled');
    expect(recommend).toHaveAttribute('aria-checked', 'true');
    expect(off).toHaveAttribute('aria-checked', 'false');
    expect(recommend).toHaveAttribute('tabindex', '0');
    expect(off).toHaveAttribute('tabindex', '-1');
    expect(auto).toHaveAttribute('tabindex', '-1');
  });

  it('gives a blocked option aria-disabled, a lock and its own resolving reason', () => {
    const { auto, off } = renderMode(EDITABLE);
    expect(auto).toHaveAttribute('aria-disabled', 'true');
    expect(auto.querySelector('[data-gated-lock]')).not.toBeNull();
    expect(descriptionOf(auto).textContent).toContain("Workload autoscaling changes are off until the vertical rightsizer's access checks are confirmed.");
    expect(off).not.toHaveAttribute('aria-disabled');
    expect(off).not.toHaveAttribute('aria-describedby');
  });

  it('arrow keys reach a blocked option without checking it, and check an editable one', async () => {
    const user = userEvent.setup();
    const { recommend, auto, off, onChange } = renderMode(EDITABLE);
    await user.tab();
    expect(recommend).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(auto).toHaveFocus();
    expect(onChange).not.toHaveBeenCalled();
    await user.keyboard('{ArrowRight}');
    expect(off).toHaveFocus();
    expect(onChange).toHaveBeenCalledWith('OFF');
  });

  it('Space checks only an editable option', async () => {
    const user = userEvent.setup();
    const { auto, off, onChange } = renderMode(EDITABLE);
    auto.focus();
    await user.keyboard(' ');
    expect(onChange).not.toHaveBeenCalled();
    off.focus();
    await user.keyboard(' ');
    expect(onChange).toHaveBeenCalledWith('OFF');
  });

  it('lets Off and Recommend be chosen while only Auto is blocked', async () => {
    const user = userEvent.setup();
    const { off, auto, onChange } = renderMode(EDITABLE, 'AUTO');
    await user.click(auto);
    expect(onChange).not.toHaveBeenCalled();
    await user.click(off);
    expect(onChange).toHaveBeenCalledWith('OFF');
  });

  it('blocks the whole group with aria-disabled and a resolving reason', async () => {
    const user = userEvent.setup();
    const { group, off, recommend, auto, onChange } = renderMode(READ_ONLY);
    expect(group).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(group).textContent).toContain('Read-only connection.');
    for (const radio of [off, recommend, auto]) {
      expect(radio).toHaveAttribute('aria-disabled', 'true');
      descriptionOf(radio);
    }
    recommend.focus();
    await user.keyboard('{ArrowLeft}');
    expect(off).toHaveFocus();
    await user.keyboard(' ');
    await user.click(off);
    expect(onChange).not.toHaveBeenCalled();
    expect(document.querySelector('[disabled]')).toBeNull();
  });
});
