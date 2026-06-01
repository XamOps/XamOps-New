/**
 * GCP FinOps Budgets Component
 *
 * Displays and manages GCP budgets with visual progress indicators
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { GcpBudget } from '../../types/gcpFinops';

interface GcpFinOpsBudgetsProps {
  budgets: GcpBudget[];
  onCreateBudget: () => void;
  onScheduleReport: () => void;
  onDeleteBudget: (budgetName: string) => void;
}

const formatCurrency = (value: number): string => {
  if (value === null || value === undefined || isNaN(value)) return '$0.00';
  return (value || 0).toLocaleString('en-US', {
    style: 'currency',
    currency: 'USD',
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
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Header */}
      <div className="flex justify-between items-start mb-5">
        <div className="flex items-center gap-3">
          <div
            className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]"
            style={{ backgroundColor: '#4285F414' }}
          >
            <Icon icon="mdi:wallet" width={16} color="#4285F4" />
          </div>
          <div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Budgets & Alerts</h3>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
              <span className="w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse inline-block"></span>
              Monitor and control your GCP spending
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={onScheduleReport}
            className="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-bold transition-colors"
            style={{ backgroundColor: '#f2fa15', color: 'rgb(0,0,0)' }}
          >
            <Icon icon="mdi:email-outline" width={14} />
            Schedule Report
          </button>
          <button
            onClick={onCreateBudget}
            className="flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-bold transition-colors"
            style={{ backgroundColor: '#1BE68B', color: 'rgb(0,0,0)' }}
          >
            <Icon icon="mdi:plus" width={14} />
            Create Budget
          </button>
        </div>
      </div>

      {/* Empty state */}
      {(!budgets || budgets.length === 0) && (
        <div className="text-center py-10 rounded-xl border-2 border-dashed border-[rgba(0,0,0,0.1)]">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:wallet" width={24} color="rgba(0,0,0,0.25)" />
          </div>
          <h4 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Budgets Found</h4>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">Set up budgets to track and control your GCP spending</p>
          <button
            onClick={onCreateBudget}
            className="px-4 py-2 rounded-xl text-xs font-bold transition-colors inline-flex items-center gap-1.5"
            style={{ backgroundColor: '#1BE68B', color: 'rgb(0,0,0)' }}
          >
            <Icon icon="mdi:plus" width={14} />
            Create Your First Budget
          </button>
        </div>
      )}

      {/* Budget cards */}
      {budgets && budgets.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {budgets.map((budget) => {
            const percentage = budget.budgetLimit > 0 ? (budget.actualSpend / budget.budgetLimit) * 100 : 0;
            const clampedPercentage = Math.min(percentage, 100);
            const progressColor =
              percentage > 80
                ? 'bg-red-400'
                : percentage > 60
                ? 'bg-yellow-300'
                : 'bg-green-400';
            const percentageBadgeClass =
              percentage > 80
                ? 'text-red-600 bg-red-50'
                : percentage > 60
                ? 'text-yellow-700 bg-yellow-50'
                : 'text-green-700 bg-green-50';

            return (
              <div
                key={budget.budgetName}
                className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4"
              >
                <div className="flex items-center justify-between mb-3">
                  <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] truncate pr-2">{budget.budgetName}</h4>
                  <button
                    onClick={() => onDeleteBudget(budget.budgetName)}
                    className="text-[rgba(0,0,0,0.3)] hover:text-red-500 transition-colors flex-shrink-0"
                    title="Delete budget"
                  >
                    <Icon icon="mdi:trash-can-outline" width={15} />
                  </button>
                </div>
                <div className="space-y-2">
                  <div className="w-full bg-[rgba(0,0,0,0.06)] rounded-full h-1.5 overflow-hidden">
                    <div
                      className={`h-1.5 rounded-full transition-all duration-500 ${progressColor}`}
                      style={{ width: `${clampedPercentage}%` }}
                    ></div>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-[13px] font-normal text-[rgba(0,0,0,0.88)]">
                      Spent: {formatCurrency(budget.actualSpend)}
                    </span>
                    <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded-full ${percentageBadgeClass}`}>
                      {Math.round(percentage)}%
                    </span>
                  </div>
                  <div className="flex justify-between text-[12px] text-[rgba(0,0,0,0.45)]">
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
