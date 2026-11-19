/**
 * Keyset paging for the SaaS Files tab (design 6.5): a stack of cursors, `cursors[i]` being the cursor that
 * requests page `i` (`cursors[0]` is null, the first page). Previous replays a stored cursor; Next pushes
 * the cursor the page on screen returned. Pure functions.
 *
 * Every move starts from the index of the page on screen, never from the requested index, which already
 * points at the next page while it loads or after it failed.
 */
export interface KeysetState {
  cursors: ReadonlyArray<string | null>;
  index: number;
}

export const KEYSET_START: KeysetState = { cursors: [null], index: 0 };

export const currentCursor = (s: KeysetState): string | null => s.cursors[s.index] ?? null;

/** The page after `s.index`: the cursors after it are dropped and `nextCursor` pushed. */
export function nextPage(s: KeysetState, nextCursor: string): KeysetState {
  return { cursors: [...s.cursors.slice(0, s.index + 1), nextCursor], index: s.index + 1 };
}

/** The page before `s.index`, never before the first. The cursors are kept, so Next can replay them. */
export function previousPage(s: KeysetState): KeysetState {
  return { cursors: s.cursors, index: Math.max(0, s.index - 1) };
}

/** "Showing a - b" for the page at `index` with `rows` rows; null for an empty page. */
export function keysetRange(index: number, pageSize: number, rows: number): { from: number; to: number } | null {
  if (rows === 0) return null;
  return { from: index * pageSize + 1, to: index * pageSize + rows };
}
