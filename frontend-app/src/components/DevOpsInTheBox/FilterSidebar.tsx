import React, { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Icon } from '@iconify/react';
import {
  ScriptFilters,
  ScriptCategory,
  CloudProvider,
  DifficultyLevel,
} from '../../types/devopsInTheBox';

/**
 * FilterSidebar — the Script Explorer's faceted filter & sort panel.
 *
 * It is a fully controlled, presentational component: it receives the current
 * {@link ScriptFilters} plus an `onChange` callback and never owns the filter
 * state itself (only transient UI state such as which sections are expanded and
 * the tag search box). This keeps it trivially testable without a Router.
 *
 * Features (REQ-2.3.2, REQ-2.3.3, REQ-2.4.2):
 *  - Collapsible sections: Categories, Cloud Providers, Difficulty, Tags, Rating.
 *  - Multi-select checkboxes for categories / cloudProviders / difficulties.
 *  - Tag selector: popular tags shown as toggles, with a search box to find more.
 *  - Rating filter with a 1–5 star "minimum rating" selector.
 *  - "Show Only Favorites" toggle.
 *  - Sort dropdown (Recent, Popular, Rating, Name).
 *  - "Reset All Filters" button.
 *  - Active filter count badge.
 *
 * URL persistence (REQ-2.3.3 — shareable filters) is provided by the exported
 * {@link filtersToSearchParams} / {@link searchParamsToFilters} serialisers and
 * the {@link useScriptFilterUrlSync} hook, which a parent page can wire up to
 * `useSearchParams`. The sidebar stays decoupled from the router so it can be
 * reused and unit-tested in isolation.
 */

// ─── Defaults & option metadata ──────────────────────────────────────────────────

const SORT_OPTIONS: Array<{ value: ScriptFilters['sortBy']; label: string }> = [
  { value: 'recent', label: 'Most Recent' },
  { value: 'popular', label: 'Most Popular' },
  { value: 'rating', label: 'Highest Rated' },
  { value: 'name', label: 'Name (A–Z)' },
];

const CATEGORY_OPTIONS: Array<{ value: ScriptCategory; label: string; icon: string }> = [
  { value: 'terraform', label: 'Terraform', icon: 'simple-icons:terraform' },
  { value: 'bash', label: 'Bash', icon: 'mdi:console' },
  { value: 'python', label: 'Python', icon: 'mdi:language-python' },
  { value: 'ansible', label: 'Ansible', icon: 'simple-icons:ansible' },
];

const PROVIDER_OPTIONS: Array<{ value: CloudProvider; label: string }> = [
  { value: 'aws', label: 'AWS' },
  { value: 'azure', label: 'Azure' },
  { value: 'gcp', label: 'GCP' },
  { value: 'multi-cloud', label: 'Multi-Cloud' },
];

const DIFFICULTY_OPTIONS: Array<{ value: DifficultyLevel; label: string }> = [
  { value: 'beginner', label: 'Beginner' },
  { value: 'intermediate', label: 'Intermediate' },
  { value: 'advanced', label: 'Advanced' },
];

/** A sensible blank filter state used by "Reset All Filters". */
export const EMPTY_FILTERS: ScriptFilters = {
  searchQuery: '',
  categories: [],
  cloudProviders: [],
  difficulties: [],
  tags: [],
  minRating: undefined,
  sortBy: 'recent',
  showOnlyFavorites: false,
};

const POPULAR_TAGS_VISIBLE = 12;

// ─── Pure helpers ────────────────────────────────────────────────────────────────

/** Toggle membership of `value` within `list`, returning a new array. */
function toggleInArray<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

/**
 * Count how many distinct filter facets are active. Sort order is excluded as
 * it is always set; the search query is also excluded (it lives in the SearchBar)
 * — this badge reflects the sidebar's own facets.
 */
export function countActiveFilters(filters: ScriptFilters): number {
  let count = 0;
  count += filters.categories.length;
  count += filters.cloudProviders.length;
  count += filters.difficulties.length;
  count += filters.tags.length;
  if (typeof filters.minRating === 'number' && filters.minRating > 0) count += 1;
  if (filters.showOnlyFavorites) count += 1;
  return count;
}

