import { useCallback, useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DrawerShell } from '../../drawer/DrawerShell';
import { FullscreenFrame } from '../FullscreenFrame';
import { ColumnsMenu } from '../ColumnsMenu';

const NO_HIDDEN: ReadonlySet<string> = new Set();

/** A drawer whose content, with its own toggle and a Columns menu, can go full screen. */
function Harness({ onClose }: { onClose: () => void }) {
  const close = useRef<HTMLButtonElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const [full, setFull] = useState(false);
  const exit = useCallback(() => setFull(false), []);
  return (
    <DrawerShell label="Datastore" onClose={onClose} initialFocus={close}>
      <button ref={close} type="button" onClick={onClose}>Close</button>
      <FullscreenFrame open={full} title="Lineage for report.pdf" onExit={exit} toggleRef={toggle}>
        <div>
          <button ref={toggle} type="button" onClick={() => setFull(f => !f)}>Full screen</button>
          <ColumnsMenu columns={[{ id: 'a', label: 'Alpha' }, { id: 'b', label: 'Beta' }]} hidden={NO_HIDDEN} onToggle={() => {}} />
        </div>
      </FullscreenFrame>
    </DrawerShell>
  );
}

function renderHarness() {
  const onClose = vi.fn();
  render(<Harness onClose={onClose} />);
  return { drawer: screen.getByRole('dialog', { name: 'Datastore' }), onClose };
}

const frame = () => screen.queryByRole('dialog', { name: 'Lineage for report.pdf' });

afterEach(() => {
  vi.clearAllMocks();
});

describe('FullscreenFrame', () => {
  it('renders its children in place while closed', () => {
    const { drawer } = renderHarness();
    expect(frame()).toBeNull();
    expect(within(drawer).getByRole('button', { name: 'Full screen' })).toBeInTheDocument();
  });

  it('opens as a full-screen dialog with the exit button focused, and returns focus to the toggle on exit', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = renderHarness();
    await user.click(within(drawer).getByRole('button', { name: 'Full screen' }));

    const dialog = frame() as HTMLElement;
    expect(dialog.parentElement).toBe(document.body);
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveClass('fixed', 'inset-0', 'z-[70]', 'overflow-auto', 'bg-white');
    expect(within(dialog).getByRole('button', { name: 'Exit full screen' })).toHaveFocus();
    expect(within(dialog).getByRole('button', { name: 'Full screen' })).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: 'Full screen' })).toBeNull();

    await user.click(within(dialog).getByRole('button', { name: 'Exit full screen' }));
    expect(frame()).toBeNull();
    // The toggle remounted in place; focus lands on the new element.
    expect(within(drawer).getByRole('button', { name: 'Full screen' })).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('leaves full screen on the first Escape and closes the drawer on the next', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = renderHarness();
    await user.click(within(drawer).getByRole('button', { name: 'Full screen' }));
    expect(frame()).not.toBeNull();

    await user.keyboard('{Escape}');
    expect(frame()).toBeNull();
    expect(within(drawer).getByRole('button', { name: 'Full screen' })).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes a popover opened in full screen before full screen itself, then the drawer', async () => {
    const user = userEvent.setup();
    const { drawer, onClose } = renderHarness();
    await user.click(within(drawer).getByRole('button', { name: 'Full screen' }));
    const dialog = frame() as HTMLElement;
    await user.click(within(dialog).getByRole('button', { name: /^Columns/ }));
    expect(within(dialog).getByText('Alpha')).toBeInTheDocument();

    // Pressed outside the menu, so the stack decides: the menu registered after full screen.
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(within(dialog).queryByText('Alpha')).toBeNull();
    expect(frame()).not.toBeNull();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(frame()).toBeNull();
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
