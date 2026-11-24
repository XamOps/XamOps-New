import { describe, expect, it } from 'vitest';
import {
  fileExtensionLabel, formatConsoleDay, formatLineageTime, formatRelativeLong, initialOf, percentShare,
} from '../saasFormat';

/** Epoch seconds of a local time, so the expectations hold in every time zone. */
const local = (year: number, month: number, day: number, hours = 0, minutes = 0) =>
  new Date(year, month - 1, day, hours, minutes).getTime() / 1000;

const NOW_MS = Date.UTC(2026, 10, 17, 12, 0, 0);
const ago = (seconds: number) => NOW_MS / 1000 - seconds;
const DAY = 86_400;

describe('formatConsoleDay', () => {
  it('prints a zero-padded day, a short month and the year', () => {
    expect(formatConsoleDay(local(2026, 9, 8, 23, 59))).toBe('08 Sep 2026');
    expect(formatConsoleDay(local(2027, 1, 21))).toBe('21 Jan 2027');
  });
});

describe('formatLineageTime', () => {
  it('prints an unpadded day, the en-GB month and a 24-hour time', () => {
    expect(formatLineageTime(local(2026, 9, 10, 12, 54))).toBe('10 Sept 2026, 12:54');
    expect(formatLineageTime(local(2026, 6, 3, 7, 5))).toBe('3 Jun 2026, 07:05');
    expect(formatLineageTime(local(2026, 12, 31, 23, 0))).toBe('31 Dec 2026, 23:00');
  });
});

describe('formatRelativeLong', () => {
  it.each([
    [0, 'a few seconds ago'],
    [44, 'a few seconds ago'],
    [45, 'a minute ago'],
    [89, 'a minute ago'],
    [90, '2 minutes ago'],
    [44 * 60, '44 minutes ago'],
    [45 * 60, 'an hour ago'],
    [89 * 60, 'an hour ago'],
    [5_370, 'an hour ago'],   // 89.5 minutes rounds to 90, then to 1 hour
    [3 * 3600, '3 hours ago'],
    [21 * 3600, '21 hours ago'],
    [22 * 3600, 'a day ago'],
    [35 * 3600, 'a day ago'],
    [36 * 3600, '2 days ago'],
    [25 * DAY, '25 days ago'],
    [26 * DAY, 'a month ago'],
    [45 * DAY, 'a month ago'],
    [46 * DAY, '2 months ago'],
    [68 * DAY, '2 months ago'],
    [300 * DAY, '10 months ago'],
    [320 * DAY, 'a year ago'],
    [540 * DAY, 'a year ago'],
    [560 * DAY, '2 years ago'],
    [3 * 365 * DAY, '3 years ago'],
  ])('reads %i seconds as "%s"', (seconds, text) => {
    expect(formatRelativeLong(ago(seconds), NOW_MS)).toBe(text);
  });

  it('reads a future time as now', () => {
    expect(formatRelativeLong(ago(-3 * DAY), NOW_MS)).toBe('a few seconds ago');
  });
});

describe('percentShare', () => {
  it('gives two decimals', () => {
    expect(percentShare(18_161, 18_415)).toBe('98.62%');
    expect(percentShare(254, 18_415)).toBe('1.38%');
    expect(percentShare(0, 7)).toBe('0.00%');
  });

  it('gives "0%" for a zero total', () => {
    expect(percentShare(0, 0)).toBe('0%');
  });
});

describe('fileExtensionLabel', () => {
  it('upper-cases a short alphanumeric extension', () => {
    expect(fileExtensionLabel('travel-itinerary.pdf')).toBe('PDF');
    expect(fileExtensionLabel('offer letter.DOCX')).toBe('DOCX');
    expect(fileExtensionLabel('archive.tar.gz')).toBe('GZ');
    expect(fileExtensionLabel('folder/notes.md')).toBe('MD');
  });

  it('gives null when there is no usable extension', () => {
    expect(fileExtensionLabel('Re: v1.2 notes')).toBeNull();
    expect(fileExtensionLabel('quarterly summary')).toBeNull();
    expect(fileExtensionLabel('trailing.')).toBeNull();
    expect(fileExtensionLabel('data.verylongext')).toBeNull();
    expect(fileExtensionLabel(null)).toBeNull();
  });
});

describe('initialOf', () => {
  it('takes the first letter, upper-cased', () => {
    expect(initialOf('pat@example.com')).toBe('P');
    expect(initialOf('  sam lee')).toBe('S');
  });

  it('gives "?" for none', () => {
    expect(initialOf(null)).toBe('?');
    expect(initialOf('   ')).toBe('?');
  });
});
