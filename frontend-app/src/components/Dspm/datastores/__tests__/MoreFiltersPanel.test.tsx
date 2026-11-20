import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MoreFiltersPanel from '../MoreFiltersPanel';
import type { DatastoreFilterOptions } from '../../../../types/dspmDatastores';

const OPTIONS: DatastoreFilterOptions = {
  dimensions: [{ key: 'sensitives', options: [{ value: 'PII', label: 'Personal', count: null }], hasMore: false }],
};

function renderPanel() {
  render(
    <MoreFiltersPanel
      dimensions={['sensitives']}
      labelFor={key => (key === 'sensitives' ? 'Sensitives' : key)}
      options={OPTIONS}
      optionsLoading={false}
      optionsError={false}
      filters={{}}
      labelMemory={new Map()}
      onChange={vi.fn()}
      local
    />,
  );
}

/** A 1440 px wide viewport, the button's wrapper at `left` and a 544 px wide panel (jsdom has no layout). */
function layout(left: number) {
  vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1440);
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(544);
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    left, right: left + 100, x: left, y: 0, top: 0, bottom: 30, width: 100, height: 30, toJSON: () => ({}),
  } as DOMRect);
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MoreFiltersPanel placement', () => {
  it('opens left-aligned under its button when it fits', async () => {
    layout(300);
    renderPanel();
    await userEvent.setup().click(screen.getByRole('button', { name: 'More filters' }));
    expect(screen.getByRole('dialog', { name: 'More filters' }).style.left).toBe('');
  });

  it('leaves a panel that fits where it is, even close to the edge', async () => {
    layout(890);
    renderPanel();
    await userEvent.setup().click(screen.getByRole('button', { name: 'More filters' }));
    // 890 + 544 = 1434 still fits in 1440.
    expect(screen.getByRole('dialog', { name: 'More filters' }).style.left).toBe('');
  });

  it('moves left until 16 px are free at the viewport edge when it runs past it', async () => {
    layout(905);
    renderPanel();
    await userEvent.setup().click(screen.getByRole('button', { name: 'More filters' }));
    // 905 + 544 = 1449 would pass 1440 - 16 = 1424 by 25 px.
    expect(screen.getByRole('dialog', { name: 'More filters' }).style.left).toBe('-25px');
  });

  it('never moves past the viewport\'s left edge', async () => {
    layout(20);
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(400);
    renderPanel();
    await userEvent.setup().click(screen.getByRole('button', { name: 'More filters' }));
    expect(screen.getByRole('dialog', { name: 'More filters' }).style.left).toBe('-4px');
  });
});

describe('MoreFiltersPanel defaults', () => {
  it('has no heading, no Include / Exclude select in local mode and checkbox options without the new props', async () => {
    renderPanel();
    await userEvent.setup().click(screen.getByRole('button', { name: 'More filters' }));
    const dialog = screen.getByRole('dialog', { name: 'More filters' });
    expect(dialog.querySelector('h3')).toBeNull();
    expect(dialog.className).not.toContain('flex-col');
    expect(screen.queryByRole('combobox', { name: 'Include or exclude' })).toBeNull();
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(screen.getByRole('checkbox', { name: 'Personal' })).toBeInTheDocument();
  });
});
