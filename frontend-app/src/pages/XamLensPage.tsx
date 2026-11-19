import React, { useEffect } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { useTopbar } from '../contexts/TopbarContext';
import { useDspmStatus } from '../hooks/useDspmData';
import {
  LEGACY_XAMLENS_SEGMENTS, XAMLENS_FILES_HOME, XAMLENS_HOME, XAMLENS_PAGE_TABS, datastoreTab, filesTab,
  type XamLensPageTab,
} from '../constants/xamlensTabs';
import PoweredByProvider from '../components/XamSecure/PoweredByProvider';
import DatastoresPage from '../components/Dspm/datastores/DatastoresPage';
import MisconfigurationsPage from '../components/Dspm/misconfigurations/MisconfigurationsPage';
import FilesPage from '../components/Dspm/files/FilesPage';
import DspmStatusTab from '../components/Dspm/DspmStatusTab';
import DataLineagePage from '../components/Dspm/lineage/DataLineagePage';
import DashboardPage from '../components/Dspm/dashboard/DashboardPage';

/**
 * XamLens: where sensitive data lives across cloud datastores, SaaS accounts, endpoints and on-prem
 * databases.
 *
 * Backed by Matters.ai through the DSPM add-on. Not scoped by the sidebar account picker, unlike
 * XamSecure: Matters rows carry a Matters integration id rather than a cloud account number, so the
 * Source / Account filter is the equivalent control. There is no account guard here for the same
 * reason, which also means a customer without cloud accounts can still use it.
 *
 * Routes: `/xamlens/datastores/:datastoreTab` is the Datastores page; `/xamlens/dashboard` the
 * Dashboard; `/xamlens/files/:filesTab` the Files page (`/xamlens/files` and an unknown tab land on
 * SaaS); `/xamlens/misconfigurations` the Misconfigurations page; `/xamlens/data-lineage` the Data
 * Lineage page; `/xamlens/connection` the connection status. The old `/xamlens/cloud`, `/saas` and
 * `/endpoint` links land on the matching Datastores tab, and any other segment on All.
 */
const XamLensPage: React.FC = () => {
  const { tab, datastoreTab: datastoreSlug, filesTab: filesSlug } =
    useParams<{ tab?: string; datastoreTab?: string; filesTab?: string }>();
  const { setTitle, setTitleAdornment } = useTopbar();
  // Fetched once per visit rather than per tab: the route element is reused across tab changes.
  const { status, statusLoading, reloadStatus } = useDspmStatus();

  useEffect(() => {
    setTitle('XamLens');
    setTitleAdornment(<PoweredByProvider provider="matters" />);
    // Cleared on the way out, or the attribution carries into the next page's topbar.
    return () => { setTitle(null); setTitleAdornment(null); };
  }, [setTitle, setTitleAdornment]);

  const filesDef = filesSlug === undefined ? undefined : filesTab(filesSlug);
  if (filesSlug !== undefined && !filesDef) return <Navigate to={XAMLENS_FILES_HOME} replace />;
  if (filesSlug === undefined && datastoreSlug === undefined && tab === 'files') {
    return <Navigate to={XAMLENS_FILES_HOME} replace />;
  }
  const def = datastoreSlug === undefined ? undefined : datastoreTab(datastoreSlug);
  if (datastoreSlug !== undefined && !def) return <Navigate to={XAMLENS_HOME} replace />;

  const connection = datastoreSlug === undefined && tab === 'connection';
  const misconfigurations = datastoreSlug === undefined && tab === 'misconfigurations';
  const lineage = datastoreSlug === undefined && tab === 'data-lineage';
  const page = datastoreSlug === undefined && (XAMLENS_PAGE_TABS as ReadonlyArray<string>).includes(tab ?? '')
    ? tab as XamLensPageTab
    : undefined;
  if (datastoreSlug === undefined && !connection && !misconfigurations && !lineage && !filesDef && !page) {
    const legacy = tab !== undefined && Object.prototype.hasOwnProperty.call(LEGACY_XAMLENS_SEGMENTS, tab)
      ? LEGACY_XAMLENS_SEGMENTS[tab]
      : undefined;
    return <Navigate to={legacy ? `/xamlens/datastores/${legacy}` : XAMLENS_HOME} replace />;
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-white">
      {/* pb-24 keeps the pager clear of the floating chat-support widget (bottom-right). */}
      <div className="p-6 pb-24 space-y-6">
        {statusLoading && (
          <p className="py-16 text-center text-sm text-gray-400">Loading XamLens…</p>
        )}
        {!statusLoading && status && def && (
          <DatastoresPage tab={def.slug} status={status} onRetry={reloadStatus} />
        )}
        {!statusLoading && status && misconfigurations && (
          <MisconfigurationsPage status={status} onRetry={reloadStatus} />
        )}
        {!statusLoading && status && filesDef && (
          <FilesPage tab={filesDef.slug} status={status} onRetry={reloadStatus} />
        )}
        {!statusLoading && status && connection && (
          <DspmStatusTab status={status} onRetry={reloadStatus} />
        )}
        {!statusLoading && status && lineage && (
          <DataLineagePage status={status} onRetry={reloadStatus} />
        )}
        {!statusLoading && status && page === 'dashboard' && (
          <DashboardPage status={status} onRetry={reloadStatus} />
        )}
      </div>
    </main>
  );
};

export default XamLensPage;
