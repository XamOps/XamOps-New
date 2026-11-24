import { useCallback, useState } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NestedDrawerShell } from '../../datastores/drawer/DrawerShell';
import FileDrawerHost from '../FileDrawerHost';

/** A popover that handles Escape itself, as the filter popovers do. */
function SelfClosingPopover() {
  const [open, setOpen] = useState(true);
  if (!open) return null;
  return (
    <div
      role="dialog"
      aria-label="Popover"
      onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); setOpen(false); } }}
    >
      <button type="button">Inside popover</button>
    </div>
  );
}

function Harness({ withPopover = false, register = true }: { withPopover?: boolean; register?: boolean }) {
  const [opener, setOpener] = useState<HTMLButtonElement | null>(null);
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  return (
    <>
      <button ref={setOpener} type="button" onClick={() => setOpen(true)}>file row</button>
      {open && (
        <FileDrawerHost>
          {register ? (
            <NestedDrawerShell label="File drawer" returnFocus={opener} onClose={close}>
              {({ closeButton }) => (
                <>
                  <button ref={closeButton} type="button">Close</button>
                  {withPopover && <SelfClosingPopover />}
                </>
              )}
            </NestedDrawerShell>
          ) : (
            <div role="dialog" aria-label="Unregistered" />
          )}
        </FileDrawerHost>
      )}
    </>
  );
}

describe('FileDrawerHost', () => {
  it('closes the registered drawer on Escape and focuses its opener', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: 'file row' }));
    expect(screen.getByRole('dialog', { name: 'File drawer' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File drawer' })).toBeNull();
    expect(screen.getByRole('button', { name: 'file row' })).toHaveFocus();
  });

  it('does nothing on Escape with nothing registered', async () => {
    const user = userEvent.setup();
    render(<Harness register={false} />);
    await user.click(screen.getByRole('button', { name: 'file row' }));
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Unregistered' })).toBeInTheDocument();
  });

  it('keeps the drawer open when a popover inside stops Escape', async () => {
    const user = userEvent.setup();
    render(<Harness withPopover />);
    await user.click(screen.getByRole('button', { name: 'file row' }));
    await user.click(screen.getByRole('button', { name: 'Inside popover' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'Popover' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'File drawer' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File drawer' })).toBeNull();
  });
});
