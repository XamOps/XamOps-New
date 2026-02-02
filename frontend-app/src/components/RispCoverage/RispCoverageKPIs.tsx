import React from 'react';
import { RispCoverageData } from '../../hooks/useRispCoverage';

interface RispCoverageKPIsProps {
  data: RispCoverageData;
  accountId: string | null;
}

const RispCoverageKPIs: React.FC<RispCoverageKPIsProps> = ({ data, accountId }) => {
  const formatTimeAgo = (dateString: string | null): string => {
    if (!dateString) return 'Never';

    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffHours = Math.floor(diffMs / (1000 * 60 * 60));

    if (diffHours < 1) return 'Just now';
    if (diffHours === 1) return '1 hour ago';
    if (diffHours < 24) return `${diffHours} hours ago`;

    const diffDays = Math.floor(diffHours / 24);
    if (diffDays === 1) return '1 day ago';
    return `${diffDays} days ago`;
  };

  const commitmentWaste = 100 - data.totalRiUtilizationPercent;
  const shouldUseSpot = data.totalRiUtilizationPercent >= 90 && data.totalSpUtilizationPercent >= 90;

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-6">
      {/* Last Checked */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-gray-100 hover:shadow-md transition-shadow">
        <div className="text-center">
          <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">Last Checked</p>
          <h3 className="text-xl font-bold text-gray-900">{formatTimeAgo(data.collectedAt)}</h3>
          <p className="text-xs text-gray-400 mt-1">Account: {accountId || 'N/A'}</p>
        </div>
      </div>

      {/* RI Coverage */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-gray-100 hover:shadow-md transition-shadow">
        <div className="text-center">
          <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">RI Coverage</p>
          <h3 className={`text-3xl font-bold ${data.totalRiUtilizationPercent >= 70 ? 'text-emerald-600' : 'text-red-500'}`}>
            {data.totalRiUtilizationPercent.toFixed(1)}%
          </h3>
          <p className="text-xs text-gray-400 mt-1">Utilization: 0.0%</p>
        </div>
      </div>

      {/* SP Coverage */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-gray-100 hover:shadow-md transition-shadow">
        <div className="text-center">
          <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">SP Coverage</p>
          <h3 className={`text-3xl font-bold ${data.totalSpUtilizationPercent >= 70 ? 'text-emerald-600' : 'text-red-500'}`}>
            {data.totalSpUtilizationPercent.toFixed(1)}%
          </h3>
          <p className="text-xs text-gray-400 mt-1">Utilization: 0.0%</p>
        </div>
      </div>

      {/* Commitment Waste */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-gray-100 relative overflow-hidden hover:shadow-md transition-shadow">
        <div className="absolute top-0 right-0 p-2 opacity-10">
          <i className="fas fa-trash-alt text-6xl text-gray-500"></i>
        </div>
        <div className="text-center relative z-10">
          <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">Commitment Waste</p>
          <h3 className={`text-3xl font-bold ${commitmentWaste > 10 ? 'text-red-500' : 'text-gray-700'}`}>
            {commitmentWaste.toFixed(1)}%
          </h3>
          <p className="text-xs text-gray-400 mt-1">Unused RIs</p>

          <div className="w-full bg-gray-100 rounded-full h-1.5 mt-3">
            <div
              className={`h-1.5 rounded-full transition-all duration-500 ${commitmentWaste > 10 ? 'bg-red-500' : 'bg-emerald-500'}`}
              style={{ width: `${Math.min(commitmentWaste, 100)}%` }}
            ></div>
          </div>
        </div>
      </div>

      {/* AutoSpotting Strategy */}
      <div className="bg-white rounded-2xl shadow-sm p-6 border border-gray-100 hover:shadow-md transition-shadow">
        <div className="text-center">
          <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">AutoSpotting Strategy</p>
          <h3 className={`text-2xl font-bold ${shouldUseSpot ? 'text-emerald-500' : 'text-orange-500'}`}>
            {shouldUseSpot ? 'Use Spot' : 'Use On-Demand'}
          </h3>
          <p className="text-[10px] text-gray-400 mt-2 leading-tight">
            {shouldUseSpot
              ? 'All commitments fully utilized - will use Spot for maximum savings'
              : 'Commitments available - will use On-Demand to maximize RI/SP utilization'}
          </p>
        </div>
      </div>
    </div>
  );
};

export default RispCoverageKPIs;
