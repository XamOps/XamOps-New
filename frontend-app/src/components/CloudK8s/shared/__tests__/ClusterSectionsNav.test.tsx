import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ALL_CLUSTERS_LABEL, CLUSTER_SECTIONS_LABEL, ClusterSectionsNav } from '../ClusterSectionsNav';
import { EKS_SECTIONS, LEGACY_TAB_MAP, resolveSectionRoute } from '../../../../hooks/useEksSectionRoute';
import { publishClusterNavAccess } from '../../../../utils/k8sNavAccess';
import { pageKeyOf } from '../../../../utils/inPageNavigation';
import { DEMO_MODE_KEY } from '../../../../demo/demoConfig';

const ACCOUNT = '111111111111';
const BASE = `/eks-details?name=prod-eks&region=eu-west-1&accountIds=${ACCOUNT}`;

function renderAt(url: string, dark = false) {
  const router = createMemoryRouter(
    [{ path: '*', element: <ClusterSectionsNav dark={dark} /> }],
    { initialEntries: [url] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

const nav = () => screen.getByRole('navigation', { name: CLUSTER_SECTIONS_LABEL });
const sectionLink = (id: string) => nav().querySelector<HTMLAnchorElement>(`a[data-section="${id}"]`)!;
const subLinks = () => [...nav().querySelectorAll<HTMLAnchorElement>('a[data-sub]')];
const searchOf = (href: string) => new URL(href, 'http://x').search.slice(1);

afterEach(() => {
  publishClusterNavAccess(null);
  sessionStorage.clear();
});

describe('ClusterSectionsNav (Addendum 1)', () => {
  it.each([
    ['the fleet page', '/cloudk8s'],
    ['another page', '/xamsecure/overview'],
    ['a cluster link the page rejects', `/eks-details?name=-bad&accountIds=${ACCOUNT}`],
    ['a link without an account', '/eks-details?name=prod-eks'],
    ['a demo account outside a demo session', '/eks-details?name=prod-eks-cluster&accountIds=demo-aws-001'],
  ])('shows no cluster sections on %s', (_label, url) => {
    renderAt(url);
    expect(screen.queryByRole('navigation', { name: CLUSTER_SECTIONS_LABEL })).toBeNull();
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  it('shows the cluster in context from the link, with All clusters', () => {
    renderAt(`${BASE}&tab=nodes&sub=karpenter`);
    expect(nav().querySelector('[data-cluster-name]')).toHaveTextContent('prod-eks');
    expect(nav().querySelector('[data-cluster-region]')).toHaveTextContent('eu-west-1');
    expect(within(nav()).getByRole('link', { name: ALL_CLUSTERS_LABEL })).toHaveAttribute('href', '/cloudk8s');
    // Greyscale only: a bundled mdi glyph, not the coloured AWS service image.
    expect(nav().querySelector('img')).toBeNull();
  });

  it('shows no region when the link has none, rather than a default', () => {
    renderAt(`/eks-details?name=prod-eks&accountIds=${ACCOUNT}`);
    expect(nav().querySelector('[data-cluster-region]')).toBeNull();
    expect(nav()).not.toHaveTextContent('us-east-1');
  });

  it('accepts a demo account in a demo session', () => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    renderAt('/eks-details?name=prod-eks-cluster&region=us-east-1&accountIds=demo-aws-001');
    expect(nav().querySelector('[data-cluster-name]')).toHaveTextContent('prod-eks-cluster');
  });

  it('lists every registered section in registry order, each a link that resolves to itself', () => {
    renderAt(BASE);
    const links = [...nav().querySelectorAll<HTMLAnchorElement>('a[data-section]')];
    expect(links.map((a) => a.dataset.section)).toEqual(EKS_SECTIONS.map((s) => s.id));
    expect(links.map((a) => a.textContent)).toEqual(EKS_SECTIONS.map((s) => s.label));
    for (const a of links) {
      const href = a.getAttribute('href')!;
      expect(new URL(href, 'http://x').pathname).toBe('/eks-details');
      const section = EKS_SECTIONS.find((s) => s.id === a.dataset.section)!;
      expect(resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, searchOf(href)))
        .toEqual({ tab: section.id, sub: section.defaultSub, corrected: null });
      // Every cluster parameter is kept.
      const p = new URLSearchParams(searchOf(href));
      expect([p.get('name'), p.get('region'), p.get('accountIds')]).toEqual(['prod-eks', 'eu-west-1', ACCOUNT]);
    }
  });

  it('nests the current section\'s subtabs only, and marks the current section and subtab', () => {
    renderAt(`${BASE}&tab=workloads&sub=pods`);
    expect(sectionLink('workloads')).toHaveAttribute('aria-current', 'page');
    for (const s of EKS_SECTIONS.filter((x) => x.id !== 'workloads')) {
      expect(sectionLink(s.id)).not.toHaveAttribute('aria-current');
    }
    expect(subLinks().map((a) => a.textContent)).toEqual(['Workloads', 'Pods', 'Containers', 'Resources']);
    expect(subLinks().filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.dataset.sub)).toEqual(['pods']);
    for (const a of subLinks()) {
      expect(resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, searchOf(a.getAttribute('href')!)))
        .toEqual({ tab: 'workloads', sub: a.dataset.sub, corrected: null });
    }
    // The subtabs follow their section in DOM order, so the keyboard reaches them next.
    const order = [...nav().querySelectorAll('a')].map((a) => a.dataset.section ?? a.dataset.sub ?? a.textContent);
    expect(order).toEqual([ALL_CLUSTERS_LABEL, 'overview', 'cost', 'workloads', 'workloads', 'pods', 'containers', 'resources', 'nodes', 'automation', 'security']);
  });

  it('marks what the page shows for a legacy link', () => {
    renderAt(`${BASE}&tab=performance`);
    expect(sectionLink('nodes')).toHaveAttribute('aria-current', 'page');
    expect(subLinks().find((a) => a.getAttribute('aria-current') === 'page')?.dataset.sub).toBe('utilization');
  });

  it('pushes history and keeps the page key, so Back and Forward step through sections in place', async () => {
    const user = userEvent.setup();
    const router = renderAt(`${BASE}&tab=overview&sub=summary`);
    const opened = router.state.location.key;

    await user.click(sectionLink('nodes'));
    expect(router.state.historyAction).toBe('PUSH');
    expect(new URLSearchParams(router.state.location.search).get('tab')).toBe('nodes');
    expect(pageKeyOf(router.state.location)).toBe(opened);
    expect(sectionLink('nodes')).toHaveAttribute('aria-current', 'page');

    await user.click(subLinks().find((a) => a.dataset.sub === 'karpenter')!);
    expect(new URLSearchParams(router.state.location.search).get('sub')).toBe('karpenter');
    expect(pageKeyOf(router.state.location)).toBe(opened);

    await act(async () => { await router.navigate(-1); });
    expect(new URLSearchParams(router.state.location.search).get('sub')).toBe('nodes');
    await act(async () => { await router.navigate(-1); });
    expect(sectionLink('overview')).toHaveAttribute('aria-current', 'page');
    expect(pageKeyOf(router.state.location)).toBe(opened);
    await act(async () => { await router.navigate(1); });
    expect(sectionLink('nodes')).toHaveAttribute('aria-current', 'page');
  });

  it('marks Automation "View only" with the reason as its description, for this cluster only', () => {
    publishClusterNavAccess({ clusterName: 'prod-eks', accountId: ACCOUNT, viewOnly: true, reason: 'Read-only connection. Automation changes need read-write access.' });
    renderAt(BASE);
    const automation = sectionLink('automation');
    expect(automation).toHaveTextContent('AutomationView only');
    expect(automation).toHaveAccessibleDescription('Read-only connection. Automation changes need read-write access.');
    expect(automation).toHaveAttribute('title', 'Read-only connection. Automation changes need read-write access.');
    // Still a real link, never hidden or disabled.
    expect(automation).toHaveAttribute('href');
    expect(automation).not.toHaveAttribute('aria-disabled');
    expect(nav().querySelectorAll('[data-view-only]')).toHaveLength(1);
  });

  it.each([
    ['allowed', { clusterName: 'prod-eks', accountId: ACCOUNT, viewOnly: false, reason: null }],
    ['another cluster', { clusterName: 'eu-eks', accountId: ACCOUNT, viewOnly: true, reason: 'x' }],
    ['another account', { clusterName: 'prod-eks', accountId: '222222222222', viewOnly: true, reason: 'x' }],
    ['still loading', null],
  ])('shows no marker when %s', (_label, access) => {
    publishClusterNavAccess(access);
    renderAt(BASE);
    expect(nav().querySelector('[data-view-only]')).toBeNull();
    expect(sectionLink('automation')).not.toHaveAttribute('aria-describedby');
  });

  it('follows the sidebar theme, in greyscale', () => {
    renderAt(`${BASE}&tab=nodes&sub=nodes`, true);
    expect(sectionLink('nodes').className).toContain('text-white');
    expect(sectionLink('cost').className).toContain('text-[rgba(255,255,255,0.6)]');
  });
});
