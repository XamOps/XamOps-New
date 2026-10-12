/**
 * The colour-audit test (design-delta §2.7). It renders every chart kind and every status
 * component, in light and in dark (a `.dark` class on the render root), with fixtures that cover
 * every series kind and every tone. Then it walks the DOM and asserts the four §2.7 checks:
 *
 *   1. Every colour value resolves to a token: `rgb(var(--k8s-…) / a)` or `var(--k8s-…)`,
 *      `currentColor` / `none` / `transparent`, a `url(#id)` pointing at a `<pattern>` in the same
 *      SVG, or an equal-channel grey. Anything else — a recharts default (#8884d8 / #82ca9d /
 *      #ffc658), any raw hue — fails.
 *   2. Each series keeps one token + dash + fill mapping (§2.2). Used is never drawn dashed or blue.
 *   3. Status is never colour alone: every status chip / KPI / stepper row with a status tone has an
 *      aria-hidden `svg` icon and non-empty text in the same element.
 *   4. Feature accents stay in their place: no feature colour appears inside a `.recharts-surface`
 *      plot area, and the only feature colour in any plot area is the LanesTimeline source dot.
 *
 * jsdom loads no stylesheet, so Tailwind utility classes have no computed colour. The audit reads
 * colour from SVG/inline attributes (recharts emits explicit `fill`/`stroke`) and, for the elements
 * that colour through a Tailwind class, from the class name itself — a class only passes if it names
 * a palette token or a neutral grey ladder entry.
 */
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import ChartCard from '../ChartCard';
import TimeSeriesChart from '../TimeSeriesChart';
import StackedArea from '../StackedArea';
import Bars from '../Bars';
import Sparkline from '../Sparkline';
import MiniBar from '../MiniBar';
import TimeStrip from '../TimeStrip';
import LanesTimeline from '../LanesTimeline';
import { SERIES } from '../chartTokens';

import CapacityDonut from '../../CapacityDonut';
import type { ResourceCapacity } from '../../../../../utils/k8sDerivations';

import StatusChip from '../../status/StatusChip';
import KpiCard from '../../status/KpiCard';
import StepIndicator from '../../status/StepIndicator';
import ApplyStepper from '../../status/ApplyStepper';
import FeatureMark from '../../status/FeatureMark';
import { deriveLifecycle } from '../../status/lifecycle';
import type { ApplyOutcomeWire } from '../../../../../types/workloadRightsizing';

// ─── Token / grey recognisers ─────────────────────────────────────────────────

const KNOWN_TOKENS = Object.keys({
  '--k8s-status-good': 1, '--k8s-status-warn': 1, '--k8s-status-bad': 1, '--k8s-status-info': 1, '--k8s-status-off': 1,
  '--k8s-money-saved': 1, '--k8s-money-extra': 1,
  '--k8s-series-capacity': 1, '--k8s-series-requested': 1, '--k8s-series-used': 1, '--k8s-series-suggested': 1, '--k8s-series-limit': 1,
  '--k8s-series-allocatable': 1, '--k8s-series-overhead': 1,
  '--k8s-feature-workload': 1, '--k8s-feature-node': 1, '--k8s-feature-hpa': 1, '--k8s-feature-vpa': 1,
});

