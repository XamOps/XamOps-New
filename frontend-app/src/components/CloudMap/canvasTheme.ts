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
import { TIER_ORDER as TIER_RANK } from './liveTopology';

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

// ─── Icon preloading ─────────────────────────────────────────────────────────

/**
 * Service icons as data URLs, so they can be baked into the card SVG.
 *
 * An SVG used as an image cannot load an external file, but it can draw a nested data:
 * URL. Fetching each icon once and inlining it lets the whole card — chrome, icon, name,
 * caption, status — be one background image. That is what finally makes the card render
 * the same in every browser: the two-layer attempt mis-indexed its layers, and the
 * single-layer 'contain' icon ignored its own width and filled the card height.
 */
const iconDataCache = new Map<string, string>();
const iconInflight = new Map<string, Promise<void>>();

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}

/**
 * Re-encode an icon as a small PNG before it is baked into cards.
 *
 * Source icons are up to 512px. Every card carries its own copy, so on a 700-resource
 * account full-size icons meant ~13MB of data URLs for the canvas to decode. A 64px
 * raster is crisp at 30px on a 2x display and a few KB. Falls back to the original when
 * there is no canvas (tests) or decoding fails.
 */
function downscaleIcon(dataUrl: string): Promise<string> {
  const SIZE = 64;
  if (typeof document === 'undefined' || typeof Image === 'undefined') return Promise.resolve(dataUrl);
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        canvas.width = SIZE;
        canvas.height = SIZE;
        const ctx = canvas.getContext('2d');
        if (!ctx) return resolve(dataUrl);
        const scale = Math.min(SIZE / (img.naturalWidth || SIZE), SIZE / (img.naturalHeight || SIZE));
        const w = (img.naturalWidth || SIZE) * scale;
        const h = (img.naturalHeight || SIZE) * scale;
        ctx.drawImage(img, (SIZE - w) / 2, (SIZE - h) / 2, w, h);
        resolve(canvas.toDataURL('image/png'));
      } catch {
        resolve(dataUrl);
      }
    };
    img.onerror = () => resolve(dataUrl);
    img.src = dataUrl;
  });
}

function loadIcon(url: string): Promise<void> {
  if (url.startsWith('data:')) { iconDataCache.set(url, url); return Promise.resolve(); }
  if (iconDataCache.has(url)) return Promise.resolve();
  const existing = iconInflight.get(url);
  if (existing) return existing;
  const p = fetch(url)
    .then((res) => (res.ok ? res.blob() : Promise.reject(new Error(String(res.status)))))
    .then(blobToDataUrl)
    .then(downscaleIcon)
    .then((data) => { iconDataCache.set(url, data); })
    // A missing icon must not block the map: the card falls back to initials.
    .catch(() => { iconDataCache.set(url, ''); })
    .finally(() => { iconInflight.delete(url); });
  iconInflight.set(url, p);
  return p;
}

/** Fetch every icon the given types need. Resolves even if some fail. */
export function preloadCardIcons(types: Iterable<string>): Promise<void> {
  const urls = new Set<string>();
  for (const t of types) {
    const url = AWS_ICONS[t];
    if (url) urls.add(url);
  }
  return Promise.all([...urls].map(loadIcon)).then(() => undefined);
}

/** Inline icon for a type, or '' when none is loaded. */
function inlineIconFor(type: string): string {
  const url = AWS_ICONS[type];
  return url ? iconDataCache.get(url) ?? '' : '';
}

/** Monochrome globe for the synthetic Internet node; no AWS icon exists for it. */
function globeSvg(x: number, y: number, size: number, ink: string): string {
  const s = size / 24;
  return `<g transform="translate(${x} ${y}) scale(${s})" fill="none" stroke="${ink}" stroke-width="1.5" stroke-linecap="round">
    <circle cx="12" cy="12" r="9.5"/>
    <path d="M2.5 12h19M12 2.5c2.8 2.6 4.2 5.8 4.2 9.5s-1.4 6.9-4.2 9.5M12 2.5C9.2 5.1 7.8 8.3 7.8 12s1.4 6.9 4.2 9.5"/>
  </g>`;
}

// ─── Resource card ───────────────────────────────────────────────────────────

/**
 * Inner padding of each live-mode container, shared by the stylesheet and the layout so the
 * layout's idea of a box matches what Cytoscape draws.
 */
