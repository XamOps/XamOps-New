import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useRispCoverage } from '../../hooks/useRispCoverage';
import RispCoverageKPIs from './RispCoverageKPIs';
import RispCoverageTable from './RispCoverageTable';
import RispSettings from './RispSettings';
import { useTopbar } from '../../contexts/TopbarContext';
import '../../styles/rispCoverage.css';

interface RispCoverageProps {
  accountId: string | null;
  /** The XamOps account id: the shell's Commitments view reads and saves through the v2 API. */
  v2AccountId?: number | null;
}

const RispCoverage: React.FC<RispCoverageProps> = ({ accountId, v2AccountId }) => {
  const hasAccount = !!accountId || v2AccountId != null;
  const { loading, error, data, fetchRispCoverage } = useRispCoverage(accountId, v2AccountId);
  const { setActions } = useTopbar();

  // Load data on mount and when account changes
  useEffect(() => {
    if (hasAccount) {
      fetchRispCoverage();
    }
  }, [hasAccount, fetchRispCoverage]);

  const handleRefresh = () => {
    fetchRispCoverage();
  };

  // Add refresh button to topbar
  useEffect(() => {
    setActions(
      <button
        onClick={handleRefresh}
        disabled={loading}
        className="px-4 py-2 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white rounded-md disabled:opacity-50 disabled:cursor-not-allowed transition-all font-semibold text-sm flex items-center shadow-md"
      >
        <Icon icon={loading ? 'mdi:loading' : 'mdi:refresh'} className={`mr-2 ${loading ? 'animate-spin' : ''}`} width={16} />
        <span>{loading ? 'Refreshing...' : 'Refresh'}</span>
      </button>
    );

    // Cleanup: remove actions when component unmounts
    return () => setActions(null);
  }, [loading, setActions]);

  if (!hasAccount) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-center">
          <Icon icon="mdi:alert-circle-outline" className="text-5xl text-gray-700 mb-4" width={48} />
          <p className="text-[rgba(0,0,0,0.6)] font-semibold">No account selected</p>
          <p className="text-sm text-[rgba(0,0,0,0.5)] mt-2">Please select an AWS account to view RI/SP coverage</p>
        </div>
      </div>
    );
  }

  return (
    <div className="risp-coverage-container">
      {/* Error State */}
      {error && (
        <div className="bg-gray-100 border-l-4 border-gray-700 p-4 mb-6 rounded-lg">
          <div className="flex items-center">
            <Icon icon="mdi:alert-outline" className="text-gray-700 text-xl mr-3" width={24} />
            <div>
              <p className="text-gray-700 font-semibold">Failed to load RI/SP coverage data</p>
              <p className="text-gray-700 text-sm mt-1">{error}</p>
            </div>
          </div>
        </div>
      )}

      {/* Loading State */}
      {loading && (
        <div className="bg-white rounded-2xl shadow-sm border border-[rgba(0,0,0,0.12)] p-16 text-center mb-6">
          <Icon icon="mdi:loading" className="animate-spin text-6xl text-gray-700 mb-4" width={60} />
          <p className="text-[rgba(0,0,0,0.7)] font-semibold text-xl mb-2">Loading coverage data...</p>
          <p className="text-[rgba(0,0,0,0.5)] text-sm">Analyzing Reserved Instance and Savings Plan utilization</p>
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
            <div className="bg-gradient-to-br from-gray-50 to-gray-100 rounded-2xl shadow-sm border border-gray-300 p-6 text-center py-12">
              <div className="flex flex-col items-center">
                <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
                  <Icon icon="mdi:chart-areaspline" className="text-gray-700" width={24} />
                </div>
                <h4 className="text-sm font-bold text-[rgba(0,0,0,0.7)] uppercase mb-2">RI Utilization</h4>
                <p className="text-[rgba(0,0,0,0.5)] italic text-sm">No active Reserved Instances</p>
                <p className="text-xs text-[rgba(0,0,0,0.4)] mt-2">Utilization charts will appear when RIs are available</p>
              </div>
            </div>
            <div className="bg-gradient-to-br from-gray-50 to-gray-100 rounded-2xl shadow-sm border border-gray-300 p-6 text-center py-12">
              <div className="flex flex-col items-center">
                <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
                  <Icon icon="mdi:chart-line" className="text-gray-700" width={24} />
                </div>
                <h4 className="text-sm font-bold text-[rgba(0,0,0,0.7)] uppercase mb-2">SP Utilization</h4>
                <p className="text-[rgba(0,0,0,0.5)] italic text-sm">No active Savings Plans</p>
                <p className="text-xs text-[rgba(0,0,0,0.4)] mt-2">Utilization charts will appear when Savings Plans are available</p>
              </div>
            </div>
          </div>

          {/* RI/SP Respect Settings */}
          <RispSettings accountId={accountId} v2AccountId={v2AccountId} />
        </div>
      )}
    </div>
  );
};

export default RispCoverage;
