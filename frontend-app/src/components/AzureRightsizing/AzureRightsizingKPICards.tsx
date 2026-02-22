import React from 'react';
import type { AzureRightsizingRecommendation } from '../../types/azureRightsizing';

interface AzureRightsizingKPICardsProps {
  recommendations: AzureRightsizingRecommendation[];
}

const formatINR = (value: number) =>
  '₹' + value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function topResourceType(recs: AzureRightsizingRecommendation[]): string {
  if (recs.length === 0) return '—';
  const counts: Record<string, number> = {};
  for (const r of recs) {
    const t = r.resourceType || 'Other';
    counts[t] = (counts[t] || 0) + 1;
  }
  return Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '—';
}

const AzureRightsizingKPICards: React.FC<AzureRightsizingKPICardsProps> = ({ recommendations }) => {
  const totalRecs = recommendations.length;
  const potentialSavings = recommendations.reduce((sum, r) => sum + (r.monthlySavings ?? 0), 0);
  const highImpact = recommendations.filter(r => r.impact === 'High').length;
  const topType = topResourceType(recommendations);

  return (
    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
      {/* Total Recommendations */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-blue-700">
            Total Recommendations
          </span>
          <div className="w-9 h-9 rounded-lg bg-blue-100 flex items-center justify-center">
            <i className="fas fa-lightbulb text-blue-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-blue-600 to-blue-800 bg-clip-text text-transparent">
          {totalRecs}
        </div>
        <p className="text-xs text-gray-500 mt-1">From Azure Advisor</p>
      </div>

      {/* Potential Monthly Savings */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-green-700">
            Potential Monthly Savings
          </span>
          <div className="w-9 h-9 rounded-lg bg-green-100 flex items-center justify-center">
            <i className="fas fa-rupee-sign text-green-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-green-600 to-green-800 bg-clip-text text-transparent">
          {totalRecs > 0 ? formatINR(potentialSavings) : '₹0.00'}
        </div>
        <p className="text-xs text-gray-500 mt-1">If all recommendations applied</p>
      </div>

      {/* High Impact */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-red-700">
            High Impact
          </span>
          <div className="w-9 h-9 rounded-lg bg-red-100 flex items-center justify-center">
            <i className="fas fa-exclamation-triangle text-red-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-red-600 to-red-800 bg-clip-text text-transparent">
          {highImpact}
        </div>
        <p className="text-xs text-gray-500 mt-1">Critical optimizations</p>
      </div>

      {/* Top Resource Type */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-purple-700">
            Top Resource Type
          </span>
          <div className="w-9 h-9 rounded-lg bg-purple-100 flex items-center justify-center">
            <i className="fas fa-server text-purple-600 text-sm"></i>
          </div>
        </div>
        <div className="text-xl font-black bg-gradient-to-r from-purple-600 to-purple-800 bg-clip-text text-transparent truncate">
          {topType}
        </div>
        <p className="text-xs text-gray-500 mt-1">Most affected resource</p>
      </div>
    </div>
  );
};

export default AzureRightsizingKPICards;
