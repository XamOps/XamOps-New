/**
 * CloudMap canvas visual system.
 *
 * Owns every pixel the Cytoscape canvas draws, so the 4,600-line page component
 * doesn't. Three things live here:
 *
 *   1. CANVAS_TOKENS — light/dark surface, hairline, text and semantic tones,
 *      derived from the shipped XamOps console (#020617 shell, pump-green accent),
 *      not from frontend-app/DESIGN.md, which documents a different product.
 *   2. resourceCardUrl() — bakes a resource "card" (name, type, status, icon well)
 *      into an SVG data URL used as a Cytoscape background layer.
 *   3. buildCytoscapeStyle() — the stylesheet, replacing the old
 *      getCytoscapeStyle() in CloudMapPage.
 *
 * WHY BAKE TEXT INTO SVG (live mode only)
 * Cytoscape renders one single-line label per node and can only anchor it
 * center/left/right of the whole node. A card needs a left-aligned name with a
 * type caption under it, offset past an icon — not expressible in Cytoscape
 * style. Baking gives exact control and keeps text crisp at any zoom, because it
 * scales with the node instead of staying at a fixed 10px.
 *
 * Design mode keeps native Cytoscape labels: inline label editing (F2 /
 * double-click) writes to data('label'), which a baked SVG could not reflect
 * without a re-render per keystroke.
 */

import { AWS_ICONS, NODE_TYPE_DATA, TAILWIND_COLORS } from '../../types/cloudmap';

export type CanvasTheme = 'light' | 'dark';

// ─── Tokens ───────────────────────────────────────────────────────────────────

export interface CanvasTokens {
  /** Canvas floor. */
  canvas: string;
  /** Dot-grid / ruler ink. */
  grid: string;
  /** Resource card fill. */
  card: string;
  cardHover: string;
  /** Card border, and the selected variant. */
  hairline: string;
  /** Resource name. */
  ink: string;
  /** Type caption, region, secondary metadata. */
  inkMuted: string;
  /** Container (VPC / subnet / AZ) fills + borders. */
  containerFill: string;
  containerInk: string;
  /** Edges. */
  edge: string;
  edgeLabel: string;
  edgeLabelBg: string;
  /** Selection + focus accent. Tracks the console's pump-green. */
  accent: string;
  accentSoft: string;
  /** Card drop shadow, tinted to the surface hue rather than pure black. */
  shadow: string;
}

export const CANVAS_TOKENS: Record<CanvasTheme, CanvasTokens> = {
  light: {
    canvas: '#fbfcfd',
    grid: 'rgba(15,23,42,0.07)',
    card: '#ffffff',
    cardHover: '#f7f9fc',
    hairline: '#dde3ec',
    ink: '#0f172a',
    inkMuted: '#5c6b85',
    containerFill: '#f1f5f9',
    containerInk: '#334155',
    edge: '#94a3b8',
    edgeLabel: '#475569',
    edgeLabelBg: '#ffffff',
    accent: '#0d9f63',
    accentSoft: 'rgba(13,159,99,0.16)',
    shadow: 'rgba(15,23,42,0.13)',
  },
  dark: {
    canvas: '#020617',
    grid: 'rgba(148,163,184,0.13)',
    card: '#0e1728',
    cardHover: '#16223a',
    hairline: '#243350',
    ink: '#e9eef8',
    inkMuted: '#8fa0bd',
    containerFill: '#0a1120',
    containerInk: '#b6c4dc',
    edge: '#41537a',
    edgeLabel: '#a8b6cf',
    edgeLabelBg: '#0b1322',
    accent: '#1BE68B',
    accentSoft: 'rgba(27,230,139,0.18)',
    shadow: 'rgba(0,0,0,0.55)',
  },
};

// ─── Resource state → semantic tone ──────────────────────────────────────────

export type StateTone = 'healthy' | 'idle' | 'warning' | 'danger' | 'unknown';

const TONE_COLOR: Record<StateTone, string> = {
  healthy: '#10b981',
  idle: '#94a3b8',
  warning: '#f59e0b',
  danger: '#ef4444',
  unknown: '#64748b',
};

/**
 * Map a raw provider state string onto a tone.
 *
 * Values are the ones AWS actually returns across the types CloudListService
 * enumerates (EC2 instance-state, EBS volume-state, RDS DBInstanceStatus, ELB
 * State.Code, EKS cluster status). Unrecognised or absent states resolve to
 * 'unknown' and render a hollow dot, so "we don't know" never masquerades as
 * healthy.
 */
export function stateTone(state?: string | null): StateTone {
  if (!state) return 'unknown';
  const s = String(state).trim().toLowerCase();

  if (
    s === 'running' || s === 'available' || s === 'active' || s === 'in-use' ||
    s === 'inuse' || s === 'healthy' || s === 'ok' || s === 'attached' ||
    s === 'issued' || s === 'enabled' || s === 'succeeded'
  ) return 'healthy';

  if (
    s === 'stopped' || s === 'stopping' || s === 'shutting-down' ||
    s === 'terminated' || s === 'deleted' || s === 'inactive' ||
    s === 'disabled' || s === 'detached' || s === 'unused'
  ) return 'idle';

  if (
    s === 'pending' || s === 'creating' || s === 'modifying' ||
    s === 'provisioning' || s === 'updating' || s === 'rebooting' ||
    s === 'backing-up' || s === 'storage-optimization' || s === 'degraded' ||
    s === 'provisioned' || s === 'impaired'
  ) return 'warning';

  if (
    s === 'failed' || s === 'error' || s === 'unhealthy' ||
    s === 'inaccessible-encryption-credentials' || s === 'incompatible-network' ||
    s === 'restore-error' || s === 'expired' || s === 'revoked'
  ) return 'danger';

  return 'unknown';
}

