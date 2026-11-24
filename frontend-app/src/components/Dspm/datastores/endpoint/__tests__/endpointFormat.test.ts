import { describe, expect, it } from 'vitest';
import {
  EMPTY_FILTER_PARAMS, ENDPOINT_FILTER_DIMENSIONS, endpointFilterValueAllowed, filterParamsOf, sentenceCase,
  utilizationText,
} from '../endpointFormat';

describe('sentenceCase', () => {
  it('upper-cases the first letter and lower-cases the rest', () => {
    expect(sentenceCase('EMAIL ADDRESS')).toBe('Email address');
    expect(sentenceCase('PERSON NAME')).toBe('Person name');
    expect(sentenceCase('ip ADDRESS')).toBe('Ip address');
    expect(sentenceCase('X')).toBe('X');
    expect(sentenceCase('')).toBe('');
  });
});

describe('utilizationText', () => {
  it('reads a whole percent while the agent is online (A17)', () => {
    expect(utilizationText(0.2546, true)).toBe('25%');
    expect(utilizationText(0.61, true)).toBe('61%');
    expect(utilizationText(0, true)).toBe('0%');
    expect(utilizationText(1, true)).toBe('100%');
  });

  it('reads "-" for an offline agent, a missing value or a non-finite one', () => {
    expect(utilizationText(0.2546, false)).toBe('-');
    expect(utilizationText(0, false)).toBe('-');
    expect(utilizationText(null, true)).toBe('-');
    expect(utilizationText(Number.NaN, true)).toBe('-');
    expect(utilizationText(Number.POSITIVE_INFINITY, true)).toBe('-');
  });
});

describe('endpointFilterValueAllowed', () => {
  const allowed = (dimension: string, value: string) => endpointFilterValueAllowed(dimension)(value);

  it('accepts well-formed values of every dimension', () => {
    expect(allowed('sensitives', 'PII')).toBe(true);
    expect(allowed('sensitives', 'IT_SECURITY')).toBe(true);
    expect(allowed('documentClass', 'incident reports')).toBe(true);
    expect(allowed('category', 'it & technical')).toBe(true);
    expect(allowed('dataElement', 'EMAIL ADDRESS')).toBe(true);
  });

  it('refuses a comma, surrounding spaces and a control character on every dimension', () => {
    const base: Record<string, string> = {
      sensitives: 'PII', documentClass: 'incident reports', category: 'it & technical', dataElement: 'EMAIL ADDRESS',
    };
    for (const dimension of ENDPOINT_FILTER_DIMENSIONS) {
      const value = base[dimension];
      expect(allowed(dimension, value)).toBe(true);
      expect(allowed(dimension, `${value},${value}`)).toBe(false);
      expect(allowed(dimension, ` ${value}`)).toBe(false);
      expect(allowed(dimension, `${value} `)).toBe(false);
      expect(allowed(dimension, `${value.slice(0, 1)}\u0007${value.slice(1)}`)).toBe(false);
      expect(allowed(dimension, `${value.slice(0, 1)}\t${value.slice(1)}`)).toBe(false);
      expect(allowed(dimension, `${value.slice(0, 1)}\u0085${value.slice(1)}`)).toBe(false);
    }
  });

  it('refuses an empty or blank value and one over 128 characters', () => {
    expect(allowed('documentClass', '')).toBe(false);
    expect(allowed('documentClass', '   ')).toBe(false);
    expect(allowed('documentClass', 'a'.repeat(128))).toBe(true);
    expect(allowed('documentClass', 'a'.repeat(129))).toBe(false);
  });

  it('accepts only an upper-case code for sensitives', () => {
    expect(allowed('sensitives', 'pii')).toBe(false);
    expect(allowed('sensitives', 'Personal')).toBe(false);
    expect(allowed('sensitives', '1PII')).toBe(false);
    expect(allowed('sensitives', 'PII-X')).toBe(false);
    expect(allowed('sensitives', `P${'I'.repeat(63)}`)).toBe(true);
    expect(allowed('sensitives', `P${'I'.repeat(64)}`)).toBe(false);
    // The code rule is the sensitives dimension's only.
    expect(allowed('documentClass', 'pii')).toBe(true);
  });
});

describe('filterParamsOf', () => {
  it('sends nothing for an empty state', () => {
    expect(filterParamsOf({})).toEqual(EMPTY_FILTER_PARAMS);
  });

  it('takes the include values of the four dimensions and nothing else', () => {
    expect(filterParamsOf({
      sensitives: { mode: 'include', values: ['PII', 'PCI'] },
      documentClass: { mode: 'include', values: ['incident reports', 'system documentation'] },
      category: { mode: 'include', values: [] },
      dataElement: { mode: 'include', values: ['EMAIL ADDRESS'] },
      provider: { mode: 'include', values: ['S3'] },
    })).toEqual({
      sensitives: ['PII', 'PCI'],
      documentClass: ['incident reports', 'system documentation'],
      category: [],
      dataElement: ['EMAIL ADDRESS'],
    });
  });

  it('leaves out an exclude selection', () => {
    expect(filterParamsOf({ sensitives: { mode: 'exclude', values: ['PII'] } })).toEqual(EMPTY_FILTER_PARAMS);
  });
});
