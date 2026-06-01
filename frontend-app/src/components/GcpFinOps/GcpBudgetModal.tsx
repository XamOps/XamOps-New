/**
 * GCP Budget Modal Component
 *
 * Modal for creating new GCP budgets
 */

import React, { useState, useEffect } from 'react';
import type { GcpNewBudget } from '../../types/gcpFinops';
import { DialogRoot, DialogPanel } from '../ui';
import { Icon } from '@iconify/react';

interface GcpBudgetModalProps {
  isOpen: boolean;
  isCreating: boolean;
  onClose: () => void;
  onSubmit: (budget: GcpNewBudget) => void;
}

const GcpBudgetModal: React.FC<GcpBudgetModalProps> = ({ isOpen, isCreating, onClose, onSubmit }) => {
  const [budgetName, setBudgetName] = useState('');
  const [budgetLimit, setBudgetLimit] = useState<string>('');

  // Reset form when modal opens
  useEffect(() => {
    if (isOpen) {
      setBudgetName('');
      setBudgetLimit('');
    }
  }, [isOpen]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({
      budgetName,
      budgetLimit: parseFloat(budgetLimit),
      currencyCode: 'USD',
    });
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel
        title={
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl flex items-center justify-center">
              <Icon icon="mdi:wallet-outline" width={20} color="#2563eb" />
            </div>
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Create GCP Budget</span>
          </div>
        }
        onClose={onClose}
        footer={
          <>
            <button
              type="button"
              onClick={onClose}
              className="px-6 py-3 bg-white border border-gray-200 text-gray-700 font-semibold rounded-lg hover:bg-gray-50 transition-all"
            >
              Cancel
            </button>
            <button
              type="submit"
              form="gcp-budget-form"
              disabled={isCreating}
              className="px-6 py-3 bg-blue-600 text-white font-semibold rounded-lg shadow-md hover:bg-blue-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
            >
              {isCreating ? <><div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></div>Creating...</> : 'Create Budget'}
            </button>
          </>
        }
      >
        <form id="gcp-budget-form" onSubmit={handleSubmit}>
          <div className="space-y-6">
            <div>
              <label htmlFor="gcpBudgetName" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Name
              </label>
              <input
                type="text"
                id="gcpBudgetName"
                value={budgetName}
                onChange={(e) => setBudgetName(e.target.value)}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                placeholder="e.g., Monthly Compute Budget"
                required
              />
            </div>
            <div>
              <label htmlFor="gcpBudgetLimit" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Amount (USD)
              </label>
              <input
                type="number"
                id="gcpBudgetLimit"
                value={budgetLimit}
                onChange={(e) => setBudgetLimit(e.target.value)}
                min="1"
                step="1"
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                placeholder="e.g., 1000"
                required
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-gray-700 mb-2">Alert Thresholds</label>
              <p className="text-xs text-gray-500 mb-2">Default alerts at 50%, 80%, 90%, and 100%</p>
            </div>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default GcpBudgetModal;
