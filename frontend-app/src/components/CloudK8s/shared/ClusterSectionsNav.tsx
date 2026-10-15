/**
 * The EKS cluster page's sections in the left sidebar, under CloudK8s
 * (implementation plan, Addendum 1; design §4.2, §5, §14).
 *
 * Rendered by the Sidebar right after the CloudK8s entry, the way XamSecure and XamLens list their
 * tabs. It shows only while a cluster the page accepts is in the URL: a context row (cluster name and
 * region from the link, and "All clusters"), one link per registered section in registry order, and
 * the current section's registered subtabs. A group of subtabs (Automation › Workload autoscaler) is
 * one plain link row; its subtabs show at a third level only while the group is current. Every entry
 * is a real link built by the page's own
 * resolver and serializer, so open-in-new-tab works and the sidebar and the page can't disagree.
 * Links push history entries, so Back and Forward step through sections, and carry
 * `inPageState`, so the layout keeps the page mounted and nothing is refetched.
 *
 * Navigation semantics, not a tablist: a `nav` landmark "Cluster sections", `aria-current="page"`
 * on the current section and subtab. Automation is listed for everyone who sees CloudK8s; when no
 * change is allowed it reads "View only", with the reason as its description (J-5).
 */
import { useId } from 'react';
import { Icon } from '@iconify/react';
import { Link, useLocation } from 'react-router-dom';
import SubNav from '../../Sidebar/SubNav';
import { EKS_SECTIONS, LEGACY_TAB_MAP, sectionNavEntries, type SectionNavSub } from '../../../hooks/useEksSectionRoute';
import { isDemoMode } from '../../../demo/demoConfig';
import { parseClusterLink } from '../../../utils/k8sClusterLink';
import { inPageState } from '../../../utils/inPageNavigation';
import { VIEW_ONLY_TEXT, useClusterNavAccess } from '../../../utils/k8sNavAccess';

export const CLUSTER_PAGE_PATH = '/eks-details';
export const FLEET_PATH = '/cloudk8s';
export const CLUSTER_SECTIONS_LABEL = 'Cluster sections';
export const ALL_CLUSTERS_LABEL = 'All clusters';

/** The section whose entry carries "View only" (J-5). */
const AUTOMATION_SECTION = 'automation';

const focus = (dark: boolean) =>
  `outline-none focus-visible:ring-2 focus-visible:ring-inset ${dark ? 'focus-visible:ring-white' : 'focus-visible:ring-[rgba(0,0,0,0.88)]'}`;

/** The look of the sidebar's NavItem, in greyscale (its active icon is indigo). */
function entryClass(dark: boolean, current: boolean): string {
  if (dark) {
    return current
      ? 'bg-[rgba(255,255,255,0.1)] font-semibold text-white'
      : 'font-medium text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.07)] hover:text-white';
  }
  return current
    ? 'bg-white font-semibold text-[rgba(0,0,0,0.9)] shadow-[0_1px_2px_rgba(0,0,0,0.06),0_1px_3px_rgba(0,0,0,0.05)]'
    : 'font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.9)]';
}

function iconClass(dark: boolean, current: boolean): string {
  if (dark) return current ? 'text-white' : 'text-[rgba(255,255,255,0.4)]';
  return current ? 'text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.4)]';
}

/**
 * A group row (e.g. Workload autoscaler): open and in strong text while one of its subtabs is the
 * page, never the "you are here" fill, which belongs to the leaf.
 */
function groupClass(dark: boolean, current: boolean): string {
  if (dark) {
    return current
      ? 'font-semibold text-white hover:bg-[rgba(255,255,255,0.07)]'
      : 'font-medium text-[rgba(255,255,255,0.6)] hover:bg-[rgba(255,255,255,0.07)] hover:text-white';
  }
  return current
    ? 'font-semibold text-[rgba(0,0,0,0.9)] hover:bg-[rgba(0,0,0,0.05)]'
    : 'font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.05)] hover:text-[rgba(0,0,0,0.9)]';
}

const secondary = (dark: boolean) => (dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]');

/** One subtab link, at the second level or under a group at the third. Its label wraps, never truncates. */
function SubLink({ sub, state, dark }: { sub: SectionNavSub; state: { pageKey: string }; dark: boolean }) {
  return (
    <Link
      to={sub.href}
      state={state}
      aria-current={sub.current ? 'page' : undefined}
      data-sub={sub.id}
      className={`flex min-h-[30px] items-center rounded-lg px-2.5 py-1 text-[13px] leading-snug tracking-[-0.01em] transition-colors duration-150 ${entryClass(dark, sub.current)} ${focus(dark)}`}
    >
      {sub.label}
    </Link>
  );
}

export interface ClusterSectionsNavProps {
  /** Dark theme, as the rest of the sidebar. */
  dark: boolean;
}

