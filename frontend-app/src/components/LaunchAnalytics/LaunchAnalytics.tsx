import React, { useState, useEffect } from 'react';
import { useLaunchAnalytics } from '../../hooks/useLaunchAnalytics';
import LaunchAnalyticsKPIs from './LaunchAnalyticsKPIs';
import SpotMarketHeatmap from './SpotMarketHeatmap';
import RegionSuccessChart from './RegionSuccessChart';
import FailureReasonsCard from './FailureReasonsCard';
import RecommendedTypesCard from './RecommendedTypesCard';
import '../../styles/launchAnalytics.css';

interface LaunchAnalyticsProps {
  accountId: string;
}

const LaunchAnalytics: React.FC<LaunchAnalyticsProps> = ({ accountId }) => {
  const { loading, error, data, fetchAnalytics } = useLaunchAnalytics(accountId);

  // Initialize dates: default to last 30 days
  const [filters, setFilters] = useState({
    startDate: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    endDate: new Date().toISOString().split('T')[0]
  });

  // Load analytics on mount and when account changes
  useEffect(() => {
    if (accountId) {
      fetchAnalytics(filters.startDate, filters.endDate);
    }
  }, [accountId]); // Only fetch on accountId change

  const handleRefresh = () => {
    fetchAnalytics(filters.startDate, filters.endDate);
  };

  const handleDateChange = (field: 'startDate' | 'endDate', value: string) => {
    setFilters(prev => ({ ...prev, [field]: value }));
  };

  if (!accountId) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-center">
          <i className="fas fa-exclamation-circle text-5xl text-red-500 mb-4"></i>
          <p className="text-gray-600 font-semibold">No account selected</p>
          <p className="text-sm text-gray-500 mt-2">Please select an AWS account to view analytics</p>
        </div>
      </div>
    );
  }

  return (
    <div className="launch-analytics-container">
      {/* Compact filter bar */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 px-4 py-3 mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <i className="fas fa-chart-line text-blue-600 text-sm"></i>
          <span className="font-bold text-gray-800 text-sm">Launch Analytics</span>
          <span className="text-gray-400 text-xs hidden sm:inline">— Spot instance launch metrics</span>
        </div>
        <div className="flex items-center gap-2">
          <label className="text-xs font-semibold text-gray-500 uppercase tracking-wide">From</label>
          <input
            type="date"
            value={filters.startDate}
            onChange={(e) => handleDateChange('startDate', e.target.value)}
            max={filters.endDate}
            className="px-2.5 py-1.5 border border-gray-200 rounded-lg text-xs text-gray-700 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <label className="text-xs font-semibold text-gray-500 uppercase tracking-wide">To</label>
          <input
            type="date"
            value={filters.endDate}
            onChange={(e) => handleDateChange('endDate', e.target.value)}
            min={filters.startDate}
            max={new Date().toISOString().split('T')[0]}
            className="px-2.5 py-1.5 border border-gray-200 rounded-lg text-xs text-gray-700 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <button
            onClick={handleRefresh}
            disabled={loading}
            className="px-4 py-1.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-semibold text-xs flex items-center gap-1.5 shadow-sm"
          >
            <i className={`fas ${loading ? 'fa-spinner fa-spin' : 'fa-sync-alt'}`}></i>
            {loading ? 'Loading...' : 'Refresh'}
          </button>
        </div>
      </div>

      {/* Error State */}
      {error && (
        <div className="bg-red-50 border-l-4 border-red-500 p-3 mb-4 rounded-lg flex items-center gap-3">
          <i className="fas fa-exclamation-triangle text-red-500"></i>
          <div>
            <p className="text-red-800 font-semibold text-sm">Failed to load analytics</p>
            <p className="text-red-600 text-xs mt-0.5">{error}</p>
          </div>
        </div>
      )}

      {/* Sticky KPI Cards */}
      <div className="sticky top-0 z-20 bg-gray-50 pb-3 pt-0">
        <LaunchAnalyticsKPIs data={data} />
      </div>

      {/* Main Content */}
      {!loading && (
        <>
          {/* Two Column Layout - Heatmap and Region Chart */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
            <SpotMarketHeatmap data={data.byInstanceType} />
            <RegionSuccessChart data={data.byRegion} />
          </div>

          {/* Two Column Layout - Failure Reasons and Recommendations */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <FailureReasonsCard data={data.failureReasons} />
            <RecommendedTypesCard data={data.recommendations} />
          </div>
        </>
      )}

      {/* Loading skeleton for charts */}
      {loading && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="bg-white rounded-xl shadow-sm border border-gray-100 p-6 animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-1/3 mb-4"></div>
              <div className="space-y-3">
                {[1,2,3].map(j => <div key={j} className="h-3 bg-gray-200 rounded w-full"></div>)}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default LaunchAnalytics;
