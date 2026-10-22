// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * The CloudK8s design-rule guard (CLAUDE.md; design-delta v3 §2.6).
 *
 * CLAUDE.md governs: black, white and grey chrome, colour only for meaning and only through a
 * component that pairs it with an icon and a label, no gradients, no emoji, bundled Iconify icons
 * rather than Font Awesome, and status never carried by colour alone. Every rule fails with
 * `file:line`. There is no inline escape hatch: a false positive is fixed by renaming it.
 *
 * v3 adds the owner's palette (§2.2) and makes the token file reviewable: a reviewed PALETTE table
 * sits here, `palette-matches-file` fails unless the token file matches it exactly, and
 * `palette-contrast` fails if a tweak breaks WCAG AA. `channel-triple` closes the loophole of
 * minting a colour outside the token file; `token-scope` keeps each colour family in the files that
 * pair it with an icon and a label.
 */

const SRC = fileURLToPath(new URL('..', import.meta.url));

/** The one file that carries the palette's channel triples. */
export const PALETTE_FILE = 'styles/k8sSemanticTokens.css';

/**
 * The one file that carries the slate-tinted chrome ramp (owner-decided; see the file header).
 *
 * Chrome neutrals are NOT pure grey — the owner's palette is a cool, slate-tinted ramp (R≠G≠B, blue
 * channel highest) that gives the page depth without a competing hue. Those values are non-equal
 * channel on purpose, so the `hex-colour` / `rgb-colour` colour rules are skipped for THIS file
 * ONLY, and the exact values are pinned by CHROME_RAMP + the `chrome-matches-file` test below so the
 * ramp can't silently drift (or slide back to grey). This is a narrow, reviewed exemption — it does
 * NOT widen either rule anywhere else; any non-grey colour in any other file still fails.
 */
export const CHROME_RAMP_FILE = 'components/CloudK8s/shared/styles/k8sChromeTokens.css';

/** The exact slate chrome ramp this file must carry (`:root` light / `.dark` dark). */
export const CHROME_RAMP: Record<string, { light: string; dark: string }> = {
  '--chrome-ink': { light: '#1E293B', dark: '#E2E8F0' },
  '--chrome-muted': { light: '#64748B', dark: '#94A3B8' },
  '--chrome-page': { light: '#F4F6F9', dark: '#0B0F17' },
  '--chrome-card': { light: '#FFFFFF', dark: '#121824' },
  '--chrome-surface-2': { light: '#EDF1F6', dark: '#1A2130' },
  '--chrome-line': { light: 'rgba(15, 23, 42, 0.08)', dark: 'rgba(148, 163, 184, 0.12)' },
};

/** Colour rules the slate chrome ramp file is exempt from (pinned by CHROME_RAMP instead). */
const CHROME_RAMP_EXEMPT: readonly string[] = ['hex-colour', 'rgb-colour'];

/**
 * design-delta v3 §2.2. A colour change edits this table and the token file together, in one
 * reviewed change. `role` drives the contrast threshold: 'text' needs 4.5:1, 'graphic' needs 3:1.
 */
export const PALETTE: Record<string, { light: string; dark: string; role: 'text' | 'graphic' }> = {
  // Brand green = good = savings (merged, owner-decided): deep #0B8F5A on light, bright #1BE68B on dark.
  '--k8s-status-good': { light: '7 122 77', dark: '27 230 139', role: 'text' },
  '--k8s-status-warn': { light: '138 90 0', dark: '235 170 60', role: 'text' },
  '--k8s-status-bad': { light: '196 22 63', dark: '255 90 102', role: 'text' },
  '--k8s-status-info': { light: '10 95 191', dark: '107 163 242', role: 'text' },
  '--k8s-status-off': { light: '102 102 102', dark: '158 158 158', role: 'text' },
  '--k8s-money-saved': { light: '7 122 77', dark: '27 230 139', role: 'text' },
  '--k8s-money-extra': { light: '138 90 0', dark: '235 170 60', role: 'text' },
  '--k8s-series-capacity': { light: '102 117 131', dark: '163 173 182', role: 'graphic' },
  '--k8s-series-requested': { light: '11 103 160', dark: '86 171 228', role: 'graphic' },
  '--k8s-series-used': { light: '0 147 107', dark: '37 192 142', role: 'graphic' },
  '--k8s-series-suggested': { light: '156 61 114', dark: '215 116 182', role: 'graphic' },
  '--k8s-series-limit': { light: '194 122 0', dark: '242 165 30', role: 'graphic' },
  '--k8s-series-allocatable': { light: '102 117 131', dark: '163 173 182', role: 'graphic' }, // alias until FE-10
  '--k8s-series-overhead': { light: '102 117 131', dark: '163 173 182', role: 'graphic' }, // alias until FE-10
  '--k8s-feature-workload': { light: '91 63 176', dark: '157 140 240', role: 'graphic' },
  '--k8s-feature-node': { light: '0 131 139', dark: '111 211 222', role: 'graphic' },
  '--k8s-feature-hpa': { light: '161 68 42', dark: '227 154 128', role: 'graphic' },
  '--k8s-feature-vpa': { light: '105 126 15', dark: '201 212 90', role: 'graphic' },
};

