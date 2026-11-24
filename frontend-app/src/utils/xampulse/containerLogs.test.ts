import { describe, expect, it } from 'vitest';
import { MAX_CONTAINER_IDS, containerLogQuery, quotePhrase, severityTotals } from './containerLogs';

describe('containerLogQuery', () => {
  it('scopes to the host and every version of the container', () => {
    expect(containerLogQuery('i-0abc', ['aaa', 'bbb'], 'all', '')).toBe('instance:i-0abc container:(aaa OR bbb)');
    expect(containerLogQuery('i-0abc', ['aaa'], 'all', '')).toBe('instance:i-0abc container:aaa');
  });

  it('adds the severity floor and the search text as one quoted phrase', () => {
    expect(containerLogQuery('i-0abc', ['aaa'], 'error', ' connection refused ')).toBe('instance:i-0abc container:aaa severity:>=error "connection refused"');
    expect(containerLogQuery('i-0abc', ['aaa'], 'warn', '')).toBe('instance:i-0abc container:aaa severity:>=warn');
  });

  it('cannot be broken out of by quotes, parentheses or DSL words in the search', () => {
    expect(quotePhrase('say "hi" \\ OR (x)')).toBe('"say \\"hi\\" \\\\ OR (x)"');
    expect(containerLogQuery('i-1', ['a'], 'all', 'severity:fatal OR x')).toBe('instance:i-1 container:a "severity:fatal OR x"');
  });

  it('keeps the newest container ids when a container was redeployed many times', () => {
    const ids = Array.from({ length: 30 }, (_, i) => `c${i}`);
    const q = containerLogQuery('i-1', ids, 'all', '');
    expect(q).toContain('c29');
    expect(q).not.toContain('c0 ');
    expect((q.match(/ OR /g) ?? []).length).toBe(MAX_CONTAINER_IDS - 1);
  });

  it('stays inside the parser limits with full 64-character ids and a long search', () => {
    const ids = Array.from({ length: 30 }, (_, i) => i.toString(16).padStart(64, 'f'));
    expect(containerLogQuery('i-0123456789abcdef0', ids, 'warn', 'x'.repeat(5000)).length).toBeLessThan(2000);
  });
});

describe('severityTotals', () => {
  it('sums all lines, errors (ERROR + FATAL) and warnings', () => {
    expect(severityTotals({ bucketSec: 60, buckets: [{ t: 'a', counts: { INFO: 10, ERROR: 2 } }, { t: 'b', counts: { WARN: 3, FATAL: 1 } }] }))
      .toEqual({ total: 16, errors: 3, warnings: 3 });
  });
  it('is unknown (null) before the histogram arrives', () => {
    expect(severityTotals(undefined)).toBeNull();
  });
});
