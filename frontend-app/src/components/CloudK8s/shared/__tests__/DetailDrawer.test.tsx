import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import DetailDrawer, { type DrawerSection } from '../DetailDrawer';
import { DASH } from '../../../../utils/k8sQuantities';

const SECTIONS: DrawerSection[] = [
  { title: 'Overview', data: [{ label: 'Name', value: 'pod-1' }, { label: 'Phase', value: 'Running' }] },
  // A section that is kept (it has a value) but carries one missing datum to render as a dash.
  { title: 'Cost', data: [{ label: 'Node', value: 'ip-10-0-1-5' }, { label: 'Monthly', value: null, reason: 'no per-pod cost' }] },
];

function Harness({ sections = SECTIONS }: { sections?: DrawerSection[] }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>Open drawer</button>
      <DetailDrawer open={open} onClose={() => setOpen(false)} title="pod-1" sections={sections} />
    </div>
  );
}

describe('DetailDrawer (FEAT-001, §8.1)', () => {
  it('is a labelled modal dialog when open and absent when closed', () => {
    const { rerender } = render(<DetailDrawer open={false} onClose={() => {}} title="pod-1" />);
    expect(screen.queryByRole('dialog')).toBeNull();
    rerender(<DetailDrawer open onClose={() => {}} title="pod-1" />);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('pod-1');
  });

  it('closes on Esc and on a scrim click, and returns focus to the opener', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole('button', { name: 'Open drawer' });

    await user.click(opener);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(opener).toHaveFocus();

    await user.click(opener);
    const scrim = document.querySelector('[data-drawer-scrim]')!;
    await user.click(scrim);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(opener).toHaveFocus();
  });

  it('traps Tab within the panel', async () => {
    const user = userEvent.setup();
    render(<DetailDrawer open onClose={() => {}} title="pod-1" sections={SECTIONS} />);
    const dialog = screen.getByRole('dialog');
    const close = screen.getByRole('button', { name: 'Close' });
    close.focus();
    expect(close).toHaveFocus();
    // Tab from the only focusable wraps back to it, staying inside the dialog.
    await user.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
  });

  it('renders a null datum as a muted dash with its reason, never 0', () => {
    render(<DetailDrawer open onClose={() => {}} title="pod-1" sections={SECTIONS} />);
    const dash = screen.getByText(DASH);
    expect(dash).toHaveAttribute('title', 'no per-pod cost');
  });

  it('drops a section whose every value is empty', () => {
    const sections: DrawerSection[] = [
      { title: 'Shown', data: [{ label: 'a', value: 'x' }] },
      { title: 'All empty', data: [{ label: 'b', value: null }, { label: 'c', value: DASH }] },
    ];
    render(<DetailDrawer open onClose={() => {}} title="pod-1" sections={sections} />);
    expect(screen.getByText('Shown')).toBeInTheDocument();
    expect(screen.queryByText('All empty')).toBeNull();
  });

  it('shows a skeleton while loading', () => {
    render(<DetailDrawer open onClose={() => {}} title="pod-1" sections={SECTIONS} loading />);
    // Section content is hidden behind the skeleton while loading.
    expect(screen.queryByText('Overview')).toBeNull();
  });
});