/** Files or folders (trailing slash) under src/, relative, POSIX separators. */
export const GUARDED_PATHS: readonly string[] = [
  'components/CloudK8s/shared/',
  'hooks/useK8sCapabilities.tsx',
  'hooks/useK8sCacheIdentity.ts',
  'hooks/useEksSectionRoute.ts',
  'utils/k8sCache.ts',
  'pages/EksDetailsPage.tsx',
  'pages/CloudK8sPage.tsx',
  'components/EksDetails/ClusterHeader.tsx',
  'components/EksDetails/sections/',
  'utils/inPageNavigation.ts',
  'utils/k8sVersion.ts',
  'components/Shared/XamTabPermissionToggle.tsx',
  // FEAT-003
  'utils/k8sQuantities.ts',
  'utils/k8sPricing.ts',
  'utils/k8sDerivations.ts',
  'utils/csvExport.ts',
  'utils/k8sClusterLink.ts',
  // Addendum 1 (sidebar section navigation); ClusterSectionsNav is under components/CloudK8s/shared/.
  'utils/k8sNavAccess.ts',
  'hooks/useK8sNavAccess.ts',
  'components/Sidebar/SubNav.tsx',
  // FEAT-003 item 23a: the status strip; the dashboard hook without its score colours.
  // SummaryPanel is under components/EksDetails/sections/.
  'components/EksDetails/ClusterStatusStrip.tsx',
  'hooks/useEksDashboard.ts',
  // Item 24a: the engine's read hook; WorkloadsPanel and verticalCopy are under sections/.
  'hooks/useVerticalRightsizer.ts',
  // Item 24b: Pods and Containers, rewritten on K8sDataTable.
  'components/EksDetails/tabs/EksPodsTab.tsx',
  'components/EksDetails/tabs/EksContainersTab.tsx',
  // Item 25a: the node list (NodeGroupsPanel is under sections/, RequestBar under CloudK8s/shared/).
  'components/EksDetails/tabs/EksNodesTab.tsx',
  // Item 25b: Karpenter and Utilization.
  'components/EksDetails/tabs/EksKarpenterTab.tsx',
  'components/EksDetails/tabs/EksPerformanceTab.tsx',
  // FEAT-001 (FE-0): the palette file itself, and the status/charts component folders.
  PALETTE_FILE,
  'components/CloudK8s/shared/status/',
  'components/CloudK8s/shared/charts/',
  // FEAT-003: the Workload autoscaler apply UI, re-skinned to bundled mdi:* icons (no Font Awesome).
  'components/EksDetails/tabs/EksAutomationTab.tsx',
];

const TAILWIND_PREFIX = '(?:text|bg|border|from|via|to|ring|fill|stroke|divide|outline|shadow|accent|decoration|placeholder|caret)';
const CHROMATIC = '(?:slate|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)';

// ─── Token-scope families (design-delta §2.6 rule 5) ──────────────────────────

const TOKEN_PREFIX = '(?:text|bg|border|ring|outline|fill|stroke|decoration)';

interface ScopedFamily {
  id: string;
  /** The class pattern that only its allowed files may use. */
  pattern: RegExp;
  /** Relative files/folders (trailing slash) the family is allowed in. */
  allowed: readonly string[];
  message: string;
}

