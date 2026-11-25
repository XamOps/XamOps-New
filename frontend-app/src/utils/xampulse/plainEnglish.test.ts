import { describe, expect, it } from 'vitest';
import { presetFor, translate } from './plainEnglish';
import { parseFilter } from './filterDsl';

describe('plain-English log search', () => {
  it('reads severity, service, instance, a phrase and a time range', () => {
    const t = translate('errors from api on i-0abc12345 in the last hour containing connection reset');
    expect(t.query).toBe('severity:>=error service:api instance:i-0abc12345 "connection reset"');
    expect(t.range).toBe('1h');
    expect(t.understood).toEqual(['errors and worse', 'service api', 'instance i-0abc12345', 'last 1 hour', 'containing "connection reset"']);
  });

  it('keeps quoted phrases, negations, regions and "<name> service"', () => {
    expect(translate('show me warnings for checkout service in us-east-1 not healthcheck "slow query"').query)
      .toBe('severity:>=warn service:checkout region:us-east-1 -healthcheck "slow query"');
    expect(translate('crashes in payments today').query).toBe('severity:fatal service:payments');
    expect(translate('crashes in payments today').range).toBe('24h');
  });

  it('rounds a range up to the next preset so nothing asked for is cut off', () => {
    expect(translate('errors in the past 2 hours').range).toBe('6h');
    expect(translate('timeouts last 3 days').range).toBe('3d');
    expect(translate('oom killed last week').range).toBe('7d');
    expect(translate('errors in the past 2 hours').understood).toContain('last 2 hours (shown: 6h)');
    expect(presetFor(45 * 86_400_000)).toBe('30d');
  });

  it('leaves other words as search words and always produces a query the parser accepts', () => {
    expect(translate('OOMKilled pods').query).toBe('OOMKilled pods');
    for (const s of ['errors from api', 'what happened on i-0123456789abcdef0 last 15 minutes', 'not "a(b" with x:y', 'container web-1 failed', 'last', '']) {
      const q = translate(s).query;
      expect(parseFilter(q).ok, `${s} -> ${q}`).toBe(true);
    }
  });
});
