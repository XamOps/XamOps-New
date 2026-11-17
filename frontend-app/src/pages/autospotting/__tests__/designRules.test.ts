/**
 * Design-system scan for the AutoSpotting shell (design-ux-ops.md §2.4, AC-UI-05): black, white and
 * grey only, no emoji, no Font Awesome for provider identity, no currency symbols on USD figures.
 * Scans every non-test source file under pages/autospotting.
 */
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(__dirname, '..');

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === '__tests__') continue;
      out.push(...sourceFiles(full));
    } else if (/\.(ts|tsx|css)$/.test(name)) {
      out.push(full);
    }
  }
  return out;
}

const COLOR_CLASS =
  /(?:^|[^a-z])(?:red|orange|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|amber|slate|zinc|stone|neutral)-\d/;
const FORBIDDEN_WORDS = /(?:red|green|amber|blue|emerald|indigo)-/;
const HEX = /#[0-9a-fA-F]{3,8}\b/g;
const EMOJI = /[\p{Extended_Pictographic}\u2190-\u21FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u20E3]/u;
const FONT_AWESOME = /\bfa[srlb]?\s+fa-|\bfa-[a-z]/;
const CURRENCY_SYMBOL = /[\u20B9\u20AC\u00A3\u00A5]/;

function isGreyHex(hex: string): boolean {
  let h = hex.slice(1);
  if (h.length === 3 || h.length === 4) h = h.split('').map(c => c + c).join('');
  const r = h.slice(0, 2);
  const g = h.slice(2, 4);
  const b = h.slice(4, 6);
  return r.toLowerCase() === g.toLowerCase() && g.toLowerCase() === b.toLowerCase();
}

export function violations(text: string): string[] {
  const found: string[] = [];
  if (COLOR_CLASS.test(text)) found.push('non-grey color class');
  if (FORBIDDEN_WORDS.test(text)) found.push('red-|green-|amber-|blue-|emerald-|indigo-');
  for (const m of text.match(HEX) ?? []) {
    if (!isGreyHex(m)) found.push(`non-grey hex ${m}`);
  }
  if (EMOJI.test(text)) found.push('emoji code point');
  if (FONT_AWESOME.test(text)) found.push('Font Awesome icon');
  if (CURRENCY_SYMBOL.test(text)) found.push('currency symbol');
  return found;
}

describe('design rules (AC-UI-05)', () => {
  const files = sourceFiles(ROOT);

  it('scans the shell sources', () => {
    expect(files.length).toBeGreaterThan(15);
  });

  it.each(files.map(f => [f.slice(ROOT.length + 1), f]))('%s has no design-rule violations', (_name, file) => {
    expect(violations(readFileSync(file, 'utf8'))).toEqual([]);
  });

  describe('the scanner itself', () => {
    it('rejects the old violations', () => {
      expect(violations('class="text-red-600"')).not.toEqual([]);
      expect(violations('class="bg-emerald-100"')).not.toEqual([]);
      expect(violations('class="text-indigo-500"')).not.toEqual([]);
      expect(violations('class="border-blue-400"')).not.toEqual([]);
      expect(violations('class="text-amber-500"')).not.toEqual([]);
      expect(violations('class="bg-green-50"')).not.toEqual([]);
      expect(violations("color: '#4f46e5'")).not.toEqual([]);
      expect(violations('<i className="fa fa-magic" />')).not.toEqual([]);
      expect(violations('Save \u{1F680}')).not.toEqual([]);
      expect(violations('Done \u2705')).not.toEqual([]);
      expect(violations('\u20B9 820')).not.toEqual([]);
    });

    it('accepts black, white and grey', () => {
      expect(violations('class="bg-black text-white border-gray-400 text-gray-600"')).toEqual([]);
      expect(violations("color: '#111111'; background: #fff; border: #000")).toEqual([]);
    });
  });
});
