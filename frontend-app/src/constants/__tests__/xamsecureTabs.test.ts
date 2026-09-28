import { describe, it, expect } from 'vitest';
import {
  XAMSECURE_TABS, XAMSECURE_SIDEBAR_TABS, VALID_XAMSECURE_TABS,
  firstTabForGroups, groupForTab, isComingSoon,
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

  it('never lands a client on a coming-soon tab', () => {
    // A placeholder is a poor first impression of a product someone is paying for, so the landing
    // helper skips them. dspm-saas is declared before... no: dspm-cloud is first in DSPM order, but
    // this must hold even if the declaration order changes, which is why it is asserted rather than
    // left to the array.
    expect(firstTabForGroups(['DSPM'])).toBe('dspm-cloud');
    expect(isComingSoon(firstTabForGroups(['DSPM']))).toBe(false);
    expect(isComingSoon(firstTabForGroups(['CNAPP']))).toBe(false);
    expect(isComingSoon(firstTabForGroups(['CNAPP', 'DSPM']))).toBe(false);
  });

  it('gives a DSPM-only client a DSPM landing tab, not a CNAPP one', () => {
    // The bug this pins: /xamsecure used to redirect unconditionally to `overview`, a CNAPP tab, so a
    // DSPM-only customer landed on an Upwind page with no data and no route out.
    expect(groupForTab(firstTabForGroups(['DSPM']))).toBe('DSPM');
    expect(firstTabForGroups(['CNAPP'])).toBe('overview');
    // CNAPP wins when both are present, matching the nav order.
    expect(firstTabForGroups(['CNAPP', 'DSPM'])).toBe('overview');
    expect(firstTabForGroups([])).toBeNull();
  });

  it('describes every coming-soon tab, so the placeholder is never blank', () => {
    const soon = XAMSECURE_TABS.filter((t) => t.comingSoon);
    // Announced-but-unbuilt tabs exist to set expectations; one with no blurb sets none.
    expect(soon.length).toBeGreaterThan(0);
    for (const t of soon) {
      expect(t.comingSoonBlurb, `${t.id} has no blurb`).toBeTruthy();
      // They are listed on purpose — hiding them is what made "coming" indistinguishable from
      // "not a feature" in the first place.
      expect(t.sidebar).not.toBe(false);
    }
  });

  it('tags every tab with a product group, so the nav can gate the two separately', () => {
    for (const t of XAMSECURE_TABS) {
      expect(['CNAPP', 'DSPM']).toContain(t.group);
    }
    // Nothing DSPM may hide inside the CNAPP group: the two are separately sold, and a mis-grouped tab
    // would leak one product's nav into a customer entitled only to the other.
    expect(XAMSECURE_TABS.filter((t) => t.id.startsWith('dspm-')).every((t) => t.group === 'DSPM'))
      .toBe(true);
  });
});
