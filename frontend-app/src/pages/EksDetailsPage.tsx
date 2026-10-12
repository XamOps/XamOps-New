/**
 * The EKS cluster page (docs/cloudk8s/ui-revamp-design.md §4.2, §5, §13, §15; plan item 19).
 *
 * Validates the link (a bad one goes back to /cloudk8s with a notice), loads capabilities for the
 * account, and renders the header, the dashboard's loading and error states, the diagnostics panel
 * with `?debug=true`, the summary cards, and the primary sections as WAI-ARIA tabs. `?tab=&sub=`
 * belongs to useEksSectionRoute. Each section is its own lazy chunk inside Suspense and an
 * ErrorBoundary, so a section that fails leaves the rest of the page working.
 */
import React, { Suspense, lazy, useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import { Navigate, useSearchParams } from 'react-router-dom';
import { useEksDashboard } from '../hooks/useEksDashboard';
import { useEksSectionRoute } from '../hooks/useEksSectionRoute';
import { useK8sCacheIdentity } from '../hooks/useK8sCacheIdentity';
import { K8sCapabilitiesProvider } from '../hooks/useK8sCapabilities';
import { usePublishClusterNavAccess } from '../hooks/useK8sNavAccess';
import { isDemoMode } from '../demo/demoConfig';
import { resolveClusterVersion } from '../utils/k8sVersion';
import { parseClusterLink, type ClusterLink } from '../utils/k8sClusterLink';
import ErrorBoundary from '../components/Shared/ErrorBoundary';
import { SectionTabs } from '../components/CloudK8s/shared/SectionTabs';
import { ClusterHeader } from '../components/EksDetails/ClusterHeader';
import EksSummaryCards from '../components/EksDetails/EksSummaryCards';
import EksDiagnosticsPanel from '../components/EksDetails/EksDiagnosticsPanel';
import type { EksSectionProps } from '../components/EksDetails/sections/sectionTypes';
import type { EksSectionId } from '../types/eksTypes';

/** 69 KB of guide; loaded the first time Guide is opened (§15). */
const EksIntegrationModal = lazy(() => import('../components/EksDetails/EksIntegrationModal'));

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

/** Section-shaped: a subtab bar, a row of tiles and a table (§15). */
function SectionSkeleton() {
  return (
    <div className="space-y-3" aria-busy="true">
      <span className="sr-only">Loading section…</span>
      <div className={`h-8 w-72 ${block}`} />
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

function ClusterPage({ link, debug }: { link: ClusterLink; debug: boolean }) {
  const { clusterName, accountId, region, version } = link;
  useK8sCacheIdentity();
  // The sidebar's "View only" marker reads this page's capabilities (Addendum 1, A4).
  usePublishClusterNavAccess(clusterName, accountId);
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

  return (
    <main className="flex min-h-screen flex-1 flex-col bg-white" style={{ fontFamily: "'Inter', sans-serif" }}>
      <div className="w-full space-y-4 p-6">
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

        {loading && !dashboard && <PageSkeleton />}

        {error && !loading && <LoadError message={error} onRetry={retry} />}

        {(!loading || dashboard) && !error && (
          <>
            {debug && dashboard && <EksDiagnosticsPanel dashboard={dashboard} />}

            <EksSummaryCards eksData={eksData} clusterVersion={clusterVersion} />

            <SectionTabs
              variant="primary"
              label="Cluster sections"
              items={route.registry}
              selectedKey={route.tab}
              onSelectionChange={(tab) => route.select(tab)}
              // Today's tabs still read eks-details.css through this class (removed in item 31); the
              // overrides drop the page-level min-height and tinted background it also sets.
              panelClassName="eks-details-page !min-h-0 !bg-transparent"
              renderPanel={(tab) => (
                <SectionPanel tab={tab} clusterName={clusterName} eksData={eksData} route={route} />
              )}
            />
          </>
        )}
      </div>

      {guideLoaded && (
        <Suspense fallback={null}>
          <EksIntegrationModal isOpen={guideOpen} onClose={closeGuide} clusterName={clusterName} region={region} />
        </Suspense>
      )}
    </main>
  );
}

const EksDetailsPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const link = parseClusterLink(searchParams, isDemoMode());
  if (!link) return <Navigate to="/cloudk8s" replace state={{ notice: INVALID_LINK_NOTICE }} />;
  return (
    <K8sCapabilitiesProvider accountId={link.accountId} provider="AWS">
      <ClusterPage link={link} debug={searchParams.get('debug') === 'true'} />
    </K8sCapabilitiesProvider>
  );
};

export default EksDetailsPage;
