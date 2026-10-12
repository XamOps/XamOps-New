/**
 * In-page navigations: URL updates that must not remount the page.
 *
 * The app layout keys each page by its location, so every navigation re-enters the page with a
 * transition (App.tsx). A page that keeps state in the URL, such as the cluster page's `?tab=&sub=`
 * or the fleet's one-time notice, navigates with `state: inPageState(location)`. The layout then
 * keeps the key of the entry the page was opened on, so the page re-renders in place instead of
 * remounting, and refetching, on every tab click. Every other navigation gets a fresh key, as before.
 */
import type { Location } from 'react-router-dom';

type KeyedLocation = Pick<Location, 'key' | 'state'>;

/** The layout's key for the page showing `location`. */
export function pageKeyOf(location: KeyedLocation): string {
  const pageKey = (location.state as { pageKey?: unknown } | null | undefined)?.pageKey;
  return typeof pageKey === 'string' ? pageKey : location.key;
}

/** Navigation state that keeps the current page mounted. */
export function inPageState(location: KeyedLocation): { pageKey: string } {
  return { pageKey: pageKeyOf(location) };
}
