/**
 * Commitments view (design-ux-ops.md section 2.2, plan-phase-14 task 14.1): RI and Savings Plan coverage
 * and the respect setting, through the v2 endpoints. Reuses the RispCoverage components; their
 * behavior is unchanged, only the URLs come from `autospottingApi`.
 */
import React from 'react';
import RispCoverage from '../../../components/RispCoverage/RispCoverage';
import { EmptyState } from '../components/theme';

const CommitmentsView: React.FC<{ accountId: number | null }> = ({ accountId }) => {
  if (accountId === null) {
    return <EmptyState icon="mdi:account-search-outline" title="Select an AWS account to see its commitments." />;
  }
  return <RispCoverage accountId={null} v2AccountId={accountId} />;
};

export default CommitmentsView;
