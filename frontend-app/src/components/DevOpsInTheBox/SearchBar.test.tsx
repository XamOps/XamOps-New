/**
 * Unit tests for the SearchBar component.
 *
 * Uses Vitest + React Testing Library with fake timers to exercise the 300ms
 * debounce. Covers debounced onChange, clear button, autocomplete dropdown with
 * matched-term highlighting, suggestion selection, Enter-to-submit,
 * Escape-to-clear, and the loading spinner.
 *
 * Requirements: REQ-2.3.1, REQ-2.6.1
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import SearchBar, { highlightMatch } from './SearchBar';

const DEBOUNCE_MS = 300;

describe('highlightMatch', () => {
  it('returns a single non-matching segment when query is empty', () => {
    expect(highlightMatch('Provision VPC', '')).toEqual([{ text: 'Provision VPC', match: false }]);
  });

  it('marks the matched portion case-insensitively', () => {
    const segments = highlightMatch('Provision AWS VPC', 'aws');
    const matched = segments.filter(s => s.match);
    expect(matched).toHaveLength(1);
    expect(matched[0].text).toBe('AWS');
  });

  it('escapes regex special characters in the query', () => {
    const segments = highlightMatch('a+b value', 'a+b');
    const matched = segments.filter(s => s.match);
    expect(matched).toHaveLength(1);
    expect(matched[0].text).toBe('a+b');
  });
});

describe('SearchBar', () => {
  beforeEach(() => {
    // Advance real time alongside fake timers so RTL's `waitFor` (which polls
    // on timers) still resolves while we drive the debounce manually.
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  function setup(overrides: Partial<React.ComponentProps<typeof SearchBar>> = {}) {
    const onChange = vi.fn();
    const onSubmit = vi.fn();
    const fetchSuggestions = vi.fn().mockResolvedValue(['Provision AWS VPC', 'Provision AWS RDS']);
    const utils = render(
      <SearchBar
        value=""
        onChange={onChange}
        onSubmit={onSubmit}
        fetchSuggestions={fetchSuggestions}
        {...overrides}
      />,
    );
    return { onChange, onSubmit, fetchSuggestions, ...utils };
  }

  it('debounces onChange by 300ms', () => {
    const { onChange } = setup();
    const input = screen.getByRole('searchbox');

    fireEvent.change(input, { target: { value: 'vpc' } });
    expect(onChange).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(DEBOUNCE_MS - 1);
    });
    expect(onChange).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onChange).toHaveBeenCalledWith('vpc');
  });

  it('fetches and shows highlighted suggestions after debounce', async () => {
    const { fetchSuggestions } = setup();
    const input = screen.getByRole('searchbox');

    fireEvent.change(input, { target: { value: 'aws' } });
    await act(async () => {
      vi.advanceTimersByTime(DEBOUNCE_MS);
    });

    expect(fetchSuggestions).toHaveBeenCalledWith('aws');
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeTruthy();
    });
    expect(screen.getAllByRole('option')).toHaveLength(2);
    // Highlighted matched term should be rendered in a <mark>.
    const marks = document.querySelectorAll('mark');
    expect(marks.length).toBeGreaterThan(0);
  });

  it('shows a loading spinner while suggestions are in flight', async () => {
    let resolveFn: (v: string[]) => void = () => {};
    const fetchSuggestions = vi.fn().mockImplementation(
      () => new Promise<string[]>(resolve => {
        resolveFn = resolve;
      }),
    );
    setup({ fetchSuggestions });
    const input = screen.getByRole('searchbox');

    fireEvent.change(input, { target: { value: 'aws' } });
    await act(async () => {
      vi.advanceTimersByTime(DEBOUNCE_MS);
    });

    expect(screen.getByRole('status', { name: /searching/i })).toBeTruthy();

    await act(async () => {
      resolveFn(['Provision AWS VPC']);
    });
    await waitFor(() => {
      expect(screen.queryByRole('status', { name: /searching/i })).toBeNull();
    });
  });

  it('submits the current term on Enter', () => {
    const { onSubmit } = setup();
    const input = screen.getByRole('searchbox');

    fireEvent.change(input, { target: { value: 'vpc' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onSubmit).toHaveBeenCalledWith('vpc');
  });

  it('selects the active suggestion on Enter when dropdown is open', async () => {
    const { onSubmit } = setup();
    const input = screen.getByRole('searchbox');

    fireEvent.change(input, { target: { value: 'aws' } });
    await act(async () => {
      vi.advanceTimersByTime(DEBOUNCE_MS);
    });
    await waitFor(() => expect(screen.getByRole('listbox')).toBeTruthy());

    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onSubmit).toHaveBeenCalledWith('Provision AWS VPC');
  });

  it('clears the input via the clear button', () => {
    const { onChange } = setup();
    const input = screen.getByRole('searchbox') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'vpc' } });
    expect(input.value).toBe('vpc');

    fireEvent.click(screen.getByRole('button', { name: /clear search/i }));
    expect(input.value).toBe('');
    expect(onChange).toHaveBeenCalledWith('');
  });

  it('closes the dropdown on first Escape and clears on second', async () => {
    const { onChange } = setup();
    const input = screen.getByRole('searchbox') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'aws' } });
    await act(async () => {
      vi.advanceTimersByTime(DEBOUNCE_MS);
    });
    await waitFor(() => expect(screen.getByRole('listbox')).toBeTruthy());

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByRole('listbox')).toBeNull();

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input.value).toBe('');
    expect(onChange).toHaveBeenCalledWith('');
  });

  it('selecting a suggestion submits it', async () => {
    const { onSubmit } = setup();
    const input = screen.getByRole('searchbox');

    fireEvent.change(input, { target: { value: 'aws' } });
    await act(async () => {
      vi.advanceTimersByTime(DEBOUNCE_MS);
    });
    await waitFor(() => expect(screen.getByRole('listbox')).toBeTruthy());

    fireEvent.mouseDown(screen.getAllByRole('option')[1]);
    expect(onSubmit).toHaveBeenCalledWith('Provision AWS RDS');
  });
});
