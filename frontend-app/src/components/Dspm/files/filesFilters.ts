/**
 * Which Files filter values can be sent (design 6.4, mirroring the backend's `FilterValues` rules). A value
 * that fails renders disabled, so the UI never builds a request the backend rejects or splits.
 *
 * Every value: no surrounding whitespace (the backend trims), no comma (lists are comma-joined), no
 * control character. Then per dimension: `owner` a 24-hex id; `appType` and `sensitives` upper-case codes;
 * `internalUser` and `externalUser` 1 to 320 characters; `sharing` and `lastModified` one of their
 * choices; any other dimension 1 to 128 characters.
 */
import { MAX_FREE_TEXT } from '../datastores/datastoreFilters';

/** Longest e-mail value, as the backend's `FilterValues.MAX_EMAIL_LENGTH`. */
export const MAX_EMAIL_TEXT = 320;

const HEX24 = /^[0-9a-fA-F]{24}$/;
const CODE = /^[A-Z][A-Z0-9_]{0,63}$/;
/** What Java's `Character.isISOControl` rejects: C0, DEL and C1. */
const CONTROL_CHARACTER = /[\u0000-\u001F\u007F-\u009F]/;

const CHOICES: Readonly<Record<string, ReadonlySet<string>>> = {
  sharing: new Set(['internal', 'external', 'public']),
  lastModified: new Set(['48h', '7d', '30d']),
};
const CODE_DIMENSIONS: ReadonlySet<string> = new Set(['appType', 'sensitives']);
const EMAIL_DIMENSIONS: ReadonlySet<string> = new Set(['internalUser', 'externalUser']);

export const filesValueAllowed = (dimension: string) => (value: string): boolean => {
  if (value !== value.trim() || value.includes(',') || CONTROL_CHARACTER.test(value)) return false;
  const choices = CHOICES[dimension];
  if (choices) return choices.has(value);
  if (dimension === 'owner') return HEX24.test(value);
  if (CODE_DIMENSIONS.has(dimension)) return CODE.test(value);
  const max = EMAIL_DIMENSIONS.has(dimension) ? MAX_EMAIL_TEXT : MAX_FREE_TEXT;
  return value.length >= 1 && value.length <= max;
};
