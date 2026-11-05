/**
 * The EKS cluster page (docs/cloudk8s/ui-revamp-design.md §4.2, §5, §13, §15; plan item 19).
 *
 * Validates the link (a bad one goes back to /cloudk8s with a notice), loads capabilities for the
 * account, and renders the header, the dashboard's loading and error states, the diagnostics panel
 * with `?debug=true`, the status strip, and the section the URL names under a heading. `?tab=&sub=`
 * belongs to useEksSectionRoute; the sections and subtabs are links in the left sidebar
 * (ClusterSectionsNav, implementation plan Addendum 1), not a tab strip. Each section is its own lazy
 * chunk inside Suspense and an ErrorBoundary, so a section that fails leaves the rest of the page
 * working.
 */
import React, { Fragment, Suspense, lazy, useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useTheme } from '../contexts/ThemeContext';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { writeLastCluster } from '../utils/k8sLastCluster';
import { useEksDashboard } from '../hooks/useEksDashboard';
import { useEksSectionRoute } from '../hooks/useEksSectionRoute';
import { useK8sCacheIdentity } from '../hooks/useK8sCacheIdentity';
import { K8sCapabilitiesProvider, useK8sCapabilities } from '../hooks/useK8sCapabilities';
import { presentOnboarding } from '../components/CloudK8s/onboarding/onboardingPresentation';
import { usePublishClusterNavAccess } from '../hooks/useK8sNavAccess';
import { isDemoMode } from '../demo/demoConfig';
import { resolveClusterVersion } from '../utils/k8sVersion';
import { parseClusterLink, type ClusterLink } from '../utils/k8sClusterLink';
import ErrorBoundary from '../components/Shared/ErrorBoundary';
import SessionExpiredOverlay from '../components/Shared/SessionExpiredOverlay';
import { AutomationControlBar } from '../components/EksDetails/AutomationControlBar';
import { ClusterHeader } from '../components/EksDetails/ClusterHeader';
import { ClusterStatusStrip } from '../components/EksDetails/ClusterStatusStrip';
import EksDiagnosticsPanel from '../components/EksDetails/EksDiagnosticsPanel';
import type { EksSectionProps } from '../components/EksDetails/sections/sectionTypes';
import type { EksSectionId } from '../types/eksTypes';

/**
 * The two-phase onboarding guide (design §9.1). Loaded the first time Guide / Set-up-monitoring is
 * opened (§15); which drawer opens is chosen from the cluster's onboarding state (see guideKind).
 */
const ConnectClusterDrawer = lazy(() => import('../components/CloudK8s/onboarding/ConnectClusterDrawer'));
const EnableAutomationDrawer = lazy(() => import('../components/CloudK8s/onboarding/EnableAutomationDrawer'));

type SectionComponent = React.LazyExoticComponent<React.ComponentType<EksSectionProps>>;

/** One chunk per registered primary section (§15). */
const SECTIONS: Partial<Record<EksSectionId, SectionComponent>> = {
  overview: lazy(() => import('../components/EksDetails/sections/OverviewSection')),
  cost: lazy(() => import('../components/EksDetails/sections/CostSection')),
  workloads: lazy(() => import('../components/EksDetails/sections/WorkloadsSection')),
  nodes: lazy(() => import('../components/EksDetails/sections/NodesSection')),
  automation: lazy(() => import('../components/EksDetails/sections/AutomationSection')),
  security: lazy(() => import('../components/EksDetails/sections/SecuritySection')),
};

export const INVALID_LINK_NOTICE = 'That cluster link is invalid.';

// The parser lives in utils/k8sClusterLink so the sidebar reads the same link without this chunk.
export { parseClusterLink };
export type { ClusterLink };

const actionClass =
  'inline-flex items-center gap-1.5 rounded-md border border-[rgba(0,0,0,0.2)] bg-white px-3 py-1.5 text-[12px] font-semibold '
  + 'text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

const block = 'animate-pulse rounded-lg bg-[rgba(0,0,0,0.06)]';

function PageSkeleton() {
  return (
    <div className="space-y-6" aria-busy="true">
      <span className="sr-only">Loading cluster…</span>
      <div className="grid grid-cols-4 gap-6">
        {[0, 1, 2, 3].map((i) => <div key={i} className={`h-40 ${block}`} />)}
      </div>
      <div className={`h-96 ${block}`} />
    </div>
  );
}

/** Section-shaped: a row of tiles and a table (§15). */
function SectionSkeleton() {
  return (
    <div className="space-y-3" aria-busy="true">
      <span className="sr-only">Loading section…</span>
      <div className="grid grid-cols-3 gap-3">
        {[0, 1, 2].map((i) => <div key={i} className={`h-24 ${block}`} />)}
      </div>
      <div className={`h-64 ${block}`} />
    </div>
  );
}

