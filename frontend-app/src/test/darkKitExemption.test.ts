// @vitest-environment node
/**
 * Phase-1 dark-mode fix: the legacy `.dark` overrides in style.css must never reach a v3 element.
 *
 * The CloudK8s page puts `.dark` on its wrapper (pages/EksDetailsPage.tsx) so the v3 components'
 * own `dark:` variants and dark tokens apply. style.css still holds the app's older dark overrides,
 * written for a global dark mode: they paint every SVG path slate (a map rule) and force text and
 * link colours, which turned every chart bar and icon slate and stripped every status colour. Each
 * of those selectors now ends in `:where(:not([data-k8s-kit], [data-k8s-kit] *))`, and the v3
 * status and chart components mark their root element `data-k8s-kit`. jsdom applies no stylesheet,
 * so these checks read the source; the light and dark screenshot pass checks the rendered result.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const SRC = fileURLToPath(new URL('..', import.meta.url));
const KIT_EXEMPTION = ':where(:not([data-k8s-kit], [data-k8s-kit] *))';
const SVG_EXEMPTION = ':where(:not([data-k8s-kit], [data-k8s-kit] *, .iconify, .iconify *))';

interface Rule {
  selectors: string[];
  properties: string[];
}

/** The style rules of a stylesheet (comments dropped), each selector list split at top-level commas. */
function rules(css: string): Rule[] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const out: Rule[] = [];
  for (const m of text.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const prelude = m[1].trim();
    if (prelude.startsWith('@')) continue;
    const selectors: string[] = [];
    let depth = 0;
    let start = 0;
    for (let i = 0; i < prelude.length; i += 1) {
      const ch = prelude[i];
      if (ch === '(' || ch === '[') depth += 1;
      else if (ch === ')' || ch === ']') depth -= 1;
      else if (ch === ',' && depth === 0) {
        selectors.push(prelude.slice(start, i).trim());
        start = i + 1;
      }
    }
    selectors.push(prelude.slice(start).trim());
    const properties = m[2].split(';').map((d) => d.split(':')[0].trim()).filter(Boolean);
    out.push({ selectors, properties });
  }
  return out;
}

const darkRules = rules(readFileSync(join(SRC, 'style.css'), 'utf8'))
  .map((r) => ({ ...r, selectors: r.selectors.filter((s) => s.includes('.dark') && s !== '.dark') }))
  .filter((r) => r.selectors.length > 0);

describe('the legacy dark CSS leaves the v3 kit alone', () => {
  it('reads the legacy dark rules', () => {
    expect(darkRules.length).toBeGreaterThan(200);
  });

  it('ends every legacy .dark selector with the kit exemption, before any pseudo-element', () => {
    const missing = darkRules.flatMap((r) =>
      r.selectors.filter((s) => {
        const at = s.indexOf(':where(:not([data-k8s-kit], [data-k8s-kit] *');
        if (at === -1) return true;
        const rest = s.slice(s.indexOf('))', at) + 2);
        return rest !== '' && !rest.startsWith('::');
      }),
    );
    expect(missing).toEqual([]);
  });

  it('leaves Iconify icons alone in every rule that paints SVG fill or stroke', () => {
    const paints = darkRules.filter((r) => r.properties.some((p) => p === 'fill' || p === 'stroke'));
    expect(paints.length).toBeGreaterThan(10);
    expect(paints.flatMap((r) => r.selectors.filter((s) => !s.includes(SVG_EXEMPTION)))).toEqual([]);
    // Every other rule uses the plain exemption.
    const others = darkRules.filter((r) => !paints.includes(r));
    expect(others.flatMap((r) => r.selectors.filter((s) => !s.includes(KIT_EXEMPTION)))).toEqual([]);
  });

  it('marks the root of every status and chart component data-k8s-kit', () => {
    const unmarked: string[] = [];
    for (const dir of ['components/CloudK8s/shared/status', 'components/CloudK8s/shared/charts']) {
      for (const file of readdirSync(join(SRC, dir))) {
        // ChartDefs renders <defs> inside another chart's SVG, which is marked already.
        if (!file.endsWith('.tsx') || file === 'ChartDefs.tsx') continue;
        if (!readFileSync(join(SRC, dir, file), 'utf8').includes('data-k8s-kit=""')) unmarked.push(`${dir}/${file}`);
      }
    }
    expect(unmarked).toEqual([]);
  });
});
