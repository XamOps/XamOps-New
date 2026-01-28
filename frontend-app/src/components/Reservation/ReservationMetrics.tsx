/**
 * ReservationMetrics Component
 *
 * KPI metric cards showing utilization, coverage, active RIs, and active plans
 *
 * Converted from: reservation.html (metrics grid)
 */

import React from 'react';
import type { ReservationAnalysis, ReservationInventoryItem, SavingsPlan } from '../../types/reservation';

interface ReservationMetricsProps {
  analysis: ReservationAnalysis;
  inventory: ReservationInventoryItem[];
  savingsPlans: SavingsPlan[];
}

const ReservationMetrics: React.FC<ReservationMetricsProps> = ({
  analysis,
  inventory,
  savingsPlans,
}) => {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
      {/* Avg Utilization */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-indigo-500 fade-in">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
            Avg Utilization (30d)
          </h3>
          <div
            className={`w-10 h-10 rounded-lg flex items-center justify-center shadow-md ${
              analysis.utilizationPercentage >= 90
                ? 'bg-gradient-to-br from-green-100 to-emerald-100'
                : 'bg-gradient-to-br from-yellow-100 to-amber-100'
            }`}
          >
            <i
              className={`fas fa-chart-pie ${
                analysis.utilizationPercentage >= 90 ? 'text-green-600' : 'text-yellow-600'
              }`}
            ></i>
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-3">
          <h3 className="text-3xl font-black metric-value">
            {analysis.utilizationPercentage?.toFixed(1) || 0}%
          </h3>
        </div>
        <div className="w-full bg-gray-100 rounded-full h-2 shadow-inner overflow-hidden">
          <div
            className={`h-2 rounded-full transition-all duration-1000 shadow-md ${
              analysis.utilizationPercentage >= 90
                ? 'bg-gradient-to-r from-green-500 to-emerald-500'
                : 'bg-gradient-to-r from-yellow-500 to-amber-500'
            }`}
            style={{ width: `${analysis.utilizationPercentage || 0}%` }}
          ></div>
        </div>
      </div>

      {/* Coverage */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-blue-500 fade-in">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Coverage (30d)</h3>
          <div className="w-10 h-10 bg-gradient-to-br from-blue-100 to-indigo-100 rounded-lg flex items-center justify-center shadow-md">
            <i className="fas fa-shield-alt text-blue-600"></i>
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-3">
          <h3 className="text-3xl font-black metric-value">
            {analysis.coveragePercentage?.toFixed(1) || 0}%
          </h3>
        </div>
        <div className="w-full bg-gray-100 rounded-full h-2 shadow-inner overflow-hidden">
          <div
            className="bg-gradient-to-r from-blue-500 to-indigo-500 h-2 rounded-full transition-all duration-1000 shadow-md"
            style={{ width: `${analysis.coveragePercentage || 0}%` }}
          ></div>
        </div>
      </div>

      {/* Active RIs */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-purple-500 fade-in">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Active RIs</h3>
          <div className="w-10 h-10 bg-gradient-to-br from-purple-100 to-pink-100 rounded-lg flex items-center justify-center shadow-md">
            <i className="fas fa-tags text-purple-600"></i>
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-2">
          <h3 className="text-4xl font-black metric-value">{inventory?.length || 0}</h3>
        </div>
        <p className="text-xs text-gray-500 font-semibold mt-3 flex items-center gap-1">
          <i className="fas fa-server text-purple-500 text-[10px]"></i>
          Reserved Instances
        </p>
      </div>

      {/* Active Plans */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-orange-500 fade-in">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Active Plans</h3>
          <div className="w-10 h-10 bg-gradient-to-br from-orange-100 to-yellow-100 rounded-lg flex items-center justify-center shadow-md">
            <i className="fas fa-piggy-bank text-orange-600"></i>
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-2">
          <h3 className="text-4xl font-black metric-value">{savingsPlans?.length || 0}</h3>
        </div>
        <p className="text-xs text-gray-500 font-semibold mt-3 flex items-center gap-1">
          <i className="fas fa-coins text-orange-500 text-[10px]"></i>
          Savings Plans
        </p>
      </div>
    </div>
  );
};

export default ReservationMetrics;
