import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EventCard, EventDetails, LaneLabel } from '../LineageParts';
import type { LineageEventView, LineageLaneView } from '../lineageModel';
import { CONSOLE_ONLY_TOOLTIP } from '../../ui/ConsoleOnly';

const EVENT: LineageEventView = {
  key: 'event:0',
  type: 'File Created',
  actor: 'pat',
  actorEmail: 'pat@example.com',
  internal: true,
  at: null,
  fileName: 'quarterly-plan.pdf',
  channel: 'Example Laptop',
};

const LANE: LineageLaneView = {
  key: 'lane-1', icon: null, fileName: 'quarterly-plan.pdf', owner: 'Pat Example', own: false, events: [EVENT],
};

describe('EventDetails', () => {
  it('keeps "View full details →" greyed without onViewDetails', async () => {
    const user = userEvent.setup();
    render(<EventDetails event={EVENT} />);
    expect(screen.getByText('File Created')).toBeInTheDocument();
    expect(screen.getByText('Internal')).toBeInTheDocument();
    expect(screen.getByText('pat@example.com')).toBeInTheDocument();
    expect(screen.getByText('Example Laptop')).toBeInTheDocument();
    const link = screen.getByRole('button', { name: 'View full details →' });
    expect(link).toHaveAttribute('aria-disabled', 'true');
    await user.hover(link);
    expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
  });

  it('renders a working link that calls onViewDetails', async () => {
    const user = userEvent.setup();
    const onViewDetails = vi.fn();
    render(<EventDetails event={EVENT} onViewDetails={onViewDetails} />);
    const link = screen.getByRole('button', { name: 'View full details →' });
    expect(link).not.toHaveAttribute('aria-disabled');
    expect(link).toHaveClass('inline-flex', 'items-center', 'gap-1', 'text-xs', 'font-semibold', 'text-sky-700', 'hover:underline');
    expect(screen.queryByRole('tooltip')).toBeNull();
    await user.click(link);
    expect(onViewDetails).toHaveBeenCalledTimes(1);
  });

  it('reads "-" for a missing e-mail, file and channel', () => {
    render(<EventDetails event={{ ...EVENT, internal: false, actorEmail: null, fileName: null, channel: null }} />);
    expect(screen.getAllByText('-')).toHaveLength(3);
    expect(screen.queryByText('Internal')).toBeNull();
  });
});

describe('EventCard', () => {
  it('is a popup button on its lane with the type and "actor · time"', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<EventCard event={EVENT} laneKey="lane-1" open style={{ left: 10, top: 20 }} onClick={onClick} />);
    const card = screen.getByRole('button', { name: /File Created/ });
    expect(card).toHaveAttribute('data-event-lane', 'lane-1');
    expect(card).toHaveAttribute('aria-haspopup', 'dialog');
    expect(card).toHaveAttribute('aria-expanded', 'true');
    expect(card).toHaveClass('border-sky-400');
    expect(card).toHaveTextContent('pat · -');
    await user.click(card);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});

describe('LaneLabel', () => {
  it('shows the file name with its copy button and the owner', () => {
    render(<LaneLabel lane={LANE} />);
    expect(screen.getByText('quarterly-plan.pdf')).toHaveAttribute('title', 'quarterly-plan.pdf');
    expect(screen.getByRole('button', { name: 'Copy file name' })).toBeInTheDocument();
    expect(screen.getByText('Pat Example')).toBeInTheDocument();
  });

  it('has no copy button for "-"', () => {
    render(<LaneLabel lane={{ ...LANE, fileName: '-' }} />);
    expect(screen.queryByRole('button', { name: 'Copy file name' })).toBeNull();
  });
});