function SectionError({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-start gap-2 rounded-lg border border-[rgba(0,0,0,0.88)] bg-white p-4 text-[13px]">
      <span className="flex items-center gap-2 font-semibold text-[rgba(0,0,0,0.88)]">
        <Icon icon="mdi:alert-octagon" width={16} aria-hidden="true" />
        This section couldn't be shown.
      </span>
      <span className="text-[rgba(0,0,0,0.6)]">The other sections still work. Try again, or open another section.</span>
      <button type="button" onClick={onRetry} className={actionClass}>
        <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
        Try again
      </button>
    </div>
  );
}

function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="flex items-start gap-3 rounded-lg border border-[rgba(0,0,0,0.88)] bg-white p-5">
      <Icon icon="mdi:alert-octagon" width={18} aria-hidden="true" className="mt-0.5 shrink-0 text-[rgba(0,0,0,0.88)]" />
      <div className="min-w-0 flex-1">
        <h2 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Couldn't load this cluster</h2>
        <p className="mt-0.5 break-words text-[13px] text-[rgba(0,0,0,0.6)]">{message}</p>
        <button type="button" onClick={onRetry} className={`mt-3 ${actionClass}`}>
          <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
          Retry
        </button>
      </div>
    </div>
  );
}

/**
 * Names the section and subtab on screen (Addendum 1, J-7). The sidebar lists them as links
 * (ClusterSectionsNav), so this is what tells a screen reader the content changed: the heading sits
 * in a polite live region and is announced as "Workloads, Pods" when the URL moves on. A grouped
 * subtab names its group too: "Automation, Workload autoscaler, Workloads".
 */
function SectionHeading({ route }: { route: EksSectionProps['route'] }) {
  const sub = route.section.subs.find((s) => s.id === route.sub) ?? route.section.subs[0];
  const trail = sub ? [...(sub.group ? [sub.group] : []), sub.label] : [];
  return (
    <div aria-live="polite" aria-atomic="true">
      <h2 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]" data-section-heading="">
        {route.section.label}
        {trail.map((part) => (
          <Fragment key={part}>
            {/* The space is a text node of its own: names trim the inside of a span. */}
            <span aria-hidden="true" className="ml-1.5 font-normal text-[rgba(0,0,0,0.4)]">›</span>
            <span className="sr-only">,</span> {part}
          </Fragment>
        ))}
      </h2>
    </div>
  );
}

/** One primary section: its chunk, a skeleton while it loads, and a boundary reset per tab. */
function SectionPanel({ tab, ...props }: EksSectionProps & { tab: EksSectionId }) {
  const [attempt, setAttempt] = useState(0);
  const Section = SECTIONS[tab];
  if (!Section) return null;
  return (
    // The shared boundary resets on pathname only, so the key resets it per tab and on Try again.
    <ErrorBoundary key={`${tab}:${attempt}`} fallback={<SectionError onRetry={() => setAttempt((n) => n + 1)} />}>
      <Suspense fallback={<SectionSkeleton />}>
        <Section {...props} />
      </Suspense>
    </ErrorBoundary>
  );
}

/**
 * Remembers the cluster this page is showing, so the CloudK8s resolver reopens it (design §3.1).
 * ALWAYS keyed by `link.accountId` — the account the cluster BELONGS to — never the selected account,
 * which may differ mid-switch. Writes on the cluster the link names (deep-link or switch), and skips
 * nothing: a valid link always records.
 */
function useRememberLastCluster(link: ClusterLink): void {
  const { userProfile } = useAuth();
  const email = userProfile?.email ?? null;
  const { clusterName, accountId, region } = link;
  useEffect(() => {
    writeLastCluster(email, accountId, { name: clusterName, region });
  }, [email, accountId, clusterName, region]);
}

/**
 * One net navigation to the CloudK8s resolver when the selected account genuinely changes away from
 * the cluster this page is showing (design §3.1; review Finding 2).
 *
 * Fires `navigate('/cloudk8s', { replace: true })` only on a POST-MOUNT transition between two
 * differing, non-null selections where the new selection differs from `link.accountId` — never on the
 * initial value and never on a null→value transition. Both the `selectedAccountIds` change and a
 * post-mount `account-changed` event are watched, but a single resolution ref guarantees exactly one
 * net navigation per switch.
 *
 * It runs on a microtask AFTER the triggering selection settles and only navigates when the page is
 * still on `/eks-details` for the OLD account: `AccountContext.applySelection` already soft-navigates
 * away from `/eks-details` (to the provider dashboard) on a same-page switch, so if it has already
 * moved us we add no second navigation.
 */
