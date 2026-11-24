/**
 * Pure rules of the Document Classes page (design 6.5, brief 10.2): the Category filter's options, the
 * browser-side search and filter, and each category's colour.
 */
import type { DatastoreFilterOption } from '../../../types/dspmDatastores';
import type { DocumentClassList, DocumentClassRow } from '../../../types/dspmPages';
import type { DatastoreFilterState } from '../datastores/datastoreFilters';

/** The Category filter's key in the page's filter state. */
export const CATEGORY_DIMENSION = 'category';

/** The categories in route order, each with its number of classes. */
export function documentClassCategoryOptions(list: DocumentClassList): DatastoreFilterOption[] {
  const counts = new Map<string, number>();
  for (const row of list.rows) counts.set(row.category, (counts.get(row.category) ?? 0) + 1);
  return list.categories.map(category => ({ value: category, label: category, count: counts.get(category) ?? 0 }));
}

/**
 * The rows a search and the Category filter leave: the class name contains the trimmed search (case
 * insensitive), and its category is one of the chosen ones when any is chosen. Route order is kept.
 */
export function filterDocumentClasses(
  rows: ReadonlyArray<DocumentClassRow>, search: string, filters: DatastoreFilterState,
): DocumentClassRow[] {
  const needle = search.trim().toLowerCase();
  const chosen = filters[CATEGORY_DIMENSION]?.values ?? [];
  const categories = chosen.length > 0 ? new Set(chosen) : null;
  return rows.filter(row =>
    (needle === '' || row.name.toLowerCase().includes(needle))
    && (categories === null || categories.has(row.category)));
}

/**
 * Eleven fixed colours, one per category by its index in route order (wrapping after the eleventh).
 * Full literals, so Tailwind generates them. The console's own colours are unverified.
 */
export const DOCUMENT_CLASS_COLOURS = [
  'bg-sky-500', 'bg-violet-500', 'bg-emerald-500', 'bg-amber-500', 'bg-rose-500', 'bg-indigo-500',
  'bg-teal-500', 'bg-orange-500', 'bg-fuchsia-500', 'bg-lime-600', 'bg-cyan-600',
] as const;

/** A category the list does not name. */
export const NEUTRAL_COLOUR = 'bg-[rgba(0,0,0,0.3)]';

/** The colour class of a category: the class chip's left bar and the category chip's dot. */
export function documentClassColour(category: string, categories: ReadonlyArray<string>): string {
  const index = categories.indexOf(category);
  return index < 0 ? NEUTRAL_COLOUR : DOCUMENT_CLASS_COLOURS[index % DOCUMENT_CLASS_COLOURS.length];
}
