import { describe, it, expect } from 'vitest';
import cytoscape from 'cytoscape';
import { buildCytoscapeStyle, CARD_W, CARD_H } from './canvasTheme';

/**
 * Guards the live resource card against the defect that produced wide stretched
 * icons with no visible name.
 *
 * The card used to be two background layers — a baked card SVG in slot 0 and the
 * service icon in slot 1, each with its own width, height and offset supplied as
 * positional arrays. The icon rendered stretched across the whole card, and forcing
 * slot 1 to 'contain' changed nothing: an icon genuinely in slot 1 could not have
 * been stretched to the full card width, so it was being resolved into slot 0 and
 * inheriting slot 0's 'none' fit and CARD_W width.
 *
 * The fix is structural rather than a tuned value: exactly one background image, so
 * there is no slot to be wrong about. These assertions encode that, because the
 * symptom is only visible in a browser and the cause is not.
 */

const liveNode = (type: string, label = 'web-01') =>
  cytoscape({
    headless: true,
    styleEnabled: true,
    style: buildCytoscapeStyle('light', 'live') as any,
    elements: [{ group: 'nodes', data: { id: 'n1', label, type } }],
  }).getElementById('n1') as any;

describe('live resource card', () => {
  it('uses exactly one background image, so no layer can be mis-indexed', () => {
    const bg = liveNode('EC2 Instance').style('background-image');
    const layers = Array.isArray(bg) ? bg.length : 1;
    expect(layers, `expected a single background layer, got ${layers}`).toBe(1);
  });

  it('never stretches the icon, whatever aspect ratio AWS_ICONS returns', () => {
    const fit = liveNode('EC2 Instance').style('background-fit');
    // 'none' is what stretched the artwork to fill the box.
    expect(Array.isArray(fit) ? fit[0] : fit).toBe('contain');
  });

  it('sizes the icon to its own well and not to the whole card', () => {
    const n = liveNode('EC2 Instance');
    const w = parseFloat(String(Array.isArray(n.style('background-width'))
      ? n.style('background-width')[0]
      : n.style('background-width')));
    const h = parseFloat(String(Array.isArray(n.style('background-height'))
      ? n.style('background-height')[0]
      : n.style('background-height')));

    expect(w).toBeGreaterThan(0);
    expect(w).toBeLessThan(CARD_W / 2);
    expect(h).toBeLessThan(CARD_H);
    // A square well is what keeps a square service icon square.
    expect(w).toBe(h);
  });

  it('shows the resource name on the node', () => {
    // The stretched icon covered the name baked into the old card, so the node read
    // as an unlabelled block. The label is now Cytoscape's own.
    expect(liveNode('EC2 Instance', 'prod-api-01').style('label')).toBe('prod-api-01');
  });

  it('keeps the name inside the card rather than trailing off its edge', () => {
    const n = liveNode('EC2 Instance', 'a-very-long-resource-name-that-would-overflow');
    const maxWidth = parseFloat(String(n.style('text-max-width')));
    expect(maxWidth).toBeGreaterThan(0);
    expect(maxWidth).toBeLessThan(CARD_W);
    // Long names must be clipped, not allowed to spill across neighbours.
    expect(n.style('text-wrap')).toBe('ellipsis');
  });

  it('draws a card the icon sits on, not a transparent node', () => {
    const n = liveNode('EC2 Instance');
    expect(parseFloat(String(n.style('background-opacity')))).toBe(1);
    expect(parseFloat(String(n.style('border-width')))).toBeGreaterThan(0);
  });

  it('applies the same single-layer treatment to a type with no icon file', () => {
    // Route Table resolved through the initials-glyph fallback before it gained a
    // NODE_TYPE_DATA entry; an unknown type still must not stretch.
    const n = liveNode('Totally Unknown Service');
    const bg = n.style('background-image');
    expect(Array.isArray(bg) ? bg.length : 1).toBe(1);
    expect(Array.isArray(n.style('background-fit'))
      ? n.style('background-fit')[0]
      : n.style('background-fit')).toBe('contain');
  });
});
