import React, { useEffect } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { useTopbar } from '../contexts/TopbarContext';
import { useDspmStatus } from '../hooks/useDspmData';
import { xamLensTab } from '../constants/xamlensTabs';
import PoweredByProvider from '../components/XamSecure/PoweredByProvider';
import DspmAssetsTab from '../components/Dspm/DspmAssetsTab';
import DspmStatusTab from '../components/Dspm/DspmStatusTab';

/**
 * XamLens: where sensitive data lives across cloud datastores, SaaS accounts and endpoints.
 *
 * Backed by Matters.ai through the DSPM add-on. Not scoped by the sidebar account picker, unlike
 * XamSecure: Matters rows carry a Matters integration id rather than a cloud account number, so each
 * tab's Account filter is the equivalent control. There is no account guard here for the same reason,
 * which also means a customer without cloud accounts can still use it.
 */
const XamLensPage: React.FC = () => {
  const { tab } = useParams<{ tab?: string }>();
  const def = xamLensTab(tab);
  const { setTitle, setTitleAdornment } = useTopbar();
  // Fetched once per visit rather than per tab: the route element is reused across tab changes.
  const { status, statusLoading, reloadStatus } = useDspmStatus();

  useEffect(() => {
    setTitle('XamLens');
    setTitleAdornment(<PoweredByProvider provider="matters" />);
    // Cleared on the way out, or the attribution carries into the next page's topbar.
    return () => { setTitle(null); setTitleAdornment(null); };
  }, [setTitle, setTitleAdornment]);

  if (!def) return <Navigate to="/xamlens/cloud" replace />;

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-white">
      {/* pb-24 keeps the pager clear of the floating chat-support widget (bottom-right). */}
      <div className="p-6 pb-24 space-y-6">
        {statusLoading && (
          <p className="py-16 text-center text-sm text-gray-400">Loading XamLens…</p>
        )}
        {!statusLoading && status && def.surface && (
          // Keyed by surface so filters, paging and search reset between tabs: a provider=S3 filter
          // means nothing on Endpoint.
          <DspmAssetsTab key={def.surface} surface={def.surface} status={status} onRetry={reloadStatus} />
        )}
        {!statusLoading && status && def.id === 'connection' && (
          <DspmStatusTab status={status} onRetry={reloadStatus} />
        )}
      </div>
    </main>
  );
};

export default XamLensPage;
