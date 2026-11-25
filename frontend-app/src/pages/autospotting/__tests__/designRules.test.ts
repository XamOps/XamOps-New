/**
 * Design-system scan for the AutoSpotting shell (design-ux-ops.md §2.4, AC-UI-05, revised 2026-11-22): the
 * app's indigo house style. Status colour only from the palette (emerald, amber, red/rose, indigo, grey),
 * no gradients, no raw hex/rgb colours except translucent black, no emoji, no Font Awesome, no currency
 * symbols on USD figures. Scans every non-test source file under pages/autospotting.
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

/** Status palette of the app's indigo house style: emerald good, amber warning, red/rose failure, indigo accent. */
const OFF_PALETTE_CLASS =
  /(?:^|[^a-z])(?:orange|yellow|lime|green|teal|cyan|sky|blue|violet|purple|fuchsia|pink|slate|zinc|stone|neutral)-\d/;
const GRADIENT = /\bbg-gradient-|\b(?:from|via|to)-(?:[a-z]+-\d|\[)/;
const HEX = /#[0-9a-fA-F]{3,8}\b/;
/** Only translucent black (`rgba(0,0,0,x)`) is allowed as a raw colour; anything else comes from the palette. */
const RAW_RGB = /\brgba?\((?!\s*0\s*,\s*0\s*,\s*0\s*,)/;
const EMOJI = /[\p{Extended_Pictographic}\u2190-\u21FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u20E3]/u;
const FONT_AWESOME = /\bfa[srlb]?\s+fa-|\bfa-[a-z]/;
const CURRENCY_SYMBOL = /[\u20B9\u20AC\u00A3\u00A5]/;
export function violations(text: string): string[] {
  const found: string[] = [];
  if (OFF_PALETTE_CLASS.test(text)) found.push('colour class outside the palette');
  if (GRADIENT.test(text)) found.push('gradient');
  if (HEX.test(text)) found.push('hex colour');
  if (RAW_RGB.test(text)) found.push('raw rgb colour');
  if (EMOJI.test(text)) found.push('emoji code point');
  if (FONT_AWESOME.test(text)) found.push('Font Awesome icon');
  if (CURRENCY_SYMBOL.test(text)) found.push('currency symbol');
  return found;
}
describe('design rules (AC-UI-05, style A)', () => {
  const files = sourceFiles(ROOT);

  it('scans the shell sources', () => {
    expect(files.length).toBeGreaterThan(15);
  });

  it.each(files.map(f => [f.slice(ROOT.length + 1), f]))('%s has no design-rule violations', (_name, file) => {
    expect(violations(readFileSync(file, 'utf8'))).toEqual([]);
  });

  describe('the scanner itself', () => {
    it('rejects off-palette colours, gradients, raw colours, emoji, Font Awesome and currency symbols', () => {
      expect(violations('class="text-blue-600"')).not.toEqual([]);
      expect(violations('class="bg-green-100"')).not.toEqual([]);
      expect(violations('class="border-purple-400"')).not.toEqual([]);
      expect(violations('class="text-slate-500"')).not.toEqual([]);
      expect(violations('class="bg-gradient-to-r from-indigo-500 to-emerald-500"')).not.toEqual([]);
      expect(violations("color: '#4f46e5'")).not.toEqual([]);
      expect(violations('class="bg-[#111111]"')).not.toEqual([]);
      expect(violations("color: 'rgb(79, 70, 229)'")).not.toEqual([]);
      expect(violations('class="text-[rgba(255,255,255,0.7)]"')).not.toEqual([]);
      expect(violations('<i className="fa fa-magic" />')).not.toEqual([]);
      expect(violations('Save \u{1F680}')).not.toEqual([]);
      expect(violations('Done \u2705')).not.toEqual([]);
      expect(violations('\u20B9 820')).not.toEqual([]);
    });
    it('accepts the house palette and translucent black', () => {
      expect(violations('class="bg-indigo-600 text-white hover:bg-indigo-700"')).toEqual([]);
      expect(violations('class="border-emerald-200 bg-emerald-50 text-emerald-700"')).toEqual([]);
      expect(violations('class="bg-amber-50 text-amber-900 bg-red-50 text-red-700 text-rose-600"')).toEqual([]);
      expect(violations('class="text-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.12)] text-gray-600 top-2"')).toEqual([]);
    });
  });
});
