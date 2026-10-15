import { describe, it, expect, beforeAll, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GraphElement } from '../../../types/cloudmap';
import ResourceConnectivityFlow from '../Connectivity/ResourceConnectivityFlow';

/**
 * The only guard on a 1,600-line component whose glyph table, tooltip builder and id shortener
 * were just moved out of it into `connectivityView` and `utils/shortenId`.
 *
 * <p>The move promises no behaviour and no visual change, and this component had no test at all —
 * so this renders one branch and asserts the three things the move could have broken: the peer
 * rows' accessible names, the `title` text each row carries, and that a type with no artwork still
 * gets its hue-tinted initial rather than one generic mark.
 */

const ELEMENTS: GraphElement[] = [
  { group: 'nodes', data: { id: 'vpc-001', label: 'prod-vpc\n10.0.0.0/16', type: 'VPC' } },
  { group: 'nodes', data: { id: 'subnet-001', label: 'private-1a\n10.0.1.0/24\nap-south-1a', type: 'Subnet', parent: 'vpc-001' } },
  { group: 'nodes', data: { id: 'i-0abc1001a2b3c4d5e', label: 'prod-web-01', type: 'EC2 Instance', parent: 'subnet-001', 'Private IP': '10.0.1.21' } },
  { group: 'nodes', data: { id: 'vol-0aa1122334455667', label: 'prod-web-01-data', type: 'EBS Volume', parent: 'subnet-001', Size: '500 GiB' } },
  { group: 'edges', data: { id: 'e-vol', source: 'vol-0aa1122334455667', target: 'i-0abc1001a2b3c4d5e', label: 'attached to' } },
];

/*
 * 20 s rather than the 5 s default. Typing a search term is 16 keystrokes, each re-rendering a
 * 1,600-line pan/zoom canvas: 840 ms in isolation, and past the default under `vitest run` on the
 * full tree where three workers contend. The alternative — setting the value in one event — would
 * stop exercising the walk a reader actually performs, which is the only reason this test reaches
 * a resource card at all.
 */
describe('ResourceConnectivityFlow, after the shared helpers moved out', () => {
  beforeAll(() => {
    // The canvas measures its own viewport to fit the tree; jsdom has no ResizeObserver. Stubbed
    // the way loginAsAccountDashboard.test.tsx does for recharts.
    vi.stubGlobal('ResizeObserver', class {
      observe() {}

      unobserve() {}

      disconnect() {}
    });
  });

  it('draws a resource and its one connection, with the tooltip text each card shows', async () => {
    render(
      <ResourceConnectivityFlow
        accountId="605134457560"
        accountName="xammer-local"
        elements={ELEMENTS}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />,
    );

    // Searching is what makes the view walk to a branch on its own, which is both the shortest
    // path to a rendered resource card and the behaviour a reader actually takes.
    await userEvent.type(
      await screen.findByRole('textbox', { name: /Find a resource/ }),
      'prod-web-01-data',
    );

    // The peer column's one card: the instance the volume is attached to. Several buttons carry
    // the name, so it is picked by the glyph type on its own tooltip.
    const buttons = await screen.findAllByRole('button');
    const peer = buttons.find((button) =>
      (button.getAttribute('title') ?? '').includes('EC2 Instance'));
    expect(peer, 'no peer card rendered').toBeTruthy();

    // hoverText drops blank lines rather than padding the tooltip with gaps, and carries every
    // line the card displays — a title that stops before the line the reader is squinting at is
    // no help.
    const title = peer?.getAttribute('title') ?? '';
    expect(title).toContain('prod-web-01');
    expect(title).toContain('attached to');
    expect(title.split('\n').every((line) => line.trim().length > 0)).toBe(true);
    // shortenId left a 19-character id alone; a budget applied unconditionally would have cut it.
    expect(screen.getAllByText('vol-0aa1122334455667').length).toBeGreaterThan(0);

    // The legend and the edges moved into `connectivityView` too, and both are pure rendering —
    // nothing else on this page would notice if the swatches lost their words or every `<path>`
    // came back without a `d`.
    expect(screen.getByText('contains')).toBeInTheDocument();
    expect(screen.getByText('connects to')).toBeInTheDocument();
    expect(screen.getByText('inferred')).toBeInTheDocument();
    expect(document.querySelectorAll('svg path[d]').length).toBeGreaterThan(0);
  });

  it('gives a type with no artwork its initial rather than a generic mark', async () => {
    render(
      <ResourceConnectivityFlow
        accountId="605134457560"
        accountName="xammer-local"
        elements={[
          ...ELEMENTS,
          { group: 'nodes', data: { id: 'thing-1', label: 'mystery', type: 'Invented Type', parent: 'subnet-001' } },
        ]}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />,
    );

    await userEvent.type(
      await screen.findByRole('textbox', { name: /Find a resource/ }),
      'mystery',
    );

    await screen.findAllByText('mystery');
    // TypeIcon's fallback: the type's initial on a hue-tinted square, rather than one generic AWS
    // mark repeated across every unrecognised node. Rendered in both the service and the resource
    // column, hence all rather than one.
    const glyphs = screen.getAllByText('I');
    expect(glyphs.length).toBeGreaterThan(0);
    expect(glyphs[0]).toHaveAttribute('aria-hidden', 'true');
  });
}, 20_000);
