import { describe, expect, it } from 'vitest';
import cases from './__fixtures__/dsl-cases.json';
import { addFacetTerm, parseFilter } from './filterDsl';

// Same fixture file as the Java FilterDslParser tests (copied from xamops-service/src/test/resources/xampulse).
describe('filterDsl parity fixtures', () => {
  it.each(cases.valid)('normalises $input', ({ input, normalised }) => {
    const r = parseFilter(input);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.normalised).toBe(normalised);
  });
  it.each(cases.invalid)('rejects %s', (input) => {
    expect(parseFilter(input).ok).toBe(false);
  });
  it('never throws on injection-shaped input', () => {
    cases.injection.forEach((i) => expect(() => parseFilter(i)).not.toThrow());
  });
  it('enforces length and term limits', () => {
    expect(parseFilter('a'.repeat(2001)).ok).toBe(false);
    expect(parseFilter(Array.from({ length: 41 }, (_, i) => `w${i}`).join(' ')).ok).toBe(false);
  });
});

describe('addFacetTerm', () => {
  it('appends include and exclude terms and quotes values with spaces', () => {
    expect(addFacetTerm('', 'service', 'api', false)).toBe('service:api');
    expect(addFacetTerm('error', 'service', 'my svc', true)).toBe('error -service:"my svc"');
    expect(addFacetTerm('a OR b', 'severity', 'ERROR', false)).toBe('(a OR b) severity:ERROR');
  });
});
