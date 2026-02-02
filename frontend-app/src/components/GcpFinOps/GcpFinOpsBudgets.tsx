/**
 * GCP FinOps Budgets Component
 *
 * Displays and manages GCP budgets with visual progress indicators
 */

import React from 'react';
import type { GcpBudget } from '../../types/gcpFinops';

interface GcpFinOpsBudgetsProps {
  budgets: GcpBudget[];
  onCreateBudget: () => void;
  onScheduleReport: () => void;
  onDeleteBudget: (budgetName: string) => void;
}

const formatCurrency = (value: number): string => {
  if (value === null || value === undefined || isNaN(value)) return '₹0.00';
  return (value || 0).toLocaleString('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
};

const GcpFinOpsBudgets: React.FC<GcpFinOpsBudgetsProps> = ({
  budgets,
  onCreateBudget,
  onScheduleReport,
  onDeleteBudget,
}) => {
  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-8 shadow-md fade-in">
      <div className="flex justify-between items-center mb-6">
        <div className="flex items-center space-x-3">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-green-500 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-wallet text-white"></i>
          </div>
          <div>
            <h3 className="text-xl font-bold text-gray-800">Budgets & Alerts</h3>
            <p className="text-sm text-gray-600">Monitor and control your GCP spending</p>
          </div>
        </div>
        <div className="flex space-x-3">
          <button
            onClick={onScheduleReport}
            className="px-6 py-3 bg-white border border-gray-200 text-gray-700 text-sm font-semibold rounded-lg shadow-sm hover:bg-gray-50 transition-all"
          >
            <i className="fas fa-envelope mr-2"></i>Schedule Report
          </button>
          <button
            onClick={onCreateBudget}
            className="px-6 py-3 bg-gradient-to-r from-blue-500 to-green-500 text-white text-sm font-semibold rounded-lg shadow-md hover:shadow-lg transition-all"
          >
            <i className="fas fa-plus mr-2"></i>Create Budget
          </button>
        </div>
      </div>

      {(!budgets || budgets.length === 0) && (
        <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
          <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-wallet text-gray-400 text-2xl"></i>
          </div>
          <h4 className="text-lg font-bold text-gray-800 mb-2">No Budgets Found</h4>
          <p className="text-gray-600 mb-4">Set up budgets to track and control your GCP spending</p>
          <button
            onClick={onCreateBudget}
            className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition"
          >
            <i className="fas fa-plus mr-2"></i>Create Your First Budget
          </button>
        </div>
      )}

      {budgets && budgets.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {budgets.map((budget) => {
            const percentage = (budget.actualSpend / budget.budgetLimit) * 100;
            const clampedPercentage = Math.min(percentage, 100);
            const progressColor =
              percentage > 80
                ? 'bg-gradient-to-r from-red-500 to-red-600'
                : percentage > 60
                ? 'bg-gradient-to-r from-yellow-400 to-orange-500'
                : 'bg-gradient-to-r from-green-400 to-green-600';

            return (
              <div key={budget.budgetName} className="bg-gray-50 border border-gray-200 rounded-xl p-6 hover:shadow-lg transition-all">
                <div className="flex items-center justify-between mb-4">
                  <h4 className="font-bold text-gray-800">{budget.budgetName}</h4>
                  <button
                    onClick={() => onDeleteBudget(budget.budgetName)}
                    className="text-red-500 hover:text-red-700"
                    title="Delete budget"
                  >
                    <i className="fas fa-trash-alt"></i>
                  </button>
                </div>
                <div className="space-y-3">
                  <div className="w-full bg-gray-200 rounded-full h-2.5 overflow-hidden shadow-inner">
                    <div
                      className={`h-2.5 rounded-full transition-all duration-500 shadow-sm ${progressColor}`}
                      style={{ width: `${clampedPercentage}%` }}
                    ></div>
                  </div>
                  <div className="flex justify-between text-sm font-semibold">
                    <span className="text-gray-700">Spent: {formatCurrency(budget.actualSpend)}</span>
                    <span className="text-gray-600">{Math.round(percentage)}%</span>
                  </div>
                  <div className="flex justify-between text-xs text-gray-500">
                    <span>Limit: {formatCurrency(budget.budgetLimit)}</span>
                    <span>Forecast: {formatCurrency(budget.forecastedSpend)}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default GcpFinOpsBudgets;
