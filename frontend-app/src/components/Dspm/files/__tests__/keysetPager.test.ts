import { describe, expect, it } from 'vitest';
import { KEYSET_START, currentCursor, keysetRange, nextPage, previousPage } from '../keysetPager';

describe('keysetPager', () => {
  it('starts on the first page without a cursor', () => {
    expect(KEYSET_START).toEqual({ cursors: [null], index: 0 });
    expect(currentCursor(KEYSET_START)).toBeNull();
  });

  it('pushes the next cursor and moves back to a stored one', () => {
    const second = nextPage(KEYSET_START, 'c2');
    expect(second).toEqual({ cursors: [null, 'c2'], index: 1 });
    expect(currentCursor(second)).toBe('c2');
    const third = nextPage(second, 'c3');
    expect(third).toEqual({ cursors: [null, 'c2', 'c3'], index: 2 });
    const back = previousPage(third);
    expect(back).toEqual({ cursors: [null, 'c2', 'c3'], index: 1 });
    expect(currentCursor(back)).toBe('c2');
    expect(previousPage(KEYSET_START)).toEqual(KEYSET_START);
  });

  it('drops the cursors after the page it moves on from', () => {
    const third = nextPage(nextPage(KEYSET_START, 'c2'), 'c3');
    const fromFirst = nextPage({ cursors: third.cursors, index: 0 }, 'c2b');
    expect(fromFirst).toEqual({ cursors: [null, 'c2b'], index: 1 });
  });

  it('replays the failed page from the page on screen, not from the requested one', () => {
    // Page 3 (index 2) failed while page 2 (index 1) is on screen.
    const cursors = [null, 'c2', 'c3'];
    expect(nextPage({ cursors, index: 1 }, 'c3')).toEqual({ cursors: [null, 'c2', 'c3'], index: 2 });
    expect(previousPage({ cursors, index: 1 })).toEqual({ cursors, index: 0 });
    expect(keysetRange(1, 10, 10)).toEqual({ from: 11, to: 20 });
  });

  it('gives the range of the page on screen, null for no rows', () => {
    expect(keysetRange(0, 10, 10)).toEqual({ from: 1, to: 10 });
    expect(keysetRange(2, 10, 4)).toEqual({ from: 21, to: 24 });
    expect(keysetRange(1, 25, 0)).toBeNull();
  });
});