export function ClusterSectionsNav({ dark }: ClusterSectionsNavProps) {
  const location = useLocation();
  const descriptionId = useId();
  const link = location.pathname === CLUSTER_PAGE_PATH
    ? parseClusterLink(new URLSearchParams(location.search), isDemoMode())
    : null;
  const access = useClusterNavAccess(link?.clusterName ?? null, link?.accountId ?? null);

  // Nothing is rendered without a cluster in context, so a collapsed group holds no focusable links.
  if (!link) return <SubNav isOpen={false} dark={dark}>{null}</SubNav>;

  const entries = sectionNavEntries(EKS_SECTIONS, LEGACY_TAB_MAP, location.pathname, location.search);
  const state = inPageState(location);
  // The link's own region, never a default (Addendum 1, A3).
  const region = new URLSearchParams(location.search).get('region');
  const viewOnly = access?.viewOnly === true;

  return (
    <SubNav isOpen dark={dark}>
      <nav aria-label={CLUSTER_SECTIONS_LABEL} data-cluster-sections="">
        <div className="px-2.5 pb-1 pt-1.5">
          <p className="flex min-w-0 items-center gap-2">
            <Icon icon="mdi:kubernetes" width={16} aria-hidden="true" className={`shrink-0 ${iconClass(dark, false)}`} />
            <span className="sr-only">Cluster </span>
            <span
              className={`truncate text-[13px] font-semibold ${dark ? 'text-white' : 'text-[rgba(0,0,0,0.88)]'}`}
              title={link.clusterName}
              data-cluster-name=""
            >
              {link.clusterName}
            </span>
          </p>
          {region && (
            <p className={`truncate pl-6 text-[12px] ${secondary(dark)}`} data-cluster-region="">
              <span className="sr-only">Region </span>
              {region}
            </p>
          )}
          <Link
            to={FLEET_PATH}
            className={`mt-1 inline-flex items-center gap-1 rounded text-[12px] font-medium underline-offset-2 hover:underline ${secondary(dark)} ${focus(dark)}`}
          >
            <Icon icon="mdi:arrow-left" width={13} aria-hidden="true" />
            {ALL_CLUSTERS_LABEL}
          </Link>
        </div>

        <ul className="space-y-0.5">
          {entries.map((entry) => {
            const describe = entry.id === AUTOMATION_SECTION && viewOnly && access?.reason ? descriptionId : undefined;
            return (
              <li key={entry.id}>
                <Link
                  to={entry.href}
                  state={state}
                  aria-current={entry.current ? 'page' : undefined}
                  aria-describedby={describe}
                  title={describe ? access?.reason ?? undefined : undefined}
                  data-section={entry.id}
                  className={`flex min-h-[34px] items-center gap-2.5 rounded-lg px-2.5 py-1.5 text-[13px] tracking-[-0.01em] transition-colors duration-150 ${entryClass(dark, entry.current)} ${focus(dark)}`}
                >
                  <Icon icon={entry.icon} width={16} aria-hidden="true" className={`shrink-0 ${iconClass(dark, entry.current)}`} />
                  <span className="flex min-w-0 flex-col leading-tight">
                    <span className="truncate">{entry.label}</span>
                    {entry.id === AUTOMATION_SECTION && viewOnly && (
                      <span className={`text-[12px] font-medium ${secondary(dark)}`} data-view-only="">{VIEW_ONLY_TEXT}</span>
                    )}
                  </span>
                </Link>
                {describe && <span id={descriptionId} hidden>{access?.reason}</span>}
                {entry.current && entry.subs.length > 0 && (
                  <SubNav isOpen dark={dark} level={2}>
                    <ul className="space-y-0.5">
                      {entry.subs.map((sub) => (
                        <li key={sub.id}>
                          {sub.children ? (
                            <>
                              {/* A group row is a plain link to its first subtab; aria-current stays on the leaf. */}
                              <Link
                                to={sub.href}
                                state={state}
                                data-sub-group={sub.id}
                                className={`flex min-h-[30px] items-center gap-1 rounded-lg px-2.5 py-1 text-[13px] leading-snug tracking-[-0.01em] transition-colors duration-150 ${groupClass(dark, sub.current)} ${focus(dark)}`}
                              >
                                <span className="min-w-0 flex-1">{sub.label}</span>
                                <Icon
                                  icon={sub.current ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                                  width={14}
                                  aria-hidden="true"
                                  className={`shrink-0 ${iconClass(dark, false)}`}
                                />
                              </Link>
                              {/* Only the current group expands (automation-v2 design §3.1). */}
                              {sub.current && (
                                <SubNav isOpen dark={dark} level={3}>
                                  <ul className="space-y-0.5">
                                    {sub.children.map((child) => (
                                      <li key={child.id}>
                                        <SubLink sub={child} state={state} dark={dark} />
                                      </li>
                                    ))}
                                  </ul>
                                </SubNav>
                              )}
                            </>
                          ) : (
                            <SubLink sub={sub} state={state} dark={dark} />
                          )}
                        </li>
                      ))}
                    </ul>
                  </SubNav>
                )}
              </li>
            );
          })}
        </ul>
      </nav>
    </SubNav>
  );
}

export default ClusterSectionsNav;
