/**
 * `/asg-detail` and `/asg-config` (plan-phase-14 task 14.3). With `autospotting.ui.shell-enabled` on, they
 * send an AWS account to the group's deep link in the shell, so nothing the old pages offered is lost
 * (PD-08). With the flag off, while the config loads, or when it cannot be read, the legacy page renders
 * exactly as before.
 */
import React from 'react';
import { Navigate, useSearchParams } from 'react-router-dom';
import { useAccounts } from '../../contexts/AccountContext';
import { useAutoSpottingConfig } from '../../hooks/autospotting/useAutoSpottingConfig';

/** The shell's deep link to one group by its `region/name` key. */
export const groupDeepLink = (region: string | null, asgName: string | null): string =>
  region && asgName ? `/autospotting?group=${encodeURIComponent(`${region}/${asgName}`)}` : '/autospotting';

const LegacyAsgRoute: React.FC<{ legacy: React.ReactElement }> = ({ legacy }) => {
  const { accounts, selectedAccountIds } = useAccounts();
  const { data: config, isLoading } = useAutoSpottingConfig();
  const [params] = useSearchParams();
  const provider = accounts.find(a => a.id === selectedAccountIds[0])?.provider;
  // Wait for the flag so the legacy page does not mount and then swap out.
  if (isLoading) return null;
  if (config?.shellEnabled !== true || provider === 'GCP') return legacy;
  return <Navigate to={groupDeepLink(params.get('region'), params.get('asgName'))} replace />;
};

export default LegacyAsgRoute;
