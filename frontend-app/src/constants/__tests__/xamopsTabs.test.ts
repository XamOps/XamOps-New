import { describe, expect, it } from 'vitest';
import {
  ALL_XAMOPS_TAB_KEYS,
  XAMOPS_MODULES,
  XAMOPS_TAB_PERMISSIONS,
  makeXamTabGate,
  normalizeAllowedTabs,
  selectAllXamTabs,
  toggleAllowedTab,
} from '../xamopsTabs';

const PERMISSION = 'cloudops-cloudk8s-automation';
const PARENT = 'cloudops-cloudk8s';

describe('XAMOPS_TAB_PERMISSIONS (D2)', () => {
  it('holds the CloudK8s automation permission under its tab', () => {
    expect(XAMOPS_TAB_PERMISSIONS).toEqual([{ key: PERMISSION, parent: PARENT, label: 'Allow automation changes' }]);
    expect(ALL_XAMOPS_TAB_KEYS).toContain(PARENT);
  });

  it('is not a sidebar tab, so Select all, module toggles and the gate never grant it', () => {
    expect(ALL_XAMOPS_TAB_KEYS).not.toContain(PERMISSION);
    expect(XAMOPS_MODULES.flatMap((m) => m.tabs.map((t) => t.key))).not.toContain(PERMISSION);
    expect(selectAllXamTabs([])).toEqual(ALL_XAMOPS_TAB_KEYS);
  });

  it('leaves makeXamTabGate as it was: a permission-only list is "restricted to nothing"', () => {
    // The case normalizeAllowedTabs exists to prevent.
    const gate = makeXamTabGate([PERMISSION]);
    expect(gate.restricted).toBe(true);
    expect(gate.isTabAllowed(PARENT)).toBe(false);
    expect(makeXamTabGate([]).isTabAllowed(PARENT)).toBe(true);
  });
});

describe('normalizeAllowedTabs', () => {
  it('drops the permission without its parent tab', () => {
    expect(normalizeAllowedTabs([PERMISSION])).toEqual([]);
    expect(normalizeAllowedTabs(['finops-cost', PERMISSION])).toEqual(['finops-cost']);
  });

  it('keeps the permission with its parent, and every other key in order', () => {
    expect(normalizeAllowedTabs(['finops-cost', PERMISSION, PARENT, 'all-tickets']))
      .toEqual(['finops-cost', PERMISSION, PARENT, 'all-tickets']);
    expect(normalizeAllowedTabs([])).toEqual([]);
  });

  it('removes the permission when its tab is unchecked', () => {
    expect(toggleAllowedTab([PARENT, PERMISSION, 'finops-cost'], PARENT)).toEqual(['finops-cost']);
  });

  it('checks and unchecks the permission itself only while its tab is checked', () => {
    expect(toggleAllowedTab([PARENT], PERMISSION)).toEqual([PARENT, PERMISSION]);
    expect(toggleAllowedTab([PARENT, PERMISSION], PERMISSION)).toEqual([PARENT]);
    expect(toggleAllowedTab(['finops-cost'], PERMISSION)).toEqual(['finops-cost']);
  });

  it('keeps a granted permission through Select all, and adds none', () => {
    expect(selectAllXamTabs([PARENT, PERMISSION])).toEqual([...ALL_XAMOPS_TAB_KEYS, PERMISSION]);
    expect(selectAllXamTabs(['finops-cost'])).not.toContain(PERMISSION);
  });
});
