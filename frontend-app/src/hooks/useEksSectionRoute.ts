/**
 * Cluster-page section routing: `?tab=<primary>&sub=<subtab>` (docs/cloudk8s/ui-revamp-design.md §4.2,
 * §5; plan P3, P5, P11).
 *
 * The registry lists only subtabs that render real content; it grows by slice. Resolution: a known
 * primary is used as is, an unknown `sub` falls back to the section default, an unknown `tab` to the
 * first section's default, and the old flat `?tab=` values map through {@link LEGACY_TAB_MAP}. When
 * the URL had to be corrected (legacy value, unknown value, missing sub), the hook rewrites it once
 * with `replace`, keeping every other parameter, so links stay shareable and Back leaves the page.
 */
import { useCallback, useEffect, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { EksSectionId, EksSubtabId, TabType } from '../types/eksTypes';
import { inPageState } from '../utils/inPageNavigation';

export interface SubtabDef<S extends string = string> {
  id: S;
  label: string;
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
 * The cluster page's sections in slice 1 (P3): the targets of the 11 legacy tabs plus
 * Automation › Overview. Later slices add Overview › Events, the cost subtabs, Nodes › Node groups,
 * Security › Compliance and Runtime, Data sources and the other Automation subtabs.
 */
export const EKS_SECTIONS: SectionRegistry<EksSectionId, EksSubtabId> = [
  { id: 'overview', label: 'Overview', icon: 'mdi:view-dashboard-outline', defaultSub: 'summary',
    subs: [{ id: 'summary', label: 'Summary' }] },
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
      { id: 'karpenter', label: 'Karpenter' },
      { id: 'utilization', label: 'Utilization' },
    ] },
  { id: 'automation', label: 'Automation', icon: 'mdi:robot-outline', defaultSub: 'overview',
    subs: [{ id: 'overview', label: 'Overview' }] },
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
  // The realtime branch's Automation tab on the old page.
  automation: { tab: 'automation', sub: 'overview' },
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

/** Search string with tab and sub set and every other parameter kept in place. */
export function withSection(search: string, tab: string, sub: string): string {
  const params = new URLSearchParams(search);
  params.set('tab', tab);
  params.set('sub', sub);
  return params.toString();
}

/** Pure resolution of `search` against a registry and a legacy map (P11). */
export function resolveSectionRoute<T extends string, S extends string>(
  registry: SectionRegistry<T, S>,
  legacyMap: Readonly<Record<string, { tab: string; sub: string }>>,
  search: string,
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
    return { tab: direct.id, sub: direct.defaultSub, corrected: withSection(search, direct.id, direct.defaultSub) };
  }

  const legacy = rawTab !== null ? legacyMap[rawTab] : undefined;
  const mapped = legacy ? findSection(registry, legacy.tab) : undefined;
  if (legacy && mapped) {
    const sub = hasSub(mapped, legacy.sub) ? legacy.sub : mapped.defaultSub;
    return { tab: mapped.id, sub, corrected: withSection(search, mapped.id, sub) };
  }

  return { tab: first.id, sub: first.defaultSub, corrected: withSection(search, first.id, first.defaultSub) };
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
): SectionRoute<T, S> {
  const location = useLocation();
  const navigate = useNavigate();
  const resolved = useMemo(
    () => resolveSectionRoute(registry, legacyMap, location.search),
    [registry, legacyMap, location.search],
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
