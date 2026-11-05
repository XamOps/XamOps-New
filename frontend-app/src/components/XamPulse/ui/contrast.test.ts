import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { contrastRatio } from '../../../utils/xampulse/contrast';
import { XP_PALETTE, type XpPalette, type XpTheme } from './tokens';

const TEXT_ON_BG: (keyof XpPalette)[] = ['text', 'textMuted', 'accentText', 'ok', 'warn', 'crit', 'info', 'noData'];
const BACKGROUNDS: (keyof XpPalette)[] = ['bg', 'surface', 'surface2'];

describe.each(['dark', 'light'] as XpTheme[])('XamPulse %s theme contrast', (theme) => {
  const p = XP_PALETTE[theme];
  it.each(TEXT_ON_BG.flatMap((fg) => BACKGROUNDS.map((bg) => [fg, bg] as const)))('%s on %s is at least 4.5:1', (fg, bg) => {
    expect(contrastRatio(p[fg], p[bg])).toBeGreaterThanOrEqual(4.5);
  });
  it('button text on the accent is at least 4.5:1', () => {
    expect(contrastRatio(p.onAccent, p.accent)).toBeGreaterThanOrEqual(4.5);
  });
  it('borders are at least 3:1 against the page and card surfaces', () => {
    expect(contrastRatio(p.border, p.bg)).toBeGreaterThanOrEqual(3);
    expect(contrastRatio(p.border, p.surface)).toBeGreaterThanOrEqual(3);
  });
});

// The CSS custom properties must match the JS palette, otherwise the test above proves nothing about the UI.
describe('css token parity', () => {
  const css = readFileSync(resolve(__dirname, '../../../styles/xampulse.css'), 'utf8');
  const map: Record<string, keyof XpPalette> = {
    '--xp-bg': 'bg', '--xp-surface': 'surface', '--xp-surface2': 'surface2', '--xp-border': 'border', '--xp-text': 'text', '--xp-muted': 'textMuted',
    '--xp-accent': 'accent', '--xp-accent-text': 'accentText', '--xp-on-accent': 'onAccent', '--xp-ok': 'ok', '--xp-warn': 'warn', '--xp-crit': 'crit', '--xp-info': 'info', '--xp-nodata': 'noData',
  };
  it.each(['dark', 'light'] as XpTheme[])('%s block matches XP_PALETTE', (theme) => {
    const block = new RegExp(`\\.xp-root\\[data-theme='${theme}'\\] \\{([^}]*)\\}`).exec(css)?.[1] ?? '';
    Object.keys(map).forEach((v) => {
      const m = new RegExp(`${v}:\\s*(#[0-9a-fA-F]{6})`).exec(block);
      expect(m?.[1]?.toLowerCase(), `${theme} ${v}`).toBe(XP_PALETTE[theme][map[v]].toLowerCase());
    });
  });
});
