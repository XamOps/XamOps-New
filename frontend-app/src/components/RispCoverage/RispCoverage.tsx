import React, { useEffect } from 'react';
import { useRispCoverage } from '../../hooks/useRispCoverage';
import RispCoverageKPIs from './RispCoverageKPIs';
import RispCoverageTable from './RispCoverageTable';
import '../../styles/rispCoverage.css';

interface RispCoverageProps {
  accountId: string | null;
}

const RispCoverage: React.FC<RispCoverageProps> = ({ accountId }) => {
  const { loading, error, data, fetchRispCoverage } = useRispCoverage(accountId);

  // Load data on mount and when account changes
  useEffect(() => {
    if (accountId) {
      fetchRispCoverage();
    }
  }, [accountId, fetchRispCoverage]);

  const handleRefresh = () => {
    fetchRispCoverage();
  };

  if (!accountId) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-center">
          <i className="fas fa-exclamation-circle text-5xl text-red-500 mb-4"></i>
          <p className="text-gray-600 font-semibold">No account selected</p>
          <p className="text-sm text-gray-500 mt-2">Please select an AWS account to view RI/SP coverage</p>
        </div>
      </div>
    );
  }

  return (
    <div className="risp-coverage-container">
      {/* Header with gradient background */}
      <div className="bg-gradient-to-r from-blue-600 to-indigo-700 rounded-2xl shadow-xl p-8 mb-6 text-white">
        <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div>
            <h2 className="text-3xl font-bold mb-2 flex items-center gap-3">
              <i className="fas fa-chart-pie"></i>
              RI/SP Coverage & Utilization
            </h2>
            <p className="text-blue-100 text-sm">
              Monitor Reserved Instance and Savings Plan coverage to optimize Spot/On-Demand provisioning
            </p>
          </div>

          <div className="flex items-center gap-3">
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
              <p className="text-red-800 font-semibold">Failed to load RI/SP coverage data</p>
              <p className="text-red-600 text-sm mt-1">{error}</p>
            </div>
          </div>
        </div>
      )}

      {/* Loading State */}
      {loading && (
        <div className="bg-white rounded-2xl shadow-sm border border-gray-200 p-16 text-center mb-6">
          <i className="fas fa-spinner fa-spin text-6xl text-blue-600 mb-4"></i>
          <p className="text-gray-700 font-semibold text-xl mb-2">Loading coverage data...</p>
          <p className="text-gray-500 text-sm">Analyzing Reserved Instance and Savings Plan utilization</p>
        </div>
      )}

      {/* Main Content */}
      {!loading && (
        <div className="space-y-6">
          {/* KPI Cards */}
          <RispCoverageKPIs data={data} accountId={accountId} />

          {/* Coverage Tables */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <RispCoverageTable
              title="RI Coverage"
              type="ri"
              data={data.riCoverages}
            />
            <RispCoverageTable
              title="SP Coverage"
              type="sp"
              data={data.spCoverages}
            />
          </div>

          {/* Utilization Placeholders */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="bg-gradient-to-br from-blue-50 to-indigo-50 rounded-2xl shadow-sm border border-blue-100 p-6 text-center py-12">
              <div className="flex flex-col items-center">
                <div className="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mb-4">
                  <i className="fas fa-chart-area text-blue-600 text-2xl"></i>
                </div>
                <h4 className="text-sm font-bold text-gray-700 uppercase mb-2">RI Utilization</h4>
                <p className="text-gray-500 italic text-sm">No active Reserved Instances</p>
                <p className="text-xs text-gray-400 mt-2">Utilization charts will appear when RIs are available</p>
              </div>
            </div>
            <div className="bg-gradient-to-br from-purple-50 to-indigo-50 rounded-2xl shadow-sm border border-purple-100 p-6 text-center py-12">
              <div className="flex flex-col items-center">
                <div className="w-16 h-16 bg-purple-100 rounded-full flex items-center justify-center mb-4">
                  <i className="fas fa-chart-line text-purple-600 text-2xl"></i>
                </div>
                <h4 className="text-sm font-bold text-gray-700 uppercase mb-2">SP Utilization</h4>
                <p className="text-gray-500 italic text-sm">No active Savings Plans</p>
                <p className="text-xs text-gray-400 mt-2">Utilization charts will appear when Savings Plans are available</p>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default RispCoverage;
