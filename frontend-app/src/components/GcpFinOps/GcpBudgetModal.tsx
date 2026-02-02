/**
 * GCP Budget Modal Component
 *
 * Modal for creating new GCP budgets
 */

import React, { useState, useEffect } from 'react';
import type { GcpNewBudget } from '../../types/gcpFinops';

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

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-white rounded-2xl p-8 max-w-md w-full mx-4 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-green-500 rounded-xl flex items-center justify-center">
            <i className="fas fa-wallet text-white"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-800">Create GCP Budget</h3>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="space-y-6">
            <div>
              <label htmlFor="budgetName" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Name
              </label>
              <input
                type="text"
                id="budgetName"
                value={budgetName}
                onChange={(e) => setBudgetName(e.target.value)}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-200 transition-all"
                placeholder="e.g., Monthly Compute Budget"
                required
              />
            </div>
            <div>
              <label htmlFor="budgetLimit" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Amount (INR)
              </label>
              <input
                type="number"
                id="budgetLimit"
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

          <div className="flex justify-end space-x-3 mt-8">
            <button
              type="button"
              onClick={onClose}
              className="px-6 py-3 bg-white border border-gray-200 text-gray-700 font-semibold rounded-lg hover:bg-gray-50 transition-all"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isCreating}
              className="px-6 py-3 bg-blue-600 text-white font-semibold rounded-lg shadow-md hover:bg-blue-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center"
            >
              {isCreating ? (
                <>
                  <div className="loading-spinner mr-2"></div>
                  Creating...
                </>
              ) : (
                'Create Budget'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default GcpBudgetModal;
