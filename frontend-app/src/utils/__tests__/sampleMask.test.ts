import { describe, expect, it } from 'vitest';
import { SAMPLE_MASK, isSecretEntity, maskSample, maskSampleFor, maskSecretSample } from '../sampleMask';

/* Build 9 design sections 2.1 and 2.2; the same vectors as xamops-service SampleMaskTest. */

const HALF: [string, string][] = [
  ['', ''],
  ['a', '•'], ['ab', '••'], ['abc', '•••'],
  ['abcd', 'ab••'], ['abcde', 'ab•••'], ['abcdef', 'abc•••'],
  ['Jane Doe', 'Ja•• D••'],
  ['<<Jane Doe>>', '<<Ja•• D••>>'],
  ['<<Sukhbir Singh>>', '<<Suk•••• Si•••>>'],
  ['jane.doe@example.com', 'ja••.d••@exa••••.c••'],
  ['a@b.co', '•@•.c•'],
  ['+1 555 0100', '•••••• 0100'],
  ['4111 1111 1111 1111', '•••• •••• •••• 1111'],
  ['4111-1111-1111-1111', '••••-••••-••••-1111'],
  ['1234', '••34'], ['12345', '•••45'],
  ['192.168.1.10', '••••••8.1.10'],
  ['AKIAIOSFODNN7EXAMPLE', 'AKIAIOSFOD••••••••••'],
  ['Zo\u00eb \u00c5ngstr\u00f6m', 'Z•• \u00c5ngs••••'],
  ['日本語テキスト', '日本語••••'],
  ['\u{1F44D}\u{1F3FD}\u{1F44D}\u{1F3FD}', '\u{1F44D}\u{1F3FD}••'],
  ['J*** D**', 'J••• D••'],
  ['j*******@e******.com', 'j•••••••@e••••••.com'],
  ['+1 ***-***-0142', '+• •••-•••-••42'],
  ['********4021', '••••••••4021'],
  ['*****1234*', '•••••1234•'],
  ['  r***@example.test ', '  r•••@ex•••••.t••• '],
  ['<<ab>>', '<<••>>'], ['<<>>', '<<>>'],
  ['<<SYNTHETIC-SAMPLE-7F3A>>', '<<SYNT•••••-SAM•••-7•••>>'],
  ['<<Second Sample>>', '<<Sec••• Sa••••>>'],
  ['SYNTHMARKER-a', 'SYNTH••••••-•'],
  ['j***@e******.org', 'j•••@e••••••.org'],
  ['********', '••••••••'],
  ['<<PLANTED-RAW-9Q4Z-SAMPLE>>', '<<PLA••••-R••-9Q••-SA••••>>'],
  ['sample-0', 'sam•••-•'], ['sample-19', 'sa••••-1•'],
  ['pat@example.com', 'p••@exa••••.c••'],
  ['ab12cd34', 'ab12••••'],
];

const SECRET: [string, string][] = [
  ['', ''], ['abc', '•••'],
  ['abcd', 'ab••'], ['abcde', 'ab•••'], ['abcdefgh', 'abcd••••'],
  ['AKIAIOSFODNN7EXAMPLE', 'AKIA••••••••••••••••'],
  ['AKIA************DEMO', 'AKIA••••••••••••••••'],
  ['<<AKIA…DEMO1>>', '<<AKIA••••••>>'],
  ['<<9f2c…e41a>>', '<<9f2c•••••>>'],
  ['$2b$12$abcdefghij', '$2b$•••••••••••••'],
  ['ghp_x1Y2z3W4v5U6', 'ghp_••••••••••••'],
  ['<<PLANTED-RAW-9Q4Z-SAMPLE>>', '<<PLAN•••••••••••••••••••>>'],
  ['<<SYNTHETIC-SAMPLE-7F3A>>', '<<SYNT•••••••••••••••••>>'],
];

/** The part the rule counts: the inside of a `<<…>>` frame, else the whole value. */
function counted(value: string): string[] {
  const cps = Array.from(value);
  const n = cps.length;
  return n >= 4 && value.startsWith('<<') && value.endsWith('>>') ? cps.slice(2, n - 2) : cps;
}
const masks = (cps: string[]) => cps.filter(c => c === SAMPLE_MASK).length;
function expectHalf(out: string) {
  const part = counted(out);
  if (part.length < 4) expect(masks(part)).toBe(part.length);
  else expect(masks(part)).toBeGreaterThanOrEqual(Math.ceil(part.length / 2));
}

describe('maskSample (half rule)', () => {
  it.each(HALF)('%j → %j, same length, at least half masked, idempotent, no *', (input, expected) => {
    const out = maskSample(input);
    expect(out).toBe(expected);
    expect(Array.from(out)).toHaveLength(Array.from(input).length);
    expectHalf(out);
    expect(maskSample(out)).toBe(out);
    expect(out).not.toContain('*');
  });
});

describe('maskSecretSample (secret rule)', () => {
  it.each(SECRET)('%j → %j, at most 4 visible, at least half masked, idempotent', (input, expected) => {
    const out = maskSecretSample(input);
    expect(out).toBe(expected);
    expect(Array.from(out)).toHaveLength(Array.from(input).length);
    const part = counted(out);
    expect(part.length - masks(part)).toBeLessThanOrEqual(4);
    expectHalf(out);
    expect(maskSecretSample(out)).toBe(out);
    expect(maskSample(maskSecretSample(input))).toBe(out);
    expect(out).not.toContain('*');
  });
});

describe('isSecretEntity', () => {
  it.each([
    ['ACCESS KEY AWS', ['SECRETS'], true],
    ['SHA KEY', [], true],
    ['HASHED SECRET KEY', null, true],
    ['DB PASSWORD', [], true],
    ['GITHUB TOKEN', [], true],
    ['NEW DETECTOR', ['secrets'], true],
    ['X', ['IT & Security'], true],
    ['PERSON NAME', ['PII'], false],
    ['EMAIL ADDRESS', ['PII'], false],
    ['MONKEY NAME', [], false],
    ['', [], false],
    [null, null, false],
  ] as [string | null, string[] | null, boolean][])('isSecretEntity(%j, %j) = %s', (name, labels, expected) => {
    expect(isSecretEntity(name, labels)).toBe(expected);
  });

  it('maskSampleFor picks the secret rule for a secret type, else the half rule', () => {
    expect(maskSampleFor('ACCESS KEY AWS', ['IT & Security'], '<<AKIA…DEMO1>>')).toBe('<<AKIA••••••>>');
    expect(maskSampleFor('PERSON NAME', ['Personal'], '<<Jane Doe>>')).toBe('<<Ja•• D••>>');
  });
});
