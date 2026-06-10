import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { scriptApiClient } from '../../api/scriptApiClient';

/**
 * SearchBar — debounced, autocomplete-enabled search input for the Script
 * Explorer.
 *
 * Features:
 *  - Controlled text input whose `onChange(term)` is debounced (300ms) so the
 *    parent only reacts to settled input.
 *  - Leading search icon and a trailing clear (✕) button.
 *  - Autocomplete dropdown that surfaces script-name suggestions as the user
 *    types, with the matched portion of each suggestion highlighted.
 *  - Suggestions are fetched via an injectable `fetchSuggestions` prop; by
 *    default it maps {@link scriptApiClient.searchScripts} result names to
 *    suggestions, keeping the component cohesive with the rest of the feature.
 *  - Enter submits the current term (`onSubmit`); Escape clears the input /
 *    closes the dropdown. Arrow keys move through suggestions.
 *  - A loading spinner is shown while suggestions are in flight.
 *
 * It is presentational + self-contained: it owns the debounce, the dropdown
 * open/close state and the active suggestion index, but defers data fetching and
 * the search action itself to its props.
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Search Performance)
 */

// ─── Constants ──────────────────────────────────────────────────────────────────

const DEBOUNCE_MS = 300;
const MAX_SUGGESTIONS = 8;
const MIN_QUERY_LENGTH = 1;

// ─── Helpers ────────────────────────────────────────────────────────────────────

/** Escape a user-supplied string for safe use inside a RegExp. */
function escapeRegExp(input: string): string {
  return input.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Split `text` into segments, marking the parts that match `query`
 * (case-insensitively) so callers can render the matched terms highlighted.
 * Exported for unit testing.
 */
export function highlightMatch(
  text: string,
  query: string,
): Array<{ text: string; match: boolean }> {
  const trimmed = query.trim();
  if (!trimmed) return [{ text, match: false }];

  const re = new RegExp(`(${escapeRegExp(trimmed)})`, 'ig');
  const parts = text.split(re).filter(part => part !== '');
  const lowerQuery = trimmed.toLowerCase();
  return parts.map(part => ({ text: part, match: part.toLowerCase() === lowerQuery }));
}

/**
 * Default suggestion source: query the script search endpoint and map matched
 * script names to a de-duplicated, length-capped suggestion list.
 */
async function defaultFetchSuggestions(term: string): Promise<string[]> {
  const result = await scriptApiClient.searchScripts(term, {}, 0);
  const names = (result?.items ?? []).map(script => script.name).filter(Boolean);
  // De-duplicate while preserving relevance order.
  const seen = new Set<string>();
  const unique: string[] = [];
  for (const name of names) {
    const key = name.toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      unique.push(name);
    }
    if (unique.length >= MAX_SUGGESTIONS) break;
  }
  return unique;
}

// ─── Props ──────────────────────────────────────────────────────────────────────

export interface SearchBarProps {
  /** Current search term (controlled). */
  value: string;
  /** Debounced (300ms) notification of the settled search term. */
  onChange: (term: string) => void;
  /** Invoked when the user submits a search (Enter / suggestion select). */
  onSubmit: (term: string) => void;
  /**
   * Optional suggestion source. Receives the current term and resolves to a list
   * of suggestion strings. Defaults to mapping search-result names from the
   * shared {@link scriptApiClient}.
   */
  fetchSuggestions?: (term: string) => Promise<string[]>;
  /** Placeholder text for the input. */
  placeholder?: string;
  /** Optional extra classes for the outer container. */
  className?: string;
}

// ─── Component ────────────────────────────────────────────────────────────────────

