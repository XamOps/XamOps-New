/**
 * Commitments view (design-ux-ops.md section 2.2, plan-phase-14 task 14.1): RI and Savings Plan coverage
 * and the respect setting, through the v2 endpoints. Reuses the RispCoverage components; their
 * behavior is unchanged, only the URLs come from `autospottingApi`.
 */
import React from 'react';
import RispCoverage from '../../../components/RispCoverage/RispCoverage';

const CommitmentsView: React.FC<{ accountId: number | null }> = ({ accountId }) => {
  if (accountId === null) {
    return <p className="text-sm text-gray-600">Select an AWS account to see its commitments.</p>;
  }
  return <RispCoverage accountId={null} v2AccountId={accountId} />;
};

export default CommitmentsView;