// ─── URL persistence helpers (REQ-2.3.3) ───────────────────────────────────────────

/**
 * Serialise filters into URLSearchParams so the current view can be shared via
 * the URL. Empty / default facets are omitted to keep URLs clean.
 */
export function filtersToSearchParams(filters: ScriptFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.searchQuery) params.set('q', filters.searchQuery);
  if (filters.categories.length) params.set('categories', filters.categories.join(','));
  if (filters.cloudProviders.length) params.set('providers', filters.cloudProviders.join(','));
  if (filters.difficulties.length) params.set('difficulties', filters.difficulties.join(','));
  if (filters.tags.length) params.set('tags', filters.tags.join(','));
  if (typeof filters.minRating === 'number' && filters.minRating > 0) {
    params.set('minRating', String(filters.minRating));
  }
  if (filters.sortBy && filters.sortBy !== 'recent') params.set('sort', filters.sortBy);
  if (filters.showOnlyFavorites) params.set('favorites', '1');
  return params;
}

/** Split a comma-separated query param and keep only values present in `allowed`. */
function parseList<T extends string>(raw: string | null, allowed: readonly T[]): T[] {
  if (!raw) return [];
  const allowedSet = new Set<string>(allowed);
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => allowedSet.has(s)) as T[];
}

/**
 * Reconstruct a {@link ScriptFilters} from URLSearchParams. Unknown / malformed
 * values are dropped, falling back to {@link EMPTY_FILTERS} defaults.
 */
export function searchParamsToFilters(params: URLSearchParams): ScriptFilters {
  const categories = parseList<ScriptCategory>(
    params.get('categories'),
    CATEGORY_OPTIONS.map((o) => o.value)
  );
  const cloudProviders = parseList<CloudProvider>(
    params.get('providers'),
    PROVIDER_OPTIONS.map((o) => o.value)
  );
  const difficulties = parseList<DifficultyLevel>(
    params.get('difficulties'),
    DIFFICULTY_OPTIONS.map((o) => o.value)
  );
  const tags = (params.get('tags') ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  const minRatingRaw = params.get('minRating');
  const minRatingNum = minRatingRaw ? Number(minRatingRaw) : NaN;
  const minRating =
    Number.isFinite(minRatingNum) && minRatingNum >= 1 && minRatingNum <= 5
      ? minRatingNum
      : undefined;

  const sortRaw = params.get('sort');
  const sortBy = SORT_OPTIONS.some((o) => o.value === sortRaw)
    ? (sortRaw as ScriptFilters['sortBy'])
    : 'recent';

  return {
    searchQuery: params.get('q') ?? '',
    categories,
    cloudProviders,
    difficulties,
    tags,
    minRating,
    sortBy,
    showOnlyFavorites: params.get('favorites') === '1',
  };
}

/**
 * Optional convenience hook for pages that want filter state mirrored in the
 * URL query string (shareable links). Reads the initial filters from the URL and
 * returns a setter that pushes serialised filters back into the URL.
 *
 * Usage (in a routed page):
 *   const [filters, setFilters] = useScriptFilterUrlSync();
 *   <FilterSidebar filters={filters} onChange={setFilters} />
 */
export function useScriptFilterUrlSync(): [ScriptFilters, (next: ScriptFilters) => void] {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => searchParamsToFilters(searchParams), [searchParams]);
  const setFilters = useCallback(
    (next: ScriptFilters) => {
      setSearchParams(filtersToSearchParams(next), { replace: true });
    },
    [setSearchParams]
  );
  return [filters, setFilters];
}

// ─── Collapsible section ─────────────────────────────────────────────────────────

interface SectionProps {
  title: string;
  /** Number of active selections within the section (shown as a small badge). */
  activeCount?: number;
  defaultOpen?: boolean;
  children: React.ReactNode;
}