const SearchBar: React.FC<SearchBarProps> = ({
  value,
  onChange,
  onSubmit,
  fetchSuggestions,
  placeholder = 'Search scripts by name, description, or tags…',
  className = '',
}) => {
  // The text shown in the input. Kept in sync with the controlled `value` prop
  // but updated immediately on keystroke for a responsive feel; `onChange` to
  // the parent is debounced separately.
  const [inputValue, setInputValue] = useState(value);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [isOpen, setIsOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  const containerRef = useRef<HTMLDivElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Monotonic request id so out-of-order suggestion responses are discarded.
  const requestSeq = useRef(0);

  const resolveSuggestions = useMemo(
    () => fetchSuggestions ?? defaultFetchSuggestions,
    [fetchSuggestions],
  );

  // Keep the visible input in sync if the parent resets/changes `value`.
  useEffect(() => {
    setInputValue(value);
  }, [value]);

  // Clear any pending debounce on unmount.
  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  // Close the dropdown when clicking outside the component.
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const loadSuggestions = useCallback(
    async (term: string) => {
      const trimmed = term.trim();
      if (trimmed.length < MIN_QUERY_LENGTH) {
        setSuggestions([]);
        setIsOpen(false);
        setIsLoading(false);
        return;
      }

      const seq = ++requestSeq.current;
      setIsLoading(true);
      try {
        const results = await resolveSuggestions(trimmed);
        // Ignore stale responses.
        if (seq !== requestSeq.current) return;
        const capped = results.slice(0, MAX_SUGGESTIONS);
        setSuggestions(capped);
        setIsOpen(capped.length > 0);
        setActiveIndex(-1);
      } catch {
        if (seq !== requestSeq.current) return;
        setSuggestions([]);
        setIsOpen(false);
      } finally {
        if (seq === requestSeq.current) setIsLoading(false);
      }
    },
    [resolveSuggestions],
  );

  const handleInputChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const next = event.target.value;
      setInputValue(next);

      if (debounceRef.current) clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        onChange(next);
        void loadSuggestions(next);
      }, DEBOUNCE_MS);
    },
    [onChange, loadSuggestions],
  );

  const submit = useCallback(
    (term: string) => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
      requestSeq.current++; // invalidate any in-flight suggestion fetch
      setIsOpen(false);
      setIsLoading(false);
      setActiveIndex(-1);
      onChange(term);
      onSubmit(term);
    },
    [onChange, onSubmit],
  );

  const handleClear = useCallback(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    requestSeq.current++;
    setInputValue('');
    setSuggestions([]);
    setIsOpen(false);
    setIsLoading(false);
    setActiveIndex(-1);
    onChange('');
  }, [onChange]);

  const handleSelectSuggestion = useCallback(
    (suggestion: string) => {
      setInputValue(suggestion);
      submit(suggestion);
    },
    [submit],
  );

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLInputElement>) => {
      switch (event.key) {
        case 'Enter': {
          event.preventDefault();
          if (isOpen && activeIndex >= 0 && activeIndex < suggestions.length) {
            handleSelectSuggestion(suggestions[activeIndex]);
          } else {
            submit(inputValue);
          }
          return;
        }
        case 'Escape': {
          event.preventDefault();
          if (isOpen) {
            // First Escape closes the dropdown; a second clears the field.
            setIsOpen(false);
            setActiveIndex(-1);
          } else {
            handleClear();
          }
          return;
        }
        case 'ArrowDown': {
          if (!isOpen || suggestions.length === 0) return;
          event.preventDefault();
          setActiveIndex(prev => (prev + 1) % suggestions.length);
          return;
        }
        case 'ArrowUp': {
          if (!isOpen || suggestions.length === 0) return;
          event.preventDefault();
          setActiveIndex(prev => (prev <= 0 ? suggestions.length - 1 : prev - 1));
          return;
        }
        default:
          return;
      }
    },
    [isOpen, activeIndex, suggestions, inputValue, handleSelectSuggestion, submit, handleClear],
  );

  const showClear = inputValue.length > 0;

  return (
    <div ref={containerRef} className={`relative w-full ${className}`}>
      <div className="relative flex items-center">
        {/* Leading search icon */}
        <span className="absolute left-3.5 flex items-center pointer-events-none text-[rgba(0,0,0,0.4)]">
          <Icon icon="mdi:magnify" width={18} />
        </span>

        <input
          type="text"
          role="searchbox"
          aria-label="Search scripts"
          aria-autocomplete="list"
          aria-expanded={isOpen}
          aria-controls="searchbar-suggestions"
          autoComplete="off"
          spellCheck={false}
          value={inputValue}
          placeholder={placeholder}
          onChange={handleInputChange}
          onKeyDown={handleKeyDown}
          onFocus={() => {
            if (suggestions.length > 0) setIsOpen(true);
          }}
          className="w-full h-11 pl-11 pr-20 bg-white border border-[rgba(0,0,0,0.10)] rounded-xl text-[14px] text-[rgba(0,0,0,0.88)] placeholder:text-[rgba(0,0,0,0.35)] outline-none transition-all focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
        />

        {/* Trailing controls: loading spinner + clear button */}
        <div className="absolute right-3 flex items-center gap-1.5">
          {isLoading && (
            <span
              role="status"
              aria-label="Searching"
              className="flex items-center text-indigo-500"
            >
              <Icon icon="mdi:loading" width={18} className="animate-spin" />
            </span>
          )}
          {showClear && (
            <button
              type="button"
              onClick={handleClear}
              aria-label="Clear search"
              title="Clear search"
              className="w-7 h-7 flex items-center justify-center rounded-lg text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.7)] transition-colors"
            >
              <Icon icon="mdi:close" width={16} />
            </button>
          )}
        </div>
      </div>

      {/* Autocomplete dropdown */}
      {isOpen && suggestions.length > 0 && (
        <ul
          id="searchbar-suggestions"
          role="listbox"
          aria-label="Search suggestions"
          className="absolute z-30 mt-1.5 w-full max-h-72 overflow-auto bg-white border border-[rgba(0,0,0,0.10)] rounded-xl shadow-lg py-1.5"
        >
          {suggestions.map((suggestion, idx) => {
            const isActive = idx === activeIndex;
            return (
              <li
                key={`${suggestion}-${idx}`}
                role="option"
                aria-selected={isActive}
                // Use onMouseDown so the selection fires before the input blur
                // (which would otherwise close the dropdown first).
                onMouseDown={(e) => {
                  e.preventDefault();
                  handleSelectSuggestion(suggestion);
                }}
                onMouseEnter={() => setActiveIndex(idx)}
                className={`flex items-center gap-2.5 px-3.5 py-2 cursor-pointer text-[13px] transition-colors ${
                  isActive ? 'bg-indigo-50 text-indigo-700' : 'text-[rgba(0,0,0,0.75)] hover:bg-[rgba(0,0,0,0.03)]'
                }`}
              >
                <Icon icon="mdi:magnify" width={14} className="flex-shrink-0 text-[rgba(0,0,0,0.35)]" />
                <span className="truncate">
                  {highlightMatch(suggestion, inputValue).map((segment, i) =>
                    segment.match ? (
                      <mark key={i} className="bg-transparent font-semibold text-indigo-600">
                        {segment.text}
                      </mark>
                    ) : (
                      <span key={i}>{segment.text}</span>
                    ),
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};

export default SearchBar;
