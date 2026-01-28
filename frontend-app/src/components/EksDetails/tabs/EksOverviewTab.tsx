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
    <div className="space-y-8">
      {/* Health Score Section */}
      <section className="bg-white rounded-2xl shadow-lg p-8 border border-gray-100">
        <h3 className="text-2xl font-bold text-gray-900 mb-6 flex items-center gap-3">
          <i className="fas fa-heartbeat text-red-500"></i>
          Cluster Health Score
        </h3>

        <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
          {/* Overall Health Score */}
          <div
            className={`lg:col-span-1 flex flex-col items-center justify-center p-6 bg-gradient-to-br from-gray-50 to-white rounded-xl border-2 ${clusterHealthScore >= 80
              ? 'border-green-200'
              : clusterHealthScore >= 60
                ? 'border-blue-200'
                : clusterHealthScore >= 40
                  ? 'border-amber-200'
                  : 'border-red-200'
              }`}
          >
            <div className="relative w-36 h-36">
              <svg className="transform -rotate-90" viewBox="0 0 120 120">
                <circle cx="60" cy="60" r="54" fill="none" stroke="#e5e7eb" strokeWidth="12"></circle>
                <circle
                  cx="60"
                  cy="60"
                  r="54"
                  fill="none"
                  stroke={healthScoreColor}
                  strokeWidth="12"
                  strokeLinecap="round"
                  strokeDasharray={`${(clusterHealthScore / 100) * 339.3} 339.3`}
                  className="transition-all duration-1000"
                ></circle>
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <div className="text-4xl font-black" style={{ color: healthScoreColor }}>
                  {clusterHealthScore}
                </div>
                <div className="text-sm text-gray-500 font-medium">/ 100</div>
              </div>
            </div>
            <div className="text-center mt-4">
              <div className="text-lg font-bold text-gray-800">{healthScoreLabel}</div>
              <div className="text-xs text-gray-500 mt-1">Overall cluster wellness</div>
            </div>
          </div>

          {/* Score Breakdown */}
          <div className="lg:col-span-3 grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Resource Score */}
            <div className="bg-gradient-to-br from-blue-50 to-white p-6 rounded-xl border border-blue-100">
              <div className="flex items-center justify-between mb-4">
                <div className="w-12 h-12 bg-blue-100 rounded-lg flex items-center justify-center">
                  <i className="fas fa-server text-blue-600 text-xl"></i>
                </div>
                <div className="text-3xl font-black text-blue-600">{resourceScore}</div>
              </div>
              <h4 className="text-sm font-bold text-gray-800 mb-2">Resource Efficiency</h4>
              <p className="text-xs text-gray-600">
                CPU and memory utilization in optimal range (40-75%)
              </p>
              <div className="mt-3 h-2 bg-blue-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded-full transition-all duration-1000"
                  style={{ width: `${resourceScore}%` }}
                ></div>
              </div>
            </div>

            {/* Cost Score */}
            <div className="bg-gradient-to-br from-green-50 to-white p-6 rounded-xl border border-green-100">
              <div className="flex items-center justify-between mb-4">
                <div className="w-12 h-12 bg-green-100 rounded-lg flex items-center justify-center">
                  <i className="fas fa-dollar-sign text-green-600 text-xl"></i>
                </div>
                <div className="text-3xl font-black text-green-600">{costScore}</div>
              </div>
              <h4 className="text-sm font-bold text-gray-800 mb-2">Cost Optimization</h4>
              <p className="text-xs text-gray-600">
                Cluster resources are efficiently utilized with minimal waste
              </p>
              <div className="mt-3 h-2 bg-green-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-green-500 rounded-full transition-all duration-1000"
                  style={{ width: `${costScore}%` }}
                ></div>
              </div>
            </div>

            {/* Security Score */}
            <div className="bg-gradient-to-br from-red-50 to-white p-6 rounded-xl border border-red-100">
              <div className="flex items-center justify-between mb-4">
                <div className="w-12 h-12 bg-red-100 rounded-lg flex items-center justify-center">
                  <i className="fas fa-shield-alt text-red-600 text-xl"></i>
                </div>
                <div className="text-3xl font-black text-red-600">{securityScore}</div>
              </div>
              <h4 className="text-sm font-bold text-gray-800 mb-2">Security Posture</h4>
              <p className="text-xs text-gray-600">
                Active security threats and vulnerabilities detected
              </p>
              <div className="mt-3 h-2 bg-red-100 rounded-full overflow-hidden">
                <div
                  className="h-full bg-red-500 rounded-full transition-all duration-1000"
                  style={{ width: `${securityScore}%` }}
                ></div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* COMPONENT ANALYSIS */}
      <section className="bg-white rounded-2xl shadow-lg p-8 border border-gray-100">
        <h3 className="text-xl font-bold text-gray-800 mb-4 uppercase tracking-wider text-xs">
          COMPONENT ANALYSIS
        </h3>
        <div className="space-y-4">
          {/* CPU Utilization */}
          <div className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-amber-400"></div>
              <span className="text-gray-600 font-medium">CPU Utilization</span>
            </div>
            <div className="flex items-center gap-4">
              <span className="font-bold text-gray-900">
                {(dashboard.clusterOverview?.cpuUsagePercent || 0).toFixed(0)}%
              </span>
              <span className="px-3 py-1 rounded-full text-xs font-bold bg-amber-100 text-amber-700 flex items-center gap-1">
                <i className="fas fa-exclamation-circle"></i> Can Improve
              </span>
            </div>
          </div>

          {/* Memory Utilization */}
          <div className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-amber-400"></div>
              <span className="text-gray-600 font-medium">Memory Utilization</span>
            </div>
            <div className="flex items-center gap-4">
              <span className="font-bold text-gray-900">
                {(dashboard.clusterOverview?.memoryUsagePercent || 0).toFixed(0)}%
              </span>
              <span className="px-3 py-1 rounded-full text-xs font-bold bg-amber-100 text-amber-700 flex items-center gap-1">
                <i className="fas fa-exclamation-circle"></i> Can Improve
              </span>
            </div>
          </div>

          {/* Cost Efficiency */}
          <div className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-red-400"></div>
              <span className="text-gray-600 font-medium">Cost Efficiency</span>
            </div>
            <div className="flex items-center gap-4">
              <span className="font-bold text-gray-900">{costScore}%</span>
              <span className="px-3 py-1 rounded-full text-xs font-bold bg-red-100 text-red-700 flex items-center gap-1">
                <i className="fas fa-times-circle"></i> Wasteful
              </span>
            </div>
          </div>

          {/* Security Events */}
          <div className="flex items-center justify-between py-2 border-b border-gray-50 last:border-0">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-green-400"></div>
              <span className="text-gray-600 font-medium">Security Events</span>
            </div>
            <div className="flex items-center gap-4">
              <span className="font-bold text-gray-900">
                {dashboard?.securityMetrics?.totalEvents || 0} total
              </span>
              <span className="px-3 py-1 rounded-full text-xs font-bold bg-green-100 text-green-700 flex items-center gap-1">
                <i className="fas fa-check-circle"></i> No Critical
              </span>
            </div>
          </div>
        </div>
      </section>

      {/* Quick Wins Section */}
      {quickWins && quickWins.length > 0 && (
        <section className="bg-white rounded-2xl shadow-lg p-8 border border-gray-100">
          <h3 className="text-2xl font-bold text-gray-900 mb-6 flex items-center gap-3">
            <i className="fas fa-bolt text-yellow-500"></i>
            Quick Wins
            <span className="text-sm font-normal text-gray-500">
              — Recommended actions for immediate impact
            </span>
          </h3>

          <div className="space-y-4">
            {quickWins.map((win: QuickWin, index: number) => (
              <div
                key={index}
                className={`p-6 rounded-xl border-l-4 transition-all hover:shadow-md ${win.type === 'security'
                  ? 'bg-red-50 border-red-500'
                  : win.type === 'cost'
                    ? 'bg-amber-50 border-amber-500'
                    : 'bg-blue-50 border-blue-500'
                  }`}
              >
                <div className="flex items-start justify-between">
                  <div className="flex items-start gap-4 flex-1">
                    <div
                      className={`w-12 h-12 rounded-lg flex items-center justify-center text-2xl ${win.type === 'security'
                        ? 'bg-red-100 text-red-600'
                        : win.type === 'cost'
                          ? 'bg-amber-100 text-amber-600'
                          : 'bg-blue-100 text-blue-600'
                        }`}
                    >
                      <i className={`fas ${win.icon}`}></i>
                    </div>
                    <div className="flex-1">
                      <h4 className="text-lg font-bold text-gray-800 mb-1">{win.title}</h4>
                      <p className="text-sm text-gray-600 mb-3">{win.impact}</p>
                      <div className="flex gap-2">
                        {win.actions.map((action: string, i: number) => (
                          <button
                            key={i}
                            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${i === 0
                              ? 'bg-indigo-600 text-white hover:bg-indigo-700 shadow-sm'
                              : 'bg-white text-gray-700 hover:bg-gray-50 border border-gray-300'
                              }`}
                          >
                            {action}
                          </button>
                        ))}
                      </div>
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
        <section className="bg-gradient-to-br from-amber-50 to-orange-50 rounded-2xl shadow-lg p-8 border border-amber-200">
          <h3 className="text-2xl font-bold text-gray-900 mb-6 flex items-center gap-3">
            <i className="fas fa-piggy-bank text-amber-600"></i>
            Cost Savings Potential
          </h3>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="bg-white rounded-xl p-6 border border-amber-100">
              <div className="text-sm font-semibold text-gray-600 mb-2">Daily Savings</div>
              <div className="text-3xl font-black text-amber-600 mb-1">
                ${potentialSavings.daily.toFixed(2)}
              </div>
              <div className="text-xs text-gray-500">Per day</div>
            </div>

            <div className="bg-white rounded-xl p-6 border border-amber-100">
              <div className="text-sm font-semibold text-gray-600 mb-2">Monthly Savings</div>
              <div className="text-3xl font-black text-amber-600 mb-1">
                ${potentialSavings.monthly.toFixed(2)}
              </div>
              <div className="text-xs text-gray-500">Per month</div>
            </div>

            <div className="bg-white rounded-xl p-6 border border-amber-100">
              <div className="text-sm font-semibold text-gray-600 mb-2">Affected Nodes</div>
              <div className="text-3xl font-black text-amber-600 mb-1">
                {potentialSavings.affectedNodes}
              </div>
              <div className="text-xs text-gray-500">Underutilized (&lt;40% CPU)</div>
            </div>
          </div>
        </section>
      )}

      {/* Cluster Overview Stats */}
      <section className="bg-white rounded-2xl shadow-lg p-8 border border-gray-100">
        <h3 className="text-2xl font-bold text-gray-900 mb-6 flex items-center gap-3">
          <i className="fas fa-chart-bar text-indigo-500"></i>
          Cluster Overview
        </h3>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-3xl font-black text-gray-800 mb-1">
              {dashboard.clusterOverview?.totalNodes || 0}
            </div>
            <div className="text-sm text-gray-600">Total Nodes</div>
          </div>

          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-3xl font-black text-gray-800 mb-1">
              {dashboard.clusterOverview?.totalNamespaces || 0}
            </div>
            <div className="text-sm text-gray-600">Namespaces</div>
          </div>

          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-3xl font-black text-gray-800 mb-1">
              {(dashboard.clusterOverview?.cpuUsagePercent || 0).toFixed(1)}%
            </div>
            <div className="text-sm text-gray-600">CPU Usage</div>
          </div>

          <div className="text-center p-4 bg-gray-50 rounded-lg">
            <div className="text-3xl font-black text-gray-800 mb-1">
              {(dashboard.clusterOverview?.memoryUsagePercent || 0).toFixed(1)}%
            </div>
            <div className="text-sm text-gray-600">Memory Usage</div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default EksOverviewTab;