const SCOPED_FAMILIES: ScopedFamily[] = [
  {
    id: 'scope-status-money',
    pattern: new RegExp(`\\b${TOKEN_PREFIX}-(?:status|money)-`),
    allowed: [
      'components/CloudK8s/shared/status/',
      'components/CloudK8s/shared/StatusMark.tsx',
      'components/EksDetails/sections/overview/SummaryPanel.tsx',
    ],
    message: 'status/money colour class outside the status components, StatusMark or SummaryPanel',
  },
  {
    id: 'scope-series',
    pattern: new RegExp(`\\b${TOKEN_PREFIX}-series-`),
    allowed: [
      'components/CloudK8s/shared/charts/',
      'components/CloudK8s/shared/CapacityDonut.tsx',
      'components/CloudK8s/shared/CapacityBar.tsx',
      'components/CloudK8s/shared/RequestBar.tsx',
      'components/EksDetails/sections/overview/SummaryPanel.tsx',
    ],
    message: 'series colour class outside the chart components, CapacityDonut, CapacityBar, RequestBar or SummaryPanel',
  },
  {
    id: 'scope-feature',
    pattern: new RegExp(`\\b${TOKEN_PREFIX}-feature-`),
    allowed: [
      'components/CloudK8s/shared/status/FeatureMark.tsx',
      'components/CloudK8s/shared/charts/LanesTimeline.tsx',
    ],
    message: 'feature accent class outside FeatureMark or LanesTimeline',
  },
  {
    id: 'scope-raw-var',
    pattern: /var\(--k8s-/,
    allowed: [
      'components/CloudK8s/shared/charts/chartTokens.ts',
      'components/CloudK8s/shared/status/',
    ],
    message: 'raw var(--k8s-…) outside chartTokens or the status components',
  },
];

function fileAllowed(file: string, allowed: readonly string[]): boolean {
  return allowed.some((a) => (a.endsWith('/') ? file.startsWith(a) : file === a));
}

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
    test: (line) => /bg-gradient-|(?:linear|radial|conic|repeating-linear|repeating-radial|repeating-conic)-gradient\(/.test(line),
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
  {
    id: 'channel-triple',
    message: 'a --name: R G B declaration with unequal channels (mint colours only in the token file)',
    test: (line) => {
      const re = /--[\w-]+\s*:\s*(\d{1,3})\s+(\d{1,3})\s+(\d{1,3})\b/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(line)) !== null) {
        if (m[1] !== m[2] || m[2] !== m[3]) return true;
      }
      return false;
    },
  },
];

export interface Violation {
  file: string;
  line: number;
  rule: string;
  text: string;
}

/**
 * Scan a file's text. `file` is the relative POSIX path, used by the per-file rules
 * (`channel-triple` is skipped in the palette file, `token-scope` honours each family's allow-list).
 */
/** Blank `/* *​/` and `//` comments, keeping newlines so reported line numbers stay right. */
function stripComments(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (b) => b.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, pre) => pre + ' '.repeat(m.length - pre.length));
}

export function scanText(file: string, text: string): Violation[] {
  const out: Violation[] = [];
  stripComments(text).split('\n').forEach((line, index) => {
    for (const rule of RULES) {
      // The palette file is the one place allowed to carry unequal channels; it is checked by
      // palette-matches-file instead.
      if (rule.id === 'channel-triple' && file === PALETTE_FILE) continue;
      // The slate chrome ramp carries non-grey hex/rgba on purpose (owner-decided); its exact values
      // are pinned by chrome-matches-file, so the two colour rules are skipped for this file ONLY.
      if (file === CHROME_RAMP_FILE && CHROME_RAMP_EXEMPT.includes(rule.id)) continue;
      if (rule.test(line)) out.push({ file, line: index + 1, rule: rule.id, text: line.trim() });
    }
    for (const family of SCOPED_FAMILIES) {
      if (family.pattern.test(line) && !fileAllowed(file, family.allowed)) {
        out.push({ file, line: index + 1, rule: 'token-scope', text: line.trim() });
      }
    }
  });
  return out;
}

