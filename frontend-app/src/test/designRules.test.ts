// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The CloudK8s design-rule guard (docs/cloudk8s/ui-revamp-design.md §14, §14.1; design-review NIT 9).
 *
 * CLAUDE.md governs: black, white and grey only, no gradients, no emoji, bundled Iconify icons
 * rather than Font Awesome, and status never carried by colour alone. Every rule fails with
 * `file:line`. There is no inline escape hatch: a false positive is fixed by renaming it.
 *
 * Scope grows by slice (plan P3). In FEAT-002 it holds only the paths FEAT-002 creates; each later
 * item appends its own, and FEAT-004 widens it to the whole CloudK8s tree.
 */

const SRC = fileURLToPath(new URL('..', import.meta.url));

/** Files or folders (trailing slash) under src/, relative, POSIX separators. */
export const GUARDED_PATHS: readonly string[] = [
  'components/CloudK8s/shared/',
];

const TAILWIND_PREFIX = '(?:text|bg|border|from|via|to|ring|fill|stroke|divide|outline|shadow|accent|decoration|placeholder|caret)';
const CHROMATIC = '(?:slate|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)';

export interface Rule {
  id: string;
  /** Why it fails, shown with file:line. */
  message: string;
  test: (line: string) => boolean;
}

function hexDiffers(hex: string): boolean {
  const h = hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex;
  const r = h.slice(0, 2).toLowerCase();
  return r !== h.slice(2, 4).toLowerCase() || r !== h.slice(4, 6).toLowerCase();
}

export const RULES: Rule[] = [
  {
    id: 'tailwind-hue',
    message: 'Tailwind colour utility in a chromatic family or slate (use the neutral ladder)',
    test: (line) => new RegExp(`\\b${TAILWIND_PREFIX}(?:-[trblxyse])?-${CHROMATIC}-(?:50|[1-9]00|950)\\b`).test(line),
  },
  {
    id: 'hex-colour',
    message: 'non-grey #rgb / #rrggbb colour',
    test: (line) => {
      const re = /#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-zA-Z_-])/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(line)) !== null) {
        if (hexDiffers(m[1])) return true;
      }
      return false;
    },
  },
  {
    id: 'rgb-colour',
    message: 'chromatic rgb() / rgba() colour (channels must be equal)',
    test: (line) => {
      const re = /rgba?\(\s*(\d{1,3})\s*[,\s]\s*(\d{1,3})\s*[,\s]\s*(\d{1,3})/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(line)) !== null) {
        if (m[1] !== m[2] || m[2] !== m[3]) return true;
      }
      return false;
    },
  },
  {
    id: 'gradient',
    message: 'gradient',
    test: (line) => /bg-gradient-|linear-gradient\(/.test(line),
  },
  {
    id: 'emoji',
    message: 'emoji or pictograph',
    test: (line) => /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}]/u.test(line),
  },
  {
    id: 'font-awesome',
    message: 'Font Awesome class (use a bundled mdi:* icon)',
    test: (line) => /\bfa[srb]? fa-[a-z0-9-]+/.test(line),
  },
  {
    id: 'template-colour',
    message: 'template-built Tailwind colour class (NIT 9)',
    test: (line) => new RegExp(`\\b${TAILWIND_PREFIX}-\\$\\{`).test(line),
  },
  {
    id: 'font-awesome-data',
    message: 'Font Awesome name held as data (NIT 9)',
    test: (line) => /['"`]fa-[a-z0-9-]+['"`]|\bfa[srb]?\s+\$\{/.test(line),
  },
];

export interface Violation {
  file: string;
  line: number;
  rule: string;
  text: string;
}

export function scanText(file: string, text: string): Violation[] {
  const out: Violation[] = [];
  text.split('\n').forEach((line, index) => {
    for (const rule of RULES) {
      if (rule.test(line)) out.push({ file, line: index + 1, rule: rule.id, text: line.trim() });
    }
  });
  return out;
}

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

const toPosix = (p: string) => p.split(sep).join('/');
const isSource = (file: string) => /\.(ts|tsx|css)$/.test(file) && !/\.test\.|\.spec\./.test(file) && !file.includes('__tests__/');

export function guardedFiles(): string[] {
  const files = new Set<string>();
  for (const entry of GUARDED_PATHS) {
    const full = join(SRC, entry);
    if (entry.endsWith('/')) {
      for (const f of walk(full)) {
        const rel = toPosix(relative(SRC, f));
        if (isSource(rel)) files.add(rel);
      }
    } else {
      files.add(entry);
    }
  }
  return [...files].sort();
}

describe('design-rule guard: self-tests', () => {
  const caught = (text: string) => scanText('fixture.tsx', text).map((v) => v.rule);

  it.each([
    ['text-indigo-500', 'tailwind-hue'],
    ['hover:bg-slate-100', 'tailwind-hue'],
    ['border-l-rose-400', 'tailwind-hue'],
    ['#4f46e5', 'hex-colour'],
    ["color: '#e5e7eb'", 'hex-colour'],
    ['rgba(220,38,38,0.1)', 'rgb-colour'],
    ['rgb(124 58 237)', 'rgb-colour'],
    ['bg-gradient-to-r', 'gradient'],
    ['background: linear-gradient(90deg, #000, #fff)', 'gradient'],
    ['💎 On-Demand', 'emoji'],
    ['⚡ Spot', 'emoji'],
    ['✓ ready', 'emoji'],
    ['<i className="fas fa-server" />', 'font-awesome'],
    ['className={`bg-${c}-50`}', 'template-colour'],
    ["icon: 'fa-server'", 'font-awesome-data'],
    ['className={`fas ${stat.icon}`}', 'font-awesome-data'],
  ])('catches %s', (text, rule) => {
    expect(caught(text)).toContain(rule);
  });

  it.each([
    'text-[rgba(0,0,0,0.88)]',
    'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.3)]',
    'rgba(255,255,255,0.9)',
    'rgb(0 0 0)',
    '#fff #000000 #e5e5e5',
    'bg-gray-100 text-neutral-500 border-zinc-200 bg-stone-50 text-black bg-white',
    "icon=\"mdi:lock-outline\"",
    "anchor: 'automation-agent'",
    "hash: '#cluster-access'",
    'Last cycle · 4 min ago — ok',
  ])('passes %s', (text) => {
    expect(caught(text)).toEqual([]);
  });
});

describe('design-rule guard: CloudK8s files', () => {
  it('scans at least one file per guarded path', () => {
    const files = guardedFiles();
    for (const entry of GUARDED_PATHS) {
      expect(files.some((f) => (entry.endsWith('/') ? f.startsWith(entry) : f === entry)), entry).toBe(true);
    }
  });

  it('finds no violations', () => {
    const violations = guardedFiles().flatMap((file) => scanText(file, readFileSync(join(SRC, file), 'utf8')));
    expect(violations.map((v) => `${v.file}:${v.line} [${v.rule}] ${v.text}`)).toEqual([]);
  });
});
