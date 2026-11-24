import { describe, expect, it } from 'vitest';
import { gcpAccessLabel } from './gcpAccessLabel';

describe('gcpAccessLabel', () => {
  it('describes each stored access type', () => {
    expect(gcpAccessLabel('READ_ONLY')).toContain('can plan but not convert');
    expect(gcpAccessLabel('AUTOSPOTTING')).toContain('convert');
    expect(gcpAccessLabel('READ_WRITE')).toContain('start, stop');
  });
  it('returns null when the type is not checked yet', () => {
    expect(gcpAccessLabel(null)).toBeNull();
    expect(gcpAccessLabel(undefined)).toBeNull();
  });
});
