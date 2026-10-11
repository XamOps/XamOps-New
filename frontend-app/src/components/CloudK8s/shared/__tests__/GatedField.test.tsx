import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Button, Checkbox, Group, Input, NumberField, Switch, TextField } from 'react-aria-components';
import { describe, expect, it } from 'vitest';
import { GatedField } from '../GatedField';
import type { Capability } from '../../../../types/k8sAutomation';

const READ_ONLY: Capability = { allowed: false, editable: false, gate: { reason: 'READ_ONLY_CONNECTION' }, also: [] };
const DEMO_ONLY: Capability = { allowed: false, editable: true, gate: { reason: 'DEMO' }, also: [] };

const wrap = (node: React.ReactNode) => render(<MemoryRouter>{node}</MemoryRouter>);

function descriptionOf(element: HTMLElement): HTMLElement {
  const id = element.getAttribute('aria-describedby');
  expect(id).toBeTruthy();
  const description = document.getElementById(id!);
  expect(description, 'aria-describedby resolves').not.toBeNull();
  return description!;
}

function TextInput({ capability }: { capability: Capability }) {
  return (
    <GatedField type="text" capability={capability} label="Schedule">
      {({ isReadOnly, inputProps }) => (
        <TextField aria-label="Schedule" defaultValue="0 2 * * 0" isReadOnly={isReadOnly}>
          <Input {...inputProps} />
        </TextField>
      )}
    </GatedField>
  );
}

function NumberInput({ capability }: { capability: Capability }) {
  return (
    <GatedField type="number" capability={capability} label="Drain timeout">
      {({ isReadOnly, showSteppers, inputProps }) => (
        <NumberField aria-label="Drain timeout" defaultValue={20} isReadOnly={isReadOnly}>
          <Group>
            {showSteppers && <Button slot="decrement">minus</Button>}
            <Input {...inputProps} />
            {showSteppers && <Button slot="increment">plus</Button>}
          </Group>
        </NumberField>
      )}
    </GatedField>
  );
}

describe('GatedField, not editable', () => {
  it('text: read-only input with aria-disabled and a resolving description, value kept', async () => {
    const user = userEvent.setup();
    const { container } = wrap(<TextInput capability={READ_ONLY} />);
    const input = screen.getByRole('textbox', { name: 'Schedule' });
    expect(input).toHaveAttribute('readonly');
    expect(input).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(input).textContent).toContain('Read-only connection.');
    await user.type(input, 'x');
    expect(input).toHaveValue('0 2 * * 0');
    expect(container.querySelector('[disabled]')).toBeNull();
    expect(container.querySelector('[data-gated-lock]')).not.toBeNull();
  });

  it('number: read-only, no stepper buttons, aria-disabled next to any aria-readonly', () => {
    const { container } = wrap(<NumberInput capability={READ_ONLY} />);
    const input = container.querySelector('input')!;
    expect(input).toHaveAttribute('readonly');
    expect(input).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(input).textContent).toContain('Read-only connection.');
    expect(input).toHaveValue('20');
    expect(screen.queryByText('minus')).toBeNull();
    expect(screen.queryByText('plus')).toBeNull();
    for (const el of container.querySelectorAll('[aria-readonly]')) {
      expect(el).toHaveAttribute('aria-disabled', 'true');
    }
    expect(container.querySelector('[disabled]')).toBeNull();
  });

  it.each(['checkbox', 'switch'] as const)('%s: a replica with the matching role that does nothing', async (type) => {
    const user = userEvent.setup();
    const { container } = wrap(
      <GatedField type={type} capability={READ_ONLY} label="Protect single replicas" isSelected>
        {type === 'checkbox' ? <Checkbox isSelected>Protect single replicas</Checkbox> : <Switch isSelected>Protect single replicas</Switch>}
      </GatedField>,
    );
    const replica = screen.getByRole(type, { name: 'Protect single replicas' });
    expect(replica.tagName).toBe('BUTTON');
    expect(replica).toHaveAttribute('aria-checked', 'true');
    expect(replica).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(replica).textContent).toContain('Read-only connection.');
    await user.click(replica);
    expect(replica).toHaveAttribute('aria-checked', 'true');
    replica.focus();
    expect(replica).toHaveFocus();
    expect(container.querySelector('[aria-readonly]')).toBeNull();
    expect(container.querySelector('[disabled]')).toBeNull();
    expect(container.querySelector('input')).toBeNull();
  });

  it('select: a focusable group naming the label and value, over an aria-hidden replica', () => {
    const { container } = wrap(
      <GatedField type="select" capability={READ_ONLY} label="Window" readOnlyValue="24h">
        <select aria-label="Window"><option>24h</option></select>
      </GatedField>,
    );
    const group = screen.getByRole('group', { name: 'Window, 24h' });
    expect(group).toHaveAttribute('tabindex', '0');
    expect(group).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(group).textContent).toContain('Read-only connection.');
    const replica = group.querySelector('[aria-hidden="true"]');
    expect(replica).not.toBeNull();
    expect(replica).toHaveTextContent('24h');
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(container.querySelector('select')).toBeNull();
    expect(container.querySelector('[aria-readonly]')).toBeNull();
    expect(container.querySelector('[disabled]')).toBeNull();
  });

  it('slider: a focusable group with the value as text and the track as an aria-hidden replica', () => {
    const { container } = wrap(
      <GatedField type="slider" capability={READ_ONLY} label="Headroom" readOnlyValue="25%" value={25} minValue={0} maxValue={100}>
        <input type="range" aria-label="Headroom" />
      </GatedField>,
    );
    const group = screen.getByRole('group', { name: 'Headroom, 25%' });
    expect(group).toHaveAttribute('aria-disabled', 'true');
    expect(descriptionOf(group).textContent).toContain('Read-only connection.');
    expect(group.querySelector('[data-slider-replica][aria-hidden="true"]')).not.toBeNull();
    expect(group).toHaveTextContent('25%');
    expect(screen.queryByRole('slider')).toBeNull();
    expect(container.querySelector('[aria-readonly]')).toBeNull();
    expect(container.querySelector('[disabled]')).toBeNull();
  });
});

