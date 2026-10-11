import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmDialog } from '../ConfirmDialog';

function renderDialog(isOpen = true, pending = false) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog
      isOpen={isOpen}
      title="Turn on node automation for prod-eks?"
      confirmLabel="Turn on"
      onConfirm={onConfirm}
      onCancel={onCancel}
      pending={pending}
    >
      The automation agent will receive this cluster's policy and start node analysis.
    </ConfirmDialog>,
  );
  return { onConfirm, onCancel };
}

describe('ConfirmDialog', () => {
  it('renders nothing while closed', () => {
    renderDialog(false);
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('opens with focus on Cancel, never on the confirm button', () => {
    renderDialog();
    const dialog = screen.getByRole('alertdialog', { name: 'Turn on node automation for prod-eks?' });
    expect(dialog).toHaveTextContent("The automation agent will receive this cluster's policy");
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  });

  it('Escape cancels', async () => {
    const user = userEvent.setup();
    const { onCancel, onConfirm } = renderDialog();
    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('confirms only from the confirm button', async () => {
    const user = userEvent.setup();
    const { onCancel, onConfirm } = renderDialog();
    await user.click(screen.getByRole('button', { name: 'Turn on' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();
  });

  it('does nothing on confirm while pending', async () => {
    const user = userEvent.setup();
    const { onConfirm } = renderDialog(true, true);
    const confirm = screen.getByRole('button', { name: 'Working…' });
    expect(confirm).toHaveAttribute('aria-disabled', 'true');
    await user.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
