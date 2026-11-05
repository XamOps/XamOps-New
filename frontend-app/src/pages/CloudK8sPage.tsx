/**
 * CloudK8s landing route (docs/cloudk8s/ui-revamp-design.md §3.3).
 *
 * `/cloudk8s` is not a cluster list first — it is a resolver that opens the user DIRECTLY on a
 * cluster dashboard. It picks the landing cluster for the selected account (last-viewed → the sole
 * cluster → the first configured one → the first in list order) and `<Navigate replace>`s to its
 * `/eks-details` URL, keeping the top-bar account dropdown as the only cluster chooser. The fleet
 * list ("All clusters") is on-demand only: `/cloudk8s?view=all`.
 *
 * The resolver is honest about waiting and never spins forever (review Finding 7, LOCKED default):
 * while auth or the account list is still settling it shows a neutral "Opening your dashboard" state
 * with no list, count or fetch; once that settles it either redirects, shows the fleet, or lands on a
 * TERMINAL state — "That account isn't available" (the selected id isn't in the list after a short
 * self-heal window) or "Connect an account" (no accounts at all).
 *
 * A non-AWS selection is redirected to the provider's own CloudK8s page (GCP/Azure), matching
 * AccountContext's provider page map.
 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { motion, useIsPresent } from 'framer-motion';
import { Icon } from '@iconify/react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useAuth } from '../contexts/AuthContext';
import { useK8sClusters } from '../hooks/useK8sClusters';
import { useK8sCacheIdentity } from '../hooks/useK8sCacheIdentity';
import { readLastCluster, type LastCluster } from '../utils/k8sLastCluster';
import { serializeClusterLink, FLEET_VIEW_PARAM, FLEET_VIEW_ALL } from '../utils/k8sClusterLink';
import { inPageState } from '../utils/inPageNavigation';
import CloudK8sHeader from '../components/CloudK8s/CloudK8sHeader';
import CloudK8sClusterTable from '../components/CloudK8s/CloudK8sClusterTable';
import CloudK8sIntegrations from '../components/CloudK8s/CloudK8sIntegrations';

export interface EKSCluster {
  name: string;
  region: string;
  version: string;
  /** The cluster's lifecycle status as the backend reports it (e.g. ACTIVE); absent from older cached lists. */
  status?: string;
  /** Whether the Container Insights performance log group exists (the backend's exact-match check). */
  connected: boolean;
  /** The same check in detail; absent from lists cached before it existed. */
  containerInsights?: { status: string; checkedAt?: string | null; reason?: string | null; action?: string | null } | null;
  isEnabling?: boolean;
}

/** How long the resolver waits for a just-selected but not-yet-present account before giving a terminal state (§3.3). */
export const SELF_HEAL_MS = 3000;

export const OPENING_TEXT = 'Opening your dashboard…';
export const ACCOUNT_UNAVAILABLE_TITLE = "That account isn't available.";
export const CONNECT_ACCOUNT_TITLE = 'Connect an account to get started.';

/** A message another page sent with the navigation, e.g. the cluster page's "That cluster link is invalid." */
function noticeFrom(state: unknown): string | null {
  const notice = (state as { notice?: unknown } | null)?.notice;
  return typeof notice === 'string' && notice ? notice : null;
}

/**
 * The cluster `/cloudk8s` should open for this account, or null when the list is empty (§3.3):
 *   1. the last-viewed cluster, when it is still in the list by name+region;
 *   2. else the sole cluster, when there is exactly one;
 *   3. else the first with Container Insights CONFIGURED, else the first in list order (never re-sorted);
 *   4. empty list → null.
 */
export function pickLandingCluster(clusters: EKSCluster[], last: LastCluster | null): EKSCluster | null {
  if (clusters.length === 0) return null;
  if (last) {
    const remembered = clusters.find((c) => c.name === last.name && c.region === last.region);
    if (remembered) return remembered;
  }
  if (clusters.length === 1) return clusters[0];
  const configured = clusters.find((c) => c.containerInsights?.status === 'CONFIGURED');
  return configured ?? clusters[0];
}

/** The provider's own CloudK8s route; AccountContext maps the same paths. */
function providerCloudK8sPath(provider: string | undefined): string | null {
  if (provider === 'GCP') return '/gcp-cloudk8s';
  if (provider === 'Azure') return '/azure-cloudk8s';
  return null; // AWS or unknown: resolve on this page.
}

const linkClass =
  'inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.2)] bg-white px-3.5 py-2 text-[13px] font-semibold '
  + 'text-[rgba(0,0,0,0.88)] transition-colors hover:bg-[rgba(0,0,0,0.04)] outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

const primaryLinkClass =
  'inline-flex items-center gap-1.5 rounded-lg bg-[rgba(0,0,0,0.88)] px-3.5 py-2 text-[13px] font-semibold text-white transition-colors '
  + 'hover:bg-black outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