describe('GatedField, editable', () => {
  it('a DEMO-only capability leaves every type editable', async () => {
    const user = userEvent.setup();
    wrap(
      <>
        <TextInput capability={DEMO_ONLY} />
        <NumberInput capability={DEMO_ONLY} />
        <GatedField type="checkbox" capability={DEMO_ONLY} label="Protect single replicas" isSelected={false}>
          <Checkbox>Protect single replicas</Checkbox>
        </GatedField>
        <GatedField type="switch" capability={DEMO_ONLY} label="Spot interruptions" isSelected={false}>
          <Switch>Spot interruptions</Switch>
        </GatedField>
        <GatedField type="select" capability={DEMO_ONLY} label="Window" readOnlyValue="24h">
          <select aria-label="Window"><option>24h</option></select>
        </GatedField>
        <GatedField type="slider" capability={DEMO_ONLY} label="Headroom" readOnlyValue="25%" value={25} minValue={0} maxValue={100}>
          <input type="range" aria-label="Headroom" />
        </GatedField>
      </>,
    );
    const text = screen.getByRole('textbox', { name: 'Schedule' });
    expect(text).not.toHaveAttribute('readonly');
    expect(text).not.toHaveAttribute('aria-disabled');
    await user.clear(text);
    await user.type(text, '0 3 * * 1');
    expect(text).toHaveValue('0 3 * * 1');
    expect(screen.getByText('plus')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Protect single replicas' }).tagName).toBe('INPUT');
    expect(screen.getByRole('switch', { name: 'Spot interruptions' }).tagName).toBe('INPUT');
    expect(screen.getByRole('combobox', { name: 'Window' })).toBeInTheDocument();
    expect(screen.getByRole('slider', { name: 'Headroom' })).toBeInTheDocument();
    expect(document.querySelector('[data-gated="blocked"]')).toBeNull();
  });
});

describe('GatedField types', () => {
  it('requires readOnlyValue for select and slider', () => {
    const element = (
      <>
        {/* @ts-expect-error readOnlyValue is required for a select */}
        <GatedField type="select" capability={READ_ONLY} label="Window"><select /></GatedField>
        {/* @ts-expect-error readOnlyValue is required for a slider */}
        <GatedField type="slider" capability={READ_ONLY} label="Headroom" value={25} minValue={0} maxValue={100}><input /></GatedField>
      </>
    );
    expect(element).toBeTruthy();
  });
});
