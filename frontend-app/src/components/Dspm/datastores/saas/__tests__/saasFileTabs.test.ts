import { describe, expect, it } from 'vitest';
import { saasFileTabs } from '../saasFileTabs';

const labels = (driveType: string | null) => saasFileTabs(driveType).map(tab => tab.label);

describe('saasFileTabs', () => {
  it('gives a Gmail file five tabs, without File Copies', () => {
    expect(labels('GMAIL')).toEqual(['Sensitives', 'Details', 'File Activity', 'Lineage', 'Related Files']);
  });

  it('gives a Google Drive file six tabs, File Copies before Related Files', () => {
    const six = ['Sensitives', 'Details', 'File Activity', 'Lineage', 'File Copies', 'Related Files'];
    expect(labels('GOOGLE_WORKSPACE')).toEqual(six);
    expect(labels('GOOGLE_DRIVE')).toEqual(six);
  });

  it('gives any other drive type, and none, five tabs', () => {
    expect(labels('OFFICE_365')).toHaveLength(5);
    expect(labels(null)).toEqual(labels('GMAIL'));
  });

  it('uses the tab ids the drawer switches on', () => {
    expect(saasFileTabs('GOOGLE_DRIVE').map(tab => tab.id)).toEqual(['sensitives', 'details', 'activity', 'lineage', 'copies', 'related']);
  });
});
