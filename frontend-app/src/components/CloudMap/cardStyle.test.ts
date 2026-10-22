import { describe, it, expect } from 'vitest';
import cytoscape from 'cytoscape';
import { buildCytoscapeStyle, liveCardUrl, CARD_W, CARD_H } from './canvasTheme';

/**
 * Guards the live resource card.
 *
 * Live cards rendered for a long time as a stretched service icon with no name. Two
 * attempts blamed Cytoscape's background layers. The real cause was in CloudMapPage:
 * the design-mode style-restore helper ran on live nodes too and stamped the raw icon
 * as an inline bypass, overriding whatever the stylesheet said.
 *
 * The card is now one baked SVG carrying chrome, icon, name, caption and status. These
 * tests pin that contract: one background image that is the card, sized to the node,
 * with no Cytoscape label competing with the baked name.
 */

const liveNode = (type: string, label = 'web-01', extra: Record<string, unknown> = {}) =>
  cytoscape({
    headless: true,
    styleEnabled: true,
    style: buildCytoscapeStyle('light', 'live') as any,
    elements: [{ group: 'nodes', data: { id: 'n1', label, type, ...extra } }],
  }).getElementById('n1') as any;

const first = (v: any) => (Array.isArray(v) ? v[0] : v);
const decode = (url: string) => decodeURIComponent(url.replace(/^data:image\/svg\+xml,/, ''));

describe('live resource card', () => {
  it('uses exactly one background image, and that image is the baked card', () => {
    const bg = liveNode('EC2 Instance').style('background-image');
    expect(Array.isArray(bg) ? bg.length : 1).toBe(1);
    expect(String(first(bg))).toMatch(/^data:image\/svg\+xml,/);
  });

  it('sizes the card image to the whole node', () => {
    const n = liveNode('EC2 Instance');
    expect(parseFloat(String(n.style('width')))).toBe(CARD_W);
    expect(parseFloat(String(n.style('height')))).toBe(CARD_H);
    expect(String(first(n.style('background-width')))).toBe('100%');
    expect(String(first(n.style('background-height')))).toBe('100%');
  });

  it('bakes the name and type into the card rather than using a Cytoscape label', () => {
    const n = liveNode('EC2 Instance', 'prod-api-01');
    expect(n.style('label')).toBe('');
    const svg = decode(String(first(n.style('background-image'))));
    expect(svg).toContain('prod-api-01');
    expect(svg).toContain('EC2 Instance');
  });

  it('clips long names inside the card', () => {
    const svg = decode(liveCardUrl({ name: 'a-very-long-resource-name-that-would-overflow-the-card', type: 'EC2 Instance', theme: 'light' }));
    expect(svg).toContain('…');
    expect(svg).not.toContain('overflow-the-card');
  });

  it('escapes tag text so a customer name cannot break the image', () => {
    const svg = decode(liveCardUrl({ name: 'R&D <prod>', type: 'EC2 Instance', theme: 'light' }));
    expect(svg).toContain('R&amp;D &lt;prod&gt;');
  });

  it('says a non-normal state in words, not only by colour', () => {
    const svg = decode(liveCardUrl({ name: 'bastion', type: 'EC2 Instance', state: 'stopped', theme: 'light' }));
    expect(svg).toContain('stopped');
  });

  it('draws no status dot for an unknown state', () => {
    const svg = decode(liveCardUrl({ name: 'x', type: 'EC2 Instance', state: null, theme: 'light' }));
    expect(svg).not.toContain('<circle');
  });

  it('falls back to initials for a type with no icon', () => {
    const svg = decode(liveCardUrl({ name: 'thing', type: 'Totally Unknown Service', theme: 'light' }));
    expect(svg).toContain('>TU<');
  });
});