function useAccountSwitchReconciler(link: ClusterLink): void {
  const navigate = useNavigate();
  const location = useLocation();
  const { selectedAccountIds } = useAccounts();
  const selected = selectedAccountIds[0] ?? null;
  const linkAccountId = link.accountId;

  // The latest selection, so the event path reads a fresh value rather than a stale closure.
  const selectedRef = useRef<string | null>(selected);
  selectedRef.current = selected;
  // The router's current path (never window.location, which MemoryRouter and SSR don't update).
  const pathnameRef = useRef(location.pathname);
  pathnameRef.current = location.pathname;
  // The selection we last reconciled against; seeded so the initial value never navigates.
  const prevSelectedRef = useRef<string | null>(selected);
  // One navigation per switch, whichever trigger (state change or event) arrives first.
  const reconciledRef = useRef(false);

  // A single decision, shared by both triggers: navigate once on a genuine switch from `from` to a
  // non-null selection that differs from this cluster's account, and only while we're still on
  // /eks-details (AccountContext may have already soft-navigated us off it — review Finding 2).
  const reconcile = useCallback((from: string | null) => {
    if (reconciledRef.current) return;
    const current = selectedRef.current;
    if (!from || !current || from === current || current === linkAccountId) return;
    if (pathnameRef.current !== '/eks-details') return;
    reconciledRef.current = true;
    navigate('/cloudk8s', { replace: true });
  }, [linkAccountId, navigate]);

  // React to the selection value changing (sidebar apply, URL sync, popstate): remember the new value
  // and reconcile against the OLD one once it has settled (a microtask after this commit).
  useEffect(() => {
    const prev = prevSelectedRef.current;
    if (prev !== selected && selected) {
      // reconcile() ignores a null `from` (the initial / null -> value case), so we only ever
      // navigate on a genuine value -> different-value switch.
      prevSelectedRef.current = selected;
      queueMicrotask(() => reconcile(prev));
    }
  }, [selected, reconcile]);

  // React to a soft 'account-changed' broadcast meeting the same condition, against the last value we
  // reconciled from.
  useEffect(() => {
    const onAccountChanged = () => {
      const from = prevSelectedRef.current;
      queueMicrotask(() => reconcile(from));
    };
    globalThis.addEventListener('account-changed', onAccountChanged);
    return () => globalThis.removeEventListener('account-changed', onAccountChanged);
  }, [reconcile]);
}

/**
 * Which onboarding drawer the Guide opens, keyed off presentOnboarding().nextAction (design §9.1).
 *
 * The read-only/automation family — a cluster that is already connected read-only ('Enable
 * automation'), or automation is enabled and being viewed/opened ('View', 'Open automation') —
 * opens the Phase-2 EnableAutomationDrawer. Everything else (the not-installed / waiting-for-agent /
 * retry / troubleshoot family, whose action is 'Connect' or a recovery of the Phase-1 connect)
 * opens the Phase-1 ConnectClusterDrawer. 'Open automation' falls back to EnableAutomationDrawer
 * rather than deep-linking the Automation section, so the Guide entry point stays a single surface.
 */
type GuideKind = 'connect' | 'automation';

const AUTOMATION_FAMILY: ReadonlySet<string> = new Set(['Enable automation', 'View', 'Open automation']);

function guideKind(nextAction: string): GuideKind {
  return AUTOMATION_FAMILY.has(nextAction) ? 'automation' : 'connect';
}

