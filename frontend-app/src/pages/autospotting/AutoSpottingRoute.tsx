/**
 * Chooses between the shared shell and the legacy pages (plan-phase-13: the shell ships behind
 * `autospotting.ui.shell-enabled=false`). With the flag off, while the config is loading or failing,
 * the legacy AWS page renders as today (the legacy GCP page on `/gcp-autospotting`). With the flag on, AWS and GCP
 * accounts get the shell; `/gcp-autospotting` opens it with GCP preselected (plan-phase-26 task 26.5).
 */
import React, { lazy, Suspense } from 'react';
import { Navigate } from 'react-router-dom';
import { useAccounts } from '../../contexts/AccountContext';
import { useAutoSpottingConfig } from '../../hooks/autospotting/useAutoSpottingConfig';

const AutoSpottingPage = lazy(() => import('./AutoSpottingPage'));
const SpotAutomationPage = lazy(() => import('../SpotAutomationPage'));
const GcpSpotAutomationPage = lazy(() => import('../GcpSpotAutomationPage'));

/**
 * `canonical` marks a legacy URL (`/spot-automation`): with the shell on, an AWS account is sent to
 * `/autospotting`; with it off, nothing changes (plan-phase-14 task 14.3). `gcp` marks `/gcp-autospotting`.
 */
const AutoSpottingRoute: React.FC<{ canonical?: boolean; gcp?: boolean }> = ({ canonical, gcp }) => {
  const { accounts, selectedAccountIds } = useAccounts();
  const { data: config, isLoading } = useAutoSpottingConfig();
  const provider = accounts.find(a => a.id === selectedAccountIds[0])?.provider;

  // Wait for the flag so the legacy page does not mount and then swap out.
  if (isLoading) return null;

  // Flag off (or config unreadable): the legacy pages, exactly as before.
  if (config?.shellEnabled !== true) {
    return <Suspense fallback={null}>{gcp ? <GcpSpotAutomationPage /> : <SpotAutomationPage />}</Suspense>;
  }

  if (canonical && provider !== 'GCP') return <Navigate to="/autospotting" replace />;
  return (
    <Suspense fallback={null}>
      <AutoSpottingPage preselect={gcp || provider === 'GCP' ? 'GCP' : undefined} />
    </Suspense>
  );
};

export default AutoSpottingRoute;
