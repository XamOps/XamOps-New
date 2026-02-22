import React from 'react';
import { AzureFinOpsReportData } from '../../types/azureFinops';
import clsx from 'clsx';

interface AzureFinOpsSavingsCalloutProps {
  reportData: AzureFinOpsReportData | null;
}

const AzureFinOpsSavingsCallout: React.FC<AzureFinOpsSavingsCalloutProps> = ({ reportData }) => {
  const savings = reportData?.totalPotentialSavings ?? 0;
  const rightsizingCount = reportData?.rightsizingItems?.length ?? 0;
  const wasteCount = reportData?.wasteItems?.length ?? 0;

  if (savings <= 0) return null;

  const formatted = savings.toLocaleString('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  });

  return (
    <div className={clsx(
      'rounded-xl p-5 border',
      'bg-gradient-to-r from-emerald-50 to-teal-50 border-emerald-200'
    )}>
      <div className="flex items-center gap-4">
        <div className="w-11 h-11 bg-gradient-to-br from-emerald-500 to-teal-600 rounded-xl flex items-center justify-center shadow-lg flex-shrink-0">
          <i className="fas fa-piggy-bank text-white text-lg" />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-semibold text-emerald-800">
            Total Potential Monthly Savings: <span className="text-lg font-extrabold">{formatted}</span>
          </p>
          <p className="text-xs text-emerald-600 mt-0.5">
            {rightsizingCount > 0 && `${rightsizingCount} rightsizing recommendation${rightsizingCount > 1 ? 's' : ''}`}
            {rightsizingCount > 0 && wasteCount > 0 && ' · '}
            {wasteCount > 0 && `${wasteCount} waste resource${wasteCount > 1 ? 's' : ''}`}
            {' — review the Insights section below to act on these opportunities.'}
          </p>
        </div>
        <div className="flex-shrink-0">
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-700 border border-emerald-300">
            <i className="fas fa-arrow-down text-[10px]" />
            Optimize Now
          </span>
        </div>
      </div>
    </div>
  );
};

export default AzureFinOpsSavingsCallout;
