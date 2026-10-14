/**
 * Freezes the page behind a modal, counted so stacked overlays cannot unfreeze each other.
 *
 * <h2>Why this is not two lines in a `useEffect`</h2>
 * Every drawer in the app used to own its own lock: set `overflow: hidden` on open, set it back to
 * `''` on close. That is correct for one drawer and wrong for two, because the second one's cleanup
 * runs while the first is still open — so closing an inner panel handed the scroll back to a page
 * the outer panel was still covering, and the reader's wheel moved the content underneath it.
 *
 * <p>The Cost page stacks exactly that way: a service's resource list opens over the invoice
 * breakdown, and one resource's detail opens over the list. Dismissing the detail must leave the
 * list's lock standing.
 *
 * <p>A depth count fixes it without the overlays knowing about each other. The first acquire locks,
 * the last release restores — and it restores the value that was there beforehand rather than
 * assuming `''`, so a lock taken inside something else that had already set `overflow` leaves it as
 * it found it.
 *
 * <h2>Why two elements</h2>
 * The app does not scroll `<body>`; it scrolls a `.main-content-scroll` element inside the layout
 * (see `App.tsx`). Locking only `<body>` leaves the real scroller free, which is the bug this
 * originally worked around. Both are locked, and the scroller is re-queried on release because a
 * route change can replace the node while an overlay is open.
 */

/** How many overlays currently hold the lock. The page is frozen while this is above zero. */
let depth = 0;

/** What `overflow` was before the first acquire, so the last release can put it back. */
let bodyPrevious = '';
let scrollerPrevious = '';

const scrollerEl = (): HTMLElement | null =>
  document.querySelector('.main-content-scroll') as HTMLElement | null;

/**
 * Takes the lock and returns the release for it.
 *
 * <p>The returned function is safe to call more than once — React runs an effect's cleanup on
 * unmount and again whenever its dependencies change, and a release that decremented twice would
 * let the count fall below the number of overlays still open. Each release is spent once.
 *
 * @returns the matching release; call it from the effect's cleanup
 */
export const lockPageScroll = (): (() => void) => {
  if (depth === 0) {
    const scroller = scrollerEl();
    bodyPrevious = document.body.style.overflow;
    scrollerPrevious = scroller?.style.overflow ?? '';
    document.body.style.overflow = 'hidden';
    if (scroller) scroller.style.overflow = 'hidden';
  }
  depth += 1;

  let spent = false;
  return () => {
    if (spent) return;
    spent = true;
    depth = Math.max(0, depth - 1);
    if (depth === 0) {
      document.body.style.overflow = bodyPrevious;
      // Re-queried rather than captured: a navigation can swap the scroll container out from
      // under an open overlay, and writing to the detached node would leave the live one locked.
      const scroller = scrollerEl();
      if (scroller) scroller.style.overflow = scrollerPrevious;
    }
  };
};
