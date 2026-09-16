/**
 * IdentityAccessTab
 *
 * Container for the Security Center's Identity & Access tab. Binds useUserManagement to
 * IdentityAccessCanvas so the AWS, GCP and Azure security pages each add the tab with a
 * single element and cannot drift apart.
 *
 * <p>The hook resolves the selected account itself, from sessionStorage plus the
 * {@code account-changed} window event, which is why this takes no accountId prop. That is
 * the same mechanism the retired User Management page used, so account switching keeps
 * working exactly as before.
 */

import React from 'react';
import { useUserManagement } from '../../../hooks/useUserManagement';
import IdentityAccessCanvas from './IdentityAccessCanvas';

const IdentityAccessTab: React.FC = () => {
  const {
    loading,
    error,
    accountName,
    accountId,
    users,
    roles,
    searchQuery,
    setSearchQuery,
    filteredUsers,
    filteredRoles,
    formatDate,
    isOldDate,
    countNoMfa,
    copyToClipboard,
    labels,
    provider,
    riskCounts,
    flaggedOnly,
    setFlaggedOnly,
    analysisDegraded,
    analysisNote,
    analysisPending,
    riskAnalysisAvailable,
    fetchData,
  } = useUserManagement();

  return (
    <IdentityAccessCanvas
      users={filteredUsers}
      roles={filteredRoles}
      // Unfiltered: the access graph rebuilds groups from user.groups, and filtering the source
      // list would make a search on role names empty the groups lane.
      allUsers={users}
      accountName={accountName}
      accountId={accountId}
      totalUsers={users.length}
      totalRoles={roles.length}
      isLoading={loading && users.length === 0 && roles.length === 0}
      error={error}
      searchQuery={searchQuery}
      onSearchChange={setSearchQuery}
      flaggedOnly={flaggedOnly}
      onFlaggedOnlyChange={setFlaggedOnly}
      riskCounts={riskCounts}
      riskAnalysisAvailable={riskAnalysisAvailable}
      analysisDegraded={analysisDegraded}
      analysisNote={analysisNote}
      analysisPending={analysisPending}
      noMfaCount={countNoMfa}
      provider={provider}
      labels={labels}
      formatDate={formatDate}
      isOldDate={isOldDate}
      onCopy={copyToClipboard}
      onRefresh={() => fetchData(true)}
      isRefreshing={loading}
    />
  );
};

export default IdentityAccessTab;
