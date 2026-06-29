import React, { useState } from 'react';
import type { NewBudget } from '../../types/finops';
import { DialogRoot, DialogPanel } from '../ui';
import { Icon } from '@iconify/react';

interface BudgetModalProps {
  isOpen: boolean;
  isCreating: boolean;
  availableServices?: string[];
  onClose: () => void;
  onSubmit: (budget: NewBudget) => void;
}

const BudgetModal: React.FC<BudgetModalProps> = ({ isOpen, isCreating, availableServices = [], onClose, onSubmit }) => {
  const [budget, setBudget] = useState<NewBudget>({ budgetName: '', budgetLimit: null, budgetUnit: 'USD', serviceName: null });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(budget);
    setBudget({ budgetName: '', budgetLimit: null, budgetUnit: 'USD', serviceName: null });
  };

  const handleClose = () => {
    setBudget({ budgetName: '', budgetLimit: null, budgetUnit: 'USD', serviceName: null });
    onClose();
  };

  const handleServiceChange = (serviceName: string) => {
    const svc = serviceName || null;
    setBudget(prev => ({
      ...prev,
      serviceName: svc,
      // Auto-fill budget name with service name if empty
      budgetName: prev.budgetName === '' && svc ? svc : prev.budgetName,
    }));
  };

  const inputCls = 'w-full px-3 py-2.5 rounded-lg text-[13px] border border-[rgba(0,0,0,0.12)] focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-colors';
  const labelCls = 'block text-[12px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wide mb-1.5';

  return (
    <DialogRoot isOpen={isOpen} onClose={handleClose}>
      <DialogPanel
        title={
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-white border border-[rgba(0,0,0,0.12)] flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:wallet-outline" width={18} color="#2563EB" />
            </div>
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Create New Budget</span>
          </div>
        }
        onClose={handleClose}
        footer={
          <>
            <button type="button" onClick={handleClose} className="px-4 py-2 text-[13px] font-medium text-[rgba(0,0,0,0.6)] border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.04)] transition-colors">
              Cancel
            </button>
            <button type="submit" form="budget-form" disabled={isCreating} className="px-4 py-2 text-[13px] font-bold bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 flex items-center gap-2">
              {isCreating ? <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Creating...</> : 'Create Budget'}
            </button>
          </>
        }
      >
        <form id="budget-form" onSubmit={handleSubmit} className="space-y-4">
          {/* Service selector */}
          {availableServices.length > 0 && (
            <div>
              <label htmlFor="budgetService" className={labelCls}>Service (optional)</label>
              <select
                id="budgetService"
                value={budget.serviceName || ''}
                onChange={(e) => handleServiceChange(e.target.value)}
                className={inputCls}
              >
                <option value="">All Services (account-level budget)</option>
                {availableServices.map((svc) => (
                  <option key={svc} value={svc}>{svc}</option>
                ))}
              </select>
              <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-1">Select a service to create a per-service budget, or leave blank for overall account budget.</p>
            </div>
          )}
          <div>
            <label htmlFor="budgetName" className={labelCls}>Budget Name</label>
            <input type="text" id="budgetName" value={budget.budgetName} onChange={(e) => setBudget({ ...budget, budgetName: e.target.value })} className={inputCls} required placeholder="e.g. EC2 Monthly Budget" />
          </div>
          <div>
            <label htmlFor="budgetLimit" className={labelCls}>Budget Limit (USD)</label>
            <input type="number" id="budgetLimit" value={budget.budgetLimit || ''} onChange={(e) => setBudget({ ...budget, budgetLimit: Number(e.target.value) })} className={inputCls} required min="1" placeholder="1000" />
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default BudgetModal;
