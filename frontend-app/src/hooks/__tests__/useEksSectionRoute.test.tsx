import { act, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import {
  EKS_SECTIONS,
  LEGACY_TAB_MAP,
  PAGE_PARAMS,
  SUB_ALIASES,
  resolveSectionRoute,
  sectionNavEntries,
  useEksSectionRoute,
  withSection,
  type SectionRegistry,
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
    // Registered in a later slice (FEAT-004); until then it falls back rather than break the link.
    expect(resolve(`${BASE}&tab=security&sub=compliance`)).toMatchObject({ tab: 'security', sub: 'vulnerabilities' });
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

  it('registers only the subtabs that render content so far, each section default among its subs (P3)', () => {
    // Slice 1's targets, plus Overview › Events and Nodes › Node groups (FEAT-003 items 24, 25),
    // Automation › Workload autoscaler › Workloads (the demo cut) and Automation › HPA and VPA (main's
    // combined Pod autoscaler page, cloudk8s-hpa-vpa FEAT-003, split in UI v3).
    expect(EKS_SECTIONS.map((s) => `${s.id}:${s.subs.map((x) => x.id).join(',')}`)).toEqual([
      'overview:summary,events',
      'cost:cluster',
      'workloads:workloads,pods,containers,resources',
      'nodes:nodes,node-groups,karpenter,utilization',
      'automation:overview,workload-autoscaler,hpa,vpa',
      'security:vulnerabilities',
    ]);
    for (const s of EKS_SECTIONS) expect(s.subs.some((x) => x.id === s.defaultSub)).toBe(true);
  });

  it('labels the Automation subs as the v3 tree, with Workloads under the Workload autoscaler group', () => {
    const automation = EKS_SECTIONS.find((s) => s.id === 'automation')!;
    expect(automation.subs.map((s) => [s.id, s.label, s.group ?? null])).toEqual([
      ['overview', 'Overview', null],
      ['workload-autoscaler', 'Workloads', 'Workload autoscaler'],
      ['hpa', 'HPA', null],
      ['vpa', 'VPA', null],
    ]);
  });

  it('keeps every group contiguous in registry order, so a group is one sidebar row', () => {
    for (const section of EKS_SECTIONS) {
      const runs = section.subs.map((s) => s.group ?? null).filter((g, i, all) => g !== null && g !== all[i - 1]);
      expect(new Set(runs).size, section.id).toBe(runs.length);
    }
  });

  it('withSection sets tab and sub only', () => {
    expect(withSection('a=1&tab=x', 'nodes', 'nodes')).toBe('a=1&tab=nodes&sub=nodes');
  });

  it('withSection drops a one-shot view filter, so it never follows the user to the next section', () => {
    expect(withSection('a=1&tab=workloads&sub=pods&phase=Pending', 'nodes', 'nodes')).toBe('a=1&tab=nodes&sub=nodes');
  });
});

