import { describe, it, expect } from 'vitest';
import { isAwsMultiAz, isAzureHa } from './multiAz';

describe('isAwsMultiAz', () => {
  it('treats only the string "Yes" as Multi-AZ (the non-empty "No" must not be truthy)', () => {
    expect(isAwsMultiAz('Yes')).toBe(true);
    expect(isAwsMultiAz('No')).toBe(false);
    expect(isAwsMultiAz('')).toBe(false);
    expect(isAwsMultiAz(null)).toBe(false);
    expect(isAwsMultiAz(undefined)).toBe(false);
  });
});

describe('isAzureHa', () => {
  it('maps the Azure zone-redundant value to HA, not the GCP "REGIONAL" value', () => {
    expect(isAzureHa('ZoneRedundant')).toBe(true);
    expect(isAzureHa('None')).toBe(false);
    expect(isAzureHa('REGIONAL')).toBe(false);
    expect(isAzureHa(null)).toBe(false);
  });
});