/** The neutral "we're on it" state: no list, no count, no fetch, no redirect. */
function OpeningState() {
  return (
    <main className="flex flex-1 items-center justify-center px-6 py-16" aria-busy="true">
      <div className="flex flex-col items-center gap-3 text-center">
        <Icon icon="mdi:loading" width={24} aria-hidden="true" className="animate-spin text-[rgba(0,0,0,0.5)]" />
        <p role="status" className="text-[14px] font-medium text-[rgba(0,0,0,0.6)]">{OPENING_TEXT}</p>
      </div>
    </main>
  );
}

/** A terminal state (§3.3): never a perpetual spinner. Title, a line of help, and one or two actions. */
function TerminalState({ title, detail, actions }: { title: string; detail: string; actions: React.ReactNode }) {
  return (
    <main className="flex flex-1 items-center justify-center px-6 py-16">
      <div className="flex max-w-md flex-col items-center gap-3 text-center">
        <Icon icon="mdi:cloud-off-outline" width={28} aria-hidden="true" className="text-[rgba(0,0,0,0.4)]" />
        <h1 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)]">{title}</h1>
        <p className="text-[13px] text-[rgba(0,0,0,0.6)]">{detail}</p>
        <div className="mt-2 flex flex-wrap items-center justify-center gap-2.5">{actions}</div>
      </div>
    </main>
  );
}