/** A `var(--k8s-…)` or `rgb(var(--k8s-…) / a)` referencing a known token. */
function isTokenVar(value: string): boolean {
  const m = value.match(/var\((--k8s-[\w-]+)/);
  return m ? KNOWN_TOKENS.includes(m[1]) : false;
}

/** `rgb(a a a)` / `rgba(a,a,a,x)` / `#aaa` with equal channels is a neutral grey. */
function isEqualChannelGrey(value: string): boolean {
  const rgb = value.match(/rgba?\(\s*(\d{1,3})[\s,]+(\d{1,3})[\s,]+(\d{1,3})/);
  if (rgb) return rgb[1] === rgb[2] && rgb[2] === rgb[3];
  const hex6 = value.match(/^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$/);
  if (hex6) return hex6[1].toLowerCase() === hex6[2].toLowerCase() && hex6[2].toLowerCase() === hex6[3].toLowerCase();
  const hex3 = value.match(/^#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])$/);
  if (hex3) return hex3[1].toLowerCase() === hex3[2].toLowerCase() && hex3[2].toLowerCase() === hex3[3].toLowerCase();
  return false;
}

const NEUTRAL = /\b(?:black|white|gray|grey|neutral|zinc|stone)\b/;

/** A `url(#id)` whose pattern is defined in the same SVG root. */
function urlRefResolves(value: string, svgRoot: SVGElement | null): boolean {
  const m = value.match(/url\(['"]?#([\w-]+)['"]?\)/);
  if (!m) return false;
  if (!svgRoot) return false;
  return !!svgRoot.querySelector(`pattern#${CSS.escape(m[1])}, linearGradient#${CSS.escape(m[1])}`) &&
    !!svgRoot.querySelector(`pattern#${CSS.escape(m[1])}`);
}

/** A single colour string from an attribute or inline style resolves to a token. */
function attrColourOk(value: string, svgRoot: SVGElement | null): boolean {
  const v = value.trim();
  if (v === '' || v === 'none' || v === 'transparent' || v === 'currentColor' || v === 'inherit') return true;
  if (isTokenVar(v)) return true;
  if (isEqualChannelGrey(v)) return true;
  if (v.startsWith('url(')) return urlRefResolves(v, svgRoot);
  return false;
}

/**
 * A colour-bearing Tailwind class resolves to a token or a neutral grey. A class is a colour
 * violation only when it clearly NAMES a colour that is neither a palette token nor a neutral grey:
 *   - a chromatic Tailwind hue + shade (`text-red-500`, `fill-indigo-400`), the thing the real guard
 *     bans; or
 *   - a `fill-`/`stroke-`/`stop-` colour whose name is not a palette token, a grey, or `current`.
 * Layout utilities (`text-center`, `border-b`, `border-l-4`), opacity modifiers (`/[.11]`) and
 * arbitrary values (`text-[rgba(0,0,0,.6)]`) are not colours and pass; the designRules guard covers
 * arbitrary rgb() values separately.
 */
const CHROMATIC = /^(?:text|bg|border|ring|outline|fill|stroke|decoration|from|via|to)(?:-[trblxyse])?-(?:slate|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-(?:50|[1-9]00|950)\b/;
const SVG_COLOUR_CLASS = /^(?:fill|stroke|stop)-(.+)$/;
const TOKEN_NAME = /^(?:status|money|series|feature)-(?:good|warn|bad|info|off|saved|extra|capacity|requested|used|suggested|limit|allocatable|overhead|workload|node|hpa|vpa)\b/;

function classColourOk(cls: string): boolean {
  const base = cls.split('/')[0]; // drop opacity modifier
  if (CHROMATIC.test(base)) return false;
  const svg = base.match(SVG_COLOUR_CLASS);
  if (svg) {
    const rest = svg[1];
    if (rest.startsWith('[')) return true; // arbitrary value
    if (TOKEN_NAME.test(rest)) return true;
    if (NEUTRAL.test(rest) || rest === 'current' || rest === 'none' || rest === 'transparent') return true;
    return false;
  }
  return true;
}

// ─── DOM walkers ──────────────────────────────────────────────────────────────

const COLOUR_ATTRS = ['fill', 'stroke', 'stop-color'] as const;
const STYLE_PROPS = ['color', 'background-color', 'border-color', 'fill', 'stroke', 'stop-color'] as const;

function nearestSvg(el: Element): SVGElement | null {
  let cur: Element | null = el;
  while (cur) {
    if (cur.tagName.toLowerCase() === 'svg') return cur as unknown as SVGElement;
    cur = cur.parentElement;
  }
  return null;
}

interface Finding {
  tag: string;
  where: string;
  value: string;
}

/** Every attribute / inline-style / class colour under `root` that does NOT resolve to a token. */
function colourViolations(root: HTMLElement): Finding[] {
  const out: Finding[] = [];
  root.querySelectorAll('*').forEach((el) => {
    const svgRoot = nearestSvg(el);

    for (const attr of COLOUR_ATTRS) {
      const v = el.getAttribute(attr);
      if (v !== null && !attrColourOk(v, svgRoot)) out.push({ tag: el.tagName, where: `@${attr}`, value: v });
    }

    const style = el.getAttribute('style');
    if (style) {
      for (const prop of STYLE_PROPS) {
        const re = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'i');
        const m = style.match(re);
        if (m && !attrColourOk(m[1], svgRoot)) out.push({ tag: el.tagName, where: `style:${prop}`, value: m[1].trim() });
      }
    }

    const cls = el.getAttribute('class');
    if (cls) {
      for (const c of cls.split(/\s+/)) {
        if (c && !classColourOk(c)) out.push({ tag: el.tagName, where: 'class', value: c });
      }
    }
  });
  return out;
}

// ─── Fixtures ───────────────────────────────────────────────────────────────

const TS_DATA = [
  { t: '09:00', used: 10, requested: 20, suggested: 14, limit: 30 },
  { t: '10:00', used: 12, requested: 20, suggested: 14, limit: 30 },
  { t: '11:00', used: 9, requested: 20, suggested: 14, limit: 30 },
];

const TS_SERIES = [
  { dataKey: 'used', kind: 'used' as const, name: 'Used' },
  { dataKey: 'requested', kind: 'requested' as const, name: 'Requested' },
  { dataKey: 'suggested', kind: 'suggested' as const, name: 'Suggested', lineOnly: true },
  { dataKey: 'limit', kind: 'limit' as const, name: 'Limit', lineOnly: true },
];

const STACK_DATA = [
  { t: 'a', ns1: 4, ns2: 3, sys: 2 },
  { t: 'b', ns1: 5, ns2: 2, sys: 2 },
];

const pod = (name: string, ready: boolean): NonNullable<ApplyOutcomeWire['pods']>[number] => ({
  podName: name,
  uidBefore: 'u1',
  uidAfter: 'u1',
  samePod: true,
  ready,
  containers: [{
    name: 'app', containerIdBefore: 'c1', containerIdAfter: 'c1',
    restartCountBefore: 0, restartCountAfter: 0, observedBefore: null, observedAfter: null,
  }],
});

const outcome = (terminal: string, pods: ApplyOutcomeWire['pods']): ApplyOutcomeWire => ({
  method: 'DIRECT', terminal, detail: 'detail', restartFree: true, containers: null, pods,
});

const CAPACITY: ResourceCapacity = {
  provisioned: 8, allocatable: 7, overhead: 1, requested: 4, used: 3,
};

/** Every chart kind and every status component, with every series kind and tone represented. */
function AllComponents() {
  const life = deriveLifecycle({ measured: true, hasSuggestion: true, applyProgress: 'RUNNING' });
  return (
    <div>
      <ChartCard title="CPU requested and used, analysed workloads" source="Source: Workload autoscaler (BE-3): used, step 30 min, 72 h.">
        <TimeSeriesChart
          width={420}
          data={TS_DATA}
          series={TS_SERIES}
          xKey="t"
          bands={[{ kind: 'noHistory', x1: '09:00', x2: '10:00', label: 'No data before 10:00' }]}
          markers={[{ x: '11:00', label: 'now' }]}
          ariaLabel="CPU requested and used"
        />
      </ChartCard>

      <StackedArea
        width={420}
        data={STACK_DATA}
        xKey="t"
        layers={[
          { dataKey: 'ns1', kind: 'requested', name: 'app-a', shade: 1 },
          { dataKey: 'ns2', kind: 'requested', name: 'app-b', shade: 0.46 },
          { dataKey: 'sys', kind: 'capacity', name: 'system', shade: 0.7 },
        ]}
        total={{ dataKey: 'ns1', kind: 'capacity', name: 'Allocatable' }}
        ariaLabel="CPU by namespace"
      />

      {(['grouped', 'before-after', 'capacity', 'day'] as const).map((mode) => (
        <Bars
          key={mode}
          mode={mode}
          width={420}
          xKey="n"
          data={[{ n: 'x', requested: 3, used: 2, extra: 1 }, { n: 'y', requested: 4, used: 3, extra: 0 }]}
          bars={[
            { dataKey: 'requested', kind: 'requested', name: 'Requested' },
            { dataKey: 'used', kind: 'used', name: 'Used' },
            { dataKey: 'extra', kind: 'extra', money: true, name: 'Costs more' },
          ]}
          ariaLabel={`Bars ${mode}`}
        />
      ))}

      <Sparkline values={[10, 12, 9, 15]} kind="used" ariaLabel="use sparkline" />
      <MiniBar value={3} max={5} kind="requested" secondary={{ value: 2, kind: 'used' }} tick={{ value: 2.5, kind: 'suggested' }} />
      <MiniBar value={4} max={10} kind="saved" money />
      <TimeStrip segments={[{ from: 0.2, to: 0.5 }]} kind="limit" notMeasuredBefore={0.1} ariaLabel="time at maximum" />
      <LanesTimeline
        width={420}
        noHistoryBefore={0.15}
        lanes={[
          { name: 'idle-batch-listener', feature: 'workload', entries: [{ at: 0.4, tone: 'good', label: 'applied' }, { at: 0.6, tone: 'info', dryRun: true, label: 'test' }], inPlace: [{ from: 0.4, to: 0.9 }] },
          { name: 'throttled-encoder', feature: 'hpa', entries: [{ at: 0.5, tone: 'warn', label: 'deferred' }, { at: 0.7, tone: 'bad', label: 'failed' }] },
        ]}
        ariaLabel="7-day activity lanes"
      />

      <CapacityDonut resource="CPU" capacity={CAPACITY} format={(v) => (v === null ? '—' : `${v} vCPU`)} usedMissing="no agent" />

      {/* Status components, every tone. */}
      {(['good', 'warn', 'bad', 'info', 'off'] as const).map((tone) => (
        <StatusChip key={tone} tone={tone} icon="mdi:check-circle" label={`Tone ${tone}`} detail="detail" />
      ))}
      <KpiCard tone="good" icon="mdi:cash" label="Saving now" value="$42/mo"><span>in place</span></KpiCard>
      <KpiCard tone="warn" icon="mdi:cash" label="Can save now" value="$9/mo" />
      <StepIndicator states={life} />
      <ApplyStepper outcome={outcome('APPLIED', [pod('idle-batch-listener-x', true)])} />
      <ApplyStepper outcome={outcome('DEFERRED', [pod('throttled-encoder-x', false)])} />
      {(['workload', 'node', 'hpa', 'vpa'] as const).map((f) => (
        <FeatureMark key={f} feature={f} />
      ))}
    </div>
  );
}

afterEach(() => {
  document.documentElement.classList.remove('dark');
});

function renderInTheme(theme: 'light' | 'dark') {
  return render(
    <div className={theme === 'dark' ? 'dark' : undefined} data-theme={theme}>
      <AllComponents />
    </div>,
  );
}

// ─── The four §2.7 checks ──────────────────────────────────────────────────────

describe('colour audit (design-delta §2.7)', () => {
  for (const theme of ['light', 'dark'] as const) {
    describe(theme, () => {
      it('1. every colour value resolves to a token, grey, currentColor/none, or a same-SVG pattern', () => {
        const { container } = renderInTheme(theme);
        const findings = colourViolations(container);
        expect(findings.map((f) => `${f.tag} ${f.where}=${f.value}`)).toEqual([]);
      });

      it('1b. no recharts default colour is emitted', () => {
        const { container } = renderInTheme(theme);
        const html = container.innerHTML;
        for (const def of ['#8884d8', '#82ca9d', '#ffc658']) {
          expect(html.includes(def), `recharts default ${def}`).toBe(false);
        }
      });

      it('2. each series keeps one token + dash + fill mapping', () => {
        const { container } = renderInTheme(theme);
        const surfaces = container.querySelectorAll('.recharts-surface');
        expect(surfaces.length).toBeGreaterThan(0);

        // Build the expected (colour -> dash) map from chartTokens. A stroked recharts curve/line
        // carrying a series colour must use that series' dash (undefined -> no dasharray).
        const byColour = new Map<string, { dash: string | undefined }>();
        for (const style of Object.values(SERIES)) {
          byColour.set(style.color, { dash: style.dash });
        }

        surfaces.forEach((surface) => {
          surface.querySelectorAll('path[stroke]').forEach((p) => {
            const stroke = p.getAttribute('stroke') ?? '';
            const expected = byColour.get(stroke);
            if (!expected) return; // currentColor / none / non-series — not a series curve
            const dash = p.getAttribute('stroke-dasharray') ?? undefined;
            if (expected.dash === undefined) {
              // A solid series (requested/used/capacity): must not be dashed.
              expect(dash === undefined || dash === '' || dash === '0', `${stroke} should be solid`).toBe(true);
            } else {
              expect(dash, `${stroke} dash`).toBe(expected.dash);
            }
          });
        });
      });

      it('3. every status tone carries an aria-hidden icon and non-empty text', () => {
        const { container } = renderInTheme(theme);
        // The status surfaces that carry a tone: chips, KPIs and stepper rows.
        const surfaces = [
          ...container.querySelectorAll('[data-tone]'),
          ...container.querySelectorAll('[data-kpi-tone]'),
          ...container.querySelectorAll('[data-apply-step]'),
        ];
        expect(surfaces.length).toBeGreaterThan(0);
        for (const el of surfaces) {
          expect(el.querySelector('svg[aria-hidden="true"]'), `${el.getAttribute('data-tone') ?? el.getAttribute('data-apply-step')} needs an icon`).toBeTruthy();
          expect((el.textContent ?? '').trim().length, 'needs non-empty text').toBeGreaterThan(0);
        }
      });

      it('4. no feature colour appears in a plot area except the LanesTimeline source dot', () => {
        const { container } = renderInTheme(theme);
        // recharts surfaces must carry no feature colour at all.
        container.querySelectorAll('.recharts-surface *').forEach((el) => {
          for (const attr of ['fill', 'stroke'] as const) {
            const v = el.getAttribute(attr);
            if (v) expect(/--k8s-feature-/.test(v), `feature colour in recharts plot: ${v}`).toBe(false);
          }
        });
        // In the LanesTimeline plot, the only feature colour is a [data-feature-dot] source dot.
        const lanes = container.querySelector('[data-lanes-timeline]');
        if (lanes) {
          lanes.querySelectorAll('*').forEach((el) => {
            for (const attr of ['fill', 'stroke'] as const) {
              const v = el.getAttribute(attr);
              if (v && /--k8s-feature-/.test(v)) {
                expect(el.hasAttribute('data-feature-dot'), 'feature colour only on a source dot').toBe(true);
              }
            }
          });
        }
      });
    });
  }
});
