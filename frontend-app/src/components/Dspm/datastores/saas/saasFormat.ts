/**
 * Pure formatting rules of the SaaS drawer and the SaaS file drawer (design 6.5). Times are epoch seconds
 * and are read in the viewer's time zone, from local date parts, as `datastoreFormat.ts` does.
 */

import { extensionOf } from '../datastoreFormat';

const pad = (n: number) => String(n).padStart(2, '0');

const DAY_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** en-GB short months, as the console's lineage cards print them ("Sept"). */
const LINEAGE_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sept', 'Oct', 'Nov', 'Dec'];

/** A date without the time, as the SaaS Information tab's Created On: "08 Sep 2026". */
export function formatConsoleDay(seconds: number): string {
  const d = new Date(seconds * 1000);
  return `${pad(d.getDate())} ${DAY_MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

/** A lineage event's time: "10 Sept 2026, 12:54" (unpadded day, en-GB month, 24-hour clock). */
export function formatLineageTime(seconds: number): string {
  const d = new Date(seconds * 1000);
  return `${d.getDate()} ${LINEAGE_MONTHS[d.getMonth()]} ${d.getFullYear()}, ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * The long relative form of the file drawer's Last Modified ("2 months ago"), with day.js's thresholds
 * (A9). The age is clamped at 0, so a future time reads "a few seconds ago". A rounded count of 1 reads
 * as the singular step before it ("an hour ago", never "1 hours ago"), as day.js does.
 */
export function formatRelativeLong(seconds: number, nowMs: number): string {
  const age = Math.max(0, nowMs / 1000 - seconds);
  if (age < 45) return 'a few seconds ago';
  if (age < 90) return 'a minute ago';
  const minutes = Math.round(age / 60);
  if (minutes < 45) return `${minutes} minutes ago`;
  if (minutes < 90) return 'an hour ago';
  const hours = Math.round(age / 3600);
  if (hours < 22) return hours <= 1 ? 'an hour ago' : `${hours} hours ago`;
  if (hours < 36) return 'a day ago';
  const days = Math.round(age / 86_400);
  if (days < 26) return days <= 1 ? 'a day ago' : `${days} days ago`;
  if (days < 46) return 'a month ago';
  const months = Math.round(days / 30.4375);
  if (months < 11) return months <= 1 ? 'a month ago' : `${months} months ago`;
  if (months < 18) return 'a year ago';
  const years = Math.round(days / 365.25);
  return years <= 1 ? 'a year ago' : `${years} years ago`;
}

/** A part's share of a total to two decimals: "98.62%"; "0%" when the total is 0. */
export function percentShare(part: number, total: number): string {
  if (total <= 0) return '0%';
  return `${((part / total) * 100).toFixed(2)}%`;
}

/**
 * The extension label under a file name ("PDF"), or null when the name has none a person would call an
 * extension: an e-mail subject such as "Re: v1.2 notes" gives null.
 */
export function fileExtensionLabel(name: string | null | undefined): string | null {
  const extension = extensionOf(name);
  return extension !== null && /^[a-z0-9]{1,8}$/.test(extension) ? extension.toUpperCase() : null;
}

/** The first letter of a name, upper-cased, for an initial avatar; "?" for none. */
export function initialOf(text: string | null | undefined): string {
  const first = [...(text ?? '').trim()][0];
  return first ? first.toUpperCase() : '?';
}