function walk(dir: string): string[] {
  let names: string[];
  try {
    names = readdirSync(dir);
  } catch {
    // A guarded folder that a later FEAT creates (e.g. charts/ in FEAT-002) may not exist yet. It
    // stays declared so its files are guarded the moment they land; until then there is nothing to
    // walk.
    return [];
  }
  return names.flatMap((name) => {
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

// ─── WCAG contrast (design-delta §2.3) ────────────────────────────────────────

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

function luminance([r, g, b]: [number, number, number]): number {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function ratio(a: [number, number, number], b: [number, number, number]): number {
  const la = luminance(a);
  const lb = luminance(b);
  const [hi, lo] = la >= lb ? [la, lb] : [lb, la];
  return (hi + 0.05) / (lo + 0.05);
}

function parseTriple(s: string): [number, number, number] {
  const [r, g, b] = s.trim().split(/\s+/).map(Number);
  return [r, g, b];
}

/** Composite a token over a surface at the given alpha (the chip tint). */
function composite(fg: [number, number, number], bg: [number, number, number], alpha: number): [number, number, number] {
  return [0, 1, 2].map((i) => Math.round(fg[i] * alpha + bg[i] * (1 - alpha))) as unknown as [number, number, number];
}

const CARD = { light: [255, 255, 255] as [number, number, number], dark: [20, 20, 20] as [number, number, number] };
const CHIP_ALPHA = { light: 0.11, dark: 0.2 };

/** The WCAG checks for one palette table (used by palette-contrast and its self-test fixtures). */
export function contrastFailures(palette: typeof PALETTE): string[] {
  const failures: string[] = [];
  for (const [name, def] of Object.entries(palette)) {
    const need = def.role === 'text' ? 4.5 : 3;
    for (const theme of ['light', 'dark'] as const) {
      const token = parseTriple(def[theme]);
      const onCard = ratio(token, CARD[theme]);
      if (onCard < need) {
        failures.push(`${name} ${theme} on card: ${onCard.toFixed(2)} < ${need}`);
      }
      if (def.role === 'text') {
        const tint = composite(token, CARD[theme], CHIP_ALPHA[theme]);
        const onTint = ratio(token, tint);
        if (onTint < 4.5) {
          failures.push(`${name} ${theme} on chip tint: ${onTint.toFixed(2)} < 4.5`);
        }
      }
    }
  }
  return failures;
}

/** Parse the `:root` and `.dark` blocks of the token file into a PALETTE-shaped record. */
function parsePaletteFile(css: string): { light: Record<string, string>; dark: Record<string, string>; forbidden: string[] } {
  const light: Record<string, string> = {};
  const dark: Record<string, string> = {};
  const forbidden: string[] = [];

  // The file holds channel triples only. Reject any hex, rgb(, color-mix( or gradient — but only in
  // real CSS, not in the comments that explain the rule. Comments are blanked (newlines kept so the
  // reported line number is still right).
  const code = css.replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '));
  code.split('\n').forEach((line, i) => {
    if (/#[0-9a-fA-F]{3,6}\b|rgb\(|rgba\(|color-mix\(|gradient\(/.test(line)) {
      forbidden.push(`${PALETTE_FILE}:${i + 1} ${line.trim()}`);
    }
  });

  const blockOf = (name: string): string => {
    const re = new RegExp(`${name.replace('.', '\\.')}\\s*\\{([^}]*)\\}`);
    const m = css.match(re);
    return m ? m[1] : '';
  };
  const vars = (block: string): Record<string, string> => {
    const out: Record<string, string> = {};
    const re = /(--[\w-]+)\s*:\s*([^;]+);/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(block)) !== null) {
      if (m[1].startsWith('--k8s-')) out[m[1]] = m[2].trim();
    }
    return out;
  };

  Object.assign(light, vars(blockOf(':root')));
  Object.assign(dark, vars(blockOf('.dark')));
  return { light, dark, forbidden };
}

// ─── Self-tests ───────────────────────────────────────────────────────────────

describe('design-rule guard: self-tests', () => {
  const caught = (text: string, file = 'components/CloudK8s/pages/EksDetailsPage.tsx') =>
    scanText(file, text).map((v) => v.rule);

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
    ['background: conic-gradient(from 0deg, #000, #fff)', 'gradient'],
    ['background: radial-gradient(#000, #fff)', 'gradient'],
    ['💎 On-Demand', 'emoji'],
    ['⚡ Spot', 'emoji'],
    ['✓ ready', 'emoji'],
    ['<i className="fas fa-server" />', 'font-awesome'],
    ['className={`bg-${c}-50`}', 'template-colour'],
    ["icon: 'fa-server'", 'font-awesome-data'],
    ['className={`fas ${stat.icon}`}', 'font-awesome-data'],
    // §2.6 item 8 failing fixtures
    ['--k8s-x: 255 0 0;', 'channel-triple'],
    ['className="text-series-used"', 'token-scope'],
    ['className="bg-feature-hpa"', 'token-scope'],
    ['background: conic-gradient(', 'gradient'],
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
    'icon="mdi:lock-outline"',
    "anchor: 'automation-agent'",
    "hash: '#cluster-access'",
    'Last cycle · 4 min ago — ok',
    // §2.6 item 8 passing fixtures
    '--k8s-grey: 128 128 128;',
  ])('passes %s', (text) => {
    expect(caught(text)).toEqual([]);
  });

  it('allows a status class inside the status components', () => {
    expect(caught('className="text-status-good"', 'components/CloudK8s/shared/status/StatusChip.tsx')).toEqual([]);
  });

  it('allows a feature class inside FeatureMark', () => {
    expect(caught('className="bg-feature-hpa"', 'components/CloudK8s/shared/status/FeatureMark.tsx')).toEqual([]);
  });

  it('allows a series class inside CapacityDonut', () => {
    expect(caught('className="stroke-series-used"', 'components/CloudK8s/shared/CapacityDonut.tsx')).toEqual([]);
  });
});

