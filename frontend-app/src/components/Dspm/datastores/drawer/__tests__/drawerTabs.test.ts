import { describe, expect, it } from 'vitest';
import { DEFAULT_DRAWER_TAB, drawerTabsFor } from '../drawerTabs';

const labels = (category: string | null, canDiscoverSchema: boolean | null | undefined) =>
  drawerTabsFor(category, canDiscoverSchema).map(tab => tab.label);

describe('drawerTabsFor', () => {
  it('gives a cloud datastore Risk and no Data Flow (A14 order)', () => {
    expect(labels('cloud', false)).toEqual(['Sensitive', 'Information', 'Risk', 'Access Graph', 'Scan History']);
  });

  it('gives an on-prem database Data Flow and no Risk', () => {
    expect(labels('on_prem', true)).toEqual(['Sensitive', 'Information', 'Data Flow', 'Access Graph', 'Scan History']);
  });

  it('gives a cloud database both Risk and Data Flow, Risk first', () => {
    expect(labels('cloud', true)).toEqual(['Sensitive', 'Information', 'Risk', 'Data Flow', 'Access Graph', 'Scan History']);
  });

  it('treats an unknown canDiscoverSchema as false until the overview arrives', () => {
    expect(labels('cloud', undefined)).toEqual(['Sensitive', 'Information', 'Risk', 'Access Graph', 'Scan History']);
    expect(labels('cloud', null)).toEqual(['Sensitive', 'Information', 'Risk', 'Access Graph', 'Scan History']);
    expect(labels('on_prem', undefined)).toEqual(['Sensitive', 'Information', 'Access Graph', 'Scan History']);
    expect(labels('on_prem', false)).toEqual(['Sensitive', 'Information', 'Access Graph', 'Scan History']);
  });

  it('opens on Sensitive, with stable ids', () => {
    expect(DEFAULT_DRAWER_TAB).toBe('sensitive');
    expect(drawerTabsFor('cloud', true).map(tab => tab.id))
      .toEqual(['sensitive', 'information', 'risk', 'dataFlow', 'accessGraph', 'scanHistory']);
  });
});
