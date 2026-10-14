import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReportCapabilities } from '../../../api/curInsightsApi';
import CostBasisToggle from '../CostBasisToggle';

/**
 * The basis control. Its duties: never offer a basis the report cannot compute, never block one
 * silently, and never change the figures on its own.
 */

const AVAILABLE: ReportCapabilities = {
  amortizedAvailable: true,
  amortizedMissingColumns: [],
  unitAvailable: true,
  amortizedDetail: '',
  unitDetail: '',
};

const UNAVAILABLE: ReportCapabilities = {
  amortizedAvailable: false,
  amortizedMissingColumns: ['reservation_effective_cost'],
  unitAvailable: true,
  amortizedDetail:
    'This export does not carry reservation_effective_cost, so the amortized basis cannot be'
    + ' computed.',
  unitDetail: '',
};

const amortized = () => screen.getByRole('button', { name: 'Amortized' });

/** The element the amortized button's aria-describedby points at. */
const describedBy = (): HTMLElement | null => {
  const id = amortized().getAttribute('aria-describedby');
  return id ? document.getElementById(id) : null;
};

describe('CostBasisToggle', () => {
  it('offers both bases as pressed-state buttons inside a labelled group', () => {
    render(<CostBasisToggle value="unblended" onChange={vi.fn()} capabilities={AVAILABLE} />);

    // A filter over one view's figures, not navigation between views — so aria-pressed buttons
    // rather than the tablist the card's sub-tabs use.
    const group = screen.getByRole('group', { name: 'Cost basis' });
    expect(group).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unblended' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(amortized()).toHaveAttribute('aria-pressed', 'false');
  });

  it('never changes basis on its own; only a click does', async () => {
    const onChange = vi.fn();
    render(<CostBasisToggle value="unblended" onChange={onChange} capabilities={AVAILABLE} />);

    expect(onChange).not.toHaveBeenCalled();
    await userEvent.click(amortized());
    expect(onChange).toHaveBeenCalledWith('amortized');
  });

  it('blocks amortized with the missing column named, and stays focusable while it does', async () => {
    const onChange = vi.fn();
    render(<CostBasisToggle value="unblended" onChange={onChange} capabilities={UNAVAILABLE} />);

    // aria-disabled, not the HTML disabled attribute: `disabled` removes the button from the tab
    // order, so the sentence explaining why it is blocked would never be announced on focus.
    expect(amortized()).toHaveAttribute('aria-disabled', 'true');
    expect(amortized()).not.toBeDisabled();
    amortized().focus();
    expect(amortized()).toHaveFocus();

    // Not merely a non-empty string: the actionable half is the column name.
    expect(describedBy()).toHaveTextContent('reservation_effective_cost');

    await userEvent.click(amortized());
    expect(onChange).not.toHaveBeenCalled();
  });

  /*
   * Two of the three blocked states. The third — the capabilities *query* erroring — is not a prop
   * of this component: the card derives it, mapping `isError` to the `null` row below rather than
   * to the `undefined` one. It is pinned where that derivation lives, in
   * `ChargesByServiceSection.basis.test.tsx`, with a rejecting capabilitiesFetcher.
   */
  it.each([
    ['the probe is still in flight', undefined, /Checking what this report can be asked/],
    ['the probe itself failed', null, /could not check what this report can be asked/],
  ])('blocks amortized and says why when %s', async (_case, capabilities, sentence) => {
    /*
     * Both states are reachable in production and neither may be silent. `undefined` and `null` are
     * both `!== false`, so a `capabilities?.amortizedAvailable === false` test would render this
     * button *live* on every page load and after every probe failure, with nothing in
     * aria-describedby — and a click inside that window issues an amortized query that 422s and
     * takes the card to its basis-failed state.
     */
    const onChange = vi.fn();
    render(
      <CostBasisToggle
        value="unblended"
        onChange={onChange}
        capabilities={capabilities as ReportCapabilities | null | undefined}
      />,
    );

    expect(amortized()).toHaveAttribute('aria-disabled', 'true');
    expect(describedBy()?.textContent ?? '').toMatch(sentence);

    await userEvent.click(amortized());
    expect(onChange).not.toHaveBeenCalled();
  });

  it('explains both bases from a button, closes on Escape and hands focus back', async () => {
    render(<CostBasisToggle value="unblended" onChange={vi.fn()} capabilities={AVAILABLE} />);

    const trigger = screen.getByRole('button', {
      name: 'What do unblended and amortized mean?',
    });
    await userEvent.click(trigger);

    // "Amortized" cannot be assumed of a reader looking at a cost page for the first time.
    expect(screen.getByText(/spreads that payment across the term/)).toBeVisible();

    await userEvent.keyboard('{Escape}');
    expect(screen.getByText(/spreads that payment across the term/)).not.toBeVisible();
    expect(trigger).toHaveFocus();
  });
});