describe('design-rule guard: palette', () => {
  it('palette-matches-file: the token file equals PALETTE exactly, triples only', () => {
    const css = readFileSync(join(SRC, PALETTE_FILE), 'utf8');
    const { light, dark, forbidden } = parsePaletteFile(css);

    expect(forbidden, 'the token file holds channel triples only: no hex, rgb(, color-mix( or gradient').toEqual([]);

    const expected = Object.keys(PALETTE).sort();
    expect(Object.keys(light).sort(), 'light tokens').toEqual(expected);
    expect(Object.keys(dark).sort(), 'dark tokens').toEqual(expected);

    for (const [name, def] of Object.entries(PALETTE)) {
      expect(light[name], `${name} light`).toBe(def.light);
      expect(dark[name], `${name} dark`).toBe(def.dark);
    }
  });

  it('palette-contrast: every token meets AA on card (text also on its chip tint)', () => {
    expect(contrastFailures(PALETTE)).toEqual([]);
  });

  it('palette-contrast: a bad status-good (120 200 120) fails', () => {
    const broken = { ...PALETTE, '--k8s-status-good': { light: '120 200 120', dark: '120 200 120', role: 'text' as const } };
    expect(contrastFailures(broken).some((f) => f.startsWith('--k8s-status-good'))).toBe(true);
  });

  it('chrome-matches-file: the slate chrome ramp equals CHROME_RAMP exactly (no drift back to grey)', () => {
    const css = readFileSync(join(SRC, CHROME_RAMP_FILE), 'utf8');
    const code = css.replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '));
    const blockOf = (name: string): string => {
      const m = code.match(new RegExp(`${name.replace('.', '\\.')}\\s*\\{([^}]*)\\}`));
      return m ? m[1] : '';
    };
    const vars = (block: string): Record<string, string> => {
      const out: Record<string, string> = {};
      const re = /(--chrome-[\w-]+)\s*:\s*([^;]+);/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(block)) !== null) out[m[1]] = m[2].trim();
      return out;
    };
    const light = vars(blockOf(':root'));
    const dark = vars(blockOf('.dark'));
    const expected = Object.keys(CHROME_RAMP).sort();
    expect(Object.keys(light).sort(), 'light chrome tokens').toEqual(expected);
    expect(Object.keys(dark).sort(), 'dark chrome tokens').toEqual(expected);
    for (const [name, def] of Object.entries(CHROME_RAMP)) {
      expect(light[name], `${name} light`).toBe(def.light);
      expect(dark[name], `${name} dark`).toBe(def.dark);
    }
    // The ramp must stay visibly slate-tinted (cool: blue channel highest) in both themes, not grey.
    const hexCool = (hex: string) => {
      const h = hex.replace('#', '');
      const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
      return b >= g && g >= r && b > r; // slate: b ≥ g ≥ r, and not neutral
    };
    for (const key of ['--chrome-ink', '--chrome-muted', '--chrome-page', '--chrome-surface-2'] as const) {
      expect(hexCool(CHROME_RAMP[key].light), `${key} light is slate-tinted`).toBe(true);
      expect(hexCool(CHROME_RAMP[key].dark), `${key} dark is slate-tinted`).toBe(true);
    }
  });
});

describe('design-rule guard: CloudK8s files', () => {
  // FEAT-002 created charts/, so every guarded path now resolves to at least one scanned file.
  const NOT_YET_CREATED: readonly string[] = [];

  it('scans at least one file per guarded path', () => {
    const files = guardedFiles();
    for (const entry of GUARDED_PATHS) {
      if (NOT_YET_CREATED.includes(entry)) continue;
      if (entry.endsWith('/')) {
        expect(files.some((f) => f.startsWith(entry)), entry).toBe(true);
      } else {
        expect(files.includes(entry), entry).toBe(true);
      }
    }
  });

  it('finds no violations', () => {
    const violations = guardedFiles().flatMap((file) => scanText(file, readFileSync(join(SRC, file), 'utf8')));
    expect(violations.map((v) => `${v.file}:${v.line} [${v.rule}] ${v.text}`)).toEqual([]);
  });
});