const FilterSection: React.FC<SectionProps> = ({ title, activeCount = 0, defaultOpen = true, children }) => {
  const [open, setOpen] = useState(defaultOpen);
  const contentId = `filter-section-${title.replace(/\s+/g, '-').toLowerCase()}`;
  return (
    <div className="border-b border-[rgba(0,0,0,0.06)] py-3">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-controls={contentId}
        className="w-full flex items-center justify-between text-left group"
      >
        <span className="flex items-center gap-2 text-[12px] font-bold uppercase tracking-[0.04em] text-[rgba(0,0,0,0.6)]">
          {title}
          {activeCount > 0 && (
            <span className="inline-flex items-center justify-center min-w-[18px] h-[18px] px-1 bg-indigo-100 text-indigo-700 rounded-full text-[10px] font-bold">
              {activeCount}
            </span>
          )}
        </span>
        <Icon
          icon="mdi:chevron-down"
          width={18}
          className={`text-[rgba(0,0,0,0.4)] transition-transform duration-200 ${open ? 'rotate-180' : ''}`}
        />
      </button>
      {open && (
        <div id={contentId} className="mt-3 space-y-1.5">
          {children}
        </div>
      )}
    </div>
  );
};

// ─── Checkbox row ───────────────────────────────────────────────────────────────

interface CheckboxRowProps {
  label: string;
  checked: boolean;
  onToggle: () => void;
  icon?: string;
}

const CheckboxRow: React.FC<CheckboxRowProps> = ({ label, checked, onToggle, icon }) => (
  <label className="flex items-center gap-2.5 px-1 py-1 rounded-md cursor-pointer hover:bg-[rgba(0,0,0,0.03)] transition-colors">
    <input
      type="checkbox"
      checked={checked}
      onChange={onToggle}
      className="w-4 h-4 rounded border-[rgba(0,0,0,0.2)] text-indigo-600 focus:ring-indigo-400 cursor-pointer"
    />
    {icon && <Icon icon={icon} width={15} className="text-[rgba(0,0,0,0.5)] flex-shrink-0" />}
    <span className="text-[13px] text-[rgba(0,0,0,0.78)] capitalize">{label}</span>
  </label>
);

// ─── Props ──────────────────────────────────────────────────────────────────────

export interface FilterSidebarProps {
  /** Current filter state (controlled by the parent). */
  filters: ScriptFilters;
  /** Invoked with the next filter state whenever the user changes a facet. */
  onChange: (filters: ScriptFilters) => void;
  /** Optional list of tags to surface in the Tags section. */
  availableTags?: string[];
}

// ─── Component ────────────────────────────────────────────────────────────────────

