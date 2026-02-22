import React from 'react';
import type { AzurePerformanceData } from '../../types/azurePerformance';

interface AzurePerformanceKPICardsProps {
  performanceData: AzurePerformanceData | null;
  isLoading: boolean;
}

const SkeletonCard: React.FC = () => (
  <div className="glass-card rounded-xl p-5 shadow-sm animate-pulse">
    <div className="flex items-center justify-between mb-3">
      <div className="h-3 bg-gray-200 rounded w-24"></div>
      <div className="w-9 h-9 rounded-lg bg-gray-200"></div>
    </div>
    <div className="h-8 bg-gray-200 rounded w-16 mb-1"></div>
    <div className="h-3 bg-gray-100 rounded w-28"></div>
  </div>
);

const AzurePerformanceKPICards: React.FC<AzurePerformanceKPICardsProps> = ({ performanceData, isLoading }) => {
  if (isLoading) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
        <SkeletonCard />
      </div>
    );
  }

  const score = performanceData?.performanceScore ?? 100;
  const insights = performanceData?.insights ?? [];

  const highCount = insights.filter(i => i.severity === 'High').length;
  const mediumCount = insights.filter(i => i.severity === 'Medium').length;
  const totalCount = insights.length;

  const scoreColor = score >= 70
    ? 'from-green-600 to-green-800'
    : score >= 40
    ? 'from-amber-600 to-amber-800'
    : 'from-red-600 to-red-800';

  return (
    <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
      {/* Performance Score */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-indigo-700">
            Performance Score
          </span>
          <div className="w-9 h-9 rounded-lg bg-indigo-100 flex items-center justify-center">
            <i className="fas fa-tachometer-alt text-indigo-600 text-sm"></i>
          </div>
        </div>
        <div className={`text-3xl font-black bg-gradient-to-r ${scoreColor} bg-clip-text text-transparent`}>
          {score}
        </div>
        <p className="text-xs text-gray-500 mt-1">Out of 100</p>
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
          {highCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">High priority recommendations</p>
      </div>

      {/* Medium Impact */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-amber-700">
            Medium Impact
          </span>
          <div className="w-9 h-9 rounded-lg bg-amber-100 flex items-center justify-center">
            <i className="fas fa-minus-circle text-amber-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-amber-600 to-amber-800 bg-clip-text text-transparent">
          {mediumCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">Medium priority recommendations</p>
      </div>

      {/* Total Insights */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-purple-700">
            Total Insights
          </span>
          <div className="w-9 h-9 rounded-lg bg-purple-100 flex items-center justify-center">
            <i className="fas fa-chart-line text-purple-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-purple-600 to-purple-800 bg-clip-text text-transparent">
          {totalCount}
        </div>
        <p className="text-xs text-gray-500 mt-1">Advisor performance recommendations</p>
      </div>
    </div>
  );
};

export default AzurePerformanceKPICards;