describe('sub aliases and page params (automation-v2 design §3.2)', () => {
  const automation = EKS_SECTIONS.find((s) => s.id === 'automation')!;
  const registered = (sub: string) => automation.subs.some((s) => s.id === sub);

  it('has exactly the planned Automation aliases', () => {
    expect(SUB_ALIASES).toEqual({
      automation: {
        'workload-autoscaling': 'workload-autoscaler',
        rightsizing: 'workload-autoscaler',
        'workload-rightsizing': 'workload-autoscaler',
        policies: 'workload-autoscaler-policies',
        'node-autoscaling': 'node-autoscaler',
        guardrails: 'node-autoscaler',
        rebalancing: 'node-autoscaler',
        spot: 'node-autoscaler',
        'pod-autoscaling': 'hpa',
      },
    });
  });

  it.each(Object.entries(SUB_ALIASES.automation))(
    'resolves ?tab=automation&sub=%s with a correction, to %s once it is registered and to the default until then',
    (alias, target) => {
      const r = resolve(`${BASE}&tab=automation&sub=${alias}`);
      const expected = registered(target) ? target : automation.defaultSub;
      expect([r.tab, r.sub]).toEqual(['automation', expected]);
      expect(r.corrected).not.toBeNull();
      expect(params(r.corrected).get('tab')).toBe('automation');
      expect(params(r.corrected).get('sub')).toBe(expected);
      expect(params(r.corrected).get('name')).toBe('prod-eks');
    },
  );

  it("sends main's ?tab=automation&sub=pod-autoscaling to HPA", () => {
    const r = resolve(`${BASE}&tab=automation&sub=pod-autoscaling`);
    expect([r.tab, r.sub]).toEqual(['automation', 'hpa']);
    expect(params(r.corrected).get('sub')).toBe('hpa');
  });

  it('sends ?sub=workload-autoscaling to Workload autoscaler › Workloads and keeps the workload it opened', () => {
    const r = resolve(`${BASE}&tab=automation&sub=workload-autoscaling&workload=a/Deployment/b`);
    expect([r.tab, r.sub]).toEqual(['automation', 'workload-autoscaler']);
    expect(params(r.corrected).get('workload')).toBe('a/Deployment/b');
  });

  it('applies an alias only inside its own section, and never a prototype key', () => {
    expect(resolve(`${BASE}&tab=nodes&sub=pod-autoscaling`)).toMatchObject({ tab: 'nodes', sub: 'nodes' });
    expect(resolve(`${BASE}&tab=automation&sub=constructor`)).toMatchObject({ tab: 'automation', sub: 'overview' });
  });

  it('owns each page param on exactly one registered page', () => {
    expect(PAGE_PARAMS).toEqual({
      workload: { tab: 'automation', sub: 'workload-autoscaler' },
      hpa: { tab: 'automation', sub: 'hpa' },
    });
    for (const owner of Object.values(PAGE_PARAMS)) {
      expect(resolve(`${BASE}&tab=${owner.tab}&sub=${owner.sub}`).corrected, `${owner.tab}/${owner.sub}`).toBeNull();
    }
  });

  it('withSection drops a page param on a link to any other page, and keeps it on its owner', () => {
    const detail = 'a=1&tab=automation&sub=workload-autoscaler&workload=a/Deployment/b';
    expect(params(withSection(detail, 'automation', 'hpa')).has('workload')).toBe(false);
    expect(params(withSection(detail, 'workloads', 'workloads')).has('workload')).toBe(false);
    expect(params(withSection(detail, 'automation', 'workload-autoscaler')).get('workload')).toBe('a/Deployment/b');
    const hpa = 'a=1&tab=automation&sub=hpa&hpa=team-b/php-apache';
    expect(params(withSection(hpa, 'automation', 'vpa')).has('hpa')).toBe(false);
    expect(params(withSection(hpa, 'automation', 'hpa')).get('hpa')).toBe('team-b/php-apache');
    // Everything else stays where it was.
    expect([...params(withSection(detail, 'automation', 'hpa')).keys()]).toEqual(['a', 'tab', 'sub']);
  });

  it('drops a page param when a correction lands somewhere else', () => {
    const r = resolve(`${BASE}&tab=automation&sub=bogus&workload=a/Deployment/b`);
    expect([r.tab, r.sub]).toEqual(['automation', 'overview']);
    expect(params(r.corrected).has('workload')).toBe(false);
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

  const automationOf = (search: string) => nav(search).find((e) => e.id === 'automation')!;

  it('folds a group into one node: id group:<slug>, its first child\'s href, current while a child is', () => {
    const automation = automationOf(`${BASE}&tab=automation&sub=workload-autoscaler`);
    expect(automation.subs.map((s) => [s.id, s.label, s.current])).toEqual([
      ['overview', 'Overview', false],
      ['group:workload-autoscaler', 'Workload autoscaler', true],
      ['hpa', 'HPA', false],
      ['vpa', 'VPA', false],
    ]);
    const group = automation.subs[1];
    expect(group.children!.map((c) => [c.id, c.label, c.current])).toEqual([['workload-autoscaler', 'Workloads', true]]);
    expect(group.href).toBe(group.children![0].href);
    expect(resolve(searchOf(group.href))).toEqual({ tab: 'automation', sub: 'workload-autoscaler', corrected: null });
    for (const leaf of automation.subs.filter((s) => s !== group)) {
      expect(leaf.children, leaf.id).toBeUndefined();
      expect(resolve(searchOf(leaf.href))).toEqual({ tab: 'automation', sub: leaf.id, corrected: null });
    }
  });

  it('leaves the group node not current while another Automation page is', () => {
    const automation = automationOf(`${BASE}&tab=automation&sub=hpa`);
    const group = automation.subs.find((s) => s.id === 'group:workload-autoscaler')!;
    expect(group.current).toBe(false);
    expect(group.children!.map((c) => c.current)).toEqual([false]);
    expect(automation.subs.filter((s) => s.current).map((s) => s.id)).toEqual(['hpa']);
  });

  it('marks HPA current for main\'s old Pod autoscaler link', () => {
    expect(automationOf(`${BASE}&tab=automation&sub=pod-autoscaling`).subs.find((s) => s.current)?.id).toBe('hpa');
  });

  it('drops the workload from links that leave Workload autoscaler › Workloads, and keeps it on Workloads itself', () => {
    const automation = automationOf(`${BASE}&tab=automation&sub=workload-autoscaler&workload=a/Deployment/b`);
    expect(params(searchOf(automation.subs.find((s) => s.id === 'hpa')!.href)).has('workload')).toBe(false);
    expect(params(searchOf(automation.subs.find((s) => s.id === 'overview')!.href)).has('workload')).toBe(false);
    const workloads = automation.subs.find((s) => s.id === 'group:workload-autoscaler')!.children![0];
    expect(params(searchOf(workloads.href)).get('workload')).toBe('a/Deployment/b');
    for (const entry of nav(`${BASE}&tab=automation&sub=workload-autoscaler&workload=a/Deployment/b`)) {
      expect(params(searchOf(entry.href)).has('workload'), entry.id).toBe(false);
    }
  });

  it('folds only consecutive subs of a group, and leaves ungrouped subs as leaves', () => {
    const registry: SectionRegistry = [
      { id: 's', label: 'S', icon: 'mdi:circle-outline', defaultSub: 'a', subs: [
        { id: 'a', label: 'A' },
        { id: 'b', label: 'B', group: 'Two words' },
        { id: 'c', label: 'C', group: 'Two words' },
        { id: 'd', label: 'D' },
      ] },
    ];
    const [entry] = sectionNavEntries(registry, {}, '/p', 'tab=s&sub=c');
    expect(entry.subs.map((s) => [s.id, s.current, s.children?.map((c) => c.id) ?? null])).toEqual([
      ['a', false, null],
      ['group:two-words', true, ['b', 'c']],
      ['d', false, null],
    ]);
    expect(entry.subs[1].href).toBe('/p?tab=s&sub=b');
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

  it.each([
    ['pod-autoscaling', 'hpa'],
    ['workload-autoscaling', 'workload-autoscaler'],
  ])('rewrites the aliased ?sub=%s to %s once, with replace', async (alias, sub) => {
    const router = renderAt(`${BASE}&tab=automation&sub=${alias}`);
    expect(screen.getByTestId('pair')).toHaveTextContent(`automation/${sub}`);
    await act(async () => {});
    expect(router.state.historyAction).toBe('REPLACE');
    const p = params(router.state.location.search);
    expect([p.get('tab'), p.get('sub')]).toEqual(['automation', sub]);
    expect(p.get('accountIds')).toBe('111111111111');
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
