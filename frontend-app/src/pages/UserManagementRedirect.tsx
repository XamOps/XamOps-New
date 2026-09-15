/**
 * UserManagementRedirect
 *
 * Forwards the retired /user-management route to the Security Center's Identity & Access tab.
 *
 * <p>The standalone User Management page listed IAM users and roles with their policy names
 * and nothing else, which could not answer the question it appeared to answer — whether an
 * identity is over-privileged. That view now lives inside the Security Center, next to the
 * findings it relates to.
 *
 * <p>A redirect rather than a deleted route: the path is in browser histories, bookmarks and
 * the command palette, and a 404 on a formerly working settings page is a worse outcome than
 * one extra hop. Provider-aware because the Security Center is three separate pages.
 */

import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';

const UserManagementRedirect: React.FC = () => {
  const { selectedAccountDisplay } = useAccounts();

  // Defaults to AWS, matching the rest of the app's behaviour before accounts have loaded.
  const provider = selectedAccountDisplay?.provider ?? 'AWS';
  const target =
    provider === 'GCP' ? '/gcp-security'
      : provider === 'Azure' ? '/azure-security'
        : '/security';

  return <Navigate to={`${target}?tab=identity`} replace />;
};

export default UserManagementRedirect;