export const CONTAINER_PAD = { VPC: 34, az: 18, Subnet: 16, default: 30 } as const;

/** Box for a VPC with no workload, which the live view collapses. */
export const COLLAPSED_VPC = { w: 280, h: 68 } as const;

/** Node box for a live-mode resource card. */
export const CARD_W = 200;
export const CARD_H = 60;

/** Icon well geometry, shared by the chrome SVG and the icon overlay layer. */
const WELL_X = 10;
const WELL_Y = 12;
const WELL_SIZE = 32;

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

/**
 * The live card, fully baked: chrome, service icon, name, caption and status in one SVG.
 *
 * Colour is reserved for meaning. The card and its text are greyscale; the only hues are the
 * provider's own service icon and the status dot (green running, amber transitional, red
 * failed, grey stopped). An unknown state draws no dot rather than a guess.
 */
export function liveCardUrl(spec: {
  name: string;
  type: string;
  state?: string | null;
  theme: CanvasTheme;
  selected?: boolean;
}): string {
  const { name, type, state, theme, selected } = spec;
  const icon = type === 'Internet' ? 'globe' : inlineIconFor(type);
  const key = `L|${theme}|${selected ? 's' : 'n'}|${type}|${state ?? ''}|${icon.length}|${name}`;
  const cached = cardCache.get(key);
  if (cached) return cached;

  const t = CANVAS_TOKENS[theme];
  const tone = stateTone(state);
  const W = CARD_W;
  const H = CARD_H;
  const ICON = 30;
  const IX = 13;
  const IY = (H - ICON) / 2;
  const TX = IX + ICON + 12;
  const font = "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif";

  let iconMarkup: string;
  if (icon === 'globe') {
    iconMarkup = globeSvg(IX + 1, IY + 1, ICON - 2, t.ink);
  } else if (icon) {
    iconMarkup = `<image href="${icon}" x="${IX}" y="${IY}" width="${ICON}" height="${ICON}" preserveAspectRatio="xMidYMid meet"/>`;
  } else {
    const initials = type.split(/[\s-]+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase() ?? '').join('');
    iconMarkup = `<rect x="${IX}" y="${IY}" width="${ICON}" height="${ICON}" rx="7" fill="${theme === 'light' ? '#f1f5f9' : 'rgba(255,255,255,0.06)'}" stroke="${t.hairline}"/>
      <text x="${IX + ICON / 2}" y="${IY + ICON / 2 + 4}" text-anchor="middle" fill="${t.inkMuted}" font-family="${font}" font-size="11" font-weight="700">${xmlEscape(initials)}</text>`;
  }

  // Say the state in words when it is not the normal one, so colour is never the only cue.
  const stateWord = state && tone !== 'healthy' && tone !== 'unknown' ? String(state).toLowerCase() : '';
  const caption = type === 'Internet' ? 'Public internet' : stateWord ? `${type} · ${stateWord}` : type;
  const hasDot = tone !== 'unknown';
  const nameBudget = W - TX - (hasDot ? 26 : 14);
  const displayName = ellipsize(name || type, nameBudget, 6.9);
  const displayCaption = ellipsize(caption, W - TX - 12, 5.6);

  const border = selected ? t.accent : t.hairline;
  const borderW = selected ? 2 : 1;
  const uid = `l${Math.abs(hashString(key)).toString(36)}`;

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <defs>
    <filter id="${uid}s" x="-10%" y="-20%" width="120%" height="150%">
      <feDropShadow dx="0" dy="1" stdDeviation="1.6" flood-color="${t.shadow}" flood-opacity="${theme === 'light' ? 0.55 : 1}"/>
    </filter>
  </defs>
  <rect x="2" y="2" width="${W - 4}" height="${H - 5}" rx="10" fill="${t.card}" filter="url(#${uid}s)"/>
  <rect x="2" y="2" width="${W - 4}" height="${H - 5}" rx="10" fill="none" stroke="${border}" stroke-width="${borderW}"/>
  ${iconMarkup}
  <text x="${TX}" y="${H / 2 - 3}" fill="${t.ink}" font-family="${font}" font-size="12.5" font-weight="600" letter-spacing="-0.1">${xmlEscape(displayName)}</text>
  <text x="${TX}" y="${H / 2 + 12}" fill="${t.inkMuted}" font-family="${font}" font-size="10" font-weight="500">${xmlEscape(displayCaption)}</text>
  ${hasDot ? `<circle cx="${W - 15}" cy="${H / 2 - 7}" r="3.6" fill="${TONE_COLOR[tone]}"/>` : ''}
</svg>`;

  const url = svgDataUrl(svg);
  if (cardCache.size > 4000) cardCache.clear();
  cardCache.set(key, url);
  return url;
}

/** Far-zoom chip: the service icon on a card, plus the status dot. No text. */
export function liveChipUrl(type: string, state: string | null | undefined, theme: CanvasTheme, selected?: boolean): string {
  const icon = type === 'Internet' ? 'globe' : inlineIconFor(type);
  const key = `C|${theme}|${selected ? 's' : 'n'}|${type}|${state ?? ''}|${icon.length}`;
  const cached = chipCache.get(key);
  if (cached) return cached;
  const t = CANVAS_TOKENS[theme];
  const tone = stateTone(state);
  const S = CHIP_W;
  const I = 34;
  const o = (S - I) / 2;
  const iconMarkup = icon === 'globe'
    ? globeSvg(o, o, I, t.ink)
    : icon
      ? `<image href="${icon}" x="${o}" y="${o}" width="${I}" height="${I}" preserveAspectRatio="xMidYMid meet"/>`
      : `<rect x="${o}" y="${o}" width="${I}" height="${I}" rx="8" fill="${t.hairline}"/>`;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${S}" height="${S}" viewBox="0 0 ${S} ${S}">
  <rect x="2" y="2" width="${S - 4}" height="${S - 4}" rx="12" fill="${t.card}" stroke="${selected ? t.accent : t.hairline}" stroke-width="${selected ? 2.5 : 1.2}"/>
  ${iconMarkup}
  ${tone !== 'unknown' ? `<circle cx="${S - 10}" cy="10" r="5" fill="${TONE_COLOR[tone]}" stroke="${t.card}" stroke-width="1.5"/>` : ''}
</svg>`;
  const url = svgDataUrl(svg);
  chipCache.set(key, url);
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

  // ── Live mode: baked cards, architecture containers ────────────────────────
  if (live) {
    /*
     * One background image per card, sized to the whole node, carrying everything the card
     * shows. The label stays empty: the name is baked into the image so it can sit beside the
     * icon with a caption under it, which Cytoscape's single label cannot lay out.
     */
    style.push({
      selector: 'node',
      style: {
        shape: 'round-rectangle',
        width: CARD_W,
        height: CARD_H,
        'background-color': t.canvas,
        'background-opacity': 0,
        'border-width': 0,
        'background-image': (ele: any) => liveCardUrl({
          name: ele.data('label'), type: ele.data('type'), state: ele.data('state'), theme,
        }),
        'background-fit': 'contain',
        'background-width': '100%',
        'background-height': '100%',
        'background-clip': 'none',
        label: '',
        'overlay-opacity': 0,
        'z-index': 10,
      },
    });

    style.push({
      selector: 'node:selected',
      style: {
        'background-image': (ele: any) => liveCardUrl({
          name: ele.data('label'), type: ele.data('type'), state: ele.data('state'), theme, selected: true,
        }),
        'z-index': 30,
      },
    });

    // Far view: icon chips. A 12px name at 0.2 zoom is noise.
    style.push({
      selector: 'node.cm-far',
      style: {
        width: CHIP_W,
        height: CHIP_H,
        'background-image': (ele: any) => liveChipUrl(ele.data('type'), ele.data('state'), theme, ele.selected()),
      },
    });

    // ── Containers ──────────────────────────────────────────────────────────
    const containerBase = {
      shape: 'round-rectangle',
      'background-image': 'none',
      'background-opacity': 1,
      'background-color': t.containerFill,
      'border-width': 1,
      'border-style': 'solid',
      'border-color': t.hairline,
      label: (ele: any) => ele.data('displayLabel') || ele.data('label') || '',
      'text-valign': 'top',
      'text-halign': 'center',
      'text-margin-y': -8,
      color: t.containerInk,
      'font-family': 'Inter, -apple-system, BlinkMacSystemFont, sans-serif',
      'text-wrap': 'ellipsis',
      'text-max-width': '640px',
      'min-width-bias-left': '0%',
      'min-width-bias-right': '100%',
      'min-height-bias-top': '0%',
      'min-height-bias-bottom': '100%',
      'z-index': 1,
    };
    style.push({ selector: ':parent', style: { ...containerBase, padding: `${CONTAINER_PAD.default}px`, 'font-size': '12px', 'font-weight': 600 } });

    style.push(
      {
        selector: 'node[type="VPC"]',
        style: {
          ...containerBase,
          padding: `${CONTAINER_PAD.VPC}px`,
          'background-color': theme === 'light' ? '#f6f8fb' : '#070d1a',
          'border-width': 1.5,
          'border-color': theme === 'light' ? '#94a3b8' : '#33435f',
          color: t.ink,
          'font-size': '15px',
          'font-weight': 700,
          'text-margin-y': -10,
        },
      },
      {
        selector: 'node[containerType="az"]',
        style: {
          ...containerBase,
          padding: `${CONTAINER_PAD.az}px`,
          'background-opacity': 0,
          'border-style': 'dashed',
          'border-dash-pattern': [4, 4],
          'border-color': theme === 'light' ? '#c3ccd9' : '#2a3852',
          color: t.inkMuted,
          'font-size': '11px',
          'font-weight': 600,
          'text-transform': 'uppercase',
        } as any,
      },
      {
        selector: 'node[type="Subnet"]',
        style: {
          ...containerBase,
          padding: `${CONTAINER_PAD.Subnet}px`,
          'background-color': t.card,
          'border-color': t.hairline,
          'font-size': '11.5px',
          'font-weight': 600,
          'min-width': (ele: any) => `${ele.data('minW') ?? 0}px`,
          'min-height': (ele: any) => `${ele.data('minH') ?? 0}px`,
        },
      },
      // A VPC with no workload, collapsed by shapeLiveTopology: a small summary box.
      {
        selector: 'node[type="VPC"]:childless',
        style: {
          width: COLLAPSED_VPC.w,
          height: COLLAPSED_VPC.h,
          'background-image': 'none',
          'background-opacity': 1,
          'background-color': theme === 'light' ? '#f6f8fb' : '#070d1a',
          'border-width': 1,
          'border-style': 'dashed',
          'border-color': theme === 'light' ? '#b6c1d1' : '#2f3d58',
          label: (ele: any) => ele.data('displayLabel') || ele.data('label') || '',
          'text-valign': 'center',
          'text-halign': 'center',
          'text-margin-y': 0,
          'text-wrap': 'wrap',
          // Unnamed VPCs are labelled by a 21-character id with no space to break on.
          'text-overflow-wrap': 'anywhere',
          'text-max-width': `${COLLAPSED_VPC.w - 24}px`,
          'line-height': 1.5,
          'font-size': '11.5px',
          'font-weight': 600,
          color: t.inkMuted,
        } as any,
      },
      // A subnet with nothing in it is a leaf, not a compound, but must still read as a box.
      {
        selector: 'node[type="Subnet"]:childless',
        style: {
          width: (ele: any) => ele.data('boxW') ?? CARD_W + CONTAINER_PAD.Subnet * 2,
          height: (ele: any) => ele.data('boxH') ?? CARD_H,
          'text-valign': 'top',
          'text-margin-y': -8,
        },
      },
      {
        selector: `node[id="${GLOBAL_SCOPE_ID}"]`,
        style: {
          ...containerBase,
          padding: `${CONTAINER_PAD.default}px`,
          'border-style': 'dashed',
          'border-dash-pattern': [6, 4],
          'border-color': theme === 'light' ? '#b6c1d1' : '#2f3d58',
          'background-color': theme === 'light' ? '#f6f8fb' : '#070d1a',
          'font-size': '13px',
          'font-weight': 700,
          'text-margin-y': -10,
        } as any,
      },
    );
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

  // ── Container variants, design mode ───────────────────────────────────────
  // Live mode draws containers in greyscale above; per-type hues there were decoration.
  const containerTone = (border: string, fillLight: string) => ({
    'border-color': border,
    'background-color': theme === 'light' ? fillLight : t.containerFill,
    'background-opacity': 1,
  });

  if (!live) style.push(
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

  if (live) {
    /*
     * Flow styles. Greyscale by design: line weight and dash say what a line means, so
     * colour is left for status and for the highlighted path. Labels are off until a line
     * is hovered, selected or part of a focused neighbourhood — sixty always-on labels are
     * what made the old view unreadable.
     */
    const strong = theme === 'light' ? '#334155' : '#a9b7cf';
    const mid = theme === 'light' ? '#7b8aa3' : '#5f7090';
    const faint = theme === 'light' ? '#a9b4c6' : '#3a4a68';
    style.push(
      { selector: 'edge', style: { label: '', 'curve-style': 'bezier', 'control-point-step-size': 28, opacity: 1, width: 1.2, 'line-color': faint, 'target-arrow-color': faint, 'arrow-scale': 0.75 } },
      { selector: 'edge[kind="traffic"]', style: { width: 1.8, 'line-color': strong, 'target-arrow-color': strong, 'arrow-scale': 0.85, 'z-index': 8 } },
      // Permissions, not traffic: present but quiet until a hover traces them.
      { selector: 'edge[kind="reach"]', style: { width: 1.1, 'line-color': mid, 'target-arrow-color': mid, 'line-style': 'dashed', 'line-dash-pattern': [6, 4], opacity: 0.55, 'curve-style': 'straight' } },
      { selector: 'edge[kind="egress"]', style: { width: 1.3, 'line-color': mid, 'target-arrow-color': mid, 'line-style': 'dashed', 'line-dash-pattern': [2, 4], 'target-arrow-shape': 'triangle' } },
      { selector: 'edge[kind="security"]', style: { 'line-style': 'dotted', 'line-color': mid, 'target-arrow-color': mid } },
      { selector: 'edge[kind="routing"], edge[kind="storage"], edge[kind="scaling"]', style: { 'line-color': faint, 'target-arrow-color': faint } },
      {
        selector: 'edge.cm-labelled, edge:selected, edge.cm-highlight',
        style: {
          label: 'data(label)', 'text-opacity': 1, 'font-size': '10px', 'font-weight': 600, color: t.ink,
          'text-background-color': t.edgeLabelBg, 'text-background-opacity': 0.95, 'text-background-padding': '3px',
          'text-background-shape': 'roundrectangle', 'text-rotation': 'none',
        },
      },
      { selector: 'node.cm-hover-dim, edge.cm-hover-dim', style: { opacity: 0.18 } },
      {
        selector: 'edge.cm-hover-path',
        style: {
          width: 2.4, opacity: 1, 'line-color': t.accent, 'target-arrow-color': t.accent, 'z-index': 60,
          label: 'data(label)', 'font-size': '10px', 'font-weight': 600, color: t.ink,
          'text-background-color': t.edgeLabelBg, 'text-background-opacity': 0.95, 'text-background-padding': '3px',
          'text-background-shape': 'roundrectangle', 'text-border-width': 1, 'text-border-color': t.hairline, 'text-border-opacity': 1,
        },
      },
    );
  }

  if (!live) for (const [kind, tone] of Object.entries(EDGE_TONE)) {
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
  // The synthetic Internet node sits above every VPC; it is not a VPC-less resource.
  'Internet',
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

  const GAP_X = 18;
  const GAP_Y = 16;
  const COMPOUND_GAP_X = 40;
  const COMPOUND_GAP_Y = 44;
  /** Room above a container for its label, which Cytoscape draws outside the box. */
  const LABEL_ROOM = 24;
  /** Space between tier rows inside a zone, on top of the next subnet's label room. */
  const TIER_GAP = 26;
  /** Subnet with nothing in it: wide enough for one card, short enough not to dominate. */
  const EMPTY_SUBNET = { w: CARD_W + CONTAINER_PAD.Subnet * 2, h: 64 };
  // NAT gateways normally sit in their public subnet; only one with no known subnet (inferred
  // from a route table) reaches the VPC level, and then it belongs in the gateway row too.
  const GATEWAY_TYPES = new Set(['Internet Gateway', 'NAT Gateway', 'Transit Gateway', 'VPN Gateway', 'Virtual Private Gateway']);

  const padOf = (n: any): number => {
    const type = n.data('type');
    if (type === 'VPC') return CONTAINER_PAD.VPC;
    if (n.data('containerType') === 'az') return CONTAINER_PAD.az;
    if (type === 'Subnet') return CONTAINER_PAD.Subnet;
    return CONTAINER_PAD.default;
  };
  const tierRank = (n: any): number => {
    const r = TIER_RANK[String(n.data('tier') ?? '')];
    return r === undefined ? 3 : r;
  };
  const byName = (a: any, b: any) =>
    String(a.data('label') ?? a.id()).localeCompare(String(b.data('label') ?? b.id()));
  const sortLeaves = (a: any, b: any) => {
    const ta = String(a.data('type') ?? '');
    const tb = String(b.data('type') ?? '');
    return ta !== tb ? ta.localeCompare(tb) : byName(a, b);
  };

  const sizes = new Map<string, { w: number; h: number }>();
  const offsets = new Map<string, PackItem[]>();

  // Reset stretch from a previous pass, so a relayout measures natural sizes.
  cy.nodes().forEach((n: any) => {
    if (n.data('minW') !== undefined) n.removeData('minW');
    if (n.data('minH') !== undefined) n.removeData('minH');
  });

  /**
   * Columns for n cards: two or three for a typical subnet, then roughly square-ish and
   * landscape for big ones, so a subnet with 300 instances is a block, not a 100-row column.
   */
  const gridCols = (n: number): number =>
    n <= 1 ? 1 : n <= 4 ? 2 : n <= 9 ? 3 : Math.min(16, Math.ceil(Math.sqrt(n * 1.6)));

  /** A fixed-column grid: cards in a subnet line up instead of ragged shelves. */
  function gridPack(items: PackItem[], cols: number): { w: number; h: number } {
    const cellW = items.reduce((m, it) => Math.max(m, it.w), 0);
    let rowH = 0;
    let y = 0;
    let w = 0;
    items.forEach((it, i) => {
      const c = i % cols;
      if (c === 0 && i > 0) { y += rowH + GAP_Y; rowH = 0; }
      it.x = c * (cellW + GAP_X);
      it.y = y;
      rowH = Math.max(rowH, it.h);
      w = Math.max(w, it.x + it.w);
    });
    return { w, h: items.length ? y + rowH : 0 };
  }

  /** Stretch a container (or empty-subnet leaf) to an outer box, via min sizes. */
  function stretch(n: any, outerW: number, outerH: number) {
    const cur = sizes.get(n.id())!;
    const w = Math.max(cur.w, outerW);
    const h = Math.max(cur.h, outerH);
    sizes.set(n.id(), { w, h });
    if (n.isParent()) {
      const pad = padOf(n);
      n.data('minW', w - pad * 2);
      n.data('minH', h - LABEL_ROOM - pad * 2);
    } else {
      n.data('boxW', w);
      n.data('boxH', h - LABEL_ROOM);
    }
  }

  function measure(node: any): { w: number; h: number } {
    if (!node.isParent()) {
      const box = node.data('type') === 'VPC'
        ? { w: COLLAPSED_VPC.w + 2, h: COLLAPSED_VPC.h + 2 }
        : node.data('type') === 'Subnet'
        ? { w: EMPTY_SUBNET.w, h: EMPTY_SUBNET.h + LABEL_ROOM }
        // Cytoscape's bounding box adds 1px around every node; measured, not assumed.
        : { w: CARD_W + 2, h: CARD_H + 2 };
      sizes.set(node.id(), box);
      return box;
    }

    const pad = padOf(node);
    const kids = node.children();
    const isVpc = node.data('type') === 'VPC';
    const compounds = kids.filter((c: any) => c.isParent() || c.data('type') === 'Subnet').toArray();
    const leaves = kids.filter((c: any) => !c.isParent() && c.data('type') !== 'Subnet').toArray().sort(sortLeaves);
    const gateways = isVpc ? leaves.filter((l: any) => GATEWAY_TYPES.has(l.data('type'))) : [];
    const loose = leaves.filter((l: any) => !gateways.includes(l));

    let y = 0;
    let innerW = 0;

    // ── Availability Zone matrix ──────────────────────────────────────────
    const azs = compounds.filter((c: any) => c.data('containerType') === 'az').sort(byName);
    const others = compounds.filter((c: any) => !azs.includes(c)).sort((a: any, b: any) => tierRank(a) - tierRank(b) || byName(a, b));
    let blockItems: PackItem[] = [];
    let blockBox = { w: 0, h: 0 };

    if (azs.length > 0) {
      /*
       * Columns are zones, rows are tiers. Public sits on top because that is where traffic
       * enters; isolated sits at the bottom. A row is as tall as its tallest cell across all
       * zones, and every subnet in a column is as wide as the widest, so prod-app-1a lines up
       * with prod-app-1b and the eye can compare zones directly.
       */
      const columns: Array<{ az: any; subs: any[] }> = azs.map((az: any) => {
        const subs = az.children().toArray().sort((a: any, b: any) => tierRank(a) - tierRank(b) || byName(a, b));
        subs.forEach(measure);
        return { az, subs };
      });
      const ranks: number[] = [...new Set<number>(columns.flatMap((c) => c.subs.map(tierRank)))].sort((a, b) => a - b);
      const rowH = new Map<number, number>();
      for (const r of ranks) {
        let h = 0;
        for (const col of columns) {
          const cell = col.subs.filter((s: any) => tierRank(s) === r);
          const cellH = cell.reduce((sum: number, s: any) => sum + sizes.get(s.id())!.h, 0) + Math.max(0, cell.length - 1) * GAP_Y;
          h = Math.max(h, cellH);
        }
        rowH.set(r, h);
      }
      const colW = Math.max(...columns.flatMap((c) => c.subs.map((s: any) => sizes.get(s.id())!.w)), EMPTY_SUBNET.w);

      let x = 0;
      for (const { az, subs } of columns) {
        const azPad = padOf(az);
        const subItems: PackItem[] = [];
        let cy0 = 0;
        for (const r of ranks) {
          const cell = subs.filter((s: any) => tierRank(s) === r);
          if (cell.length === 1) stretch(cell[0], colW, rowH.get(r)!);
          else cell.forEach((s: any) => stretch(s, colW, sizes.get(s.id())!.h));
          let yy = cy0;
          for (const s of cell) {
            subItems.push({ id: s.id(), ...sizes.get(s.id())!, x: 0, y: yy });
            yy += sizes.get(s.id())!.h + GAP_Y;
          }
          cy0 += rowH.get(r)! + TIER_GAP;
        }
        const innerH = Math.max(0, cy0 - TIER_GAP);
        offsets.set(az.id(), subItems);
        const azBox = { w: colW + azPad * 2, h: innerH + azPad * 2 + LABEL_ROOM };
        sizes.set(az.id(), azBox);
        blockItems.push({ id: az.id(), ...azBox, x, y: 0 });
        x += azBox.w + COMPOUND_GAP_X;
      }
      blockBox = { w: x - COMPOUND_GAP_X, h: Math.max(...blockItems.map((b) => b.h)) };
    }

    if (others.length > 0) {
      others.forEach(measure);
      const otherItems = others.map((c: any) => ({ id: c.id(), ...sizes.get(c.id())! }));
      const ob = shelfPack(otherItems, COMPOUND_GAP_X, COMPOUND_GAP_Y, blockBox.w);
      const dy = blockItems.length ? blockBox.h + COMPOUND_GAP_Y : 0;
      otherItems.forEach((it: PackItem) => { it.y! += dy; });
      blockItems = [...blockItems, ...otherItems];
      blockBox = { w: Math.max(blockBox.w, ob.w), h: dy + ob.h };
    }

    // ── Leaves ────────────────────────────────────────────────────────────
    gateways.forEach(measure);
    loose.forEach(measure);
    const gwItems: PackItem[] = gateways.map((g: any) => ({ id: g.id(), ...sizes.get(g.id())! }));
    const gwBox = gridPack(gwItems, Math.max(1, gwItems.length));
    const leafItems: PackItem[] = loose.map((l: any) => ({ id: l.id(), ...sizes.get(l.id())! }));
    const leafCols = compounds.length > 0
      ? Math.max(1, Math.floor((Math.max(blockBox.w, CARD_W) + GAP_X) / (CARD_W + GAP_X)))
      : gridCols(leafItems.length);
    const leafBox = gridPack(leafItems, leafCols);

    innerW = Math.max(gwBox.w, blockBox.w, leafBox.w);
    const center = (box: { w: number }, list: PackItem[]) => list.forEach((it) => { it.x! += (innerW - box.w) / 2; });

    // Gateways first: they are the VPC's front door.
    center(gwBox, gwItems);
    gwItems.forEach((it) => { it.y! += y; });
    if (gwItems.length) y += gwBox.h + COMPOUND_GAP_Y;

    center(blockBox, blockItems);
    blockItems.forEach((it) => { it.y! += y; });
    if (blockItems.length) y += blockBox.h + (leafItems.length ? COMPOUND_GAP_Y : 0);

    center(leafBox, leafItems);
    leafItems.forEach((it) => { it.y! += y; });
    if (leafItems.length) y += leafBox.h;

    offsets.set(node.id(), [...gwItems, ...blockItems, ...leafItems]);
    const box = { w: innerW + pad * 2, h: y + pad * 2 + LABEL_ROOM };
    sizes.set(node.id(), box);
    return box;
  }

  /** Top-down: packed offsets into absolute centre positions. */
  function place(node: any, topLeftX: number, topLeftY: number): void {
    const size = sizes.get(node.id())!;
    if (!node.isParent()) {
      // Leaf boxes reserve LABEL_ROOM above an empty subnet for its label.
      const top = node.data('type') === 'Subnet' ? topLeftY + LABEL_ROOM : topLeftY;
      const h = node.data('type') === 'Subnet' ? size.h - LABEL_ROOM : size.h;
      node.position({ x: topLeftX + size.w / 2, y: top + h / 2 });
      return;
    }
    const pad = padOf(node);
    const innerX = topLeftX + pad;
    const innerY = topLeftY + LABEL_ROOM + pad;
    for (const item of offsets.get(node.id()) ?? []) {
      place(cy.getElementById(item.id), innerX + item.x!, innerY + item.y!);
    }
  }

  const roots = cy.nodes().filter((n: any) => n.ancestors().length === 0);
  const internet = roots.filter((n: any) => n.data('type') === 'Internet').toArray();
  const rootCompounds = roots.filter((n: any) => n.isParent()).toArray();
  const rootLeaves = roots
    .filter((n: any) => !n.isParent() && n.data('type') !== 'Internet')
    .toArray().sort(sortLeaves);

  rootCompounds.forEach(measure);
  rootLeaves.forEach(measure);
  internet.forEach(measure);

  // VPCs first, VPCs with an internet gateway before those without, the global box last.
  const hasIgw = (n: any) => n.descendants().some((d: any) => d.data('type') === 'Internet Gateway');
  const ordered = [
    ...rootCompounds.filter((n: any) => n.id() !== GLOBAL_SCOPE_ID)
      .sort((a: any, b: any) => Number(hasIgw(b)) - Number(hasIgw(a)) || byName(a, b)),
    ...rootCompounds.filter((n: any) => n.id() === GLOBAL_SCOPE_ID),
  ];

  /*
   * Each VPC with an internet gateway has its own Internet entry, drawn above it. Its box
   * is grown to make room, so the entry packs with the VPC and never lands on a neighbour.
   */
  const INTERNET_ROOM = CARD_H + 2 + 56;
  const internetOf = new Map<string, any>();
  for (const inet of internet) {
    const vpc = inet.data('forVpc');
    if (vpc) internetOf.set(vpc, inet);
  }
  const rootItems: PackItem[] = [
    ...ordered.map((c: any) => {
      const sz = sizes.get(c.id())!;
      return { id: c.id(), w: sz.w, h: sz.h + (internetOf.has(c.id()) ? INTERNET_ROOM : 0) };
    }),
    ...rootLeaves.map((l: any) => ({ id: l.id(), ...sizes.get(l.id())! })),
  ];

  // Few top-level boxes stay on one row: canvases are wider than tall.
  const rootPreferred = rootItems.length <= 4
    ? rootItems.reduce((sum, it) => sum + it.w, 0) + COMPOUND_GAP_X * 2 * (rootItems.length - 1)
    : 0;
  shelfPack(rootItems, COMPOUND_GAP_X * 2, COMPOUND_GAP_Y * 2, rootPreferred);

  cy.batch(() => {
    for (const item of rootItems) {
      const inet = internetOf.get(item.id);
      place(cy.getElementById(item.id), item.x!, item.y! + (inet ? INTERNET_ROOM : 0));
      if (inet) {
        // Centred over this VPC's gateways, so the entry line drops straight down.
        const gws = cy.getElementById(item.id).descendants('[type="Internet Gateway"]');
        const xs = gws.map((g: any) => g.position('x'));
        const cx = xs.length ? xs.reduce((a: number, b: number) => a + b, 0) / xs.length : item.x! + item.w / 2;
        inet.position({ x: cx, y: item.y! + CARD_H / 2 });
      }
    }

    // An entry with no VPC (a gateway outside any VPC box) sits above everything.
    const loose = internet.filter((n: any) => !n.data('forVpc'));
    if (loose.length) {
      const gws = cy.nodes('[type="Internet Gateway"]');
      const xs = gws.map((g: any) => g.position('x'));
      const cx = xs.length ? (Math.min(...xs) + Math.max(...xs)) / 2 : 0;
      loose[0].position({ x: cx, y: -CARD_H - 70 });
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
