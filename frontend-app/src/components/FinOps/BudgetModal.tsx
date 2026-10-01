import React, { useState } from 'react';
import type { NewBudget } from '../../types/finops';
import { DialogRoot, DialogPanel } from '../ui';
import { Icon } from '@iconify/react';

interface BudgetModalProps {
  isOpen: boolean;
  isCreating: boolean;
  availableServices?: string[];
  /**
   * ISO-4217 code the limit is entered in. Drives the field label, the symbol shown beside the
   * amount, and the unit sent to the API. Defaults to USD so the AWS page is unchanged; the Azure
   * and GCP pages pass the subscription's billing currency (INR), which is the whole point of the
   * number on a cost page.
   */
  currency?: string;
  onClose: () => void;
  onSubmit: (budget: NewBudget) => void;
}

/** Symbol for the currencies the cost pages bill in; falls back to the bare code otherwise. */
const currencySymbol = (currency: string): string => {
  switch (currency.toUpperCase()) {
    case 'INR': return '₹';
    case 'EUR': return '€';
    case 'GBP': return '£';
    case 'USD': return '$';
    default: return '';
  }
};

/**
 * Constrains a budget name to Azure's rule: 1-63 characters of letters, digits, underscore and
 * hyphen. AWS and GCP both accept that same set, so applying it everywhere is safe and stops the
 * name reaching ARM as a value it rejects with an opaque 400. Spaces and other characters — which
 * the service-name auto-fill used to inject, e.g. "Virtual Machines" — become hyphens instead.
 */
const sanitizeBudgetName = (raw: string): string =>
  raw.replace(/[^a-zA-Z0-9_-]/g, '-').slice(0, 63);

const BudgetModal: React.FC<BudgetModalProps> = ({ isOpen, isCreating, availableServices = [], currency = 'USD', onClose, onSubmit }) => {
  const currencyCode = (currency || 'USD').toUpperCase();
  const symbol = currencySymbol(currencyCode);

  const [budget, setBudget] = useState<NewBudget>({ budgetName: '', budgetLimit: null, budgetUnit: currencyCode, serviceName: null });

  const reset = () => setBudget({ budgetName: '', budgetLimit: null, budgetUnit: currencyCode, serviceName: null });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    // budgetUnit follows the account's billing currency rather than a hardcoded USD, so an
    // INR-billed Azure/GCP subscription stores and shows its limit in rupees. Resolved here, at
    // submit time, because the currency prop can arrive after this modal first mounts.
    onSubmit({ ...budget, budgetName: sanitizeBudgetName(budget.budgetName), budgetUnit: currencyCode });
    reset();
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleServiceChange = (serviceName: string) => {
    const svc = serviceName || null;
    setBudget(prev => ({
      ...prev,
      serviceName: svc,
      // Auto-fill the budget name from the service, sanitized: raw Azure service names carry
      // spaces ("Virtual Machines") that the budget-name rule forbids.
      budgetName: prev.budgetName === '' && svc ? sanitizeBudgetName(svc) : prev.budgetName,
    }));
  };

  const fieldBase = 'w-full py-2.5 rounded-lg text-[13px] border border-[rgba(0,0,0,0.12)] focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-colors';
  const inputCls = `px-3 ${fieldBase}`;
  const limitInputCls = symbol ? `pl-7 pr-3 ${fieldBase}` : inputCls;
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
            <input
              type="text"
              id="budgetName"
              value={budget.budgetName}
              onChange={(e) => setBudget({ ...budget, budgetName: sanitizeBudgetName(e.target.value) })}
              className={inputCls}
              required
              maxLength={63}
              placeholder="e.g. EC2-Monthly-Budget"
            />
            <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-1">Letters, digits, hyphens and underscores only (max 63). Spaces become hyphens.</p>
          </div>
          <div>
            <label htmlFor="budgetLimit" className={labelCls}>Budget Limit ({currencyCode})</label>
            <div className="relative">
              {symbol && (
                <span className="absolute left-3 top-1/2 -translate-y-1/2 text-[13px] text-[rgba(0,0,0,0.5)] pointer-events-none">{symbol}</span>
              )}
              <input
                type="number"
                id="budgetLimit"
                value={budget.budgetLimit || ''}
                onChange={(e) => setBudget({ ...budget, budgetLimit: Number(e.target.value) })}
                className={limitInputCls}
                required
                min="1"
                placeholder="1000"
              />
            </div>
          </div>
        </form>
      </DialogPanel>
    </DialogRoot>
  );
};

export default BudgetModal;
