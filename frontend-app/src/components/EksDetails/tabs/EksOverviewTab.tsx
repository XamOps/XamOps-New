/**
 * EKS Overview Tab
 *
 * Main dashboard view with cluster health score and key insights
 */

import React from 'react';
import { QuickWin } from '../../../types/eksTypes';

interface EksOverviewTabProps {
  eksData: any;
}

const EksOverviewTab: React.FC<EksOverviewTabProps> = ({ eksData }) => {
  const {
    dashboard,
    clusterHealthScore,
    healthScoreLabel,
    healthScoreColor,
    resourceScore,
    costScore,
    securityScore,
    quickWins,
    potentialSavings
  } = eksData;

  if (!dashboard) return null;

  return (
    <div className="space-y-6">
      {/* Health Score Section */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center gap-3 mb-8">
          <div className="w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
            <i className="fas fa-heartbeat text-slate-700 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">
            Cluster Health Score
          </h3>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
          {/* Overall Health Score */}
          <div
            className={`lg:col-span-1 flex flex-col items-center justify-center p-6 bg-gray-50 rounded-xl border-2 ${clusterHealthScore >= 80
              ? 'border-emerald-200'
              : clusterHealthScore >= 60
                ? 'border-blue-200'
                : clusterHealthScore >= 40
                  ? 'border-amber-200'
                  : 'border-red-200'
              }`}
          >
            <div className="relative w-32 h-32">
              <svg className="transform -rotate-90" viewBox="0 0 120 120">
                <circle cx="60" cy="60" r="54" fill="none" stroke="#e5e7eb" strokeWidth="10"></circle>
                <circle
                  cx="60"
                  cy="60"
                  r="54"
                  fill="none"
                  stroke={healthScoreColor}
                  strokeWidth="10"
                  strokeLinecap="round"
                  strokeDasharray={`${(clusterHealthScore / 100) * 339.3} 339.3`}
                  className="transition-all duration-1000"
                ></circle>
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <div className="text-3xl font-bold" style={{ color: healthScoreColor }}>
                  {clusterHealthScore}
                </div>
                <div className="text-xs text-gray-500 font-medium">/ 100</div>
              </div>
            </div>
            <div className="text-center mt-4">
              <div className="text-base font-semibold text-gray-800">{healthScoreLabel}</div>
              <div className="text-xs text-gray-500 mt-1">Overall cluster status</div>
            </div>
          </div>

          {/* Score Breakdown */}
          <div className="lg:col-span-3 grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Resource Score */}
            <div className="bg-white p-6 rounded-xl border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all">
              <div className="flex items-center justify-between mb-4">
                <div className="w-10 h-10 bg-blue-50 rounded-lg flex items-center justify-center">
                  <i className="fas fa-server text-blue-600 text-lg"></i>
                </div>
                <div className="text-2xl font-bold text-gray-900">{resourceScore}</div>
              </div>
              <h4 className="text-sm font-semibold text-gray-800 mb-2">Resource Efficiency</h4>
              <p className="text-xs text-gray-600 leading-relaxed">
                CPU and memory utilization in optimal range
              </p>
              <div className="mt-3 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded-full transition-all duration-1000"
                  style={{ width: `${resourceScore}%` }}
                ></div>
              </div>
            </div>

            {/* Cost Score */}
            <div className="bg-white p-6 rounded-xl border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all">
              <div className="flex items-center justify-between mb-4">
                <div className="w-10 h-10 bg-emerald-50 rounded-lg flex items-center justify-center">
                  <i className="fas fa-dollar-sign text-emerald-600 text-lg"></i>
                </div>
                <div className="text-2xl font-bold text-gray-900">{costScore}</div>
              </div>
              <h4 className="text-sm font-semibold text-gray-800 mb-2">Cost Optimization</h4>
              <p className="text-xs text-gray-600 leading-relaxed">
                Resource efficiency and cost effectiveness
              </p>
              <div className="mt-3 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-emerald-500 rounded-full transition-all duration-1000"
                  style={{ width: `${costScore}%` }}
                ></div>
              </div>
            </div>

            {/* Security Score */}
            <div className="bg-white p-6 rounded-xl border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all">
              <div className="flex items-center justify-between mb-4">
                <div className="w-10 h-10 bg-red-50 rounded-lg flex items-center justify-center">
                  <i className="fas fa-shield-alt text-red-600 text-lg"></i>
                </div>
                <div className="text-2xl font-bold text-gray-900">{securityScore}</div>
              </div>
              <h4 className="text-sm font-semibold text-gray-800 mb-2">Security Posture</h4>
              <p className="text-xs text-gray-600 leading-relaxed">
                Vulnerability and threat detection status
              </p>
              <div className="mt-3 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-red-500 rounded-full transition-all duration-1000"
                  style={{ width: `${securityScore}%` }}
                ></div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Key Metrics */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
            <i className="fas fa-chart-bar text-slate-700 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">
            Key Metrics
          </h3>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {/* CPU Utilization */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-3">
              <i className="fas fa-microchip text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">CPU Usage</span>
            </div>
            <div className="text-2xl font-bold text-gray-900 mb-2">
              {(dashboard.clusterOverview?.cpuUsagePercent || 0).toFixed(1)}%
            </div>
            <div className="w-full h-1.5 bg-gray-200 rounded-full overflow-hidden">
              <div
                className="h-full bg-slate-600 rounded-full transition-all duration-1000"
                style={{ width: `${dashboard.clusterOverview?.cpuUsagePercent || 0}%` }}
              ></div>
            </div>
          </div>

          {/* Memory Utilization */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-3">
              <i className="fas fa-memory text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Memory Usage</span>
            </div>
            <div className="text-2xl font-bold text-gray-900 mb-2">
              {(dashboard.clusterOverview?.memoryUsagePercent || 0).toFixed(1)}%
            </div>
            <div className="w-full h-1.5 bg-gray-200 rounded-full overflow-hidden">
              <div
                className="h-full bg-slate-600 rounded-full transition-all duration-1000"
                style={{ width: `${dashboard.clusterOverview?.memoryUsagePercent || 0}%` }}
              ></div>
            </div>
          </div>

          {/* Cost Efficiency */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-3">
              <i className="fas fa-chart-line text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Efficiency</span>
            </div>
            <div className="text-2xl font-bold text-gray-900 mb-2">{costScore}%</div>
            <div className="w-full h-1.5 bg-gray-200 rounded-full overflow-hidden">
              <div
                className="h-full bg-slate-600 rounded-full transition-all duration-1000"
                style={{ width: `${costScore}%` }}
              ></div>
            </div>
          </div>

          {/* Security Events */}
          <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div className="flex items-center gap-2 mb-3">
              <i className="fas fa-shield-alt text-slate-600 text-sm"></i>
              <span className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Security</span>
            </div>
            <div className="text-2xl font-bold text-gray-900 mb-2">
              {dashboard?.securityMetrics?.totalEvents || 0}
            </div>
            <div className="text-xs text-gray-600">
              {dashboard?.securityMetrics?.eventsByPriority?.['4'] || dashboard?.securityMetrics?.eventsByPriority?.['CRITICAL'] || 0} critical events
            </div>
          </div>
        </div>
      </section>

      {/* Quick Wins Section */}
      {quickWins && quickWins.length > 0 && (
        <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
          <div className="flex items-center gap-3 mb-6">
            <div className="w-10 h-10 rounded-lg bg-amber-100 flex items-center justify-center">
              <i className="fas fa-lightbulb text-amber-600 text-lg"></i>
            </div>
            <div>
              <h3 className="text-xl font-bold text-gray-900">
                Recommendations
              </h3>
              <p className="text-sm text-gray-600">Actions for immediate impact</p>
            </div>
          </div>

          <div className="space-y-3">
            {quickWins.map((win: QuickWin, index: number) => (
              <div
                key={index}
                className="bg-gray-50 rounded-lg p-5 border border-gray-200 hover:border-gray-300 hover:shadow-sm transition-all"
              >
                <div className="flex items-start gap-4">
                  <div
                    className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${win.type === 'security'
                      ? 'bg-red-100 text-red-600'
                      : win.type === 'cost'
                        ? 'bg-amber-100 text-amber-600'
                        : 'bg-blue-100 text-blue-600'
                      }`}
                  >
                    <i className={`fas ${win.icon} text-lg`}></i>
                  </div>
                  <div className="flex-1 min-w-0">
                    <h4 className="text-base font-semibold text-gray-900 mb-1">{win.title}</h4>
                    <p className="text-sm text-gray-600 mb-3">{win.impact}</p>
                    <div className="flex gap-2 flex-wrap">
                      {win.actions.map((action: string, i: number) => (
                        <button
                          key={i}
                          className={`px-3 py-1.5 rounded-md text-xs font-semibold transition-all ${i === 0
                            ? 'bg-slate-700 text-white hover:bg-slate-800'
                            : 'bg-white text-gray-700 hover:bg-gray-100 border border-gray-300'
                            }`}
                        >
                          {action}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* Cost Savings Potential */}
      {potentialSavings.affectedNodes > 0 && (
        <section className="bg-amber-50 rounded-xl shadow-sm p-8 border border-amber-200">
          <div className="flex items-center gap-3 mb-6">
            <div className="w-10 h-10 rounded-lg bg-amber-100 flex items-center justify-center">
              <i className="fas fa-piggy-bank text-amber-600 text-lg"></i>
            </div>
            <h3 className="text-xl font-bold text-gray-900">
              Savings Opportunity
            </h3>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="bg-white rounded-lg p-5 border border-amber-100">
              <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-2">Daily Savings</div>
              <div className="text-2xl font-bold text-gray-900 mb-1">
                ${potentialSavings.daily.toFixed(2)}
              </div>
              <div className="text-xs text-gray-500">Potential per day</div>
            </div>

            <div className="bg-white rounded-lg p-5 border border-amber-100">
              <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-2">Monthly Savings</div>
              <div className="text-2xl font-bold text-gray-900 mb-1">
                ${potentialSavings.monthly.toFixed(2)}
              </div>
              <div className="text-xs text-gray-500">Potential per month</div>
            </div>

            <div className="bg-white rounded-lg p-5 border border-amber-100">
              <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide mb-2">Affected Nodes</div>
              <div className="text-2xl font-bold text-gray-900 mb-1">
                {potentialSavings.affectedNodes}
              </div>
              <div className="text-xs text-gray-500">Underutilized nodes</div>
            </div>
          </div>
        </section>
      )}

      {/* Cluster Overview Stats */}
      <section className="bg-white rounded-xl shadow-sm p-8 border border-gray-200">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
            <i className="fas fa-chart-bar text-slate-700 text-lg"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900">
            Cluster Resources
          </h3>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="bg-gray-50 rounded-lg p-5 border border-gray-200">
            <div className="text-2xl font-bold text-gray-900 mb-1">
              {dashboard.clusterOverview?.totalNodes || 0}
            </div>
            <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Total Nodes</div>
          </div>

          <div className="bg-gray-50 rounded-lg p-5 border border-gray-200">
            <div className="text-2xl font-bold text-gray-900 mb-1">
              {dashboard.clusterOverview?.totalNamespaces || 0}
            </div>
            <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Namespaces</div>
          </div>

          <div className="bg-gray-50 rounded-lg p-5 border border-gray-200">
            <div className="text-2xl font-bold text-gray-900 mb-1">
              {(dashboard.clusterOverview?.cpuUsagePercent || 0).toFixed(1)}%
            </div>
            <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide">CPU Usage</div>
          </div>

          <div className="bg-gray-50 rounded-lg p-5 border border-gray-200">
            <div className="text-2xl font-bold text-gray-900 mb-1">
              {(dashboard.clusterOverview?.memoryUsagePercent || 0).toFixed(1)}%
            </div>
            <div className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Memory Usage</div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default EksOverviewTab;
