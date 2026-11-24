import { useCallback, useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Popover } from '../Popover';
import { DrawerShell } from '../../drawer/DrawerShell';

/** A container holding an event card that opens the popover under itself. */
function Lanes({ onClose }: { onClose?: () => void }) {
  const container = useRef<HTMLDivElement>(null);
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => {
    setAnchor(null);
    onClose?.();
  }, [onClose]);
  return (
    <div>
      <button type="button">Outside</button>
      <div ref={container} data-testid="container" className="relative">
        <button type="button" onClick={e => setAnchor(e.currentTarget)}>Event card</button>
        <Popover open={anchor !== null} onClose={close} anchor={anchor} container={container} label="Event details">
          <button type="button">View details</button>
        </Popover>
      </div>
    </div>
  );
}

function rect(left: number, top: number, width: number, height: number): DOMRect {
  return {
    left, top, width, height, x: left, y: top, right: left + width, bottom: top + height, toJSON: () => ({}),
  } as DOMRect;
}

/** Places the container at (50, 100), 600 px wide, and the card at the given position. */
function layout(cardLeft: number, cardTop: number) {
  const container = screen.getByTestId('container');
  vi.spyOn(container, 'getBoundingClientRect').mockReturnValue(rect(50, 100, 600, 400));
  Object.defineProperty(container, 'clientWidth', { configurable: true, value: 600 });
  vi.spyOn(screen.getByRole('button', { name: 'Event card' }), 'getBoundingClientRect')
    .mockReturnValue(rect(cardLeft, cardTop, 160, 40));
}

const popover = () => screen.queryByRole('dialog', { name: 'Event details' });

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Popover', () => {
  it('opens under its anchor, 4 px below it, from one read of both rects', async () => {
    const user = userEvent.setup();
    render(<Lanes />);
    layout(120, 140);
    await user.click(screen.getByRole('button', { name: 'Event card' }));

    const panel = popover() as HTMLElement;
    expect(panel).toHaveClass('absolute', 'z-30', 'w-72');
    expect(panel.parentElement).toBe(screen.getByTestId('container'));
    expect(panel.style.top).toBe('84px');    // 180 − 100 + 4
    expect(panel.style.left).toBe('70px');   // 120 − 50
  });

  it("is clamped to the container's right edge and never left of its left edge", async () => {
    const user = userEvent.setup();
    render(<Lanes />);
    layout(560, 140);
    await user.click(screen.getByRole('button', { name: 'Event card' }));
    expect((popover() as HTMLElement).style.left).toBe('312px');   // 600 − 288, not 510

    await user.click(screen.getByRole('button', { name: 'Outside' }));
    layout(20, 140);
    await user.click(screen.getByRole('button', { name: 'Event card' }));
    expect((popover() as HTMLElement).style.left).toBe('0px');
  });

  it('closes on a mousedown outside, not on one inside or on its anchor', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<Lanes onClose={onClose} />);
    await user.click(screen.getByRole('button', { name: 'Event card' }));

    fireEvent.mouseDown(within(popover() as HTMLElement).getByRole('button', { name: 'View details' }));
    fireEvent.mouseDown(screen.getByRole('button', { name: 'Event card' }));
    expect(popover()).not.toBeNull();
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByRole('button', { name: 'Outside' }));
    expect(popover()).toBeNull();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape inside it, stops the key and returns focus to the anchor', async () => {
    const user = userEvent.setup();
    const onKeyDown = vi.fn();
    render(<div onKeyDown={onKeyDown}><Lanes /></div>);
    const card = screen.getByRole('button', { name: 'Event card' });
    await user.click(card);
    act(() => within(popover() as HTMLElement).getByRole('button', { name: 'View details' }).focus());

    await user.keyboard('{Escape}');
    expect(popover()).toBeNull();
    expect(card).toHaveFocus();
    expect(onKeyDown).not.toHaveBeenCalled();
  });

  it("inside a drawer, closes before the drawer on an Escape pressed outside it (the drawer's stack)", async () => {
    const user = userEvent.setup();
    const onDrawerClose = vi.fn();
    function InDrawer() {
      const close = useRef<HTMLButtonElement>(null);
      return (
        <DrawerShell label="Drawer" onClose={onDrawerClose} initialFocus={close}>
          <button ref={close} type="button">Close</button>
          <Lanes />
        </DrawerShell>
      );
    }
    render(<InDrawer />);
    const card = screen.getByRole('button', { name: 'Event card' });
    await user.click(card);
    act(() => screen.getByRole('button', { name: 'Close' }).focus());

    await user.keyboard('{Escape}');
    expect(popover()).toBeNull();
    expect(card).toHaveFocus();
    expect(onDrawerClose).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    expect(onDrawerClose).toHaveBeenCalledTimes(1);
  });
});
