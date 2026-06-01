import React, { useState, useEffect } from 'react';
import { Icon } from '@iconify/react';
import { DialogRoot, DialogPanel } from '../ui/Dialog';
import type { BudgetConfig, BudgetCreateRequest, BudgetUpdateRequest, LlmProviderConfig } from '../../types/aiops';

interface BudgetConfigModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (request: BudgetCreateRequest) => Promise<BudgetConfig>;
  onUpdate: (id: number, request: BudgetUpdateRequest) => Promise<BudgetConfig>;
  editBudget: BudgetConfig | null;
  providers: LlmProviderConfig[];
}

const DEFAULT_THRESHOLDS = [50, 80, 100];

const BudgetConfigModal: React.FC<BudgetConfigModalProps> = ({
  isOpen, onClose, onSave, onUpdate, editBudget, providers,
}) => {
  const [budgetName, setBudgetName] = useState('');
  const [providerConfigId, setProviderConfigId] = useState<string>('global');
  const [monthlyLimit, setMonthlyLimit] = useState('');
  const [thresholds, setThresholds] = useState<number[]>(DEFAULT_THRESHOLDS);
  const [autoDisable, setAutoDisable] = useState(false);
  const [notificationEmail, setNotificationEmail] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const isEditMode = editBudget !== null;

  useEffect(() => {
    if (editBudget) {
      setBudgetName(editBudget.budgetName);
      setProviderConfigId(editBudget.providerConfigId != null ? String(editBudget.providerConfigId) : 'global');
      setMonthlyLimit(String(editBudget.monthlyLimitUsd));
      setThresholds(editBudget.alertThresholds.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n)));
      setAutoDisable(editBudget.autoDisable);
      setNotificationEmail(editBudget.notificationEmail || '');
    } else {
      setBudgetName('');
      setProviderConfigId('global');
      setMonthlyLimit('');
      setThresholds(DEFAULT_THRESHOLDS);
      setAutoDisable(false);
      setNotificationEmail('');
    }
    setError('');
  }, [editBudget, isOpen]);

  const toggleThreshold = (t: number) => {
    setThresholds(prev => prev.includes(t) ? prev.filter(x => x !== t) : [...prev, t].sort((a, b) => a - b));
  };

  const handleSubmit = async () => {
    if (!budgetName.trim()) { setError('Budget name is required'); return; }
    const limit = parseFloat(monthlyLimit);
    if (isNaN(limit) || limit <= 0) { setError('Monthly limit must be greater than 0'); return; }
    if (thresholds.length === 0) { setError('Select at least one alert threshold'); return; }

    setSaving(true);
    setError('');

    try {
      if (isEditMode && editBudget?.id) {
        await onUpdate(editBudget.id, {
          monthlyLimitUsd: limit,
          alertThresholds: thresholds.join(','),
          autoDisable,
          notificationEmail: notificationEmail || undefined,
        });
      } else {
        await onSave({
          providerConfigId: providerConfigId === 'global' ? null : Number(providerConfigId),
          budgetName: budgetName.trim(),
          monthlyLimitUsd: limit,
          alertThresholds: thresholds.join(','),
          autoDisable,
          notificationEmail: notificationEmail || null,
        });
      }
      onClose();
    } catch (e: any) {
      setError(e.message || 'Failed to save budget');
    } finally {
      setSaving(false);
    }
  };

  return (
    <DialogRoot isOpen={isOpen} onClose={onClose}>
      <DialogPanel>
        <div className="p-6 max-w-lg mx-auto">
          <h2 className="text-lg font-bold text-[rgba(0,0,0,0.88)] mb-1">
            {isEditMode ? 'Edit Budget' : 'Create Budget'}
          </h2>
          <p className="text-sm text-[rgba(0,0,0,0.5)] mb-6">
            {isEditMode ? 'Update spending limits and alert settings.' : 'Set a monthly spending limit for your LLM usage.'}
          </p>

          {error && (
            <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
              <Icon icon="mdi:alert-circle-outline" width={16} className="inline mr-1" color="#b91c1c" />{error}
            </div>
          )}

          <div className="space-y-4">
            {/* Budget Name */}
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1">Budget Name</label>
              <input
                type="text"
                value={budgetName}
                onChange={e => setBudgetName(e.target.value)}
                placeholder="e.g. Production API Budget"
                disabled={isEditMode}
                className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60"
              />
            </div>

            {/* Scope */}
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1">Scope</label>
              <select
                value={providerConfigId}
                onChange={e => setProviderConfigId(e.target.value)}
                disabled={isEditMode}
                className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)] disabled:opacity-60"
              >
                <option value="global">All Providers (Global)</option>
                {providers.map(p => (
                  <option key={p.id} value={String(p.id)}>
                    {p.displayName || p.provider}
                  </option>
                ))}
              </select>
            </div>

            {/* Monthly Limit */}
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1">Monthly Limit (USD)</label>
              <div className="relative">
                <span className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.4)] text-sm">$</span>
                <input
                  type="number"
                  value={monthlyLimit}
                  onChange={e => setMonthlyLimit(e.target.value)}
                  placeholder="100.00"
                  min="0.01"
                  step="0.01"
                  className="w-full pl-7 pr-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
                />
              </div>
            </div>

            {/* Alert Thresholds */}
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-2">Alert Thresholds</label>
              <div className="flex items-center gap-3">
                {[50, 80, 100].map(t => (
                  <label key={t} className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={thresholds.includes(t)}
                      onChange={() => toggleThreshold(t)}
                      className="w-4 h-4 rounded border-[rgba(0,0,0,0.2)] text-indigo-600 focus:ring-indigo-500"
                    />
                    <span className={`text-sm font-medium ${
                      t >= 100 ? 'text-red-600' : t >= 80 ? 'text-amber-600' : 'text-blue-600'
                    }`}>
                      {t}%
                    </span>
                  </label>
                ))}
              </div>
            </div>

            {/* Auto-Disable Toggle */}
            <div className="flex items-center justify-between p-3 bg-[rgba(0,0,0,0.02)] rounded-lg border border-[rgba(0,0,0,0.08)]">
              <div>
                <p className="text-sm font-medium text-[rgba(0,0,0,0.7)]">Auto-disable on exceed</p>
                <p className="text-xs text-[rgba(0,0,0,0.4)] mt-0.5">
                  Block proxy requests (HTTP 429) when budget is exceeded
                </p>
              </div>
              <button
                type="button"
                onClick={() => setAutoDisable(!autoDisable)}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                  autoDisable ? 'bg-indigo-600' : 'bg-[rgba(0,0,0,0.15)]'
                }`}
              >
                <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                  autoDisable ? 'translate-x-6' : 'translate-x-1'
                }`} />
              </button>
            </div>

            {/* Notification Email */}
            <div>
              <label className="block text-xs font-semibold text-[rgba(0,0,0,0.7)] mb-1">
                Notification Email <span className="text-[rgba(0,0,0,0.4)]">(optional)</span>
              </label>
              <input
                type="email"
                value={notificationEmail}
                onChange={e => setNotificationEmail(e.target.value)}
                placeholder="alerts@company.com"
                className="w-full px-3 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
              />
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center justify-end gap-3 mt-6 pt-4 border-t border-[rgba(0,0,0,0.06)]">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm font-medium text-[rgba(0,0,0,0.7)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSubmit}
              disabled={saving}
              className="px-6 py-2 text-sm font-medium text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-colors shadow-sm disabled:opacity-50"
            >
              {saving ? (
                <><Icon icon="mdi:loading" width={14} className="animate-spin inline mr-2" />Saving...</>
              ) : (
                isEditMode ? 'Update Budget' : 'Create Budget'
              )}
            </button>
          </div>
        </div>
      </DialogPanel>
    </DialogRoot>
  );
};

export default BudgetConfigModal;
