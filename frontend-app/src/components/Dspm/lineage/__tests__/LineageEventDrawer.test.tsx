import React, { useCallback, useState } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { LineageEventView } from '../../datastores/saas/lineageModel';
import LineageEscapeHost from '../LineageEscapeHost';
import LineageEventDrawer from '../LineageEventDrawer';
import { formatEventTime } from '../lineageText';

const AT = 1_775_000_000;

const EVENT: LineageEventView = {
  key: 'event:0', type: 'File Modified', actor: 'alex', actorEmail: 'alex@example.com', internal: false, at: AT,
  fileName: 'q3-forecast.xlsx', channel: 'Endpoint',
};

/** A card that opens the drawer, inside the page's Escape host. */
const Harness: React.FC<{ event?: LineageEventView; elevated?: boolean }> = ({ event = EVENT, elevated = false }) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  return (
    <LineageEscapeHost>
      <button type="button" onClick={e => setAnchor(e.currentTarget)}>Event card</button>
      {anchor && <LineageEventDrawer event={event} returnFocus={anchor} elevated={elevated} onClose={close} />}
    </LineageEscapeHost>
  );
};

async function open(props: React.ComponentProps<typeof Harness> = {}) {
  const user = userEvent.setup();
  render(<Harness {...props} />);
  await user.click(screen.getByRole('button', { name: 'Event card' }));
  return { user, drawer: screen.getByRole('dialog', { name: props.event?.type ?? EVENT.type }) };
}

const rowValue = (region: HTMLElement, label: string) =>
  within(region).getByText(label, { selector: 'dt' }).nextElementSibling;

describe('LineageEventDrawer', () => {
  it('shows the type, the file name, and the Summary and Details rows (FR-EVD-2)', async () => {
    const { drawer } = await open();
    expect(within(drawer).getByRole('heading', { name: 'File Modified' })).toBeInTheDocument();
    expect(within(drawer).getByText('q3-forecast.xlsx')).toBeInTheDocument();

    const summary = within(drawer).getByRole('region', { name: 'Summary' });
    expect(rowValue(summary, 'Actor')).toHaveTextContent('alex');
    expect(rowValue(summary, 'Actor Type')).toHaveTextContent('-');
    expect(rowValue(summary, 'Email')).toHaveTextContent('alex@example.com');
    expect(rowValue(summary, 'Time')).toHaveTextContent(formatEventTime(AT));
    expect(rowValue(summary, 'Channel')).toHaveTextContent('Endpoint');

    const details = within(drawer).getByRole('region', { name: 'Details' });
    const chip = within(rowValue(details, 'Event type') as HTMLElement).getByText('File Modified');
    expect(chip).toHaveClass('bg-violet-50', 'text-violet-700');
  });

  it('shows "Internal" for an internal actor and "-" for missing values', async () => {
    const { drawer } = await open({
      event: { ...EVENT, internal: true, actorEmail: null, at: null, channel: null, fileName: null },
    });
    const summary = within(drawer).getByRole('region', { name: 'Summary' });
    expect(rowValue(summary, 'Actor Type')).toHaveTextContent('Internal');
    expect(rowValue(summary, 'Email')).toHaveTextContent('-');
    expect(rowValue(summary, 'Time')).toHaveTextContent('-');
    expect(rowValue(summary, 'Channel')).toHaveTextContent('-');
  });

  it('collapses and expands a section', async () => {
    const { user, drawer } = await open();
    const header = within(drawer).getByRole('button', { name: 'Summary' });
    expect(header).toHaveAttribute('aria-expanded', 'true');
    const region = document.getElementById(header.getAttribute('aria-controls')!)!;
    expect(region).toBeVisible();
    await user.click(header);
    expect(header).toHaveAttribute('aria-expanded', 'false');
    expect(region).not.toBeVisible();
    await user.click(header);
    expect(region).toBeVisible();
  });

  it('focuses its close X, and Escape closes it and returns focus to the card (FR-EVD-3)', async () => {
    const { user, drawer } = await open();
    expect(within(drawer).getByRole('button', { name: 'Close' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: 'File Modified' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Event card' })).toHaveFocus();
  });

  it('closes on the X and returns focus to the card', async () => {
    const { user, drawer } = await open();
    await user.click(within(drawer).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog', { name: 'File Modified' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Event card' })).toHaveFocus();
  });

  it('stacks above the full-screen modal when elevated', async () => {
    const { drawer } = await open({ elevated: true });
    expect(drawer).toHaveClass('z-[80]', 'lg:w-[30rem]');
    expect(drawer.previousElementSibling).toHaveClass('z-[75]');
  });

  it('uses the default layer otherwise', async () => {
    const { drawer } = await open();
    expect(drawer).toHaveClass('z-[60]');
    expect(drawer).not.toHaveClass('z-[80]');
  });
});
