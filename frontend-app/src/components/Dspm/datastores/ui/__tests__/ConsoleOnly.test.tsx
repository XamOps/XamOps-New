import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  CONSOLE_ONLY_TOOLTIP, ConsoleOnlyButton, ConsoleOnlyCheckbox, ConsoleOnlyField, ConsoleOnlyMenuItem, ConsoleOnlyRadio,
  ConsoleOnlySwitch,
} from '../ConsoleOnly';

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
  {
    name: 'ConsoleOnlyMenuItem',
    element: <ConsoleOnlyMenuItem icon="mdi:sync">Sync Datastore</ConsoleOnlyMenuItem>,
    get: () => screen.getByRole('menuitem', { name: 'Sync Datastore' }),
  },
  {
    name: 'ConsoleOnlySwitch',
    element: <ConsoleOnlySwitch label="Real-time scanning" checked />,
    get: () => screen.getByRole('switch', { name: 'Real-time scanning' }),
  },
  {
    name: 'ConsoleOnlyRadio',
    element: <ConsoleOnlyRadio label="All users" checked={false} />,
    get: () => screen.getByRole('radio', { name: 'All users' }),
  },
  {
    name: 'ConsoleOnlyField',
    element: <ConsoleOnlyField label="Host" value="db.example.internal" />,
    get: () => screen.getByRole('textbox', { name: 'Host' }),
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

describe('ConsoleOnlyButton card variant', () => {
  it('fills its cell as a card, shows the tooltip and does nothing on click or Enter', async () => {
    const user = userEvent.setup();
    const { onClick, onKeyDown } = renderInRow(
      <ConsoleOnlyButton variant="card"><span>Sensitive Findings</span></ConsoleOnlyButton>,
    );
    const card = screen.getByRole('button', { name: 'Sensitive Findings' });
    expect(card).toHaveAttribute('aria-disabled', 'true');
    expect(card).toHaveClass('flex', 'w-full', 'flex-col', 'items-start', 'gap-3', 'rounded-xl', 'border', 'bg-white', 'p-5', 'text-left', 'opacity-50');
    expect(card).not.toHaveClass('inline-flex', 'items-center');
    expect(card.parentElement).toHaveClass('relative', 'flex', 'w-full');
    expect(card.parentElement).not.toHaveClass('inline-flex');

    await user.hover(card);
    expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
    await user.click(card);
    await user.keyboard('{Enter}');
    expect(onClick).not.toHaveBeenCalled();
    expect(onKeyDown).not.toHaveBeenCalled();
  });
});

describe('the state the console reports', () => {
  it('ConsoleOnlyCheckbox is unchecked by default and draws checked when told', () => {
    render(
      <>
        <ConsoleOnlyCheckbox label="Unset" />
        <ConsoleOnlyCheckbox label="Set" checked />
      </>,
    );
    expect(screen.getByRole('checkbox', { name: 'Unset' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('checkbox', { name: 'Set' })).toHaveAttribute('aria-checked', 'true');
  });

  it('ConsoleOnlySwitch and ConsoleOnlyRadio carry aria-checked; the radio shows its label', () => {
    render(
      <>
        <ConsoleOnlySwitch label="On" checked />
        <ConsoleOnlySwitch label="Off" checked={false} />
        <ConsoleOnlyRadio label="Selected users" checked />
      </>,
    );
    expect(screen.getByRole('switch', { name: 'On' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: 'Off' })).toHaveAttribute('aria-checked', 'false');
    const radio = screen.getByRole('radio', { name: 'Selected users' });
    expect(radio).toHaveAttribute('aria-checked', 'true');
    expect(radio).toHaveTextContent('Selected users');
  });

  it('ConsoleOnlyField is a read-only textbox showing its value, or an empty box', () => {
    render(
      <>
        <ConsoleOnlyField label="Paths" value={'/data/a\n/data/b'} multiline />
        <ConsoleOnlyField label="Username" value={null} />
      </>,
    );
    const paths = screen.getByRole('textbox', { name: 'Paths' });
    expect(paths).toHaveAttribute('aria-readonly', 'true');
    expect(paths).toHaveAttribute('aria-multiline', 'true');
    expect(paths).toHaveAttribute('tabindex', '0');
    expect(paths).toHaveTextContent('/data/a');
    expect(screen.getByText('Username')).toBeVisible();
    expect(screen.getByRole('textbox', { name: 'Username' })).toBeEmptyDOMElement();
  });

  it('every new control is greyed', () => {
    render(
      <>
        <ConsoleOnlyMenuItem>Download Sensitives</ConsoleOnlyMenuItem>
        <ConsoleOnlySwitch label="Scan" checked={false} />
        <ConsoleOnlyRadio label="All users" checked />
        <ConsoleOnlyField label="Port" value="5432" />
      </>,
    );
    for (const control of [
      screen.getByRole('menuitem'), screen.getByRole('switch'), screen.getByRole('radio'), screen.getByRole('textbox'),
    ]) {
      expect(control).toHaveClass('opacity-50', 'cursor-not-allowed');
      expect(control).toHaveAttribute('aria-disabled', 'true');
    }
  });
});
