import { useCallback, useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DrawerShell, NestedDrawerShell } from '../DrawerShell';
import { DrawerTabList } from '../DrawerTabList';
import { ColumnsMenu } from '../../ui/ColumnsMenu';

const NO_HIDDEN: ReadonlySet<string> = new Set();

interface NestedOptions {
  widthClass?: string;
  elevated?: boolean;
}

/** A root drawer with a button that opens a nested drawer, which holds a Columns menu (a popover). */
function Harness({ onRootClose, nested = {} }: { onRootClose: () => void; nested?: NestedOptions }) {
  const close = useRef<HTMLButtonElement>(null);
  const [opener, setOpener] = useState<HTMLElement | null>(null);
  const closeNested = useCallback(() => setOpener(null), []);
  return (
    <DrawerShell label="Root drawer" onClose={onRootClose} initialFocus={close}>
      <button ref={close} type="button" onClick={onRootClose}>Close</button>
      <button type="button" onClick={e => setOpener(e.currentTarget)}>Open file</button>
      {opener && (
        <NestedDrawerShell
          label="File drawer"
          returnFocus={opener}
          onClose={closeNested}
          widthClass={nested.widthClass}
          elevated={nested.elevated}
        >
          {({ closeButton, close: closeFile }) => (
            <>
              <button ref={closeButton} type="button" onClick={closeFile}>Close file</button>
              <ColumnsMenu columns={[{ id: 'a', label: 'Alpha' }, { id: 'b', label: 'Beta' }]} hidden={NO_HIDDEN} onToggle={() => {}} />
            </>
          )}
        </NestedDrawerShell>
      )}
    </DrawerShell>
  );
}

function renderHarness(nested?: NestedOptions) {
  const onRootClose = vi.fn();
  render(<Harness onRootClose={onRootClose} nested={nested} />);
  const root = screen.getByRole('dialog', { name: 'Root drawer' });
  return { root, onRootClose };
}

async function openNested(user: ReturnType<typeof userEvent.setup>) {
  const opener = screen.getByRole('button', { name: 'Open file' });
  await user.click(opener);
  return { opener, nested: screen.getByRole('dialog', { name: 'File drawer' }) };
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('DrawerShell', () => {
  it('portals the dialog beside its overlay, focuses the close X and closes on Escape', () => {
    const { root, onRootClose } = renderHarness();
    expect(root.parentElement).toBe(document.body);
    expect(root).toHaveAttribute('aria-modal', 'true');
    expect(root).toHaveAttribute('tabindex', '-1');
    expect(root).toHaveClass('z-50', 'lg:w-[55vw]');
    expect(root.previousElementSibling).toHaveClass('z-40');
    expect(within(root).getByRole('button', { name: 'Close' })).toHaveFocus();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onRootClose).toHaveBeenCalledTimes(1);
  });

  it('closes on an overlay click', async () => {
    const user = userEvent.setup();
    const { root, onRootClose } = renderHarness();
    await user.click(root.previousElementSibling as HTMLElement);
    expect(onRootClose).toHaveBeenCalledTimes(1);
  });
});

