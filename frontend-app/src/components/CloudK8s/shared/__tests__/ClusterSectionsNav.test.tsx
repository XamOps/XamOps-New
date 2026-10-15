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

  it('is reachable with the keyboard in DOM order, each link with a visible focus ring', async () => {
    const user = userEvent.setup();
    renderAt(`${BASE}&tab=workloads&sub=pods`);
    const reached: string[] = [];
    for (let i = 0; i < 11; i += 1) {
      await user.tab();
      const el = document.activeElement as HTMLElement;
      expect(el.tagName).toBe('A');
      expect(el.className).toContain('focus-visible:ring-2');
      reached.push(el.dataset.section ?? el.dataset.sub ?? el.textContent ?? '');
    }
    expect(reached).toEqual([ALL_CLUSTERS_LABEL, 'overview', 'cost', 'workloads', 'workloads', 'pods', 'containers', 'resources', 'nodes', 'automation', 'security']);
    // Navigation semantics, not a tablist.
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.queryByRole('tab')).toBeNull();
  });

  it('follows the sidebar theme, in greyscale', () => {
    renderAt(`${BASE}&tab=nodes&sub=nodes`, true);
    expect(sectionLink('nodes').className).toContain('text-white');
    expect(sectionLink('cost').className).toContain('text-[rgba(255,255,255,0.6)]');
  });
});

describe('ClusterSectionsNav: Automation groups at the third level (automation-v2 design §3.1)', () => {
  const groupLinks = () => [...nav().querySelectorAll<HTMLAnchorElement>('a[data-sub-group]')];
  const order = () =>
    [...nav().querySelectorAll('a')].map((a) => a.dataset.section ?? a.dataset.subGroup ?? a.dataset.sub ?? a.textContent);

  it('renders Workload autoscaler as a plain group link and, while it is current, its Workloads leaf one level deeper', () => {
    renderAt(`${BASE}&tab=automation&sub=workload-autoscaler`);
    expect(groupLinks()).toHaveLength(1);
    const [group] = groupLinks();
    expect(group).toHaveTextContent('Workload autoscaler');
    expect(group.dataset.subGroup).toBe('group:workload-autoscaler');
    // A plain link to its first subtab; "you are here" belongs to the leaf.
    expect(group).not.toHaveAttribute('aria-current');
    expect(resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, searchOf(group.getAttribute('href')!)))
      .toEqual({ tab: 'automation', sub: 'workload-autoscaler', corrected: null });

    expect(subLinks().map((a) => [a.dataset.sub, a.textContent])).toEqual([
      ['overview', 'Overview'],
      ['workload-autoscaler', 'Workloads'],
      ['workload-autoscaler-policies', 'Policies'],
      ['hpa', 'HPA'],
      ['vpa', 'VPA'],
    ]);
    expect(subLinks().filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.dataset.sub)).toEqual(['workload-autoscaler']);
    expect(sectionLink('automation')).toHaveAttribute('aria-current', 'page');
    // The leaf is nested under its group row (the third level), and follows it in keyboard order.
    const leaf = nav().querySelector<HTMLAnchorElement>('a[data-sub="workload-autoscaler"]')!;
    expect(group.closest('li')!.contains(leaf)).toBe(true);
    expect(order()).toEqual([
      ALL_CLUSTERS_LABEL, 'overview', 'cost', 'workloads', 'nodes', 'automation',
      'overview', 'group:workload-autoscaler', 'workload-autoscaler', 'workload-autoscaler-policies', 'hpa', 'vpa', 'security',
    ]);
    // At 220 px a label may wrap, never truncate.
    for (const a of [group, leaf]) {
      expect(a.className).not.toContain('truncate');
      expect(a.querySelector('.truncate')).toBeNull();
    }
  });

  it('keeps the group collapsed while another Automation page is current', () => {
    renderAt(`${BASE}&tab=automation&sub=hpa`);
    expect(groupLinks().map((a) => a.textContent)).toEqual(['Workload autoscaler']);
    expect(nav().querySelector('a[data-sub="workload-autoscaler"]')).toBeNull();
    expect(subLinks().map((a) => a.dataset.sub)).toEqual(['overview', 'hpa', 'vpa']);
    expect(subLinks().filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.dataset.sub)).toEqual(['hpa']);
  });

  it("marks HPA for main's old Pod autoscaler link and VPA as its own page", () => {
    renderAt(`${BASE}&tab=automation&sub=pod-autoscaling`);
    expect(subLinks().find((a) => a.getAttribute('aria-current') === 'page')?.dataset.sub).toBe('hpa');
    const vpa = subLinks().find((a) => a.dataset.sub === 'vpa')!;
    expect(resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, searchOf(vpa.getAttribute('href')!)))
      .toEqual({ tab: 'automation', sub: 'vpa', corrected: null });
  });

  it('opens the group from its row with the keyboard, and the leaf takes the focus ring', async () => {
    const user = userEvent.setup();
    const router = renderAt(`${BASE}&tab=automation&sub=overview`);
    expect(nav().querySelector('a[data-sub="workload-autoscaler"]')).toBeNull();
    const [group] = groupLinks();
    expect(group.className).toContain('focus-visible:ring-2');
    group.focus();
    await user.keyboard('{Enter}');
    expect(new URLSearchParams(router.state.location.search).get('sub')).toBe('workload-autoscaler');
    const leaf = nav().querySelector<HTMLAnchorElement>('a[data-sub="workload-autoscaler"]')!;
    expect(leaf).toHaveAttribute('aria-current', 'page');
    expect(leaf.className).toContain('focus-visible:ring-2');
  });

  it('follows the dark sidebar theme, in greyscale', () => {
    renderAt(`${BASE}&tab=automation&sub=workload-autoscaler`, true);
    const [group] = groupLinks();
    expect(group.className).toContain('text-white');
    expect(nav().querySelector('a[data-sub="workload-autoscaler"]')!.className).toContain('text-white');
  });
});