// ─── SVG helpers ─────────────────────────────────────────────────────────────

/**
 * Escape text for inclusion in SVG markup.
 *
 * Resource names come from customer AWS tags, so they routinely contain & and
 * can contain < >. Unescaped, they produce a malformed data: URL that silently
 * renders nothing.
 */
function xmlEscape(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/**
 * Truncate to an approximate rendered width.
 *
 * Canvas-baked SVG can't measure text, so this budgets by average advance width
 * for the given size in the UI sans stack. Deliberately conservative: a name cut
 * one character early is invisible, a name overrunning the status dot is not.
 */
function ellipsize(value: string, maxPx: number, pxPerChar: number): string {
  const budget = Math.max(4, Math.floor(maxPx / pxPerChar));
  if (value.length <= budget) return value;
  return `${value.slice(0, budget - 1).trimEnd()}…`;
}

function svgDataUrl(svg: string): string {
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

// ─── Resource card ───────────────────────────────────────────────────────────

/** Node box for a live-mode resource card. */
export const CARD_W = 184;
export const CARD_H = 56;

/** Icon well geometry, shared by the chrome SVG and the icon overlay layer. */
const WELL_X = 10;
const WELL_Y = 12;
const WELL_SIZE = 32;
const ICON_INSET = 6;

const TEXT_X = WELL_X + WELL_SIZE + 11; // 53
const DOT_CX = CARD_W - 15;

const cardCache = new Map<string, string>();

interface CardSpec {
  name: string;
  type: string;
  state?: string | null;
  /** Extra right-hand metadata, e.g. a region. Rendered only if it fits. */
  meta?: string | null;
  theme: CanvasTheme;
  selected?: boolean;
}

/**
 * Render the full resource card to an SVG data URL.
 *
 * Everything except the service icon is baked here: card chrome, tinted icon
 * well, resource name, type caption, status dot. The icon itself is layered on
 * top by Cytoscape as a second background image, because it's an external file
 * that can't be inlined without fetching and base64-encoding it.
 */
export function resourceCardUrl(spec: CardSpec): string {
  const { name, type, state, meta, theme, selected } = spec;
  const key = `${theme}|${selected ? 's' : 'n'}|${type}|${state ?? ''}|${meta ?? ''}|${name}`;
  const cached = cardCache.get(key);
  if (cached) return cached;

  const t = CANVAS_TOKENS[theme];
  const typeInfo = NODE_TYPE_DATA[type];
  const palette = typeInfo
    ? TAILWIND_COLORS[typeInfo.color as keyof typeof TAILWIND_COLORS]
    : undefined;
  const accentInk = palette?.[500] ?? t.inkMuted;
  // Tint the well from the service hue: readable in light, subtle in dark.
  const wellFill = theme === 'light'
    ? (palette?.[100] ?? '#eef2f7')
    : 'rgba(255,255,255,0.05)';

  const tone = stateTone(state);
  const toneColor = TONE_COLOR[tone];

  const border = selected ? t.accent : t.hairline;
  const borderW = selected ? 2 : 1;

  const displayName = ellipsize(name || type, CARD_W - TEXT_X - 24, 6.7);
  /*
   * The caption line belongs to the type name.
   *
   * An earlier pass also right-anchored the region here. In practice it was
   * noise — a single-VPC view repeats the same region on every card — and it
   * squeezed the type badly enough that "Auto Scaling Group" and "ElastiCache
   * Cluster" both clipped. Region now lives on the VPC container label (which
   * already renders "name (region)") and in the inspector. `meta` is kept for
   * callers that genuinely span regions.
   */
  const captionBudget = CARD_W - TEXT_X - (meta ? 58 : 20);
  const displayType = ellipsize(type, captionBudget, 5.2);
  const displayMeta = meta ? ellipsize(meta, 52, 5.0) : '';

  // Unique per card so multiple <defs> on one canvas can't collide.
  const uid = `c${Math.abs(hashString(key)).toString(36)}`;

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${CARD_W}" height="${CARD_H}" viewBox="0 0 ${CARD_W} ${CARD_H}">
  <defs>
    <filter id="${uid}s" x="-12%" y="-12%" width="124%" height="140%">
      <feDropShadow dx="0" dy="1.5" stdDeviation="2.5" flood-color="${t.shadow}" flood-opacity="1"/>
    </filter>
  </defs>
  <g filter="url(#${uid}s)">
    <rect x="1.5" y="1.5" width="${CARD_W - 3}" height="${CARD_H - 5}" rx="10"
          fill="${t.card}" stroke="${border}" stroke-width="${borderW}"/>
  </g>
  <rect x="${WELL_X}" y="${WELL_Y}" width="${WELL_SIZE}" height="${WELL_SIZE}" rx="8"
        fill="${wellFill}" stroke="${accentInk}" stroke-opacity="${theme === 'light' ? 0.28 : 0.4}" stroke-width="1"/>
  <text x="${TEXT_X}" y="26" fill="${t.ink}"
        font-family="Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
        font-size="12.5" font-weight="600" letter-spacing="-0.1">${xmlEscape(displayName)}</text>
  <text x="${TEXT_X}" y="40" fill="${t.inkMuted}"
        font-family="Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
        font-size="9.5" font-weight="500" letter-spacing="0.25">${xmlEscape(displayType)}</text>
  ${displayMeta ? `<text x="${CARD_W - 13}" y="40" text-anchor="end" fill="${t.inkMuted}"
        font-family="ui-monospace, SFMono-Regular, Menlo, monospace"
        font-size="8.5" font-weight="500" opacity="0.9">${xmlEscape(displayMeta)}</text>` : ''}
  ${tone === 'unknown'
      ? `<circle cx="${DOT_CX}" cy="16" r="3.4" fill="none" stroke="${toneColor}" stroke-width="1.3"/>`
      : `<circle cx="${DOT_CX}" cy="16" r="3.8" fill="${toneColor}"/>
         <circle cx="${DOT_CX}" cy="16" r="3.8" fill="none" stroke="${t.card}" stroke-width="1.1"/>`}
</svg>`;

  const url = svgDataUrl(svg);
  // Names are unbounded (customer tags), so cap the cache rather than leak.
  if (cardCache.size > 4000) cardCache.clear();
  cardCache.set(key, url);
  return url;
}

function hashString(value: string): number {
  let h = 5381;
  for (let i = 0; i < value.length; i++) h = ((h << 5) + h + value.charCodeAt(i)) | 0;
  return h;
}

// ─── Level of detail ─────────────────────────────────────────────────────────

/**
 * Below this zoom, cards collapse to chips.
 *
 * No amount of styling makes a 12px name legible at 0.12 zoom, which is where
 * fitting a whole multi-VPC account lands. Rather than render text nobody can
 * read, the far view drops to icon + status and the near view carries the words —
 * so zooming becomes the way you read the graph.
 */
export const LOD_ZOOM_THRESHOLD = 0.42;

/** Chip box for the far view. Square so tiling stays predictable. */
export const CHIP_W = 60;
export const CHIP_H = 60;

const chipCache = new Map<string, string>();

/**
 * Compact far-view chip: icon well plus status ring, no text.
 */
export function resourceChipUrl(
  type: string,
  state: string | null | undefined,
  theme: CanvasTheme,
  selected?: boolean,
): string {
  const key = `${theme}|${selected ? 's' : 'n'}|${type}|${state ?? ''}`;
  const cached = chipCache.get(key);
  if (cached) return cached;

  const t = CANVAS_TOKENS[theme];
  const typeInfo = NODE_TYPE_DATA[type];
  const palette = typeInfo
    ? TAILWIND_COLORS[typeInfo.color as keyof typeof TAILWIND_COLORS]
    : undefined;
  const accentInk = palette?.[500] ?? t.inkMuted;
  const tone = stateTone(state);
  const toneColor = TONE_COLOR[tone];

  const border = selected ? t.accent : t.hairline;
  const borderW = selected ? 2.5 : 1.25;
  const uid = `p${Math.abs(hashString(key)).toString(36)}`;

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${CHIP_W}" height="${CHIP_H}" viewBox="0 0 ${CHIP_W} ${CHIP_H}">
  <defs>
    <filter id="${uid}s" x="-20%" y="-20%" width="150%" height="150%">
      <feDropShadow dx="0" dy="1.5" stdDeviation="2" flood-color="${t.shadow}" flood-opacity="1"/>
    </filter>
  </defs>
  <g filter="url(#${uid}s)">
    <rect x="4" y="4" width="${CHIP_W - 9}" height="${CHIP_H - 11}" rx="11"
          fill="${t.card}" stroke="${border}" stroke-width="${borderW}"/>
  </g>
  <rect x="4" y="4" width="${CHIP_W - 9}" height="${CHIP_H - 11}" rx="11"
        fill="none" stroke="${accentInk}" stroke-opacity="${theme === 'light' ? 0.22 : 0.3}" stroke-width="1"/>
  ${tone === 'unknown'
      ? `<circle cx="${CHIP_W - 9}" cy="9" r="4" fill="${t.card}" stroke="${toneColor}" stroke-width="1.5"/>`
      : `<circle cx="${CHIP_W - 9}" cy="9" r="4.6" fill="${toneColor}" stroke="${t.card}" stroke-width="1.4"/>`}
</svg>`;

  const url = svgDataUrl(svg);
  chipCache.set(key, url);
  return url;
}

/**
 * Apply the level-of-detail class for the current zoom.
 *
 * Returns true when the class actually changed, so the caller can skip a
 * redundant style pass on every zoom tick.
 */
export function applyLevelOfDetail(cy: any): boolean {
  if (!cy) return false;
  const far = cy.zoom() < LOD_ZOOM_THRESHOLD;
  const nodes = cy.nodes().filter((n: any) => !n.isParent());
  const alreadyFar = nodes.length > 0 && nodes[0].hasClass('cm-far');
  if (far === alreadyFar) return false;
  cy.batch(() => {
    if (far) nodes.addClass('cm-far');
    else nodes.removeClass('cm-far');
  });
  return true;
}

/**
 * Fallback icon well glyph for types with no file in AWS_ICONS: the type's
 * initials, drawn in the service hue.
 */
const glyphCache = new Map<string, string>();

export function typeGlyphUrl(type: string, theme: CanvasTheme): string {
  const key = `${theme}|${type}`;
  const cached = glyphCache.get(key);
  if (cached) return cached;

  const typeInfo = NODE_TYPE_DATA[type];
  const palette = typeInfo
    ? TAILWIND_COLORS[typeInfo.color as keyof typeof TAILWIND_COLORS]
    : undefined;
  const ink = palette?.[theme === 'light' ? 600 : 500] ?? CANVAS_TOKENS[theme].inkMuted;

  const initials = type
    .split(/[\s-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><text x="12" y="16.5" text-anchor="middle" fill="${ink}" font-family="Inter, system-ui, sans-serif" font-size="11" font-weight="700" letter-spacing="-0.3">${xmlEscape(initials)}</text></svg>`;
  const url = svgDataUrl(svg);
  glyphCache.set(key, url);
  return url;
}

// ─── Edge semantics ──────────────────────────────────────────────────────────

/**
 * Relationship class per edge label, so the graph carries meaning instead of
 * rendering every relationship as the same grey line. Labels are the ones
 * CloudMapService.createAllEdges emits.
 */
export type EdgeKind = 'attach' | 'security' | 'traffic' | 'manage' | 'generic';

export function edgeKind(label?: string | null): EdgeKind {
  if (!label) return 'generic';
  const l = label.toLowerCase();
  if (l.includes('sg') || l.includes('assumes')) return 'security';
  if (l.includes('routes') || l.includes('targets') || l.includes('behind')) return 'traffic';
  if (l.includes('manages') || l.includes('registers')) return 'manage';
  if (l.includes('attach') || l.includes('assigned') || l.includes('uses eip')) return 'attach';
  return 'generic';
}

const EDGE_TONE: Record<EdgeKind, { light: string; dark: string; dashed: boolean }> = {
  attach:   { light: '#64748b', dark: '#64769c', dashed: false },
  security: { light: '#dc2626', dark: '#f87171', dashed: true },
  traffic:  { light: '#0284c7', dark: '#38bdf8', dashed: false },
  manage:   { light: '#7c3aed', dark: '#a78bfa', dashed: true },
  generic:  { light: '#94a3b8', dark: '#41537a', dashed: false },
};

// ─── Stylesheet ──────────────────────────────────────────────────────────────

/**
 * Build the Cytoscape stylesheet.
 *
 * @param theme  Tracks the app's ThemeContext so the canvas stops being a white
 *               slab inside the #020617 shell.
 * @param mode   'live' bakes full resource cards; 'design' keeps native labels
 *               so inline editing still works.
 */
export function buildCytoscapeStyle(theme: CanvasTheme, mode: 'live' | 'design'): any[] {
  const t = CANVAS_TOKENS[theme];
  const live = mode === 'live';

  const style: any[] = [];

  // ── Live-mode resource cards ───────────────────────────────────────────────
  if (live) {
    style.push({
      selector: 'node',
      style: {
        shape: 'round-rectangle',
        width: CARD_W,
        height: CARD_H,
        label: '',
        'background-color': 'transparent',
        'background-opacity': 0,
        'border-width': 0,
        'background-clip': ['none', 'none'],
        'background-fit': ['none', 'none'],
        'background-image-containment': ['over', 'over'],
        'background-image': (ele: any) => [
          resourceCardUrl({
            name: ele.data('label') || ele.data('id'),
            type: ele.data('type') || 'Resource',
            state: ele.data('state') ?? ele.data('State'),
            // Region deliberately omitted: it collided with long type names
            // ("DynamoDB Table" + "ap-south-1" ran together) and repeats
            // identically on every card in a single-region view. The VPC
            // container label and the inspector both carry it.
            theme,
            selected: ele.selected(),
          }),
          iconLayerUrl(ele.data('type'), theme),
        ],
        'background-width': [CARD_W, WELL_SIZE - ICON_INSET * 2],
        'background-height': [CARD_H, WELL_SIZE - ICON_INSET * 2],
        'background-position-x': [0, WELL_X + ICON_INSET],
        'background-position-y': [0, WELL_Y + ICON_INSET],
        'background-offset-x': 0,
        'background-offset-y': 0,
        'z-index': 10,
      },
    });

    // Far view: chips. Zoomed out past LOD_ZOOM_THRESHOLD the names are
    // sub-pixel anyway, so drop them and keep shape, service and status.
    style.push({
      selector: 'node.cm-far',
      style: {
        width: CHIP_W,
        height: CHIP_H,
        'background-image': (ele: any) => [
          resourceChipUrl(
            ele.data('type') || 'Resource',
            ele.data('state') ?? ele.data('State'),
            theme,
            ele.selected(),
          ),
          iconLayerUrl(ele.data('type'), theme),
        ],
        'background-width': [CHIP_W, 26],
        'background-height': [CHIP_H, 26],
        'background-position-x': [0, (CHIP_W - 5 - 26) / 2 + 2],
        'background-position-y': [0, (CHIP_H - 7 - 26) / 2 + 2],
      },
    });

    // Selection is redrawn into the card (accent border) rather than layered as
    // a Cytoscape outline, so it reads at any zoom.
    style.push({
      selector: 'node:selected',
      style: { 'z-index': 30 },
    });

    // Containers must not inherit the card background.
    style.push({
      selector: ':parent',
      style: {
        shape: 'round-rectangle',
        'background-image': 'none',
        'background-opacity': 1,
        'background-color': t.containerFill,
        'border-width': 1.5,
        'border-style': 'dashed',
        'border-color': t.hairline,
        label: 'data(label)',
        'text-valign': 'top',
        'text-halign': 'center',
        'text-margin-y': -9,
        'font-size': '13px',
        'font-weight': 700,
        'letter-spacing': 0.2,
        color: t.containerInk,
        'text-wrap': 'ellipsis',
        'text-max-width': '320px',
        padding: '38px',
        'z-index': 1,
      },
    });
  } else {
    // ── Design mode: native labels, upgraded chrome ─────────────────────────
    style.push({
      selector: 'node',
      style: {
        shape: 'round-rectangle',
        label: 'data(label)',
        'font-size': '11px',
        'font-weight': 600,
        'text-valign': 'bottom',
        'text-halign': 'center',
        'text-margin-y': 7,
        'text-wrap': 'ellipsis',
        'text-max-width': '104px',
        color: t.ink,
        'text-outline-width': 2,
        'text-outline-color': t.canvas,
        'text-outline-opacity': 0.85,
        width: 54,
        height: 54,
        'background-color': t.card,
        'background-opacity': 1,
        'background-fit': 'none',
        'background-clip': 'none',
        'background-width': 30,
        'background-height': 30,
        'background-image': (ele: any) => {
          const type = ele.data('type');
          return type && type !== 'shape' ? iconLayerUrl(type, theme) : 'none';
        },
        'border-width': 1,
        'border-color': t.hairline,
        'corner-radius': 10,
        'z-index': 10,
      },
    });

    style.push({
      selector: 'node:selected',
      style: {
        'border-color': t.accent,
        'border-width': 2.5,
        'overlay-color': t.accent,
        'overlay-opacity': 0.1,
        'overlay-padding': 5,
        'z-index': 30,
      },
    });

    style.push({
      selector: ':parent',
      style: {
        shape: 'round-rectangle',
        'background-image': 'none',
        'background-color': t.containerFill,
        'background-opacity': 1,
        'border-width': 1.5,
        'border-style': 'dashed',
        'border-color': t.hairline,
        'text-valign': 'top',
        'text-halign': 'center',
        'text-margin-y': -9,
        'font-size': '12px',
        'font-weight': 700,
        color: t.containerInk,
        padding: '34px',
        'z-index': 1,
      },
    });

    // Per-type tint for palette-dropped nodes, which have no baked card.
    for (const [type, info] of Object.entries(NODE_TYPE_DATA)) {
      const palette = TAILWIND_COLORS[info.color as keyof typeof TAILWIND_COLORS];
      if (!palette) continue;
      style.push({
        selector: `node[type="${type}"]`,
        style: {
          'background-color': theme === 'light' ? '#ffffff' : t.card,
          'border-color': palette[500],
          'border-width': 1.5,
        },
      });
    }
  }

  // ── Container variants, both modes ────────────────────────────────────────
  const containerTone = (border: string, fillLight: string) => ({
    'border-color': border,
    'background-color': theme === 'light' ? fillLight : t.containerFill,
    'background-opacity': 1,
  });

  style.push(
    { selector: 'node[containerType="vpc"], node[type="VPC"]:parent',
      style: { ...containerTone(theme === 'light' ? '#64748b' : '#3d4f73', '#f1f5f9'), 'border-width': 2 } },
    { selector: 'node[containerType="subnet"], node[type="Subnet"]:parent',
      style: { ...containerTone(theme === 'light' ? '#0284c7' : '#1f5f86', '#eff8ff'), 'border-width': 1.5 } },
    { selector: 'node[containerType="az"]',
      style: { ...containerTone(theme === 'light' ? '#ea580c' : '#7c4318', '#fff8f2'), 'border-width': 1.5 } },
    { selector: 'node[containerType="sg"]',
      style: { ...containerTone(theme === 'light' ? '#dc2626' : '#7f2d2d', '#fff5f5'), 'border-width': 1.5 } },
    { selector: 'node[containerType="region"]',
      style: { ...containerTone(theme === 'light' ? '#94a3b8' : '#33405c', '#f8fafc'), 'border-width': 1.5 } },
  );

  // ── Edges ─────────────────────────────────────────────────────────────────
  style.push({
    selector: 'edge',
    style: {
      width: 1.6,
      'line-color': t.edge,
      'target-arrow-color': t.edge,
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.85,
      'curve-style': 'bezier',
      opacity: 0.85,
      label: 'data(label)',
      'font-size': '9.5px',
      'font-weight': 600,
      color: t.edgeLabel,
      'text-background-color': t.edgeLabelBg,
      'text-background-opacity': 0.92,
      'text-background-padding': '3px',
      'text-background-shape': 'roundrectangle',
      'text-rotation': 'autorotate',
      'source-endpoint': 'outside-to-line',
      'target-endpoint': 'outside-to-line',
      'z-index': 5,
    } as any,
  });

  for (const [kind, tone] of Object.entries(EDGE_TONE)) {
    if (kind === 'generic') continue;
    style.push({
      selector: `edge[kind="${kind}"]`,
      style: {
        'line-color': tone[theme],
        'target-arrow-color': tone[theme],
        'line-style': tone.dashed ? 'dashed' : 'solid',
        'line-dash-pattern': tone.dashed ? [5, 4] : undefined,
      },
    });
  }

  style.push({
    selector: 'edge:selected',
    style: {
      'line-color': t.accent,
      'target-arrow-color': t.accent,
      width: 2.6,
      opacity: 1,
      'z-index': 40,
    },
  });

  // ── Focus / dim, for tracing a path without deleting context ─────────────
  style.push(
    { selector: '.cm-dimmed', style: { opacity: 0.2, 'text-opacity': 0.2 } },
    { selector: '.cm-highlight', style: { 'z-index': 50, opacity: 1, 'text-opacity': 1 } },
    { selector: 'edge.cm-highlight', style: { width: 2.6, 'line-color': t.accent, 'target-arrow-color': t.accent } },
  );

  // ── Search hit ────────────────────────────────────────────────────────────
  style.push({
    selector: 'node.cm-found',
    style: {
      'overlay-color': t.accent,
      'overlay-opacity': 0.22,
      'overlay-padding': 8,
      'z-index': 60,
    },
  });

  // ── Locked ────────────────────────────────────────────────────────────────
  style.push({
    selector: 'node.locked-node',
    style: { 'border-color': '#f59e0b', 'border-width': 2.5 },
  });

  // ── Generic design shapes ────────────────────────────────────────────────
  style.push({
    selector: 'node[type="shape"]',
    style: {
      'background-image': 'none',
      'background-color': theme === 'light' ? '#e8f0fe' : '#152239',
      'border-color': theme === 'light' ? '#3b82f6' : '#3f6ea8',
      'border-width': 1.5,
      'background-opacity': 1,
      'text-valign': 'center',
      'text-halign': 'center',
      'text-outline-width': 0,
      label: 'data(label)',
      width: 96,
      height: 56,
      'z-index': 10,
    },
  });

  style.push({
    selector: 'node[customIcon]',
    style: {
      shape: 'round-rectangle',
      'background-color': t.card,
      'border-color': t.hairline,
      'border-width': 1,
      'background-opacity': 1,
      label: 'data(label)',
      'text-valign': 'bottom',
      'text-halign': 'center',
      'text-margin-y': 7,
      'font-size': '11px',
      'font-weight': 600,
      color: t.ink,
      width: 60,
      height: 56,
      'text-wrap': 'ellipsis',
      'text-max-width': '96px',
    },
  });

  // ── Edge handles ─────────────────────────────────────────────────────────
  style.push(
    { selector: '.eh-handle', style: {
      'background-color': t.accent,
      'background-image': 'none',
      'border-color': theme === 'light' ? '#ffffff' : '#020617',
      'border-width': 2,
      'border-opacity': 1,
      width: 14,
      height: 14,
      shape: 'ellipse',
      label: '',
      'overlay-opacity': 0,
      'z-index': 9999,
    } },
    { selector: '.eh-hover', style: { 'background-color': t.accent, 'border-width': 3 } },
    { selector: '.eh-source, .eh-target', style: { 'border-width': 2.5, 'border-color': t.accent } },
    { selector: '.eh-preview, .eh-ghost-edge', style: {
      'background-color': t.accent,
      'line-color': t.accent,
      'target-arrow-color': t.accent,
      'source-arrow-color': t.accent,
      'line-style': 'dashed',
    } },
  );

  return style;
}

/** Service icon file when one exists, else baked initials. */
function iconLayerUrl(type: string | undefined, theme: CanvasTheme): string {
  if (!type) return typeGlyphUrl('Resource', theme);
  return AWS_ICONS[type] ?? typeGlyphUrl(type, theme);
}

/** Id of the synthetic container that collects resources with no VPC. */
export const GLOBAL_SCOPE_ID = '__cm-global-scope';

/**
 * Types that define scope rather than sit inside it.
 *
 * These can never be "not in a VPC", so they must never be swept into the global
 * box — checking only for a missing `parent` is not enough. The all-VPCs view
 * merges one synthetic `<vpc>::vpc-root` node per VPC, carrying `type: 'VPC'` with
 * no parent and no isContainer flag. On an account whose VPCs come back with no
 * subnets or resources, nothing references those roots as a parent either, so
 * every VPC looked like a loose resource and 30 of them were grouped under
 * "not in a VPC" — which is both wrong and worse than showing nothing.
 */
const STRUCTURAL_TYPES = new Set([
  'VPC', 'Subnet', 'Availability Zone', 'Region', 'Scope',
]);

function isStructuralType(type: unknown): boolean {
  return typeof type === 'string' && STRUCTURAL_TYPES.has(type);
}

/**
 * Gather parentless resources into one synthetic "Global" compound.
 *
 * Regional services (S3, SQS, DynamoDB, CloudWatch) legitimately have no VPC, so
 * CloudMapService emits them with no parent. Left loose they behave badly in two
 * ways: the force layout flings them to opposite far corners — measured at
 * x=-928 and x=+928 around a VPC only 1015 wide, which doubled the bounding box
 * and cost ~30% of the fitted zoom — and, worse, the picture silently implies
 * they are unrelated strays rather than account-scoped services.
 *
 * One labelled box fixes both: the layout keeps it compact, and the diagram now
 * states why those resources sit outside the VPC.
 *
 * Frontend-only on purpose: the backend graph contract is unchanged, so this is
 * reversible and cannot affect saved blueprints.
 */
export function groupOrphanResources<T extends { group?: string; data: any }>(elements: T[]): T[] {
  const parentIds = new Set<string>();
  for (const el of elements) {
    const p = el.data?.parent;
    if (p) parentIds.add(String(p));
  }

  const isEdge = (el: T) => el.group === 'edges' || (el.data?.source && el.data?.target);

  const orphans = elements.filter((el) =>
    !isEdge(el) &&
    !el.data?.parent &&
    !parentIds.has(String(el.data?.id)) &&   // not currently a container
    !el.data?.isContainer &&
    !isStructuralType(el.data?.type) &&
    el.data?.id !== GLOBAL_SCOPE_ID);

  // One lone orphan reads fine on its own; a box around it is just chrome.
  if (orphans.length < 2) return elements;

  const container = {
    group: 'nodes',
    data: {
      id: GLOBAL_SCOPE_ID,
      label: 'Global · not in a VPC',
      type: 'Scope',
      isContainer: true,
      containerType: 'region',
    },
  } as unknown as T;

  const orphanIds = new Set(orphans.map((o) => String(o.data.id)));
  return [
    container,
    ...elements.map((el) =>
      orphanIds.has(String(el.data?.id))
        ? ({ ...el, data: { ...el.data, parent: GLOBAL_SCOPE_ID } } as T)
        : el),
  ];
}

// ─── Deterministic hierarchical layout ───────────────────────────────────────

interface PackItem {
  id: string;
  w: number;
  h: number;
  /** Assigned during packing: offset of this item's top-left within its parent. */
  x?: number;
  y?: number;
}

/**
 * Shelf-pack items into rows, targeting a landscape block.
 *
 * Rows are filled left to right and wrapped at a width chosen from the total
 * area, so a container ends up roughly 1.6:1 rather than a long strip or a
 * column. Non-overlapping by construction.
 */
function shelfPack(
  items: PackItem[],
  gapX: number,
  gapY: number,
  preferredWidth = 0,
): { w: number; h: number } {
  if (items.length === 0) return { w: 0, h: 0 };

  const area = items.reduce((sum, it) => sum + (it.w + gapX) * (it.h + gapY), 0);
  const widest = Math.max(...items.map((it) => it.w));
  /*
   * The area heuristic alone gives a bad answer for a handful of wide items: three
   * 184px cards produced a target of 285px, so they stacked into a single column
   * under subnets that were already ~1300px wide, leaving a large void beside
   * them. preferredWidth lets a caller say "you have this much room" so loose
   * VPC-level resources spread along the width their sibling subnets establish.
   */
  const maxWidth = Math.max(widest, preferredWidth, Math.sqrt(area * 1.7));

  let x = 0;
  let y = 0;
  let rowH = 0;
  let usedW = 0;

  for (const it of items) {
    if (x > 0 && x + it.w > maxWidth) {
      x = 0;
      y += rowH + gapY;
      rowH = 0;
    }
    it.x = x;
    it.y = y;
    x += it.w + gapX;
    rowH = Math.max(rowH, it.h);
    usedW = Math.max(usedW, x - gapX);
  }

  return { w: usedW, h: y + rowH };
}

/**
 * Lay the live topology out deterministically instead of by force simulation.
 *
 * Force layout earns its keep when position carries meaning. Here it does not:
 * every resource is an identical 184x56 card, and the meaning lives entirely in
 * the containment hierarchy (VPC > subnet > resource) that the backend already
 * computes. What fcose actually produced was voids — a subnet stretched to
 * 1115px tall because one Lambda drifted to the bottom, ALB parked in a far
 * corner of its subnet, and six pairs of cards overlapping outright.
 *
 * Shelf packing, bottom-up, gives tidy dense containers, zero overlap by
 * construction, a stable picture across refreshes (force layouts jitter), and it
 * is far cheaper on large accounts. Cards are ordered by type then name so the
 * eye can find things, which is more use to an operator than force placement.
 */
export function layoutHierarchicalGrid(cy: any): void {
  if (!cy) return;

  const GAP_X = 26;
  const GAP_Y = 20;
  const COMPOUND_GAP_X = 52;
  const COMPOUND_GAP_Y = 46;
  // Matches the 38px style padding, plus headroom for the container label.
  const PAD = 40;
  const LABEL_ROOM = 30;

  const sizes = new Map<string, { w: number; h: number }>();
  const offsets = new Map<string, PackItem[]>();

  const sortLeaves = (a: any, b: any) => {
    const ta = String(a.data('type') ?? '');
    const tb = String(b.data('type') ?? '');
    if (ta !== tb) return ta.localeCompare(tb);
    return String(a.data('label') ?? a.id()).localeCompare(String(b.data('label') ?? b.id()));
  };

  /** Bottom-up: measure a node, recording how its children pack inside it. */
  function measure(node: any): { w: number; h: number } {
    if (!node.isParent()) {
      const box = { w: CARD_W, h: CARD_H };
      sizes.set(node.id(), box);
      return box;
    }

    const kids = node.children();
    const compounds = kids.filter((c: any) => c.isParent()).toArray();
    const leaves = kids.filter((c: any) => !c.isParent()).toArray().sort(sortLeaves);

    compounds.forEach(measure);
    leaves.forEach(measure);

    // Child containers pack as one block above the loose cards, so a VPC reads
    // as "these subnets, plus these VPC-level resources".
    const compItems: PackItem[] = compounds.map((c: any) => ({ id: c.id(), ...sizes.get(c.id())! }));
    const leafItems: PackItem[] = leaves.map((l: any) => ({ id: l.id(), ...sizes.get(l.id())! }));

    const compBox = shelfPack(compItems, COMPOUND_GAP_X, COMPOUND_GAP_Y);
    // Loose resources spread across whatever width the child containers set.
    const leafBox = shelfPack(leafItems, GAP_X, GAP_Y, compBox.w);

    const innerW = Math.max(compBox.w, leafBox.w);
    const gapBetween = compItems.length && leafItems.length ? COMPOUND_GAP_Y : 0;
    const innerH = compBox.h + gapBetween + leafBox.h;

    // Centre each block horizontally within the container.
    for (const it of compItems) it.x! += (innerW - compBox.w) / 2;
    for (const it of leafItems) {
      it.x! += (innerW - leafBox.w) / 2;
      it.y! += compBox.h + gapBetween;
    }

    offsets.set(node.id(), [...compItems, ...leafItems]);

    const box = { w: innerW + PAD * 2, h: innerH + PAD * 2 + LABEL_ROOM };
    sizes.set(node.id(), box);
    return box;
  }

  /** Top-down: convert packed offsets into absolute centre positions. */
  function place(node: any, topLeftX: number, topLeftY: number): void {
    const size = sizes.get(node.id())!;
    if (!node.isParent()) {
      node.position({ x: topLeftX + size.w / 2, y: topLeftY + size.h / 2 });
      return;
    }
    const innerX = topLeftX + PAD;
    const innerY = topLeftY + PAD + LABEL_ROOM;
    for (const item of offsets.get(node.id()) ?? []) {
      place(cy.getElementById(item.id), innerX + item.x!, innerY + item.y!);
    }
  }

  const roots = cy.nodes().filter((n: any) => n.ancestors().length === 0);
  const rootCompounds = roots.filter((n: any) => n.isParent()).toArray();
  const rootLeaves = roots.filter((n: any) => !n.isParent()).toArray().sort(sortLeaves);

  rootCompounds.forEach(measure);
  rootLeaves.forEach(measure);

  // The global-scope box is an appendix; keep it last so it lands on the right.
  const ordered = [
    ...rootCompounds.filter((n: any) => n.id() !== GLOBAL_SCOPE_ID),
    ...rootCompounds.filter((n: any) => n.id() === GLOBAL_SCOPE_ID),
  ];

  const rootItems: PackItem[] = [
    ...ordered.map((c: any) => ({ id: c.id(), ...sizes.get(c.id())! })),
    ...rootLeaves.map((l: any) => ({ id: l.id(), ...sizes.get(l.id())! })),
  ];

  /*
   * Keep a small number of top-level boxes on one row.
   *
   * The area heuristic wrapped a two-item root (one VPC plus the global box) into
   * a column, making the whole diagram portrait — 872x1033 in a landscape panel,
   * which cost 20% of the fitted zoom. Canvases are wider than they are tall, so
   * bias the top level toward a row and only let it wrap once there are enough
   * VPCs that a single row would be worse.
   */
  const rootPreferred = rootItems.length <= 4
    ? rootItems.reduce((sum, it) => sum + it.w, 0) + COMPOUND_GAP_X * (rootItems.length - 1)
    : 0;
  shelfPack(rootItems, COMPOUND_GAP_X, COMPOUND_GAP_Y, rootPreferred);

  cy.batch(() => {
    for (const item of rootItems) {
      place(cy.getElementById(item.id), item.x!, item.y!);
    }
  });
}

/**
 * Stamp edge.data('kind') so the semantic edge selectors above can match.
 * Cytoscape can't derive a class from a label at style time.
 */
export function tagEdgeKinds(cy: any): void {
  if (!cy) return;
  cy.batch(() => {
    cy.edges().forEach((e: any) => {
      if (e.data('kind')) return;
      e.data('kind', edgeKind(e.data('label')));
    });
  });
}
