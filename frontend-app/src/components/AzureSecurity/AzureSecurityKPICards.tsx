import React from 'react';
import type { AzureSecurityData } from '../../types/azureSecurity';

interface AzureSecurityKPICardsProps {
  securityData: AzureSecurityData | null;
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

const AzureSecurityKPICards: React.FC<AzureSecurityKPICardsProps> = ({ securityData, isLoading }) => {
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

  const score = securityData?.secureScore ?? 0;
  const maxScore = securityData?.maxScore ?? 100;
  const assessments = securityData?.assessments ?? [];
  const alerts = securityData?.alerts ?? [];

  const highSeverity = assessments.filter(a => a.severity === 'High').length
    + alerts.filter(a => a.severity === 'High').length;
  const activeAlerts = alerts.filter(a => a.status === 'Active').length;
  const totalFindings = assessments.length;

  const scorePercent = maxScore > 0 ? Math.round((score / maxScore) * 100) : 0;
  const scoreColor = scorePercent >= 70 ? 'from-green-600 to-green-800'
    : scorePercent >= 40 ? 'from-amber-600 to-amber-800'
    : 'from-red-600 to-red-800';

  return (
    <div className="grid grid-cols-1 lg:grid-cols-4 gap-4">
      {/* Secure Score */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-blue-700">
            Secure Score
          </span>
          <div className="w-9 h-9 rounded-lg bg-blue-100 flex items-center justify-center">
            <i className="fas fa-star text-blue-600 text-sm"></i>
          </div>
        </div>
        <div className={`text-3xl font-black bg-gradient-to-r ${scoreColor} bg-clip-text text-transparent`}>
          {score > 0 ? score.toFixed(1) : '—'}
        </div>
        <p className="text-xs text-gray-500 mt-1">Out of {maxScore} ({scorePercent}%)</p>
      </div>

      {/* High Severity */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-red-700">
            High Severity
          </span>
          <div className="w-9 h-9 rounded-lg bg-red-100 flex items-center justify-center">
            <i className="fas fa-exclamation-triangle text-red-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-red-600 to-red-800 bg-clip-text text-transparent">
          {highSeverity}
        </div>
        <p className="text-xs text-gray-500 mt-1">Critical findings requiring action</p>
      </div>

      {/* Active Alerts */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-orange-700">
            Active Alerts
          </span>
          <div className="w-9 h-9 rounded-lg bg-orange-100 flex items-center justify-center">
            <i className="fas fa-bell text-orange-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-orange-600 to-orange-800 bg-clip-text text-transparent">
          {activeAlerts}
        </div>
        <p className="text-xs text-gray-500 mt-1">Unresolved security alerts</p>
      </div>

      {/* Total Findings */}
      <div className="glass-card rounded-xl p-5 shadow-sm hover:shadow-md transition-shadow">
        <div className="flex items-center justify-between mb-3">
          <span className="text-xs font-bold uppercase tracking-wider text-purple-700">
            Total Findings
          </span>
          <div className="w-9 h-9 rounded-lg bg-purple-100 flex items-center justify-center">
            <i className="fas fa-clipboard-list text-purple-600 text-sm"></i>
          </div>
        </div>
        <div className="text-3xl font-black bg-gradient-to-r from-purple-600 to-purple-800 bg-clip-text text-transparent">
          {totalFindings}
        </div>
        <p className="text-xs text-gray-500 mt-1">Unhealthy security assessments</p>
      </div>
    </div>
  );
};

export default AzureSecurityKPICards;
