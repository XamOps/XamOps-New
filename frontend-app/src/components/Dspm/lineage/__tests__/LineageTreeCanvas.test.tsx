import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { LineageLaneView } from '../../datastores/saas/lineageModel';
import LineageTreeCanvas, { type TreeBandView, type TreeRowView } from '../LineageTreeCanvas';

const CATEGORY_ROW: TreeRowView = {
  level: 'category',
  nodes: [{ key: 'SENSITIVE_FINDINGS', id: 'SENSITIVE_FINDINGS', view: { kind: 'category', name: 'Sensitive Findings', count: 9 } }],
  chosen: 'SENSITIVE_FINDINGS',
  prev: false,
  next: false,
  message: null,
};

const policyRow = (over: Partial<TreeRowView> = {}): TreeRowView => ({
  level: 'policy',
  nodes: [
    { key: 'p1', id: 'p1', view: { kind: 'policy', policy: { id: 'p1', name: 'Endpoints holding personal records', severity: 'MEDIUM', severityLabel: 'Medium', service: 'ENDPOINT', datastores: 3 } } },
    { key: 'p2', id: 'p2', view: { kind: 'policy', policy: { id: 'p2', name: 'Shared drives with card numbers', severity: 'HIGH', severityLabel: 'High', service: 'ONE_DRIVE', datastores: 1 } } },
    { key: '#2', id: null, view: { kind: 'policy', policy: { id: null, name: 'Policy without an id', severity: null, severityLabel: null, service: null, datastores: null } } },
  ],
  chosen: 'p1',
  prev: true,
  next: true,
  message: null,
  ...over,
});

const LANES: LineageLaneView[] = [{
  key: 'copy-0',
  icon: 'ENDPOINT',
  fileName: 'q3-forecast.xlsx',
  owner: 'Studio MacBook Pro',
  own: true,
  events: [{
    key: 'event:0', type: 'File Modified', actor: 'alex', actorEmail: 'alex@example.com', internal: false,
    at: 1_775_000_000, fileName: 'q3-forecast.xlsx', channel: 'Endpoint',
  }],
}, {
  key: 'copy-1', icon: 'ENDPOINT', fileName: 'q3-forecast (1).xlsx', owner: 'Ops MacBook Air', own: false, events: [],
}];

