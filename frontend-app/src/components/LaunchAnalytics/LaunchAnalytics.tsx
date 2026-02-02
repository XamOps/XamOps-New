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
      {/* Header with gradient background */}
      <div className="bg-gradient-to-r from-blue-600 via-indigo-600 to-purple-700 rounded-2xl shadow-xl p-8 mb-6 text-white">
        <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-6">
          <div>
            <h2 className="text-3xl font-bold mb-2 flex items-center gap-3">
              <i className="fas fa-chart-line"></i>
              Launch Analytics
            </h2>
            <p className="text-blue-100 text-sm">Spot instance launch success metrics and recommendations</p>
          </div>

          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label className="block text-xs font-semibold text-blue-100 mb-2 uppercase tracking-wide">
                From
              </label>
              <input
                type="date"
                value={filters.startDate}
                onChange={(e) => handleDateChange('startDate', e.target.value)}
                max={filters.endDate}
                className="px-4 py-2.5 bg-white/10 backdrop-blur-sm border border-white/20 rounded-lg text-white placeholder-blue-200 focus:ring-2 focus:ring-white/50 focus:border-transparent text-sm font-medium transition-all hover:bg-white/15"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-blue-100 mb-2 uppercase tracking-wide">
                To
              </label>
              <input
                type="date"
                value={filters.endDate}
                onChange={(e) => handleDateChange('endDate', e.target.value)}
                min={filters.startDate}
                max={new Date().toISOString().split('T')[0]}
                className="px-4 py-2.5 bg-white/10 backdrop-blur-sm border border-white/20 rounded-lg text-white placeholder-blue-200 focus:ring-2 focus:ring-white/50 focus:border-transparent text-sm font-medium transition-all hover:bg-white/15"
              />
            </div>

            <button
              onClick={handleRefresh}
              disabled={loading}
              className="px-6 py-2.5 bg-white text-blue-700 rounded-lg hover:bg-blue-50 disabled:bg-white/50 disabled:cursor-not-allowed transition-all font-semibold text-sm flex items-center shadow-lg hover:shadow-xl transform hover:-translate-y-0.5 active:translate-y-0"
            >
              <i className={`fas mr-2 ${loading ? 'fa-spinner fa-spin' : 'fa-sync-alt'}`}></i>
              <span>{loading ? 'Loading...' : 'Refresh'}</span>
            </button>
          </div>
        </div>
      </div>

      {/* Error State */}
      {error && (
        <div className="bg-red-50 border-l-4 border-red-500 p-4 mb-6 rounded-lg">
          <div className="flex items-center">
            <i className="fas fa-exclamation-triangle text-red-500 text-xl mr-3"></i>
            <div>
              <p className="text-red-800 font-semibold">Failed to load analytics</p>
              <p className="text-red-600 text-sm mt-1">{error}</p>
            </div>
          </div>
        </div>
      )}

      {/* Loading State */}
      {loading && (
        <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-16 text-center mb-6">
          <i className="fas fa-spinner fa-spin text-6xl text-blue-600 mb-4"></i>
          <p className="text-gray-700 font-semibold text-xl mb-2">Loading analytics data...</p>
          <p className="text-gray-500 text-sm">Analyzing spot instance launch patterns</p>
        </div>
      )}

      {/* Main Content */}
      {!loading && (
        <>
          {/* KPI Cards */}
          <LaunchAnalyticsKPIs data={data} />

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
    </div>
  );
};

export default LaunchAnalytics;
