import { act, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import {
  EKS_SECTIONS,
  LEGACY_TAB_MAP,
  resolveSectionRoute,
  sectionNavEntries,
  useEksSectionRoute,
  withSection,
} from '../useEksSectionRoute';
import { pageKeyOf } from '../../utils/inPageNavigation';

const BASE = 'name=prod-eks&region=us-east-1&accountIds=111111111111';
const resolve = (search: string) => resolveSectionRoute(EKS_SECTIONS, LEGACY_TAB_MAP, search);
const params = (search: string | null) => new URLSearchParams(search ?? '');

/** Design §5.2, verbatim, plus the realtime branch's Automation tab on the old page. */
const LEGACY: Array<[string, string, string]> = [
  ['overview', 'overview', 'summary'],
  ['performance', 'nodes', 'utilization'],
  ['workloads', 'workloads', 'workloads'],
  ['pods', 'workloads', 'pods'],
  ['containers', 'workloads', 'containers'],
  ['nodes', 'nodes', 'nodes'],
  ['resources', 'workloads', 'resources'],
  ['security', 'security', 'vulnerabilities'],
  ['cost', 'cost', 'cluster'],
  ['karpenter', 'nodes', 'karpenter'],
  ['autoscaling', 'automation', 'overview'],
  ['automation', 'automation', 'overview'],
];

describe('resolveSectionRoute', () => {
  it('has exactly the legacy entries', () => {
    expect(Object.keys(LEGACY_TAB_MAP).sort()).toEqual(LEGACY.map(([old]) => old).sort());
  });

  it.each(LEGACY)('maps the legacy ?tab=%s to %s/%s and corrects the URL', (old, tab, sub) => {
    const r = resolve(`${BASE}&tab=${old}`);
    expect([r.tab, r.sub]).toEqual([tab, sub]);
    expect(r.corrected).not.toBeNull();
    expect(params(r.corrected).get('tab')).toBe(tab);
    expect(params(r.corrected).get('sub')).toBe(sub);
  });

  it('sends an unknown tab to overview/summary', () => {
    const r = resolve(`${BASE}&tab=bogus&sub=whatever`);
    expect([r.tab, r.sub]).toEqual(['overview', 'summary']);
    expect(params(r.corrected).get('tab')).toBe('overview');
    expect(params(r.corrected).get('sub')).toBe('summary');
  });

  it('sends an unknown or unregistered sub to the section default', () => {
    expect(resolve(`${BASE}&tab=nodes&sub=bogus`)).toMatchObject({ tab: 'nodes', sub: 'nodes' });
    // Registered in a later slice; until then it falls back rather than break the link.
    expect(resolve(`${BASE}&tab=nodes&sub=node-groups`)).toMatchObject({ tab: 'nodes', sub: 'nodes' });
    expect(resolve(`${BASE}&tab=workloads&sub=nodes`)).toMatchObject({ tab: 'workloads', sub: 'workloads' });
  });

  it('fills in a missing sub', () => {
    const r = resolve(`${BASE}&tab=automation`);
    expect([r.tab, r.sub]).toEqual(['automation', 'overview']);
    expect(params(r.corrected).get('sub')).toBe('overview');
  });

  it('keeps every other parameter, in place', () => {
    const r = resolve(`name=prod-eks&tab=pods&region=us-east-1&accountIds=111111111111&version=1.33&debug=true`);
    const p = params(r.corrected);
    expect(p.get('name')).toBe('prod-eks');
    expect(p.get('region')).toBe('us-east-1');
    expect(p.get('accountIds')).toBe('111111111111');
    expect(p.get('version')).toBe('1.33');
    expect(p.get('debug')).toBe('true');
    expect([...p.keys()]).toEqual(['name', 'tab', 'region', 'accountIds', 'version', 'debug', 'sub']);
  });

  it('resolves every registered pair to itself without a correction', () => {
    for (const section of EKS_SECTIONS) {
      for (const sub of section.subs) {
        const r = resolve(`${BASE}&tab=${section.id}&sub=${sub.id}`);
        expect([r.tab, r.sub, r.corrected], `${section.id}/${sub.id}`).toEqual([section.id, sub.id, null]);
      }
    }
  });

  it('leaves a link without tab or sub alone and lands on overview/summary', () => {
    expect(resolve(BASE)).toEqual({ tab: 'overview', sub: 'summary', corrected: null });
  });

  it('registers only slice 1 subtabs, each section default among its subs (P3)', () => {
    expect(EKS_SECTIONS.map((s) => `${s.id}:${s.subs.map((x) => x.id).join(',')}`)).toEqual([
      'overview:summary',
      'cost:cluster',
      'workloads:workloads,pods,containers,resources',
      'nodes:nodes,karpenter,utilization',
      'automation:overview',
      'security:vulnerabilities',
    ]);
    for (const s of EKS_SECTIONS) expect(s.subs.some((x) => x.id === s.defaultSub)).toBe(true);
  });

  it('withSection sets tab and sub only', () => {
    expect(withSection('a=1&tab=x', 'nodes', 'nodes')).toBe('a=1&tab=nodes&sub=nodes');
  });
});

describe('sectionNavEntries (Addendum 1, J-2..J-4)', () => {
  const nav = (search: string) => sectionNavEntries(EKS_SECTIONS, LEGACY_TAB_MAP, '/eks-details', search);
  const searchOf = (href: string) => {
    const url = new URL(href, 'http://x');
    expect(url.pathname).toBe('/eks-details');
    return url.search.slice(1);
  };

  it('lists every registered section, in registry order', () => {
    expect(nav(BASE).map((e) => [e.id, e.label, e.icon])).toEqual(EKS_SECTIONS.map((s) => [s.id, s.label, s.icon]));
  });

  it('builds every href with the page serializer, so it resolves to the section it names without a correction', () => {
    for (const search of [BASE, `${BASE}&tab=workloads&sub=pods`, `${BASE}&tab=pods`]) {
      for (const entry of nav(search)) {
        const section = EKS_SECTIONS.find((s) => s.id === entry.id)!;
        expect(resolve(searchOf(entry.href))).toEqual({ tab: entry.id, sub: section.defaultSub, corrected: null });
        for (const sub of entry.subs) {
          expect(resolve(searchOf(sub.href))).toEqual({ tab: entry.id, sub: sub.id, corrected: null });
        }
      }
    }
  });

  it('keeps every other cluster parameter, in place', () => {
    const search = 'name=prod-eks&region=eu-west-1&accountIds=111111111111&version=1.36&debug=true';
    for (const entry of nav(search)) {
      const p = params(searchOf(entry.href));
      expect([...p.keys()]).toEqual(['name', 'region', 'accountIds', 'version', 'debug', 'tab', 'sub']);
      expect([p.get('name'), p.get('region'), p.get('accountIds'), p.get('version'), p.get('debug')])
        .toEqual(['prod-eks', 'eu-west-1', '111111111111', '1.36', 'true']);
    }
  });

  it('marks the current section and sub, and lists subtabs under the current section only', () => {
    const entries = nav(`${BASE}&tab=workloads&sub=pods`);
    expect(entries.filter((e) => e.current).map((e) => e.id)).toEqual(['workloads']);
    for (const e of entries.filter((x) => !x.current)) expect(e.subs).toEqual([]);
    const workloads = entries.find((e) => e.id === 'workloads')!;
    expect(workloads.subs.map((s) => [s.id, s.label, s.current])).toEqual([
      ['workloads', 'Workloads', false],
      ['pods', 'Pods', true],
      ['containers', 'Containers', false],
      ['resources', 'Resources', false],
    ]);
    // A section with one registered subtab lists it too: whatever the registry holds, no placeholder.
    expect(nav(`${BASE}&tab=cost&sub=cluster`).find((e) => e.id === 'cost')!.subs.map((s) => s.id)).toEqual(['cluster']);
  });

  it('marks what the page shows for plain, legacy and unknown links', () => {
    const currentOf = (search: string) => {
      const entry = nav(search).find((e) => e.current)!;
      return `${entry.id}/${entry.subs.find((s) => s.current)?.id}`;
    };
    expect(currentOf(BASE)).toBe('overview/summary');
    expect(currentOf(`${BASE}&tab=pods`)).toBe('workloads/pods');
    expect(currentOf(`${BASE}&tab=performance`)).toBe('nodes/utilization');
    expect(currentOf(`${BASE}&tab=bogus`)).toBe('overview/summary');
    expect(currentOf(`${BASE}&tab=nodes&sub=bogus`)).toBe('nodes/nodes');
  });
});

function Probe() {
  const route = useEksSectionRoute();
  return (
    <div>
      <span data-testid="pair">{`${route.tab}/${route.sub}`}</span>
      <span data-testid="href">{route.hrefFor('nodes', 'karpenter')}</span>
      <button type="button" onClick={() => route.select('workloads', 'pods')}>pods</button>
      <button type="button" onClick={() => route.select('security')}>security</button>
    </div>
  );
}

function renderAt(search: string) {
  const router = createMemoryRouter([{ path: '/eks-details', element: <Probe /> }], {
    initialEntries: [`/eks-details?${search}`],
  });
  render(<RouterProvider router={router} />);
  return router;
}

describe('useEksSectionRoute', () => {
  it.each(LEGACY)('rewrites ?tab=%s once, with replace', async (old, tab, sub) => {
    const router = renderAt(`${BASE}&tab=${old}`);
    expect(screen.getByTestId('pair')).toHaveTextContent(`${tab}/${sub}`);
    await act(async () => {});
    expect(router.state.historyAction).toBe('REPLACE');
    const p = params(router.state.location.search);
    expect([p.get('tab'), p.get('sub')]).toEqual([tab, sub]);
    expect(p.get('name')).toBe('prod-eks');
  });

  it('does not rewrite a URL that is already right', async () => {
    const router = renderAt(`${BASE}&tab=nodes&sub=karpenter`);
    await act(async () => {});
    expect(router.state.historyAction).toBe('POP');
    expect(screen.getByTestId('pair')).toHaveTextContent('nodes/karpenter');
  });

  it('keeps the page mounted: corrections and switches carry the key of the entry the page opened on', async () => {
    const router = createMemoryRouter([{ path: '/eks-details', element: <Probe /> }], {
      initialEntries: [`/eks-details?${BASE}&tab=pods`],
    });
    const opened = router.state.location.key;
    render(<RouterProvider router={router} />);
    await act(async () => {});
    // The legacy value was corrected with a new entry key, but the page key is unchanged.
    expect(router.state.location.key).not.toBe(opened);
    expect(pageKeyOf(router.state.location)).toBe(opened);
    await act(async () => screen.getByText('security').click());
    expect(screen.getByTestId('pair')).toHaveTextContent('security/vulnerabilities');
    expect(pageKeyOf(router.state.location)).toBe(opened);
  });

  it('selects with replace and builds hrefs that keep the other parameters', async () => {
    const router = renderAt(`${BASE}&tab=overview&sub=summary`);
    expect(screen.getByTestId('href')).toHaveTextContent(`/eks-details?${BASE}&tab=nodes&sub=karpenter`);
    await act(async () => screen.getByText('pods').click());
    expect(router.state.historyAction).toBe('REPLACE');
    expect(screen.getByTestId('pair')).toHaveTextContent('workloads/pods');
    await act(async () => screen.getByText('security').click());
    expect(screen.getByTestId('pair')).toHaveTextContent('security/vulnerabilities');
    expect(params(router.state.location.search).get('accountIds')).toBe('111111111111');
  });
});
