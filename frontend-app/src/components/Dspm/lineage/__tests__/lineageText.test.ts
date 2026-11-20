import { describe, expect, it } from 'vitest';
import {
  PANEL_COPY, compactCount, countText, exposureCountText, formatEventTime, formatNodeDay, rowEmptyText, rowLoadingText,
} from '../lineageText';
import { CATEGORY_ICONS, datastoreIcon, policyIcon, severityDot } from '../lineageVisuals';

/** Epoch seconds of a local time, so expectations hold in every time zone (CI runs in UTC). */
const local = (year: number, month: number, day: number, hours = 0, minutes = 0) =>
  new Date(year, month - 1, day, hours, minutes).getTime() / 1000;

describe('compactCount', () => {
  it.each([
    [0, '0'], [999, '999'], [1_000, '1k'], [1_234, '1.2k'], [12_345, '12.3k'], [999_949, '999.9k'],
    [999_950, '1m'], [1_200_000, '1.2m'],
  ])('%d → %s', (n, text) => {
    expect(compactCount(n)).toBe(text);
  });
});

describe('count texts', () => {
  it('uses the singular for exactly 1', () => {
    expect(exposureCountText(1)).toBe('1 exposure');
    expect(exposureCountText(0)).toBe('0 exposures');
    expect(exposureCountText(1_234)).toBe('1.2k exposures');
    expect(countText(1, 'datastore')).toBe('1 datastore');
    expect(countText(3, 'datastore')).toBe('3 datastores');
    expect(countText(1, 'file')).toBe('1 file');
    expect(countText(10, 'file')).toBe('10 files');
  });
});

describe('panel copy and row messages', () => {
  it('has the heading, help and placeholder per open level', () => {
    expect(PANEL_COPY.policy).toEqual({
      heading: 'Choose a policy', help: 'Select a policy to view the datastores it affects.', placeholder: 'Search policies',
    });
    expect(PANEL_COPY.datastore.heading).toBe('Choose a datastore');
    expect(PANEL_COPY.datastore.help).toBe('Select a datastore to view the affected files it contains.');
    expect(PANEL_COPY.file.placeholder).toBe('Search files');
    expect(PANEL_COPY.event).toEqual({
      heading: 'File lineage', help: 'Trace where this file came from.', placeholder: 'Search events',
    });
  });

  it('picks the empty text by search, filters, then level', () => {
    expect(rowEmptyText('policy', '', false)).toBe('No exposures found for this category.');
    expect(rowEmptyText('datastore', '', false)).toBe('No datastores found.');
    expect(rowEmptyText('file', '', false)).toBe('No files found.');
    expect(rowEmptyText('policy', 'pii', true)).toBe('No policies match “pii”.');
    expect(rowEmptyText('policy', '', true)).toBe('No policies match the applied filters.');
    expect(rowEmptyText('datastore', '', true)).toBe('No datastores match the applied filters.');
    expect(rowLoadingText('policy')).toBe('Loading policies…');
    expect(rowLoadingText('file')).toBe('Loading files…');
  });
});

describe('dates', () => {
  it('formats a node day as "6 Apr 2026"', () => {
    expect(formatNodeDay(local(2026, 4, 6, 11, 52))).toBe('6 Apr 2026');
    expect(formatNodeDay(local(2026, 11, 17))).toBe('17 Nov 2026');
  });

  it('formats an event time as "06 Apr 2026 • 11:52", 24 h', () => {
    expect(formatEventTime(local(2026, 4, 6, 11, 52))).toBe('06 Apr 2026 • 11:52');
    expect(formatEventTime(local(2026, 6, 26, 23, 5))).toBe('26 Jun 2026 • 23:05');
    expect(formatEventTime(local(2026, 1, 2, 0, 0))).toBe('02 Jan 2026 • 00:00');
  });
});

describe('visuals', () => {
  it('has an icon per category and per service, and a dot colour per severity', () => {
    expect(Object.keys(CATEGORY_ICONS)).toHaveLength(6);
    expect(CATEGORY_ICONS.SENSITIVE_FINDINGS).toBe('mdi:fingerprint');
    expect(CATEGORY_ICONS.PUBLIC_EXPOSURE).toBe('mdi:earth');
    expect(policyIcon('ENDPOINT')).toBe('mdi:devices');
    expect(policyIcon(null)).toBe('mdi:shield-outline');
    expect(datastoreIcon('ENDPOINT')).toBe('mdi:laptop');
    expect(datastoreIcon('SHARE_POINT')).toBe('mdi:microsoft-sharepoint');
    expect(datastoreIcon('S3')).toBe('mdi:database-outline');
    expect(severityDot('MEDIUM')).toBe('bg-amber-500');
    expect(severityDot('CRITICAL')).toBe('bg-rose-600');
    expect(severityDot(null)).toBe('bg-[rgba(0,0,0,0.2)]');
  });
});
