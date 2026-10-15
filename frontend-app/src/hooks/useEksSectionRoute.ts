/**
 * Cluster-page section routing: `?tab=<primary>&sub=<subtab>` (docs/cloudk8s/ui-revamp-design.md §4.2,
 * §5; plan P3, P5, P11).
 *
 * The registry lists only subtabs that render real content; it grows by slice. Resolution: a known
 * primary is used as is, an old or planned `sub` maps through {@link SUB_ALIASES} when its target is
 * registered, an unknown `sub` falls back to the section default, an unknown `tab` to the first
 * section's default, and the old flat `?tab=` values map through {@link LEGACY_TAB_MAP}. When the URL
 * had to be corrected (legacy value, alias, unknown value, missing sub), the hook rewrites it once
 * with `replace`, keeping every other parameter except the page params a different page owns
 * ({@link PAGE_PARAMS}), so links stay shareable and Back leaves the page.
 */
import { useCallback, useEffect, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { EksSectionId, EksSubtabId, TabType } from '../types/eksTypes';
import { inPageState } from '../utils/inPageNavigation';

export interface SubtabDef<S extends string = string> {
  id: S;
  label: string;
  /**
   * The sidebar group the subtab sits under (automation-v2 design §3.1), e.g. "Workload autoscaler".
   * Subs of one group are contiguous in registry order; the group row links to its first subtab.
   */
  group?: string;
}

export interface SectionDef<T extends string = string, S extends string = string> {
  id: T;
  label: string;
  /** Bundled Iconify id, a string literal. */
  icon: string;
  defaultSub: S;
  subs: ReadonlyArray<SubtabDef<S>>;
}

export type SectionRegistry<T extends string = string, S extends string = string> = ReadonlyArray<SectionDef<T, S>>;

/**
 * The cluster page's sections (P3): the targets of the 11 legacy tabs, Automation › Overview and,
 * from FEAT-003, Overview › Events, Nodes › Node groups and Automation › Workload autoscaler (the
 * realtime branch's tab, hosted for the demo cut), then Automation › HPA and VPA (UI v3). Later
 * slices add the cost subtabs, Security › Compliance and Runtime, Data sources and the other
 * Automation subtabs.
 */
export const EKS_SECTIONS: SectionRegistry<EksSectionId, EksSubtabId> = [
  { id: 'overview', label: 'Overview', icon: 'mdi:view-dashboard-outline', defaultSub: 'summary',
    subs: [{ id: 'summary', label: 'Summary' }, { id: 'events', label: 'Events' }] },
  { id: 'cost', label: 'Cost', icon: 'mdi:currency-usd', defaultSub: 'cluster',
    subs: [{ id: 'cluster', label: 'Cluster' }] },
  { id: 'workloads', label: 'Workloads', icon: 'mdi:cube-outline', defaultSub: 'workloads',
    subs: [
      { id: 'workloads', label: 'Workloads' },
      { id: 'pods', label: 'Pods' },
      { id: 'containers', label: 'Containers' },
      { id: 'resources', label: 'Resources' },
    ] },
  { id: 'nodes', label: 'Nodes', icon: 'mdi:server', defaultSub: 'nodes',
    subs: [
      { id: 'nodes', label: 'Nodes' },
      { id: 'node-groups', label: 'Node groups' },
      { id: 'karpenter', label: 'Karpenter' },
      { id: 'utilization', label: 'Utilization' },
    ] },
  { id: 'automation', label: 'Automation', icon: 'mdi:robot-outline', defaultSub: 'overview',
    // The v3 Automation tree (automation-v2 design §3.1): Workload autoscaler › Workloads hosts the
    // realtime branch's Workload rightsizing cards, and Policies is the rules they use; HPA and VPA
    // are main's combined Pod autoscaler page, split. Node autoscaler and Activity register with
    // their pages.
    subs: [
      { id: 'overview', label: 'Overview' },
      { id: 'workload-autoscaler', label: 'Workloads', group: 'Workload autoscaler' },
      { id: 'workload-autoscaler-policies', label: 'Policies', group: 'Workload autoscaler' },
      { id: 'hpa', label: 'HPA' },
      { id: 'vpa', label: 'VPA' },
    ] },
  { id: 'security', label: 'Security', icon: 'mdi:shield-outline', defaultSub: 'vulnerabilities',
    subs: [{ id: 'vulnerabilities', label: 'Vulnerabilities' }] },
];

/** The old flat `?tab=` values (design §5.2, plus the realtime branch's `automation`). `resources` goes to Workloads (P5). */
export const LEGACY_TAB_MAP: Readonly<Record<TabType, { tab: EksSectionId; sub: EksSubtabId }>> = {
  overview: { tab: 'overview', sub: 'summary' },
  performance: { tab: 'nodes', sub: 'utilization' },
  workloads: { tab: 'workloads', sub: 'workloads' },
  pods: { tab: 'workloads', sub: 'pods' },
  containers: { tab: 'workloads', sub: 'containers' },
  nodes: { tab: 'nodes', sub: 'nodes' },
  resources: { tab: 'workloads', sub: 'resources' },
  security: { tab: 'security', sub: 'vulnerabilities' },
  cost: { tab: 'cost', sub: 'cluster' },
  karpenter: { tab: 'nodes', sub: 'karpenter' },
  autoscaling: { tab: 'automation', sub: 'overview' },
  // The realtime branch's Automation tab on the old page. `automation` is also a section id, so it
  // resolves directly to the section default, and the map agrees (§5.2); Workload autoscaler is one
  // sidebar click away.
  automation: { tab: 'automation', sub: 'overview' },
};

/** Per section, an old or planned `?sub=` id and the sub it names now. */
export type SubAliasMap = Readonly<Record<string, Readonly<Record<string, string>>>>;

/**
 * Old or planned `?sub=` ids (automation-v2 design §3.2), scoped to their section. An alias resolves
 * with a `replace` correction, and only when its target is registered; until then the link lands on
 * the section default, like any unknown sub. This keeps the realtime branch's `workload-autoscaling`
 * and main's `pod-autoscaling` links working after the split into Workload autoscaler, HPA and VPA.
 */
export const SUB_ALIASES: SubAliasMap = {
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
};

/**
 * Page-scoped parameters and the one section and sub that owns each (automation-v2 design §3.2):
 * `workload` opens a workload on Workload autoscaler › Workloads, `hpa` an HPA on the HPA page. A
 * link to any other page drops them, so a sidebar click never reopens a stale detail.
 */
export const PAGE_PARAMS: Readonly<Record<string, { tab: EksSectionId; sub: EksSubtabId }>> = {
  workload: { tab: 'automation', sub: 'workload-autoscaler' },
  hpa: { tab: 'automation', sub: 'hpa' },
};

export interface ResolvedSectionRoute<T extends string = string, S extends string = string> {
  tab: T;
  sub: S;
  /** The search string to write with `replace` (no leading "?"), or null when the URL is already right. */
  corrected: string | null;
}

function findSection<T extends string, S extends string>(registry: SectionRegistry<T, S>, id: string | null) {
  return id === null ? undefined : registry.find((s) => s.id === id);
}

function hasSub<T extends string, S extends string>(section: SectionDef<T, S>, sub: string | null): sub is S {
  return sub !== null && section.subs.some((s) => s.id === sub);
}

/** The sub an alias names in this section, or null (own keys only, so `constructor` is no alias). */
function aliasOf(aliases: SubAliasMap, tab: string, sub: string | null): string | null {
  const scoped = Object.prototype.hasOwnProperty.call(aliases, tab) ? aliases[tab] : undefined;
  return scoped && sub !== null && Object.prototype.hasOwnProperty.call(scoped, sub) ? scoped[sub] : null;
}

/**
 * One-shot view filters a link may add to the URL it builds, e.g. Overview's "View pending pods"
 * (`phase=Pending` on Workloads › Pods). Section links drop them, so a filter doesn't follow the
 * user into the next section or back.
 */
export const VIEW_FILTER_PARAMS = ['phase'] as const;

/**
 * Search string with tab and sub set, the view filters dropped, the page params dropped unless the
 * target owns them, and every other parameter kept in place.
 */
export function withSection(search: string, tab: string, sub: string): string {
  const params = new URLSearchParams(search);
  for (const p of VIEW_FILTER_PARAMS) params.delete(p);
  for (const [p, owner] of Object.entries(PAGE_PARAMS)) {
    if (owner.tab !== tab || owner.sub !== sub) params.delete(p);
  }
  params.set('tab', tab);
  params.set('sub', sub);
  return params.toString();
}

/** Pure resolution of `search` against a registry, a legacy map and the sub aliases (P11). */
export function resolveSectionRoute<T extends string, S extends string>(
  registry: SectionRegistry<T, S>,
  legacyMap: Readonly<Record<string, { tab: string; sub: string }>>,
  search: string,
  subAliases: SubAliasMap = SUB_ALIASES,
): ResolvedSectionRoute<T, S> {
  if (registry.length === 0) throw new Error('resolveSectionRoute needs at least one section');
  const params = new URLSearchParams(search);
  const rawTab = params.get('tab');
  const rawSub = params.get('sub');
  const first = registry[0];

  // Nothing asked for: the default, without rewriting a plain link.
  if (rawTab === null && rawSub === null) return { tab: first.id, sub: first.defaultSub, corrected: null };

  const direct = findSection(registry, rawTab);
  if (direct) {
    if (hasSub(direct, rawSub)) return { tab: direct.id, sub: rawSub, corrected: null };
    const alias = aliasOf(subAliases, direct.id, rawSub);
    const sub = hasSub(direct, alias) ? alias : direct.defaultSub;
    return { tab: direct.id, sub, corrected: withSection(search, direct.id, sub) };
  }

  const legacy = rawTab !== null ? legacyMap[rawTab] : undefined;
  const mapped = legacy ? findSection(registry, legacy.tab) : undefined;
  if (legacy && mapped) {
    const sub = hasSub(mapped, legacy.sub) ? legacy.sub : mapped.defaultSub;
    return { tab: mapped.id, sub, corrected: withSection(search, mapped.id, sub) };
  }

  return { tab: first.id, sub: first.defaultSub, corrected: withSection(search, first.id, first.defaultSub) };
}

// ── Sidebar entries (implementation plan, Addendum 1, J-2..J-4) ──────────────

/** A group row's id in the sidebar tree, `group:<slug of the group label>`. */
export type SectionNavGroupId = `group:${string}`;

export interface SectionNavSub<S extends string = string> {
  /** A registered subtab, or a group row ({@link SectionNavGroupId}) when `children` is set. */
  id: S | SectionNavGroupId;
  label: string;
  /** `pathname?…&tab=<section>&sub=<sub>`, every other parameter kept; a group row's is its first child's. */
  href: string;
  /** For a group row, true when one of its children is current. */
  current: boolean;
  /** A group row's subtabs, in registry order; absent on a leaf. */
  children?: Array<SectionNavSub<S>>;
}

export interface SectionNavEntry<T extends string = string, S extends string = string> {
  id: T;
  label: string;
  /** Bundled Iconify id from the registry. */
  icon: string;
  /** The section with its default sub, so the link resolves to itself without a correction. */
  href: string;
  current: boolean;
  /** The current section's registered subtabs; empty for every other section. */
  subs: Array<SectionNavSub<S>>;
}

const groupSlug = (label: string): string =>
  label.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

/**
 * A section's subtabs as sidebar nodes: an ungrouped sub is a leaf; consecutive subs sharing a
 * `group` fold into one group node (`group:<slug>`) that links to its first child and is current when
 * a child is.
 */
function subNodes<S extends string>(
  subs: ReadonlyArray<SubtabDef<S>>,
  currentSub: string,
  hrefOf: (sub: S) => string,
): Array<SectionNavSub<S>> {
  const nodes: Array<SectionNavSub<S>> = [];
  for (const sub of subs) {
    const leaf: SectionNavSub<S> = { id: sub.id, label: sub.label, href: hrefOf(sub.id), current: sub.id === currentSub };
    if (!sub.group) {
      nodes.push(leaf);
      continue;
    }
    const last = nodes[nodes.length - 1];
    if (last?.children && last.label === sub.group) {
      last.children.push(leaf);
      last.current = last.current || leaf.current;
    } else {
      nodes.push({ id: `group:${groupSlug(sub.group)}`, label: sub.group, href: leaf.href, current: leaf.current, children: [leaf] });
    }
  }
  return nodes;
}

/**
 * The sidebar's cluster sections: one entry per registered section, in registry order, and the
 * current section's registered subtabs as a tree (group rows with their children). Pure, and built
 * from the same registry, resolver and serializer ({@link withSection}) as the page, so the sidebar
 * and the page can never disagree.
 */
export function sectionNavEntries<T extends string, S extends string>(
  registry: SectionRegistry<T, S>,
  legacyMap: Readonly<Record<string, { tab: string; sub: string }>>,
  pathname: string,
  search: string,
  subAliases: SubAliasMap = SUB_ALIASES,
): Array<SectionNavEntry<T, S>> {
  const resolved = resolveSectionRoute(registry, legacyMap, search, subAliases);
  const href = (tab: string, sub: string) => `${pathname}?${withSection(search, tab, sub)}`;
  return registry.map((section) => {
    const current = section.id === resolved.tab;
    return {
      id: section.id,
      label: section.label,
      icon: section.icon,
      href: href(section.id, section.defaultSub),
      current,
      subs: current ? subNodes(section.subs, resolved.sub, (sub) => href(section.id, sub)) : [],
    };
  });
}

export interface SectionRoute<T extends string = string, S extends string = string> {
  tab: T;
  sub: S;
  section: SectionDef<T, S>;
  registry: SectionRegistry<T, S>;
  /** Switches section (and subtab; the section default when omitted), replacing the history entry. */
  select: (tab: T, sub?: S) => void;
  /** A link to a section and subtab on this page, keeping every other parameter. */
  hrefFor: (tab: T, sub?: S) => string;
}

export function useEksSectionRoute<T extends string = EksSectionId, S extends string = EksSubtabId>(
  registry: SectionRegistry<T, S> = EKS_SECTIONS as unknown as SectionRegistry<T, S>,
  legacyMap: Readonly<Record<string, { tab: string; sub: string }>> = LEGACY_TAB_MAP,
  subAliases: SubAliasMap = SUB_ALIASES,
): SectionRoute<T, S> {
  const location = useLocation();
  const navigate = useNavigate();
  const resolved = useMemo(
    () => resolveSectionRoute(registry, legacyMap, location.search, subAliases),
    [registry, legacyMap, subAliases, location.search],
  );

  // In-page navigations (utils/inPageNavigation): the layout keeps the page mounted, so a tab click
  // or a correction re-renders the page instead of remounting it and refetching what it loaded.
  useEffect(() => {
    if (resolved.corrected !== null) {
      navigate({ search: resolved.corrected, hash: location.hash }, { replace: true, state: inPageState(location) });
    }
  }, [resolved.corrected, navigate, location]);

  const section = registry.find((s) => s.id === resolved.tab) ?? registry[0];

  const searchFor = useCallback((tab: T, sub?: S) => {
    const target = registry.find((s) => s.id === tab) ?? registry[0];
    const chosen = sub && target.subs.some((s) => s.id === sub) ? sub : target.defaultSub;
    return withSection(location.search, target.id, chosen);
  }, [registry, location.search]);

  const select = useCallback((tab: T, sub?: S) => {
    navigate({ search: searchFor(tab, sub) }, { replace: true, state: inPageState(location) });
  }, [navigate, searchFor, location]);

  const hrefFor = useCallback((tab: T, sub?: S) => `${location.pathname}?${searchFor(tab, sub)}`,
    [location.pathname, searchFor]);

  return { tab: resolved.tab, sub: resolved.sub, section, registry, select, hrefFor };
}

export default useEksSectionRoute;
