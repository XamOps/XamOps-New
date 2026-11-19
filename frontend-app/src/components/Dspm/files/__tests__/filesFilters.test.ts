import { describe, expect, it } from 'vitest';
import { filesValueAllowed } from '../filesFilters';

describe('filesValueAllowed', () => {
  it('takes an owner as a 24-hex id only, not as an e-mail', () => {
    expect(filesValueAllowed('owner')('64b7f0c2a1d3e4f5a6b7c8d9')).toBe(true);
    expect(filesValueAllowed('owner')('64B7F0C2A1D3E4F5A6B7C8D9')).toBe(true);
    expect(filesValueAllowed('owner')('owner@example.com')).toBe(false);
    expect(filesValueAllowed('owner')('64b7f0c2a1d3e4f5a6b7c8d')).toBe(false);
  });

  it.each(['appType', 'sensitives'])('takes %s values as upper-case codes', dimension => {
    expect(filesValueAllowed(dimension)('GOOGLE_WORKSPACE')).toBe(true);
    expect(filesValueAllowed(dimension)('PII')).toBe(true);
    expect(filesValueAllowed(dimension)('Personal')).toBe(false);
    expect(filesValueAllowed(dimension)('1PII')).toBe(false);
  });

  it.each(['internalUser', 'externalUser'])('takes %s values up to 320 characters', dimension => {
    const local = 'a'.repeat(308);
    expect(filesValueAllowed(dimension)(`${local}@example.com`)).toBe(true);
    expect(filesValueAllowed(dimension)(`${local}a@example.com`)).toBe(false);
    expect(filesValueAllowed(dimension)('')).toBe(false);
  });

  it('takes any other value up to 128 characters', () => {
    expect(filesValueAllowed('category')('a'.repeat(128))).toBe(true);
    expect(filesValueAllowed('category')('a'.repeat(129))).toBe(false);
    expect(filesValueAllowed('documentClass')('')).toBe(false);
    expect(filesValueAllowed('hostname')('build-runner-01')).toBe(true);
  });

  it('takes sharing and lastModified only as their choices', () => {
    expect(['internal', 'external', 'public', 'any'].map(filesValueAllowed('sharing'))).toEqual([true, true, true, false]);
    expect(['48h', '7d', '30d', '90d'].map(filesValueAllowed('lastModified'))).toEqual([true, true, true, false]);
  });

  it('refuses a comma, surrounding spaces and control characters everywhere', () => {
    for (const dimension of ['category', 'documentClass', 'internalUser', 'tag']) {
      expect(filesValueAllowed(dimension)('a,b')).toBe(false);
      expect(filesValueAllowed(dimension)(' padded')).toBe(false);
      expect(filesValueAllowed(dimension)('padded ')).toBe(false);
      expect(filesValueAllowed(dimension)('tab\there')).toBe(false);
      expect(filesValueAllowed(dimension)('del\u007f')).toBe(false);
    }
  });
});
