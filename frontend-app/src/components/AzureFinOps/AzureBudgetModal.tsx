/**
 * Azure Budget Modal Component
 *
 * Modal for creating new Azure budgets
 */

import React, { useState, useEffect } from 'react';
import type { AzureNewBudget } from '../../types/azureFinops';
import { DialogRoot, DialogPanel } from '../ui';
import { Icon } from '@iconify/react';

interface AzureBudgetModalProps {
  isOpen: boolean;
  isCreating: boolean;
  onClose: () => void;
  onSubmit: (budget: AzureNewBudget) => void;
}

const AzureBudgetModal: React.FC<AzureBudgetModalProps> = ({ isOpen, isCreating, onClose, onSubmit }) => {
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
      budgetUnit: 'USD',
    });
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel
        title={
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl flex items-center justify-center">
              <Icon icon="mdi:wallet-outline" width={20} color="#0078D4" />
            </div>
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Create Azure Budget</span>
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
              form="azure-budget-form"
              disabled={isCreating}
              className="px-6 py-3 bg-blue-600 text-white font-semibold rounded-lg shadow-md hover:bg-blue-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
            >
              {isCreating ? (
                <>
                  <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></div>
                  Creating...
                </>
              ) : 'Create Budget'}
            </button>
          </>
        }
      >
        <form id="azure-budget-form" onSubmit={handleSubmit}>
          <div className="space-y-6">
            <div>
              <label htmlFor="azureBudgetName" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Name
              </label>
              <input
                type="text"
                id="azureBudgetName"
                value={budgetName}
                onChange={(e) => setBudgetName(e.target.value)}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                placeholder="e.g., Monthly VM Budget"
                required
              />
            </div>
            <div>
              <label htmlFor="azureBudgetLimit" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Amount (USD)
              </label>
              <input
                type="number"
                id="azureBudgetLimit"
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

export default AzureBudgetModal;
