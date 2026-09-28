import { describe, it, expect } from 'vitest';
import {
  XAMSECURE_TABS, XAMSECURE_SIDEBAR_TABS, VALID_XAMSECURE_TABS,
} from '../xamsecureTabs';

/**
 * These pin the two facts that were wrong in production.
 *
 * The tab list was declared twice — once on XamSecurePage for route validation, once in
 * Sidebar.tsx for the nav, with a comment claiming the second mirrored the first. It did not:
 * the Components -> Packages rename (6 Aug) and the Code tab (12 Aug) both landed in the router
 * copy only, so Code was deployed and working but reachable only by typing the URL. One array
 * now feeds both, and these assertions keep the drift-prone entries honest.
 */
describe('XamSecure tab catalog', () => {
  it('puts every shipped tab in the sidebar, including the two that went missing', () => {
    const ids = XAMSECURE_SIDEBAR_TABS.map((t) => t.id);
    expect(ids).toContain('code');
    expect(ids).toContain('components');
    // The nav read "Components" for six days after the rename. Upwind's Components area is
    // sensor fleet management; this tab is a software bill of materials.
    expect(XAMSECURE_TABS.find((t) => t.id === 'components')?.label).toBe('Packages');
    expect(XAMSECURE_TABS.find((t) => t.id === 'code')?.label).toBe('Code');
  });

  it('keeps hidden routes valid, since they are still deep-link reachable', () => {
    const hidden = XAMSECURE_TABS.filter((t) => t.sidebar === false).map((t) => t.id);
    // Workflows and Onboarding map to Upwind's Settings area, not its security nav.
    // dspm-status is the DSPM connection state: only interesting when something is wrong, and the
    // Cloud Data tab links to it in that case, so it stays out of the nav in the healthy path.
    expect(hidden).toEqual(['workflows', 'onboarding', 'dspm-status']);
    for (const id of hidden) {
      expect(VALID_XAMSECURE_TABS.has(id)).toBe(true);
      expect(XAMSECURE_SIDEBAR_TABS.some((t) => t.id === id)).toBe(false);
    }
  });

  it('gives every nav entry a route and an icon, so none renders as a dead or blank link', () => {
    for (const t of XAMSECURE_SIDEBAR_TABS) {
      expect(VALID_XAMSECURE_TABS.has(t.id), `${t.id} is not a valid route`).toBe(true);
      expect(t.label.length).toBeGreaterThan(0);
      expect(t.icon).toMatch(/^fas /);
    }
  });

  it('has no duplicate ids, which would render two nav items to one route', () => {
    const ids = XAMSECURE_TABS.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
