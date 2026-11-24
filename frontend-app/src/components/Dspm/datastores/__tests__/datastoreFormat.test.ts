import { describe, expect, it } from 'vitest';
import {
  RELATIVE_TIME_CUTOFF_DAYS, abbreviate, consoleLinkLabel, extensionOf, formatListDate, formatRiskScore,
  formatScanTime, occurrenceRange, sensitiveDataText, trendBadge,
} from '../datastoreFormat';

const MINUS = '\u2212';
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const NOW = Date.UTC(2026, 10, 5, 12, 0, 0);
/** Epoch seconds `ms` before NOW. */
const ago = (ms: number) => (NOW - ms) / 1000;
const points = (...values: number[]) => values.map((value, i) => ({ date: `2026-10-${10 + i}`, value }));

describe('formatRiskScore', () => {
  it.each([
    [8.44, '8.4'], [8.29, '8.3'], [3, '3.0'], [1, '1.0'], [8.45, '8.5'],
  ])('%s gives "%s"', (score, text) => {
    expect(formatRiskScore(score)).toBe(text);
  });
});

describe('abbreviate', () => {
  it.each([
    [909, '909'], [1_559, '1.56K'], [7_784, '7.78K'], [8_634, '8.63K'], [4_401, '4.4K'], [3_700, '3.7K'],
    [865_871, '866K'], [999_999, '1M'], [0, '0'],
  ])('%s gives "%s"', (n, text) => {
    expect(abbreviate(n)).toBe(text);
  });
});

describe('formatScanTime', () => {
  it('keeps the cut-off in one constant of 30 days', () => {
    expect(RELATIVE_TIME_CUTOFF_DAYS).toBe(30);
  });

  it('floors whole units: 47 hours is "1d ago"', () => {
    expect(formatScanTime(ago(47 * HOUR), NOW)).toBe('1d ago');
  });

  it('23.08 days is "23d ago"', () => {
    expect(formatScanTime(ago(23.08 * DAY), NOW)).toBe('23d ago');
  });

  it('29.99 days is still relative', () => {
    expect(formatScanTime(ago(29.99 * DAY), NOW)).toBe('29d ago');
  });

  it('exactly 30 days is absolute', () => {
    const t = ago(30 * DAY);
    expect(formatScanTime(t, NOW)).toBe(formatListDate(t));
  });

  it('52 days is absolute', () => {
    const t = ago(52 * DAY);
    expect(formatScanTime(t, NOW)).toBe(formatListDate(t));
  });

  it('under a day uses hours, under an hour minutes, under a minute "0m ago"', () => {
    expect(formatScanTime(ago(5.5 * HOUR), NOW)).toBe('5h ago');
    expect(formatScanTime(ago(42 * MINUTE), NOW)).toBe('42m ago');
    expect(formatScanTime(ago(30_000), NOW)).toBe('0m ago');
  });

  it('a future time counts as now', () => {
    expect(formatScanTime((NOW + 2 * HOUR) / 1000, NOW)).toBe('0m ago');
  });

  it('no time gives nothing', () => {
    expect(formatScanTime(null, NOW)).toBeNull();
  });
});

describe('formatListDate', () => {
  // Built from local date parts, so the expectation holds in any time zone.
  it('prints a zero-padded day and an unpadded hour', () => {
    expect(formatListDate(new Date(2026, 8, 12, 20, 51).getTime() / 1000)).toBe('12 Sep 2026, 8:51 PM');
    expect(formatListDate(new Date(2026, 8, 3, 9, 5).getTime() / 1000)).toBe('03 Sep 2026, 9:05 AM');
    expect(formatListDate(new Date(2026, 0, 1, 0, 0).getTime() / 1000)).toBe('01 Jan 2026, 12:00 AM');
  });
});

describe('sensitiveDataText', () => {
  it('shows "-" only for a datastore that was never scanned', () => {
    expect(sensitiveDataText('NOT_SCANNED', 0)).toBe('-');
  });

  it('shows "0" for a failed scan with nothing found', () => {
    expect(sensitiveDataText('FAILED', 0)).toBe('0');
  });

  it('abbreviates any other count', () => {
    expect(sensitiveDataText('CLASSIFIED', 1_559)).toBe('1.56K');
    expect(sensitiveDataText(null, 909)).toBe('909');
  });
});

