/**
 * FinOps Budgets Component
 *
 * Displays budget cards with spending progress
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { Budget } from '../../types/finops';

interface FinOpsBudgetsProps {
  budgets: Budget[];
  onCreateBudget: () => void;
  onScheduleReport: () => void;
  onDeleteBudget: (budgetName: string) => void;
}

const FinOpsBudgets: React.FC<FinOpsBudgetsProps> = ({
  budgets,
  onCreateBudget,
  onScheduleReport,
  onDeleteBudget,
}) => {
  const formatCurrency = (value: number): string => {
    return value.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
  };

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
      {/* Header */}
      <div className="flex items-center justify-between pb-4 border-b border-[rgba(0,0,0,0.06)] mb-5">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
            <Icon icon="mdi:wallet-outline" width={16} color="#4285F4" />
          </div>
          <div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Budgets & Alerts</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
              <span className="w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse inline-block"></span>
              Monitor and control your spending
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={onScheduleReport}
            className="px-4 py-2 text-xs font-bold rounded-xl transition-colors flex items-center gap-1.5"
            style={{ backgroundColor: '#f2fa15', color: 'rgb(0,0,0)' }}
          >
            <Icon icon="mdi:email-outline" width={14} />
            Schedule Report
          </button>
          <button
            onClick={onCreateBudget}
            className="px-4 py-2 rounded-xl text-xs font-bold transition-colors flex items-center gap-1.5" style={{ backgroundColor: '#1BE68B', color: 'rgb(0,0,0)' }}
          >
            <Icon icon="mdi:plus" width={14} />
            Create Budget
          </button>
        </div>
      </div>

      {/* Empty state */}
      {(!budgets || budgets.length === 0) && (
        <div className="text-center py-10 rounded-xl border-2 border-dashed border-[rgba(0,0,0,0.1)]">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:wallet-outline" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Budgets Found</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">
            Set up budgets to track and control your cloud spending
          </p>
          <button
            onClick={onCreateBudget}
            className="px-4 py-2 rounded-xl text-xs font-bold transition-colors inline-flex items-center gap-1.5" style={{ backgroundColor: '#1BE68B', color: 'rgb(0,0,0)' }}
          >
            <Icon icon="mdi:plus" width={14} />
            Create Your First Budget
          </button>
        </div>
      )}

      {/* Budget cards grid */}
      {budgets && budgets.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {budgets.map((budget) => {
            const percentage = Math.min((budget.actualSpend / budget.budgetLimit) * 100, 100);
            const isOverBudget = percentage > 80;
            const isWarning = percentage > 60 && percentage <= 80;

            const barColor = isOverBudget
              ? '#f87171'
              : isWarning
              ? '#fbbf24'
              : '#4ade80';

            return (
              <div
                key={budget.budgetName}
                className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4"
              >
                <div className="flex items-center justify-between mb-3">
                  <p className="text-[14px] font-semibold text-black truncate pr-2">
                    {budget.budgetName}
                  </p>
                  <button
                    onClick={() => onDeleteBudget(budget.budgetName)}
                    className="text-red-500 hover:text-red-700 flex-shrink-0"
                    title="Delete budget"
                  >
                    <Icon icon="mdi:trash-can-outline" width={16} />
                  </button>
                </div>
                <div className="space-y-2">
                  <div className="bg-[rgba(0,0,0,0.06)] rounded-full h-1.5 overflow-hidden">
                    <div
                      className="h-1.5 rounded-full transition-all duration-500"
                      style={{ width: `${percentage}%`, backgroundColor: barColor }}
                    />
                  </div>
                  <div className="flex justify-between text-[13px] font-semibold text-[rgba(0,0,0,0.7)]">
                    <span>Spent: {formatCurrency(budget.actualSpend)}</span>
                    <span>{Math.round(percentage)}%</span>
                  </div>
                  <div className="flex justify-between text-[11px] text-[rgba(0,0,0,0.4)]">
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

export default FinOpsBudgets;