function renderCanvas(props: Partial<React.ComponentProps<typeof LineageTreeCanvas>> = {}) {
  const handlers = { onNode: vi.fn(), onPage: vi.fn(), onViewDetails: vi.fn() };
  render(
    <LineageTreeCanvas
      rows={[CATEGORY_ROW, policyRow()]}
      band={null}
      fullscreen={false}
      fitKey="policy"
      {...handlers}
      {...props}
    />,
  );
  return handlers;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LineageTreeCanvas', () => {
  it('draws nodes as buttons with aria-pressed, a badge on each chosen one, and their lines', () => {
    renderCanvas();
    const category = screen.getByRole('button', { name: 'Sensitive Findings' });
    expect(category).toHaveAttribute('aria-pressed', 'true');
    expect(within(category).getByText('9 exposures')).toBeInTheDocument();

    const chosen = screen.getByRole('button', { name: 'Endpoints holding personal records' });
    expect(chosen).toHaveAttribute('aria-pressed', 'true');
    expect(within(chosen).getByText('Medium')).toBeInTheDocument();
    expect(within(chosen).getByText('3 datastores')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Shared drives with card numbers' })).toHaveAttribute('aria-pressed', 'false');
    expect(document.querySelectorAll('[data-chosen-badge]')).toHaveLength(2);
    expect(within(chosen).getByText((_, el) => el?.hasAttribute('data-chosen-badge') ?? false)).toBeInTheDocument();
  });

  it('calls onNode with the level and id, and not for a node without an id', async () => {
    const user = userEvent.setup();
    const { onNode } = renderCanvas();
    await user.click(screen.getByRole('button', { name: 'Shared drives with card numbers' }));
    expect(onNode).toHaveBeenCalledWith('policy', 'p2');
    const disabled = screen.getByRole('button', { name: 'Policy without an id' });
    expect(disabled).toHaveAttribute('aria-disabled', 'true');
    await user.click(disabled);
    expect(onNode).toHaveBeenCalledTimes(1);
  });

  it('labels the pagers by row and pages with them', async () => {
    const user = userEvent.setup();
    const { onPage } = renderCanvas();
    await user.click(screen.getByRole('button', { name: 'Show more policies' }));
    await user.click(screen.getByRole('button', { name: 'Show previous policies' }));
    expect(onPage.mock.calls).toEqual([['policy', 1], ['policy', -1]]);
  });

  it('draws a loading, an empty and an error box in place of nodes, with Retry', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderCanvas({
      rows: [
        CATEGORY_ROW,
        policyRow({ nodes: [], chosen: null, prev: false, next: false, message: { kind: 'empty', text: 'No policies match “zzz”.' } }),
        { ...policyRow(), level: 'datastore', nodes: [], chosen: null, prev: false, next: false, message: { kind: 'loading', text: 'Loading datastores…' } },
        {
          ...policyRow(), level: 'file', nodes: [], chosen: null, prev: false, next: false,
          message: { kind: 'error', error: { code: 'UPSTREAM_UNAVAILABLE', message: 'down' }, onRetry, subject: 'file' },
        },
      ],
    });
    expect(screen.getByText('No policies match “zzz”.')).toBeInTheDocument();
    expect(screen.getByText('Loading datastores…')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'Show more policies' })).toBeNull();
  });

  it('zooms between 10% and 100% (AC15)', async () => {
    const user = userEvent.setup();
    renderCanvas();
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zoom in' })).toBeDisabled();
    for (let i = 0; i < 12; i += 1) {
      const out = screen.getByRole('button', { name: 'Zoom out' });
      if ((out as HTMLButtonElement).disabled) break;
      await user.click(out);
    }
    expect(screen.getByText('10%')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Zoom out' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(screen.getByText('20%')).toBeInTheDocument();
  });

  it('opens at the fitted zoom of a wide row', () => {
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(500);
    renderCanvas();
    // 3 nodes, 2 gaps, 2 pagers and the padding: 3 × 216 + 2 × 20 + 2 × 44 + 64 = 840 px.
    expect(screen.getByText('59%')).toBeInTheDocument();
  });

  it('draws the band\'s lanes, "No recorded activity", and opens the popover whose link calls onViewDetails', async () => {
    const user = userEvent.setup();
    const band: TreeBandView = { kind: 'lanes', lanes: LANES };
    const { onViewDetails } = renderCanvas({ band });
    expect(document.querySelector('[data-lineage-band]')).not.toBeNull();
    expect(screen.getByText('Studio MacBook Pro')).toBeInTheDocument();
    expect(screen.getByText('No recorded activity')).toBeInTheDocument();
    expect(document.querySelector('[data-edge="band"]')).toHaveAttribute('data-active', 'true');

    const card = screen.getByRole('button', { name: /File Modified/ });
    await user.click(card);
    const popover = screen.getByRole('dialog', { name: 'Event details' });
    expect(within(popover).getByText('alex@example.com')).toBeInTheDocument();
    await user.click(within(popover).getByRole('button', { name: 'View full details →' }));
    expect(onViewDetails).toHaveBeenCalledWith(LANES[0].events[0], card);
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
  });

  it('closes the popover on a zoom change', async () => {
    const user = userEvent.setup();
    renderCanvas({ band: { kind: 'lanes', lanes: LANES } });
    await user.click(screen.getByRole('button', { name: /File Modified/ }));
    expect(screen.getByRole('dialog', { name: 'Event details' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Zoom out' }));
    expect(screen.queryByRole('dialog', { name: 'Event details' })).toBeNull();
  });

  it('draws a band message in place of the lanes', () => {
    renderCanvas({ band: { kind: 'message', message: { kind: 'empty', text: 'No lineage recorded for this file yet.' } } });
    expect(screen.getByText('No lineage recorded for this file yet.')).toBeInTheDocument();
    expect(document.querySelector('[data-lineage-band]')).toBeNull();
  });
});
