import { describe, expect, it, vi } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { LineageCategory } from '../../../../types/dspmLineage';
import { CONSOLE_ONLY_TOOLTIP } from '../../datastores/ui/ConsoleOnly';
import LineageCategoryPicker from '../LineageCategoryPicker';

const TITLES = [
  'Sensitive Findings', 'Excessive Exposure', 'High Volume Access', 'Stale Data', 'Risky User', 'Public Exposure',
];
const IDS = [
  'SENSITIVE_FINDINGS', 'EXCESSIVE_EXPOSURE', 'HIGH_VOLUME_ACCESS', 'STALE_DATA', 'RISKY_USER', 'PUBLIC_EXPOSURE',
];

const OFF: LineageCategory[] = IDS.map((id, i) => ({ id, name: TITLES[i], exposures: null }));
const ON: LineageCategory[] = IDS.map((id, i) => ({ id, name: TITLES[i], exposures: [1, 0, 1_234, 0, 0, 0][i] }));

const cards = () => within(screen.getByRole('list')).getAllByRole('button');

describe('LineageCategoryPicker, switched off', () => {
  it('shows the title, subtitle and six greyed cards in order with no count line (AC2)', () => {
    render(<LineageCategoryPicker categories={OFF} available={false} onChoose={vi.fn()} />);
    expect(screen.getByRole('heading', { name: 'Data lineage' })).toBeInTheDocument();
    expect(screen.getByText('Start by choosing a exposure category')).toBeInTheDocument();
    expect(cards().map(card => card.textContent)).toEqual(TITLES);
    for (const card of cards()) expect(card).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByText(/exposures?$/)).toBeNull();
  });

  it('shows "Disabled by admin" on hover and focus, and does nothing on click or Enter (AC3)', async () => {
    const user = userEvent.setup();
    const onChoose = vi.fn();
    render(<LineageCategoryPicker categories={OFF} available={false} onChoose={onChoose} />);
    const first = cards()[0];

    await user.hover(first);
    expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
    await user.unhover(first);
    expect(screen.queryByRole('tooltip')).toBeNull();

    act(() => first.focus());
    expect(first).toHaveFocus();
    expect(screen.getByRole('tooltip')).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);

    await user.click(first);
    await user.keyboard('{Enter}');
    expect(onChoose).not.toHaveBeenCalled();
  });
});

describe('LineageCategoryPicker, switched on', () => {
  it('shows the counts and opens a category on click, also at 0 exposures (AC7)', async () => {
    const user = userEvent.setup();
    const onChoose = vi.fn();
    render(<LineageCategoryPicker categories={ON} available onChoose={onChoose} />);
    expect(cards().map(card => card.textContent)).toEqual([
      'Sensitive Findings1 exposure', 'Excessive Exposure0 exposures', 'High Volume Access1.2k exposures',
      'Stale Data0 exposures', 'Risky User0 exposures', 'Public Exposure0 exposures',
    ]);
    for (const card of cards()) expect(card).not.toHaveAttribute('aria-disabled');
    await user.click(screen.getByRole('button', { name: /Excessive Exposure/ }));
    expect(onChoose).toHaveBeenCalledWith('EXCESSIVE_EXPOSURE');
  });

  it('drops an unknown category id', () => {
    render(<LineageCategoryPicker categories={[...ON.slice(0, 2), { id: 'DATA_RESIDENCY', name: 'Residency', exposures: 3 }]} available onChoose={vi.fn()} />);
    expect(cards()).toHaveLength(2);
    expect(screen.queryByText('Residency')).toBeNull();
  });

  it('explains an empty list', () => {
    render(<LineageCategoryPicker categories={[]} available onChoose={vi.fn()} />);
    expect(screen.getByText('No exposure categories are available for OneDrive, SharePoint or Endpoint yet.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });
});