describe('trendBadge', () => {
  it('rounds a rising percentage up: 896,676 to 896,678 is "+1%"', () => {
    expect(trendBadge(points(896_676, 896_678), 'percent')).toEqual({ text: '+1%', direction: 'up' });
  });

  it('7 to 9 is "+29%"', () => {
    expect(trendBadge(points(7, 9), 'percent')).toEqual({ text: '+29%', direction: 'up' });
  });

  it('a rising delta is last minus first: 135 to 154 and 67 to 86 are "+19"', () => {
    expect(trendBadge(points(135, 154), 'delta')).toEqual({ text: '+19', direction: 'up' });
    expect(trendBadge(points(67, 80, 86), 'delta')).toEqual({ text: '+19', direction: 'up' });
  });

  it('a flat trend has no badge', () => {
    expect(trendBadge(points(12, 15, 12), 'percent')).toBeNull();
    expect(trendBadge(points(12, 12), 'delta')).toBeNull();
  });

  it('a falling percentage is "−N%" and points down', () => {
    expect(trendBadge(points(200, 150), 'percent')).toEqual({ text: `${MINUS}25%`, direction: 'down' });
    expect(trendBadge(points(9, 7), 'percent')).toEqual({ text: `${MINUS}23%`, direction: 'down' });
  });

  it('a falling delta is "−N" and points down: 30 to 27 is "−3"', () => {
    expect(trendBadge(points(30, 27), 'delta')).toEqual({ text: `${MINUS}3`, direction: 'down' });
  });

  it('a first point of 0 hides the percentage', () => {
    expect(trendBadge(points(0, 5), 'percent')).toBeNull();
    expect(trendBadge(points(0, 5), 'delta')).toEqual({ text: '+5', direction: 'up' });
  });

  it('fewer than two points hide the badge', () => {
    expect(trendBadge(points(5), 'percent')).toBeNull();
    expect(trendBadge([], 'delta')).toBeNull();
  });
});

describe('occurrenceRange', () => {
  it('skip 70, limit 10, total 72 is 71 - 72 of 72', () => {
    expect(occurrenceRange(70, 10, 72)).toEqual({ from: 71, to: 72, total: 72 });
  });

  it('nothing to show for an empty total', () => {
    expect(occurrenceRange(0, 10, 0)).toBeNull();
  });
});

describe('consoleLinkLabel', () => {
  it('names AWS for aws.amazon.com and its subdomains', () => {
    expect(consoleLinkLabel('https://console.aws.amazon.com/s3/buckets/example')).toBe('Go to AWS');
    expect(consoleLinkLabel('https://aws.amazon.com/')).toBe('Go to AWS');
  });

  it('says "Go to console" for any other https host, including lookalikes', () => {
    expect(consoleLinkLabel('https://portal.azure.com/#resource/example')).toBe('Go to console');
    expect(consoleLinkLabel('https://notaws.amazon.com/')).toBe('Go to console');
    expect(consoleLinkLabel('https://aws.amazon.com.example.org/')).toBe('Go to console');
  });

  it('renders no link for anything that is not an absolute https URL', () => {
    expect(consoleLinkLabel('http://console.aws.amazon.com/')).toBeNull();
    expect(consoleLinkLabel('javascript:alert(1)')).toBeNull();
    expect(consoleLinkLabel('/relative/path')).toBeNull();
    expect(consoleLinkLabel(null)).toBeNull();
  });
});

describe('extensionOf', () => {
  it('lower-cases the text after the last dot of the basename', () => {
    expect(extensionOf('reports/2026/Q3.PDF')).toBe('pdf');
    expect(extensionOf('archive.tar.gz')).toBe('gz');
  });

  it('is null without an extension', () => {
    expect(extensionOf('README')).toBeNull();
    expect(extensionOf('release.v2/notes')).toBeNull();
    expect(extensionOf('trailing.')).toBeNull();
    expect(extensionOf('')).toBeNull();
  });
});
