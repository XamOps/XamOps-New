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

describe('FullscreenFrame variant="modal"', () => {
  function ModalHarness({ onClose }: { onClose: () => void }) {
    const close = useRef<HTMLButtonElement>(null);
    const toggle = useRef<HTMLButtonElement>(null);
    const [full, setFull] = useState(false);
    const exit = useCallback(() => setFull(false), []);
    return (
      <DrawerShell label="Datastore" onClose={onClose} initialFocus={close}>
        <button ref={close} type="button" onClick={onClose}>Close</button>
        <FullscreenFrame variant="modal" open={full} title="Choose a policy" onExit={exit} toggleRef={toggle}>
          <button ref={toggle} type="button" onClick={() => setFull(f => !f)}>Full screen</button>
        </FullscreenFrame>
      </DrawerShell>
    );
  }

  const modal = () => screen.queryByRole('dialog', { name: 'Choose a policy' });

  it('opens a centred dialog up to 1280 px on a backdrop, and Escape returns focus to the toggle', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<ModalHarness onClose={onClose} />);
    await user.click(screen.getByRole('button', { name: 'Full screen' }));

    const dialog = modal() as HTMLElement;
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveClass('w-full', 'max-w-[1280px]', 'rounded-xl', 'bg-white', 'shadow-xl');
    const backdrop = dialog.parentElement as HTMLElement;
    expect(backdrop.parentElement).toBe(document.body);
    expect(backdrop).toHaveClass('fixed', 'inset-0', 'z-[70]', 'flex', 'items-start', 'justify-center', 'overflow-auto', 'bg-black/40', 'p-6');
    expect(within(dialog).getByRole('button', { name: 'Exit full screen' })).toHaveFocus();

    await user.click(backdrop);
    expect(modal()).not.toBeNull();

    await user.keyboard('{Escape}');
    expect(modal()).toBeNull();
    expect(screen.getByRole('button', { name: 'Full screen' })).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on "Exit full screen"', async () => {
    const user = userEvent.setup();
    render(<ModalHarness onClose={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Full screen' }));
    await user.click(screen.getByRole('button', { name: 'Exit full screen' }));
    expect(modal()).toBeNull();
  });
});