function ClusterPage({ link, debug }: { link: ClusterLink; debug: boolean }) {
  const { clusterName, accountId, region, version } = link;
  useK8sCacheIdentity();
  useRememberLastCluster(link);
  useAccountSwitchReconciler(link);
  // The sidebar's "View only" marker reads this page's capabilities (Addendum 1, A4).
  usePublishClusterNavAccess(clusterName, accountId);
  // State-aware guide routing (design §9.1): read the server-derived onboarding block and map its
  // next action to the drawer to open. lastExchangeAt is not read on this page, so pass null — the
  // helper tolerates it (it only affects the awaiting-agent age word, never the next action family).
  const caps = useK8sCapabilities();
  const onboarding = caps.forCluster(clusterName)?.onboarding;
  const guide = guideKind(presentOnboarding(onboarding, null, Date.now()).nextAction);
  const eksData = useEksDashboard({ clusterName, accountId, region, version });
  const route = useEksSectionRoute();
  const [guideLoaded, setGuideLoaded] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const openGuide = useCallback(() => {
    setGuideLoaded(true);
    setGuideOpen(true);
  }, []);
  const closeGuide = useCallback(() => setGuideOpen(false), []);

  const { dashboard, loading, error, fetchDashboard } = eksData;
  const retry = useCallback(() => { void fetchDashboard(false); }, [fetchDashboard]);
  const clusterVersion = resolveClusterVersion(version, dashboard?.clusterOverview?.version);

  // Dark mode is scoped to the CloudK8s subtree: ThemeContext tracks light/dark but adds no `.dark`
  // class to the document (design open-decision §9.2), so the class is applied here on the page
  // wrapper. Every v3 clarity/status/chart component carries its own `dark:` variants, so this one
  // class is all the dark CloudK8s content needs; the rest of the app is unaffected.
  const { theme } = useTheme();
  const dark = theme === 'dark';

  return (
    // FEAT-004 docs reconciliation (ui-revamp-design.md §14): the CloudK8s surface uses the app's
    // system sans stack, not a pinned 'Inter' — the design contract never names Inter.
    <main
      className={`flex min-h-screen min-w-0 flex-1 flex-col bg-[color:var(--chrome-page)] ${dark ? 'dark' : ''}`.trim()}
      data-cloudk8s-theme={theme}
      style={{ fontFamily: 'ui-sans-serif, system-ui, sans-serif' }}
    >
      {/* BUG-3: a mid-flow 401 fires `session-expired`; this honest interstitial covers the page for
          the beat before AuthContext's redirect paints, so the EKS/Automation route is never blank. */}
      <SessionExpiredOverlay />
      {/* min-w-0 down the chain: the content area is viewport − the ~220px app sidebar; without it a
          wide table (Workloads › Pods) would push this column past that width and clip the last
          column at the edge instead of scrolling inside its own card (owner polish review #5). */}
      <div className="w-full min-w-0 space-y-4 p-6">
        <ClusterHeader
          clusterName={clusterName}
          accountId={accountId}
          region={region}
          version={clusterVersion}
          isSyncing={eksData.isSyncing}
          syncTimedOut={eksData.syncTimedOut}
          lastUpdateTimestamp={eksData.lastUpdateTimestamp}
          getTimeSince={eksData.getTimeSince}
          onSync={eksData.syncInBackground}
          onOpenGuide={openGuide}
        />

        {/* Depends only on capabilities, not the dashboard fetch, so it renders during the skeleton too. */}
        <AutomationControlBar
          clusterName={clusterName}
          accountId={accountId}
          workloadsHref={route.hrefFor('automation', 'workload-autoscaler')}
          nodeAutoscalerHref={route.hrefFor('automation', 'node-autoscaler')}
        />

        {loading && !dashboard && <PageSkeleton />}

        {/* The status strip (§4.2, D10) replaces the old summary cards; when the dashboard failed it
            says "Data unavailable" above the error rather than disappearing. */}
        {(!loading || dashboard) && (
          <ClusterStatusStrip clusterName={clusterName} dashboard={dashboard} dashboardError={Boolean(error) && !loading} />
        )}

        {error && !loading && <LoadError message={error} onRetry={retry} />}

        {(!loading || dashboard) && !error && (
          <>
            {debug && dashboard && <EksDiagnosticsPanel dashboard={dashboard} />}

            <SectionHeading route={route} />

            {/* Today's tabs still read eks-details.css through this class (removed in item 31); the
                overrides drop the page-level min-height and tinted background it also sets. */}
            <div className="eks-details-page !min-h-0 min-w-0 !bg-transparent">
              <SectionPanel
                tab={route.tab}
                clusterName={clusterName}
                accountId={accountId}
                region={region}
                eksData={eksData}
                route={route}
                onOpenGuide={openGuide}
              />
            </div>
          </>
        )}
      </div>

      {guideLoaded && (
        <Suspense fallback={null}>
          {guide === 'automation' ? (
            <EnableAutomationDrawer
              open={guideOpen}
              onClose={closeGuide}
              clusterName={clusterName}
              region={region}
              accountId={accountId}
            />
          ) : (
            <ConnectClusterDrawer
              open={guideOpen}
              onClose={closeGuide}
              clusterName={clusterName}
              region={region}
              accountId={accountId}
            />
          )}
        </Suspense>
      )}
    </main>
  );
}

const EksDetailsPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const link = parseClusterLink(searchParams, isDemoMode());
  if (!link) return <Navigate to="/cloudk8s" replace state={{ notice: INVALID_LINK_NOTICE }} />;
  // The capabilities provider stays mounted per account, while a cluster (or region) change remounts
  // the ClusterPage subtree so no per-cluster hook keeps the previous cluster's data (design §3.1).
  return (
    <K8sCapabilitiesProvider accountId={link.accountId} provider="AWS">
      <ClusterPage
        key={`${link.accountId}|${link.region}|${link.clusterName}`}
        link={link}
        debug={searchParams.get('debug') === 'true'}
      />
    </K8sCapabilitiesProvider>
  );
};

export default EksDetailsPage;
