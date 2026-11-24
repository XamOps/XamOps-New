import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DatastoreFilterOption } from '../../../../types/dspmDatastores';
import type { DatastoreFilterState } from '../datastoreFilters';
import SingleChoiceList from '../SingleChoiceList';
import FilterDropdown from '../FilterDropdown';

const OPTIONS: DatastoreFilterOption[] = [
  { value: 'internal', label: 'Shared Internally', count: null },
  { value: 'external', label: 'Shared Externally', count: null },
  { value: 'public', label: 'Shared Publicly', count: null },
];

function Harness({ initial = {}, onChange, ...rest }: {
  initial?: DatastoreFilterState;
  onChange?: (next: DatastoreFilterState) => void;
  anyLabel?: string;
  hasMore?: boolean;
  options?: DatastoreFilterOption[] | null;
  loading?: boolean;
  error?: boolean;
  canSelectValue?: (value: string) => boolean;
  labelMemory?: ReadonlyMap<string, string>;
}) {
  const [filters, setFilters] = useState<DatastoreFilterState>(initial);
  return (
    <SingleChoiceList
      dimension="sharing"
      options={rest.options === undefined ? OPTIONS : rest.options}
      filters={filters}
      loading={rest.loading ?? false}
      error={rest.error ?? false}
      onChange={next => { setFilters(next); onChange?.(next); }}
      anyLabel={rest.anyLabel}
      hasMore={rest.hasMore}
      canSelectValue={rest.canSelectValue}
      labelMemory={rest.labelMemory}
    />
  );
}

const option = (name: string) => screen.getByRole('option', { name });

describe('SingleChoiceList', () => {
  it('lists the Any option first, chosen while nothing is set', () => {
    render(<Harness anyLabel="Any Access" />);
    const names = within(screen.getByRole('listbox')).getAllByRole('option').map(o => o.textContent);
    expect(names).toEqual(['Any Access', 'Shared Internally', 'Shared Externally', 'Shared Publicly']);
    expect(option('Any Access')).toHaveAttribute('aria-selected', 'true');
    expect(option('Shared Internally')).toHaveAttribute('aria-selected', 'false');
  });

  it('sets one Include value, replaces it with another, and clears on a second choice or Any', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness anyLabel="Any Access" onChange={onChange} />);
    await user.click(option('Shared Externally'));
    expect(onChange).toHaveBeenLastCalledWith({ sharing: { mode: 'include', values: ['external'] } });
    expect(option('Shared Externally')).toHaveAttribute('aria-selected', 'true');
    expect(option('Any Access')).toHaveAttribute('aria-selected', 'false');
    await user.click(option('Shared Publicly'));
    expect(onChange).toHaveBeenLastCalledWith({ sharing: { mode: 'include', values: ['public'] } });
    await user.click(option('Shared Publicly'));
    expect(onChange).toHaveBeenLastCalledWith({});
    await user.click(option('Shared Internally'));
    await user.click(option('Any Access'));
    expect(onChange).toHaveBeenLastCalledWith({});
    expect(option('Any Access')).toHaveAttribute('aria-selected', 'true');
  });

  it('filters the options by the search and says when nothing matches', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.type(screen.getByPlaceholderText('Search'), 'publ');
    expect(screen.getAllByRole('option').map(o => o.textContent)).toEqual(['Shared Publicly']);
    await user.clear(screen.getByPlaceholderText('Search'));
    await user.type(screen.getByPlaceholderText('Search'), 'zzz');
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('keeps a chosen value the response left out, with its remembered label', () => {
    render(
      <Harness
        options={[OPTIONS[0]]}
        initial={{ sharing: { mode: 'include', values: ['public'] } }}
        labelMemory={new Map([['public', 'Shared Publicly']])}
      />,
    );
    expect(option('Shared Publicly')).toHaveAttribute('aria-selected', 'true');
  });

  it('disables a value canSelectValue refuses', () => {
    render(<Harness canSelectValue={value => value !== 'public'} />);
    expect(option('Shared Publicly')).toBeDisabled();
    expect(option('Shared Internally')).toBeEnabled();
  });

  it('shows the loading, error and "Some values are not shown" lines as FilterOptionList does', () => {
    const { unmount } = render(<Harness options={null} loading />);
    expect(screen.getByText('Loading options…')).toBeInTheDocument();
    unmount();
    const second = render(<Harness error />);
    expect(screen.getByText('Could not load options')).toBeInTheDocument();
    second.unmount();
    render(<Harness hasMore />);
    expect(screen.getByText('Some values are not shown')).toBeInTheDocument();
  });
});

describe('FilterDropdown', () => {
  const props = {
    label: 'Sharing Mode', dimension: 'sharing', options: OPTIONS, hasMore: false, labelMemory: new Map<string, string>(),
    loading: false, error: false,
  };

  it('renders checkboxes by default and counts the values in its badge', async () => {
    const user = userEvent.setup();
    render(<FilterDropdown {...props} filters={{ sharing: { mode: 'include', values: ['internal', 'public'] } }} onChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Sharing Mode/ })).toHaveTextContent('2');
    await user.click(screen.getByRole('button', { name: /Sharing Mode/ }));
    expect(screen.getByRole('checkbox', { name: 'Shared Internally' })).toBeChecked();
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(screen.getByRole('combobox', { name: 'Include or exclude' })).toBeInTheDocument();
  });

  it('renders the single-choice list with single, and still counts the chosen value', async () => {
    const user = userEvent.setup();
    render(
      <FilterDropdown
        {...props}
        single
        anyLabel="Any Access"
        filters={{ sharing: { mode: 'include', values: ['external'] } }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: /Sharing Mode/ })).toHaveTextContent('1');
    await user.click(screen.getByRole('button', { name: /Sharing Mode/ }));
    expect(screen.getByRole('option', { name: 'Shared Externally' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});
