/**
 * Budget Modal Component
 *
 * Modal for creating new budgets
 */

import React, { useState } from 'react';
import type { NewBudget } from '../../types/finops';

interface BudgetModalProps {
  isOpen: boolean;
  isCreating: boolean;
  onClose: () => void;
  onSubmit: (budget: NewBudget) => void;
}

const BudgetModal: React.FC<BudgetModalProps> = ({ isOpen, isCreating, onClose, onSubmit }) => {
  const [budget, setBudget] = useState<NewBudget>({
    budgetName: '',
    budgetLimit: null,
    budgetUnit: 'USD',
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(budget);
    // Reset form
    setBudget({ budgetName: '', budgetLimit: null, budgetUnit: 'USD' });
  };

  const handleClose = () => {
    setBudget({ budgetName: '', budgetLimit: null, budgetUnit: 'USD' });
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 flex items-center justify-center z-50 bg-black/50 backdrop-blur-sm"
      onClick={handleClose}
    >
      <div
        className="bg-white rounded-2xl p-8 w-full max-w-md mx-4 shadow-2xl border border-gray-200"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center space-x-3 mb-6">
          <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-wallet text-white"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-800">Create New Budget</h3>
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
                value={budget.budgetName}
                onChange={(e) => setBudget({ ...budget, budgetName: e.target.value })}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 transition-all"
                required
              />
            </div>
            <div>
              <label htmlFor="budgetLimit" className="block text-sm font-semibold text-gray-700 mb-2">
                Budget Limit (USD)
              </label>
              <input
                type="number"
                id="budgetLimit"
                value={budget.budgetLimit || ''}
                onChange={(e) => setBudget({ ...budget, budgetLimit: Number(e.target.value) })}
                className="w-full px-4 py-3 rounded-lg border border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200 transition-all"
                required
                min="1"
              />
            </div>
          </div>

          <div className="flex justify-end space-x-3 mt-8">
            <button
              type="button"
              onClick={handleClose}
              className="px-6 py-3 bg-white border border-gray-200 text-gray-700 font-semibold rounded-lg hover:bg-gray-50 transition-all"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isCreating}
              className="px-6 py-3 bg-indigo-600 text-white font-semibold rounded-lg shadow-md hover:bg-indigo-700 transition-all flex items-center disabled:opacity-50"
            >
              {isCreating ? (
                <>
                  <div className="loading-spinner mr-2 border-2 border-white border-t-transparent rounded-full w-4 h-4 animate-spin"></div>
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

export default BudgetModal;
