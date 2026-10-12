import { act, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import {
  EKS_SECTIONS,
  LEGACY_TAB_MAP,
  resolveSectionRoute,
  useEksSectionRoute,
  withSection,
} from '../useEksSectionRoute';

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
