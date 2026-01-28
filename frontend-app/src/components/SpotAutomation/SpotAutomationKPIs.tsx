import React from 'react';
import type { SpotCosts, SpotSavings } from '../../hooks/useSpotAutomationData';

interface SpotAutomationKPIsProps {
  costs: SpotCosts;
  savings: SpotSavings;
  enabledASGsCount: number;
  totalASGsCount: number;
  enabledRegionsCount: number;
}

const SpotAutomationKPIs: React.FC<SpotAutomationKPIsProps> = ({
  costs,
  savings,
  enabledASGsCount,
  totalASGsCount,
  enabledRegionsCount,
}) => {
  const formatCurrency = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  const formatHourlyCost = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 4,
      maximumFractionDigits: 4,
    }).format(value);
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-6">
      {/* Current Cost */}
      <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100 hover:shadow-xl transition-shadow duration-300">
        <div className="flex justify-between items-start">
          <div>
            <p className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
              Current Cost
            </p>
            <h3 className="text-3xl font-bold text-purple-600 mt-2">
              {formatCurrency(costs.currentMonthlyCost)}
            </h3>
          </div>
          <div className="p-3 bg-gradient-to-br from-purple-50 to-purple-100 rounded-lg">
            <i className="fas fa-dollar-sign text-purple-600 text-2xl"></i>
          </div>
        </div>
        <div className="mt-4 text-sm text-gray-600">
          <span className="font-medium">{formatHourlyCost(costs.currentHourly)}/hr</span>
        </div>
      </div>

      {/* Actual Savings */}
      <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100 hover:shadow-xl transition-shadow duration-300">
        <div className="flex justify-between items-start">
          <div>
            <p className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
              Actual Savings
            </p>
            <h3 className="text-3xl font-bold text-emerald-600 mt-2">
              {formatCurrency(savings.actualMonthlySavings)}
            </h3>
          </div>
          <div className="p-3 bg-gradient-to-br from-emerald-50 to-emerald-100 rounded-lg">
            <i className="fas fa-piggy-bank text-emerald-600 text-2xl"></i>
          </div>
        </div>
        <div className="mt-4 text-sm">
          <span className="text-emerald-600 font-semibold flex items-center">
            <i className="fas fa-arrow-up mr-1"></i> Last 30 Days
          </span>
        </div>
      </div>

      {/* Potential Savings */}
      <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100 hover:shadow-xl transition-shadow duration-300">
        <div className="flex justify-between items-start">
          <div>
            <p className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
              Potential Savings
            </p>
            <h3 className="text-3xl font-bold text-blue-600 mt-2">
              {formatCurrency(savings.potentialMonthlySavings)}
            </h3>
          </div>
          <div className="p-3 bg-gradient-to-br from-blue-50 to-blue-100 rounded-lg">
            <i className="fas fa-chart-line text-blue-600 text-2xl"></i>
          </div>
        </div>
        <div className="mt-4 text-sm text-gray-600">
          <span>Additional if all ASGs enabled</span>
        </div>
      </div>

      {/* ASGs Count */}
      <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100 hover:shadow-xl transition-shadow duration-300">
        <div className="flex justify-between items-start">
          <div>
            <p className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
              Auto Scaling Groups
            </p>
            <h3 className="text-3xl font-bold text-indigo-600 mt-2">
              <span>{enabledASGsCount}</span>
              <span className="text-gray-400">/</span>
              <span>{totalASGsCount}</span>
            </h3>
          </div>
          <div className="p-3 bg-gradient-to-br from-indigo-50 to-indigo-100 rounded-lg">
            <i className="fas fa-layer-group text-indigo-600 text-2xl"></i>
          </div>
        </div>
        <div className="mt-4 text-sm text-gray-600">
          {enabledRegionsCount > 0 ? (
            <span>
              Across <span className="font-medium">{enabledRegionsCount}</span>{' '}
              region{enabledRegionsCount > 1 ? 's' : ''}
            </span>
          ) : (
            <span>No regions configured</span>
          )}
        </div>
      </div>
    </div>
  );
};

export default SpotAutomationKPIs;
