/**
 * The Related Files tab's rows (design 6.6, D7): the file's content-hash copies in other places, from the
 * same copies answer as File Copies. Matters returns no confidence, match reason or same-owner flag for
 * them (GAP-61), so this module derives the three; lower-confidence inferred matches are console-only
 * (GAP-29) and never listed.
 */

import type { SaasFileCopies, SaasFileCopy } from '../../../../types/dspmDrawers';

/** Every copy is a content-hash match, so every row reads the same confidence. */
export const CONTENT_HASH_VERIFIED = 'Content hash verified';

export interface RelatedFileRow {
  copy: SaasFileCopy;
  /** The copy's owner e-mail is the file's owner (A28). */
  samePerson: boolean;
  why: string;
}

const sameText = (a: string | null, b: string | null) =>
  a !== null && b !== null && a.trim() !== '' && a.trim().toLowerCase() === b.trim().toLowerCase();

/**
 * One row per copy, in API order. `owner` is the file overview's owner, or null when the overview failed:
 * then no row is "Same person".
 */
export function relatedFileRows(copies: SaasFileCopies, owner: string | null): RelatedFileRow[] {
  return copies.copies.map(copy => {
    const samePerson = sameText(owner, copy.ownerEmail);
    return { copy, samePerson, why: samePerson ? 'Same content hash · Same person' : 'Same content hash' };
  });
}

/**
 * The header "Related files for <name> — <e-mail> (matched by file owner)": the datastore's name (A28)
 * and the file's owner, or no e-mail without one.
 */
export function relatedFilesHeading(datastoreName: string, owner: string | null): { name: string; email: string | null } {
  const email = owner?.trim() ?? '';
  return { name: datastoreName, email: email === '' ? null : email };
}
