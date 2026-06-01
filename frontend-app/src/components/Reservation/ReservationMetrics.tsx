/**
 * ReservationMetrics Component
 *
 * KPI metric cards showing utilization, coverage, active RIs, and active plans
 *
 * Converted from: reservation.html (metrics grid)
 */

import React from 'react';
import { Icon } from '@iconify/react';
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
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-indigo-500 fade-in relative overflow-hidden">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
            Avg Utilization (30d)
          </h3>
          <div
            className={`w-10 h-10 rounded-lg flex items-center justify-center shadow-md ${
              analysis.utilizationPercentage >= 90
                ? 'bg-gradient-to-br from-green-100 to-emerald-100'
                : 'bg-gradient-to-br from-yellow-100 to-amber-100'
            }`}
          >
            <Icon
              icon="mdi:chart-pie"
              width={18}
              className={analysis.utilizationPercentage >= 90 ? 'text-green-600' : 'text-yellow-600'}
            />
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-3">
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
            {analysis.utilizationPercentage?.toFixed(1) || 0}%
          </span>
        </div>
        <div className="w-full bg-[rgba(0,0,0,0.06)] rounded-full h-2 shadow-inner overflow-hidden">
          <div
            className={`h-2 rounded-full transition-all duration-1000 shadow-md ${
              analysis.utilizationPercentage >= 90
                ? 'bg-gradient-to-r from-green-500 to-emerald-500'
                : 'bg-gradient-to-r from-yellow-500 to-amber-500'
            }`}
            style={{ width: `${analysis.utilizationPercentage || 0}%` }}
          ></div>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* Coverage */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-blue-500 fade-in relative overflow-hidden">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">Coverage (30d)</h3>
          <div className="w-10 h-10 bg-gradient-to-br from-blue-100 to-indigo-100 rounded-lg flex items-center justify-center shadow-md">
            <Icon icon="mdi:shield-outline" width={18} className="text-blue-600" />
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-3">
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>
            {analysis.coveragePercentage?.toFixed(1) || 0}%
          </span>
        </div>
        <div className="w-full bg-[rgba(0,0,0,0.06)] rounded-full h-2 shadow-inner overflow-hidden">
          <div
            className="bg-gradient-to-r from-blue-500 to-indigo-500 h-2 rounded-full transition-all duration-1000 shadow-md"
            style={{ width: `${analysis.coveragePercentage || 0}%` }}
          ></div>
        </div>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* Active RIs */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-purple-500 fade-in relative overflow-hidden">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">Active RIs</h3>
          <div className="w-10 h-10 bg-gradient-to-br from-purple-100 to-pink-100 rounded-lg flex items-center justify-center shadow-md">
            <Icon icon="mdi:tag-outline" width={18} className="text-purple-600" />
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-2">
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{inventory?.length || 0}</span>
        </div>
        <p className="text-xs text-[rgba(0,0,0,0.5)] font-semibold mt-3 flex items-center gap-1">
          <Icon icon="mdi:server-outline" width={12} className="text-purple-500" />
          Reserved Instances
        </p>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>

      {/* Active Plans */}
      <div className="glass-card rounded-xl p-5 metric-card border-l-4 border-l-orange-500 fade-in relative overflow-hidden">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">Active Plans</h3>
          <div className="w-10 h-10 bg-gradient-to-br from-orange-100 to-yellow-100 rounded-lg flex items-center justify-center shadow-md">
            <Icon icon="mdi:piggy-bank-outline" width={18} className="text-orange-600" />
          </div>
        </div>
        <div className="flex items-baseline gap-2 mb-2">
          <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, lineHeight: '32px', color: 'rgb(10,10,10)', fontVariantNumeric: 'tabular-nums' }}>{savingsPlans?.length || 0}</span>
        </div>
        <p className="text-xs text-[rgba(0,0,0,0.5)] font-semibold mt-3 flex items-center gap-1">
          <Icon icon="mdi:cash-multiple" width={12} className="text-orange-500" />
          Savings Plans
        </p>
        <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
          <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
        </svg>
      </div>
    </div>
  );
};

export default ReservationMetrics;