const CloudK8sPage: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { authReady, userProfile } = useAuth();
  const { accounts, selectedAccountIds, selectedAccountDisplay, isLoading } = useAccounts();

  useK8sCacheIdentity();

  const selectedAccountId = selectedAccountIds[0] || null;
  const email = userProfile?.email ?? null;

  // "All clusters" is on-demand: ?view=all renders the fleet in place and never redirects.
  const params = new URLSearchParams(location.search);
  const fleetRequested = params.get(FLEET_VIEW_PARAM) === FLEET_VIEW_ALL;

  // A notice forwarded by another page (e.g. the invalid-link bounce) also pins us to the fleet view,
  // so the message is read before any redirect.
  const isPresent = useIsPresent();
  const [notice, setNotice] = useState<string | null>(() => noticeFrom(location.state));
  // A notice pins the fleet for the life of this mount: the clearing effect below strips it from
  // history state, so reading location.state alone would let the resolver redirect a beat later.
  const noticePresentRef = useRef(noticeFrom(location.state) !== null);
  if (noticeFrom(location.state) !== null) noticePresentRef.current = true;
  const noticePresent = noticePresentRef.current;
  useEffect(() => {
    if (isPresent && noticeFrom(location.state)) {
      navigate(
        { pathname: location.pathname, search: location.search, hash: location.hash },
        { replace: true, state: inPageState(location) },
      );
    }
  }, [isPresent, location, navigate]);

  // Whether the selected id is present in the loaded account list; undefined while we can't tell.
  const selectionPresent = selectedAccountId ? accounts.some((a) => a.id === selectedAccountId) : false;

  // A selection that exists but isn't in the list yet (selection-from-storage, mid account fetch) is
  // not yet resolvable: wait rather than redirect or declare it unavailable.
  const pendingSelection = Boolean(selectedAccountId) && !selectionPresent;
  const waiting = !authReady || isLoading || pendingSelection;

  // Once the list has settled, a selection that still isn't present is "that account isn't available"
  // — but only after a short self-heal window, never a perpetual spinner (Finding 7, LOCKED default).
  const [healed, setHealed] = useState(false);
  useEffect(() => {
    setHealed(false);
    if (authReady && !isLoading && pendingSelection) {
      const timer = setTimeout(() => setHealed(true), SELF_HEAL_MS);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [authReady, isLoading, pendingSelection, selectedAccountId]);

  // The pre-first-fetch window where there is no selection and no account yet also self-heals to the
  // "Connect an account" state rather than waiting forever.
  const [emptyHealed, setEmptyHealed] = useState(false);
  const emptyPending = authReady && !isLoading && accounts.length === 0 && !selectedAccountId;
  useEffect(() => {
    setEmptyHealed(false);
    if (emptyPending) {
      const timer = setTimeout(() => setEmptyHealed(true), SELF_HEAL_MS);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [emptyPending]);

  const provider = selectedAccountDisplay.provider;
  const providerPath = providerCloudK8sPath(provider);
  // Only resolve AWS clusters on this page; a non-AWS provider redirects to its own page (skipped for
  // the fleet view and a forwarded notice, as any redirect is).
  const resolvable = authReady && !waiting && selectionPresent && providerPath === null;

  // The cluster list for the selected AWS account. The hook already clears to loading on an account
  // switch and never serves a cached empty list.
  const { clusters, status, errorKind, isRefreshing, reload, rescan } = useK8sClusters(resolvable ? selectedAccountId : null);

  // The remembered cluster is read only once we have a real, authenticated identity and a resolvable
  // account, so we never read an anonymous-keyed key before auth is ready.
  const last = useMemo(
    () => (resolvable && selectedAccountId ? readLastCluster(email, selectedAccountId) : null),
    [resolvable, selectedAccountId, email],
  );

  const pick = useMemo(
    () => (status === 'ready' ? pickLandingCluster(clusters, last) : null),
    [status, clusters, last],
  );

  // Skip the redirect when the fleet is explicitly requested, a notice is being shown, or there is no
  // cluster to open. Otherwise redirect once per mount.
  const skipRedirect = fleetRequested || noticePresent;
  const target =
    !skipRedirect && resolvable && providerPath === null && pick
      ? serializeClusterLink({ clusterName: pick.name, region: pick.region, accountId: String(selectedAccountId), version: pick.version })
      : null;

  // Once-per-mount redirect guard: a <Navigate replace> is emitted at most once, so a re-render after
  // the account list churns can't stack history entries or bounce a settled page.
  const redirectedRef = useRef(false);

  // --- Decide what to render, in priority order. ---

  // A non-AWS provider goes to its own CloudK8s page (unless the fleet/notice pins us here).
  if (!skipRedirect && providerPath && authReady && !waiting && selectionPresent) {
    redirectedRef.current = true;
    return <Navigate to={providerPath} replace />;
  }

  // Still settling: the honest wait, with no fetch and no list.
  if (!skipRedirect && waiting) {
    // Terminal states for the two windows that would otherwise wait forever.
    if (healed && pendingSelection) {
      return (
        <TerminalState
          title={ACCOUNT_UNAVAILABLE_TITLE}
          detail="The selected account isn't in your list. Pick another account, or add one."
          actions={
            <>
              <Link to="/account-manager" className={linkClass}>Manage accounts</Link>
              <Link to="/add-account" className={primaryLinkClass}>Add an account</Link>
            </>
          }
        />
      );
    }
    return <OpeningState />;
  }

  // Settled with no account at all: connect one (also covers the pre-first-fetch window via the timer).
  if (!skipRedirect && accounts.length === 0 && !selectedAccountId) {
    if (emptyHealed || (authReady && !isLoading)) {
      return (
        <TerminalState
          title={CONNECT_ACCOUNT_TITLE}
          detail="XamOps needs a connected cloud account before it can open a cluster."
          actions={<Link to="/add-account" className={primaryLinkClass}>Add an account</Link>}
        />
      );
    }
    return <OpeningState />;
  }

  // Settled, AWS, account present: resolve to a cluster dashboard. Guard to once per mount.
  if (target && !redirectedRef.current) {
    redirectedRef.current = true;
    return <Navigate to={target} replace />;
  }

  // From here we render the fleet view in place: ?view=all, a forwarded notice, a null pick (no
  // clusters / still loading / error), or a non-AWS provider we didn't redirect.
  const fleetClusters = resolvable ? clusters : [];
  const fleetLoading = resolvable ? status === 'loading' : false;

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      {notice && (
        <div
          role="status"
          className="flex items-center gap-3 rounded-lg border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] px-3.5 py-2.5 text-[13px] text-[rgba(0,0,0,0.88)]"
        >
          <Icon icon="mdi:information-outline" width={16} aria-hidden="true" className="shrink-0 text-[rgba(0,0,0,0.6)]" />
          <span className="min-w-0 flex-1">{notice}</span>
          <button
            type="button"
            onClick={() => setNotice(null)}
            aria-label="Dismiss"
            className="flex h-6 w-6 items-center justify-center rounded-md text-[rgba(0,0,0,0.6)] outline-none hover:bg-[rgba(0,0,0,0.05)] focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)]"
          >
            <Icon icon="mdi:close" width={14} aria-hidden="true" />
          </button>
        </div>
      )}
      <CloudK8sHeader
        isRefreshing={isRefreshing}
        onRefresh={rescan}
      />
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-5">
        {errorKind && status === 'error' ? (
          <div role="alert" className="flex items-start gap-3 rounded-lg border border-[rgba(0,0,0,0.88)] bg-white p-5">
            <Icon icon="mdi:alert-octagon" width={18} aria-hidden="true" className="mt-0.5 shrink-0 text-[rgba(0,0,0,0.88)]" />
            <div className="min-w-0 flex-1">
              <h2 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Couldn't load your clusters</h2>
              <p className="mt-0.5 text-[13px] text-[rgba(0,0,0,0.6)]">The cluster list didn't load. Try again.</p>
              <button type="button" onClick={reload} className={`mt-3 ${linkClass}`}>
                <Icon icon="mdi:refresh" width={14} aria-hidden="true" />
                Retry
              </button>
            </div>
          </div>
        ) : (
          <CloudK8sClusterTable
            clusters={fleetClusters}
            isLoading={fleetLoading}
            onViewDetails={(cluster) => navigate(serializeClusterLink({
              clusterName: cluster.name, region: cluster.region, accountId: String(selectedAccountId), version: cluster.version,
            }))}
          />
        )}
        <CloudK8sIntegrations />
      </motion.div>
    </main>
  );
};

export default CloudK8sPage;
