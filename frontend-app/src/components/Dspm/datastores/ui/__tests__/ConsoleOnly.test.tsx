import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CONSOLE_ONLY_TOOLTIP, ConsoleOnlyButton, ConsoleOnlyCheckbox } from '../ConsoleOnly';

/** Renders the control inside a "row" with its own click and keydown handlers, like a list row. */
function renderInRow(control: React.ReactElement) {
  const onClick = vi.fn();
  const onKeyDown = vi.fn();
  render(
    <div data-testid="row" onClick={onClick} onKeyDown={onKeyDown}>
      {control}
    </div>,
  );
  return { onClick, onKeyDown };
}

const cases = [
  {
    name: 'ConsoleOnlyButton',
    element: <ConsoleOnlyButton icon="mdi:tray-arrow-down">Export</ConsoleOnlyButton>,
    get: () => screen.getByRole('button', { name: 'Export' }),
  },
  {
    name: 'ConsoleOnlyButton (link)',
    element: <ConsoleOnlyButton variant="link">View full details →</ConsoleOnlyButton>,
    get: () => screen.getByRole('button', { name: 'View full details →' }),
  },
  {
    name: 'ConsoleOnlyCheckbox',
    element: <ConsoleOnlyCheckbox label="Select row" />,
    get: () => screen.getByRole('checkbox', { name: 'Select row' }),
  },
];

describe.each(cases)('$name', ({ element, get }) => {
  it('is aria-disabled, stays focusable and is described by the tooltip', () => {
    renderInRow(element);
    const control = get();
    expect(control).toHaveAttribute('aria-disabled', 'true');
    expect(control).not.toBeDisabled();
    act(() => control.focus());
    expect(control).toHaveFocus();
    const tooltip = screen.getByRole('tooltip');
    expect(control).toHaveAttribute('aria-describedby', tooltip.id);
    expect(tooltip).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
  });

  it('shows the tooltip on hover and hides it on leave', async () => {
    const user = userEvent.setup();
    renderInRow(element);
    expect(screen.queryByRole('tooltip')).toBeNull();
    await user.hover(get());
    expect(screen.getByRole('tooltip')).toHaveTextContent(/^Disabled by admin$/);
    await user.unhover(get());
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  it('shows the tooltip on focus and hides it on blur', async () => {
    const user = userEvent.setup();
    renderInRow(element);
    await user.tab();
    expect(get()).toHaveFocus();
    expect(screen.getByRole('tooltip')).toBeVisible();
    await user.tab();
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  it('hides the tooltip on Escape', async () => {
    const user = userEvent.setup();
    renderInRow(element);
    await user.tab();
    expect(screen.getByRole('tooltip')).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('tooltip')).toBeNull();
    expect(get()).toHaveFocus();
  });

  it('does nothing on click, and the click never reaches the row', async () => {
    const user = userEvent.setup();
    const { onClick } = renderInRow(element);
    await user.click(get());
    expect(onClick).not.toHaveBeenCalled();
  });

  it('stops Enter and Space before the row sees a keydown or a click', async () => {
    const user = userEvent.setup();
    const { onClick, onKeyDown } = renderInRow(element);
    await user.tab();
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onKeyDown).not.toHaveBeenCalled();
    expect(onClick).not.toHaveBeenCalled();
  });

  it('stops Escape while the tooltip is visible; the next Escape reaches the row', async () => {
    const user = userEvent.setup();
    const { onKeyDown } = renderInRow(element);
    await user.tab();
    expect(screen.getByRole('tooltip')).toBeVisible();
    await user.keyboard('{Escape}');
    expect(onKeyDown).not.toHaveBeenCalled();
    expect(screen.queryByRole('tooltip')).toBeNull();
    await user.keyboard('{Escape}');
    expect(onKeyDown).toHaveBeenCalledTimes(1);
    expect(onKeyDown.mock.calls[0][0].key).toBe('Escape');
  });
});

describe('ConsoleOnlyButton link variant', () => {
  it('looks like a link: sky text, no border, no padding', () => {
    render(<ConsoleOnlyButton variant="link">View full details →</ConsoleOnlyButton>);
    const link = screen.getByRole('button', { name: 'View full details →' });
    expect(link).toHaveClass('gap-1', 'text-xs', 'font-semibold', 'text-sky-700', 'opacity-50', 'cursor-not-allowed');
    expect(link.className).not.toMatch(/\bborder\b|\bp[xy]?-\d/);
  });
});
