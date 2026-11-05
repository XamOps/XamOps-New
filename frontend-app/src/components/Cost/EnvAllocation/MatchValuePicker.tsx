import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchMatchValues, type MatchValue, type PickerField } from '../../../api/envAllocationApi';
import { monthLabel } from './envAllocationText';

/**
 * A text input that offers the values a rule field takes on the client's bill.
 *
 * <p>Picking fills the input; typing still works, because "contains" and "starts with" rules use
 * part of a value, and a rule can name a value that only appears in another month. Values are read
 * only once the picker is opened, so a closed rule costs no Athena scan.
 *
 * <p>Its own query key, outside the drawer's `env-allocation` prefix, so publishing a version does
 * not refetch every list.
 */
export const MATCH_VALUES_KEY = 'env-allocation-values';

/** Below this, a search on a truncated list stays local; one or two letters match too much. */
const MIN_SERVER_SEARCH = 2;
const SEARCH_DEBOUNCE_MS = 300;

interface MatchValuePickerProps {
  accountId: string;
  period: string;
  field: PickerField;
  value: string;
  onChange: (value: string) => void;
  money: (value: number) => string;
  placeholder: string;
  ariaLabel: string;
  /** Classes for the input itself. */
  className: string;
  /** Sizing for the wrapper in its flex row. */
  wrapperClassName?: string;
  maxLength: number;
}

const useDebounced = (value: string, ms: number): string => {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
};

const MatchValuePicker: React.FC<MatchValuePickerProps> = ({
  accountId,
  period,
  field,
  value,
  onChange,
  money,
  placeholder,
  ariaLabel,
  className,
  wrapperClassName = 'flex-1 min-w-[180px]',
  maxLength,
}) => {
  const [open, setOpen] = useState(false);
  // What the user typed since opening. Empty shows the whole list, so a picked value can be changed.
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const listId = useId();
  const listRef = useRef<HTMLUListElement>(null);

  const base = useQuery({
    queryKey: [MATCH_VALUES_KEY, accountId, period, field, ''],
    queryFn: () => fetchMatchValues(accountId, period, field),
    enabled: open,
    staleTime: 10 * 60_000,
    retry: false,
  });

  // A truncated list does not hold every value, so a search goes to the server.
  const debounced = useDebounced(query.trim(), SEARCH_DEBOUNCE_MS);
  const serverSearch = Boolean(base.data?.truncated) && debounced.length >= MIN_SERVER_SEARCH;
  const searched = useQuery({
    queryKey: [MATCH_VALUES_KEY, accountId, period, field, debounced.toLowerCase()],
    queryFn: () => fetchMatchValues(accountId, period, field, { search: debounced }),
    enabled: open && serverSearch,
    staleTime: 10 * 60_000,
    retry: false,
  });

  const source = serverSearch ? searched : base;
  const options: MatchValue[] = useMemo(() => {
    const all = source.data?.values ?? [];
    const needle = query.trim().toLowerCase();
    return needle ? all.filter((o) => o.value.toLowerCase().includes(needle)) : all;
  }, [source.data, query]);

  useEffect(() => setActive(0), [query, field]);

  useEffect(() => {
    if (!open) return;
    const el = listRef.current?.querySelector<HTMLElement>(`[data-index="${active}"]`);
    el?.scrollIntoView({ block: 'nearest' });
  }, [active, open]);

  const pick = (v: string) => {
    onChange(v);
    setQuery('');
    setOpen(false);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) setOpen(true);
      else setActive((a) => Math.min(a + 1, Math.max(options.length - 1, 0)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter') {
      if (open && options[active]) {
        e.preventDefault();
        pick(options[active].value);
      }
    } else if (e.key === 'Escape') {
      if (open) {
        // The drawer closes on Escape; the first press only closes this list.
        e.stopPropagation();
        setOpen(false);
      }
    } else if (e.key === 'Tab') {
      setOpen(false);
    }
  };

  const status = (): string | null => {
    if (source.isPending) return `Reading ${monthLabel(period)}'s bill…`;
    if (source.isError) {
      return source.error instanceof Error ? source.error.message : 'Could not read the values.';
    }
    if (source.data?.message) return source.data.message;
    if (options.length === 0) {
      return query.trim()
        ? `Nothing in ${monthLabel(period)} contains "${query.trim()}". You can still use what you typed.`
        : `No values in ${monthLabel(period)}. You can still type one.`;
    }
    return null;
  };
  const note = status();
  const truncatedNote =
    source.data?.truncated && !note
      ? serverSearch
        ? 'Showing the largest matches. Keep typing to narrow them.'
        : 'Showing the largest values by cost. Type to search the whole month.'
      : null;

  return (
    <div className={`relative ${wrapperClassName}`}>
      <input
        className={`${className} w-full`}
        placeholder={placeholder}
        aria-label={ariaLabel}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && options[active] ? `${listId}-${active}` : undefined}
        autoComplete="off"
        value={value}
        maxLength={maxLength}
        onFocus={() => {
          setQuery('');
          setOpen(true);
        }}
        onClick={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onChange={(e) => {
          onChange(e.target.value);
          setQuery(e.target.value);
          setOpen(true);
        }}
        onKeyDown={onKeyDown}
      />
      {open && (
        <div
          className="absolute z-20 left-0 mt-1 w-full min-w-[320px] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-lg overflow-hidden"
          // Keep focus in the input so a click picks instead of blurring first.
          onMouseDown={(e) => e.preventDefault()}
        >
          {options.length > 0 && (
            <ul id={listId} ref={listRef} role="listbox" aria-label={ariaLabel} className="max-h-64 overflow-auto py-1">
              {options.map((o, i) => (
                <li
                  key={o.value}
                  id={`${listId}-${i}`}
                  data-index={i}
                  role="option"
                  aria-selected={i === active}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => pick(o.value)}
                  className={`flex items-baseline justify-between gap-3 px-3 py-1.5 cursor-pointer ${
                    i === active ? 'bg-[rgba(0,0,0,0.05)]' : ''
                  }`}
                >
                  <span className="text-[12.5px] text-black break-all">{o.value}</span>
                  {o.resources != null ? (
                    <span className="text-[11px] text-[rgba(0,0,0,0.45)] flex-shrink-0 tabular-nums">
                      {o.resources} {o.resources === 1 ? 'resource' : 'resources'}
                    </span>
                  ) : o.cost != null ? (
                    <span className="text-[11px] text-[rgba(0,0,0,0.45)] flex-shrink-0 tabular-nums">
                      {money(o.cost)}
                    </span>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          {(note || truncatedNote) && (
            <p
              className={`px-3 py-2 text-[11px] ${
                options.length > 0 ? 'border-t border-[rgba(0,0,0,0.06)]' : ''
              } ${source.isError ? 'text-[#B91C1C]' : 'text-[rgba(0,0,0,0.5)]'}`}
            >
              {note ?? truncatedNote}
            </p>
          )}
        </div>
      )}
    </div>
  );
};

export default MatchValuePicker;