describe('NestedDrawerShell', () => {
  it('opens over the root drawer with its close X focused', async () => {
    const user = userEvent.setup();
    renderHarness();
    const { nested } = await openNested(user);
    expect(nested.parentElement).toBe(document.body);
    expect(nested).toHaveAttribute('aria-modal', 'true');
    expect(within(nested).getByRole('button', { name: 'Close file' })).toHaveFocus();
  });

  it('closes on Escape before the root drawer and returns focus to the opener; a second Escape closes the root', async () => {
    const user = userEvent.setup();
    const { onRootClose } = renderHarness();
    const { opener } = await openNested(user);

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File drawer' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'Root drawer' })).toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(onRootClose).not.toHaveBeenCalled();

    await user.keyboard('{Escape}');
    expect(onRootClose).toHaveBeenCalledTimes(1);
  });

  it('closes only itself on an overlay click, with focus on the opener (review fix R11)', async () => {
    const user = userEvent.setup();
    const { onRootClose } = renderHarness();
    const { opener, nested } = await openNested(user);

    await user.click(nested.previousElementSibling as HTMLElement);
    expect(screen.queryByRole('dialog', { name: 'File drawer' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'Root drawer' })).toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(onRootClose).not.toHaveBeenCalled();
  });

  it('returns focus to the opener after its close X', async () => {
    const user = userEvent.setup();
    renderHarness();
    const { opener, nested } = await openNested(user);
    await user.click(within(nested).getByRole('button', { name: 'Close file' }));
    expect(screen.queryByRole('dialog', { name: 'File drawer' })).toBeNull();
    expect(opener).toHaveFocus();
  });

  it('lets a popover inside it close first, also when focus has left the popover', async () => {
    const user = userEvent.setup();
    const { onRootClose } = renderHarness();
    const { opener, nested } = await openNested(user);
    const columns = within(nested).getByRole('button', { name: /^Columns/ });

    // Escape inside the menu: read by its own onKeyDown and stopped there.
    await user.click(columns);
    expect(within(nested).getByText('Alpha')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(within(nested).queryByText('Alpha')).toBeNull();
    expect(screen.getByRole('dialog', { name: 'File drawer' })).toBeInTheDocument();

    // Escape with focus on the nested dialog: the menu is the newest entry of the root's stack.
    await user.click(columns);
    act(() => nested.focus());
    fireEvent.keyDown(nested, { key: 'Escape' });
    expect(within(nested).queryByText('Alpha')).toBeNull();
    expect(columns).toHaveFocus();
    expect(screen.getByRole('dialog', { name: 'File drawer' })).toBeInTheDocument();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: 'File drawer' })).toBeNull();
    expect(opener).toHaveFocus();
    expect(onRootClose).not.toHaveBeenCalled();
  });

  it('layers its overlay at z-[55] and its aside at z-[60] by default, 55vw wide', async () => {
    const user = userEvent.setup();
    renderHarness();
    const { nested } = await openNested(user);
    const overlay = nested.previousElementSibling as HTMLElement;
    expect(overlay).toHaveClass('z-[55]');
    expect(overlay).not.toHaveClass('z-[75]');
    expect(nested).toHaveClass('z-[60]', 'lg:w-[55vw]');
    expect(nested).not.toHaveClass('z-[80]');
  });

  it('layers above full screen with elevated: z-[75] and z-[80] (review fix R1)', async () => {
    const user = userEvent.setup();
    renderHarness({ elevated: true });
    const { nested } = await openNested(user);
    const overlay = nested.previousElementSibling as HTMLElement;
    expect(overlay).toHaveClass('z-[75]');
    expect(overlay).not.toHaveClass('z-[55]');
    expect(nested).toHaveClass('z-[80]');
    expect(nested).not.toHaveClass('z-[60]');
  });

  it('takes its width from widthClass', async () => {
    const user = userEvent.setup();
    renderHarness({ widthClass: 'lg:w-[40vw] lg:min-w-[28rem]' });
    const { nested } = await openNested(user);
    expect(nested).toHaveClass('lg:w-[40vw]', 'lg:min-w-[28rem]');
    expect(nested).not.toHaveClass('lg:w-[55vw]');
  });
});

describe('DrawerTabList', () => {
  const TABS = [{ id: 'sensitives', label: 'Sensitives' }, { id: 'details', label: 'Details' }] as const;
  type TabId = typeof TABS[number]['id'];

  function Tabs({ onSelect }: { onSelect: (id: TabId) => void }) {
    const [active, setActive] = useState<TabId>('details');
    return (
      <>
        <DrawerTabList
          tabs={TABS}
          active={active}
          onSelect={id => { onSelect(id); setActive(id); }}
          label="File details"
          idPrefix="file"
        />
        <div role="tabpanel" id="file-panel" aria-labelledby={`file-tab-${active}`}>Panel</div>
      </>
    );
  }

  it('names its tabs and panel by the prefix, marks the active tab and reports a selection', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<Tabs onSelect={onSelect} />);

    const tablist = screen.getByRole('tablist', { name: 'File details' });
    const [sensitives, details] = within(tablist).getAllByRole('tab');
    expect(sensitives).toHaveAttribute('id', 'file-tab-sensitives');
    expect(details).toHaveAttribute('id', 'file-tab-details');
    for (const tab of [sensitives, details]) expect(tab).toHaveAttribute('aria-controls', 'file-panel');
    expect(details).toHaveAttribute('aria-selected', 'true');
    expect(sensitives).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByRole('tabpanel', { name: 'Details' })).toHaveAttribute('id', 'file-panel');

    await user.click(sensitives);
    expect(onSelect).toHaveBeenCalledWith('sensitives');
    expect(sensitives).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel', { name: 'Sensitives' })).toBeInTheDocument();
  });
});