const FilterSidebar: React.FC<FilterSidebarProps> = ({ filters, onChange, availableTags = [] }) => {
  const [tagQuery, setTagQuery] = useState('');

  const activeCount = useMemo(() => countActiveFilters(filters), [filters]);

  // ── Facet update handlers (each produces a fresh ScriptFilters) ──
  const toggleCategory = useCallback(
    (value: ScriptCategory) => onChange({ ...filters, categories: toggleInArray(filters.categories, value) }),
    [filters, onChange]
  );
  const toggleProvider = useCallback(
    (value: CloudProvider) => onChange({ ...filters, cloudProviders: toggleInArray(filters.cloudProviders, value) }),
    [filters, onChange]
  );
  const toggleDifficulty = useCallback(
    (value: DifficultyLevel) => onChange({ ...filters, difficulties: toggleInArray(filters.difficulties, value) }),
    [filters, onChange]
  );
  const toggleTag = useCallback(
    (value: string) => onChange({ ...filters, tags: toggleInArray(filters.tags, value) }),
    [filters, onChange]
  );
  const setMinRating = useCallback(
    (rating: number) =>
      onChange({ ...filters, minRating: filters.minRating === rating ? undefined : rating }),
    [filters, onChange]
  );
  const setSortBy = useCallback(
    (value: ScriptFilters['sortBy']) => onChange({ ...filters, sortBy: value }),
    [filters, onChange]
  );
  const toggleFavorites = useCallback(
    () => onChange({ ...filters, showOnlyFavorites: !filters.showOnlyFavorites }),
    [filters, onChange]
  );
  const resetAll = useCallback(
    // Preserve the search query (owned by the SearchBar) and current sort order.
    () => onChange({ ...EMPTY_FILTERS, searchQuery: filters.searchQuery, sortBy: filters.sortBy }),
    [filters.searchQuery, filters.sortBy, onChange]
  );

  // ── Tag list: filter available tags by the search box, then chunk into
  //    "popular" (always shown) + "more" (revealed when searching). ──
  const filteredTags = useMemo(() => {
    const q = tagQuery.trim().toLowerCase();
    const base = q ? availableTags.filter((t) => t.toLowerCase().includes(q)) : availableTags;
    // Always include already-selected tags so they can be unchecked.
    const merged = Array.from(new Set([...filters.tags, ...base]));
    return merged;
  }, [availableTags, tagQuery, filters.tags]);

  const visibleTags = tagQuery.trim() ? filteredTags : filteredTags.slice(0, POPULAR_TAGS_VISIBLE);
  const hiddenTagCount = Math.max(0, filteredTags.length - visibleTags.length);

  return (
    <aside
      aria-label="Script filters"
      className="w-full bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-4 flex flex-col"
    >
      {/* Header: title + active count + reset */}
      <div className="flex items-center justify-between pb-3 border-b border-[rgba(0,0,0,0.08)]">
        <h2 className="flex items-center gap-2 text-[14px] font-bold text-[rgba(0,0,0,0.85)]">
          <Icon icon="mdi:filter-variant" width={18} className="text-indigo-600" />
          Filters
          {activeCount > 0 && (
            <span
              aria-label={`${activeCount} active filters`}
              className="inline-flex items-center justify-center min-w-[20px] h-[20px] px-1.5 bg-indigo-600 text-white rounded-full text-[11px] font-bold"
            >
              {activeCount}
            </span>
          )}
        </h2>
        <button
          type="button"
          onClick={resetAll}
          disabled={activeCount === 0}
          className="text-[12px] font-semibold text-indigo-600 hover:text-indigo-700 disabled:text-[rgba(0,0,0,0.25)] disabled:cursor-not-allowed transition-colors"
        >
          Reset All
        </button>
      </div>

      {/* Sort dropdown */}
      <div className="py-3 border-b border-[rgba(0,0,0,0.06)]">
        <label htmlFor="sort-by" className="block text-[12px] font-bold uppercase tracking-[0.04em] text-[rgba(0,0,0,0.6)] mb-2">
          Sort By
        </label>
        <div className="relative">
          <select
            id="sort-by"
            value={filters.sortBy}
            onChange={(e) => setSortBy(e.target.value as ScriptFilters['sortBy'])}
            className="w-full appearance-none pl-3 pr-9 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.85)] bg-white focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none cursor-pointer"
          >
            {SORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <Icon
            icon="mdi:chevron-down"
            width={18}
            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.4)] pointer-events-none"
          />
        </div>
      </div>

      {/* Show only favorites */}
      <div className="py-3 border-b border-[rgba(0,0,0,0.06)]">
        <label className="flex items-center justify-between cursor-pointer">
          <span className="flex items-center gap-2 text-[13px] font-semibold text-[rgba(0,0,0,0.78)]">
            <Icon icon="mdi:heart-outline" width={16} className="text-rose-500" />
            Show Only Favorites
          </span>
          <button
            type="button"
            role="switch"
            aria-checked={!!filters.showOnlyFavorites}
            aria-label="Show only favorites"
            onClick={toggleFavorites}
            className={`relative w-10 h-[22px] rounded-full transition-colors ${
              filters.showOnlyFavorites ? 'bg-indigo-600' : 'bg-[rgba(0,0,0,0.15)]'
            }`}
          >
            <span
              className={`absolute top-[2px] left-[2px] w-[18px] h-[18px] bg-white rounded-full shadow transition-transform ${
                filters.showOnlyFavorites ? 'translate-x-[18px]' : ''
              }`}
            />
          </button>
        </label>
      </div>

      {/* Categories */}
      <FilterSection title="Categories" activeCount={filters.categories.length}>
        {CATEGORY_OPTIONS.map((o) => (
          <CheckboxRow
            key={o.value}
            label={o.label}
            icon={o.icon}
            checked={filters.categories.includes(o.value)}
            onToggle={() => toggleCategory(o.value)}
          />
        ))}
      </FilterSection>

      {/* Cloud Providers */}
      <FilterSection title="Cloud Providers" activeCount={filters.cloudProviders.length}>
        {PROVIDER_OPTIONS.map((o) => (
          <CheckboxRow
            key={o.value}
            label={o.label}
            checked={filters.cloudProviders.includes(o.value)}
            onToggle={() => toggleProvider(o.value)}
          />
        ))}
      </FilterSection>

      {/* Difficulty */}
      <FilterSection title="Difficulty" activeCount={filters.difficulties.length}>
        {DIFFICULTY_OPTIONS.map((o) => (
          <CheckboxRow
            key={o.value}
            label={o.label}
            checked={filters.difficulties.includes(o.value)}
            onToggle={() => toggleDifficulty(o.value)}
          />
        ))}
      </FilterSection>

      {/* Tags */}
      <FilterSection title="Tags" activeCount={filters.tags.length}>
        <div className="relative mb-2">
          <Icon
            icon="mdi:magnify"
            width={14}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)] pointer-events-none"
          />
          <input
            type="text"
            value={tagQuery}
            onChange={(e) => setTagQuery(e.target.value)}
            placeholder="Search tags…"
            aria-label="Search tags"
            className="w-full pl-8 pr-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] text-[rgba(0,0,0,0.85)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none"
          />
        </div>
        {visibleTags.length === 0 ? (
          <p className="text-[12px] text-[rgba(0,0,0,0.4)] px-1 py-1">
            {availableTags.length === 0 ? 'No tags available.' : 'No matching tags.'}
          </p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {visibleTags.map((tag) => {
              const selected = filters.tags.includes(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  onClick={() => toggleTag(tag)}
                  aria-pressed={selected}
                  className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-semibold border transition-colors ${
                    selected
                      ? 'bg-indigo-600 border-indigo-600 text-white'
                      : 'bg-white border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.65)] hover:border-indigo-300 hover:text-indigo-600'
                  }`}
                >
                  {selected && <Icon icon="mdi:check" width={12} />}
                  {tag}
                </button>
              );
            })}
          </div>
        )}
        {hiddenTagCount > 0 && (
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-2 px-1">
            +{hiddenTagCount} more — search to find them.
          </p>
        )}
      </FilterSection>

      {/* Rating */}
      <FilterSection title="Minimum Rating" activeCount={filters.minRating ? 1 : 0} defaultOpen={false}>
        <div className="flex items-center gap-1" role="group" aria-label="Minimum rating filter">
          {[1, 2, 3, 4, 5].map((star) => {
            const active = (filters.minRating ?? 0) >= star;
            return (
              <button
                key={star}
                type="button"
                onClick={() => setMinRating(star)}
                aria-label={`Minimum ${star} star${star > 1 ? 's' : ''}`}
                aria-pressed={active}
                className="p-0.5 transition-transform hover:scale-110"
              >
                <Icon
                  icon={active ? 'mdi:star' : 'mdi:star-outline'}
                  width={22}
                  color={active ? '#F59E0B' : 'rgba(0,0,0,0.25)'}
                />
              </button>
            );
          })}
          {filters.minRating ? (
            <span className="ml-2 text-[12px] text-[rgba(0,0,0,0.55)]">{filters.minRating}+ &amp; up</span>
          ) : (
            <span className="ml-2 text-[12px] text-[rgba(0,0,0,0.4)]">Any rating</span>
          )}
        </div>
      </FilterSection>
    </aside>
  );
};

export default FilterSidebar;
