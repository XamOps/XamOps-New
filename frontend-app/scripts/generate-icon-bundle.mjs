#!/usr/bin/env node
/**
 * Generates src/icons/bundle.ts: the Iconify icons this app actually references, inlined.
 *
 * Why a generated subset rather than the packages themselves: the four installed collections
 * total ~15 MB of JSON (logos 7.1, simple-icons 4.5, mdi 3.0, lucide 0.56). Importing any of
 * them whole would dwarf the app bundle. This app references ~590 icons across them, which
 * inlines to a fraction of that.
 *
 * Why inline at all: @iconify/react otherwise fetches every icon from api.iconify.design at
 * runtime — a third-party request on first paint for each distinct icon, and nothing renders
 * at all without egress to that host.
 *
 * The scan takes any `prefix:name` string literal, not just `icon="..."` props, because most
 * icons here live in lookup maps (CATEGORY_META, ACTION_ICON, FUNNEL_ICON) and `??` fallbacks
 * rather than inline on the element. Verified 2026-08-06: no icon id in this codebase is built
 * by concatenation or template literal, so a literal scan is complete.
 *
 * Icons missing from a collection are reported and skipped, not failed on: the API fallback in
 * main.tsx still resolves them, so a typo degrades to a network fetch instead of a broken build.
 *
 * Regenerate with `npm run icons:bundle`; `npm run build` does it automatically.
 */
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { getIcons } from '@iconify/utils';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(root, 'src');
const OUT = join(SRC, 'icons', 'bundle.ts');

/** Collections installed as devDependencies. A prefix outside this list stays on the API. */
const COLLECTIONS = ['mdi', 'lucide', 'simple-icons', 'logos'];

const SCAN_EXT = new Set(['.ts', '.tsx', '.js', '.jsx']);
const ICON_ID = /['"`]([a-z0-9]+(?:-[a-z0-9]+)*):([a-z0-9]+(?:-[a-z0-9]+)*)['"`]/g;

function walk(dir, files = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'icons') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, files);
    else if (SCAN_EXT.has(extname(entry))) files.push(full);
  }
  return files;
}

const wanted = new Map(COLLECTIONS.map((c) => [c, new Set()]));
for (const file of walk(SRC)) {
  const text = readFileSync(file, 'utf8');
  for (const [, prefix, name] of text.matchAll(ICON_ID)) {
    if (wanted.has(prefix)) wanted.get(prefix).add(name);
  }
}

const chunks = [];
const missing = [];
let kept = 0;

for (const prefix of COLLECTIONS) {
  const names = [...wanted.get(prefix)].sort();
  if (names.length === 0) continue;
  const full = JSON.parse(
    readFileSync(join(root, 'node_modules', `@iconify-json/${prefix}`, 'icons.json'), 'utf8'),
  );
  const subset = getIcons(full, names);
  if (!subset) {
    missing.push(`${prefix}: whole collection could not be subset`);
    continue;
  }
  for (const name of names) {
    if (!subset.icons?.[name] && !subset.aliases?.[name]) missing.push(`${prefix}:${name}`);
  }
  kept += Object.keys(subset.icons ?? {}).length + Object.keys(subset.aliases ?? {}).length;
  chunks.push(`addCollection(${JSON.stringify(subset)} as IconifyJSON);`);
}

const banner = `/* eslint-disable */
// @ts-nocheck
/**
 * GENERATED FILE - do not edit. Run \`npm run icons:bundle\` to regenerate.
 *
 * Inlines only the Iconify icons this app references, so no icon requires a runtime request to
 * api.iconify.design. See scripts/generate-icon-bundle.mjs for why this is generated rather
 * than importing the collections directly (~15 MB of JSON between them).
 */
import { addCollection } from '@iconify/react';
import type { IconifyJSON } from '@iconify/types';

`;

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, banner + chunks.join('\n') + '\n', 'utf8');

const bytes = statSync(OUT).size;
console.log(`[icons] bundled ${kept} icons from ${chunks.length} collection(s) `
  + `-> src/icons/bundle.ts (${(bytes / 1024).toFixed(0)} KB)`);
for (const prefix of COLLECTIONS) {
  const n = wanted.get(prefix).size;
  if (n) console.log(`[icons]   ${prefix}: ${n} referenced`);
}
if (missing.length) {
  console.warn(`[icons] ${missing.length} not found in their collection `
    + `(these fall back to the Iconify API at runtime):`);
  for (const m of missing) console.warn(`[icons]   ${m}`);
}
