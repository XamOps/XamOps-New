/**
 * Chooses between the shared shell and the legacy pages (plan-phase-13: the shell ships behind
 * `autospotting.ui.shell-enabled=false`). With the flag off, while the config is loading or failing,
 * the legacy AWS page renders as today. With the flag on, a GCP account gets the legacy GCP page (the GCP adapter ships in Phase 26).
 */
import React, { lazy, Suspense } from 'react';
import { useAccounts } from '../../contexts/AccountContext';
import { useAutoSpottingConfig } from '../../hooks/autospotting/useAutoSpottingConfig';

const AutoSpottingPage = lazy(() => import('./AutoSpottingPage'));
const SpotAutomationPage = lazy(() => import('../SpotAutomationPage'));
const GcpSpotAutomationPage = lazy(() => import('../GcpSpotAutomationPage'));

const AutoSpottingRoute: React.FC = () => {
  const { accounts, selectedAccountIds } = useAccounts();
  const { data: config, isLoading } = useAutoSpottingConfig();
  const provider = accounts.find(a => a.id === selectedAccountIds[0])?.provider;

  // Wait for the flag so the legacy page does not mount and then swap out.
  if (isLoading) return null;

  // Flag off (or config unreadable): the legacy AWS page, exactly as before.
  if (config?.shellEnabled !== true) return <Suspense fallback={null}><SpotAutomationPage /></Suspense>;

  return (
    <Suspense fallback={null}>
      {provider === 'GCP' ? <GcpSpotAutomationPage /> : <AutoSpottingPage />}
    </Suspense>
  );
};

export default AutoSpottingRoute;
