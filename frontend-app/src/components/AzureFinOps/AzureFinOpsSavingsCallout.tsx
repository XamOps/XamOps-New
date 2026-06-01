import React from 'react';
import { Icon } from '@iconify/react';
import { AzureFinOpsReportData } from '../../types/azureFinops';

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
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

  return (
    <div className="bg-[#F0FDF4] border border-[#D1FAE5] rounded-xl p-4">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 bg-[#DCFCE7] rounded-xl flex items-center justify-center flex-shrink-0">
          <Icon icon="mdi:currency-usd" width={22} color="#158045" />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-[13px] font-semibold text-[#15803D]">
            Total Potential Monthly Savings:{' '}
            <span
              className="text-[22px] font-extrabold text-[#158045]"
              style={{ fontFamily: "'Barlow Condensed', 'Inter', sans-serif" }}
            >
              {formatted}
            </span>
          </p>
          <p className="text-[11px] text-[#16A34A] mt-0.5">
            {rightsizingCount > 0 &&
              `${rightsizingCount} rightsizing recommendation${rightsizingCount > 1 ? 's' : ''}`}
            {rightsizingCount > 0 && wasteCount > 0 && ' · '}
            {wasteCount > 0 &&
              `${wasteCount} waste resource${wasteCount > 1 ? 's' : ''}`}
            {' — review the Insights section below to act on these opportunities.'}
          </p>
        </div>
        <div className="flex-shrink-0">
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[11px] font-semibold bg-[#DCFCE7] text-[#158045] border border-[#A7F3D0]">
            <Icon icon="mdi:arrow-down" width={11} />
            Optimize Now
          </span>
        </div>
      </div>
    </div>
  );
};

export default AzureFinOpsSavingsCallout;
