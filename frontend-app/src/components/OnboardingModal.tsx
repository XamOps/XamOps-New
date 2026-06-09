import React, { useState } from 'react';
import apiClient from '../utils/apiClient';

interface AccountIdEntry {
  key: string;
  value: string;
}

interface IntakeForm {
  name: string;
  companyName: string;
  email: string;
  phone: string;
  address: string;
  notes: string;
}

interface Props {
  userEmail?: string;
  userName?: string;
  onClose: () => void;
}

type Step = 'billing-question' | 'form' | 'success';

const OnboardingModal: React.FC<Props> = ({ userEmail, userName, onClose }) => {
  const [step, setStep] = useState<Step>('billing-question');
  const [submitting, setSubmitting] = useState(false);
  const [optingOut, setOptingOut] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [accountIds, setAccountIds] = useState<AccountIdEntry[]>([{ key: '1', value: '' }]);
  const [form, setForm] = useState<IntakeForm>({
    name: userName || '',
    companyName: '',
    email: userEmail || '',
    phone: '',
    address: '',
    notes: '',
  });

  const addAccountId = () => {
    setAccountIds(prev => [...prev, { key: Date.now().toString(), value: '' }]);
  };

  const removeAccountId = (key: string) => {
    if (accountIds.length === 1) return;
    setAccountIds(prev => prev.filter(a => a.key !== key));
  };

  const updateAccountId = (key: string, value: string) => {
    setAccountIds(prev => prev.map(a => a.key === key ? { ...a, value } : a));
  };

  const handleFieldChange = (field: keyof IntakeForm, value: string) => {
    setForm(prev => ({ ...prev, [field]: value }));
    if (error) setError(null);
  };

  const handleOptOut = async () => {
    setOptingOut(true);
    try {
      await apiClient.post('/api/billops/admin/client-intake', {
        name: userName || userEmail || 'Unknown',
        companyName: '',
        email: userEmail || '',
        optedOut: true,
      });
    } catch {
      // best-effort: still close even if request fails
    } finally {
      setOptingOut(false);
      onClose();
    }
  };

  const handleSubmit = async () => {
    if (!form.name.trim() || !form.companyName.trim()) {
      setError('Name and Company Name are required.');
      return;
    }
    const trimmedAccounts = accountIds.map(a => a.value.trim()).filter(Boolean);
    setSubmitting(true);
    setError(null);
    try {
      await apiClient.post('/api/billops/admin/client-intake', {
        name: form.name.trim(),
        companyName: form.companyName.trim(),
        email: form.email.trim(),
        phone: form.phone.trim(),
        address: form.address.trim(),
        notes: form.notes.trim(),
        accountIds: trimmedAccounts,
      });
      setStep('success');
    } catch {
      setError('Failed to submit. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="bg-white dark:bg-gray-900 rounded-2xl shadow-2xl w-full max-w-lg flex flex-col max-h-[90vh] overflow-hidden">

        {/* Header */}
        <div className="px-6 py-5 bg-gradient-to-r from-indigo-600 to-indigo-700 rounded-t-2xl flex-shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-white/20 rounded-xl flex items-center justify-center flex-shrink-0">
              <i className="fas fa-building text-white text-sm" />
            </div>
            <div>
              <h2 className="text-white font-bold text-base leading-tight">Welcome to XamOps</h2>
              <p className="text-indigo-200 text-xs mt-0.5">Complete your account setup to get started</p>
            </div>
          </div>
          {/* Step indicators */}
          {(step === 'form') && (
            <div className="flex items-center gap-1.5 mt-4">
              <div className="h-1 w-12 rounded-full bg-white/90" />
              <div className="h-1 flex-1 rounded-full bg-white/90" />
            </div>
          )}
          {step === 'billing-question' && (
            <div className="flex items-center gap-1.5 mt-4">
              <div className="h-1 w-12 rounded-full bg-white/90" />
              <div className="h-1 flex-1 rounded-full bg-white/30" />
            </div>
          )}
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto">

          {/* ── Step 1: Billing question ── */}
          {step === 'billing-question' && (
            <div className="px-6 py-8">
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 bg-indigo-50 rounded-2xl flex items-center justify-center mb-5">
                  <i className="fas fa-file-invoice-dollar text-indigo-600 text-2xl" />
                </div>
                <h3 className="text-lg font-bold text-gray-900 dark:text-white mb-2">Billing Verification</h3>
                <p className="text-gray-500 dark:text-gray-400 text-sm leading-relaxed max-w-sm">
                  Are you taking billing services from <span className="font-semibold text-indigo-600">Xammer</span>?
                </p>
                <div className="flex gap-4 mt-8 w-full max-w-xs">
                  <button
                    onClick={handleOptOut}
                    disabled={optingOut}
                    className="flex-1 py-3 px-4 border-2 border-gray-200 hover:border-gray-300 hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-800 text-gray-600 dark:text-gray-300 font-semibold rounded-xl transition-all text-sm flex items-center justify-center gap-2 disabled:opacity-60"
                  >
                    {optingOut ? (
                      <i className="fas fa-spinner animate-spin text-gray-400" />
                    ) : (
                      <i className="fas fa-times-circle text-gray-400" />
                    )}
                    No
                  </button>
                  <button
                    onClick={() => setStep('form')}
                    className="flex-1 py-3 px-4 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold rounded-xl transition-all text-sm flex items-center justify-center gap-2 shadow-md shadow-indigo-200"
                  >
                    <i className="fas fa-check-circle" />
                    Yes
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* ── Step 2: Registration form ── */}
          {step === 'form' && (
            <div className="px-6 py-5 space-y-4">
              <div>
                <h3 className="text-sm font-semibold text-gray-900 dark:text-white">Account Details</h3>
                <p className="text-xs text-gray-400 mt-0.5">Please fill in your information to complete setup</p>
              </div>

              {error && (
                <div className="flex items-center gap-2 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
                  <i className="fas fa-exclamation-circle text-red-500 text-xs flex-shrink-0" />
                  <p className="text-red-700 dark:text-red-400 text-xs">{error}</p>
                </div>
              )}

              {/* Name */}
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5">
                  Full Name <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={form.name}
                  onChange={e => handleFieldChange('name', e.target.value)}
                  placeholder="John Smith"
                  className="w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                />
              </div>

              {/* Company Name */}
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5">
                  Company Name <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={form.companyName}
                  onChange={e => handleFieldChange('companyName', e.target.value)}
                  placeholder="Acme Corp"
                  className="w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                />
              </div>

              {/* Email */}
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5">
                  Email Address
                </label>
                <input
                  type="email"
                  value={form.email}
                  onChange={e => handleFieldChange('email', e.target.value)}
                  placeholder="you@company.com"
                  className="w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                />
              </div>

              {/* Phone */}
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5">
                  Phone Number
                </label>
                <input
                  type="tel"
                  value={form.phone}
                  onChange={e => handleFieldChange('phone', e.target.value)}
                  placeholder="+1 (555) 000-0000"
                  className="w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                />
              </div>

              {/* Address */}
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5">
                  Business Address
                </label>
                <input
                  type="text"
                  value={form.address}
                  onChange={e => handleFieldChange('address', e.target.value)}
                  placeholder="123 Main St, City, Country"
                  className="w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                />
              </div>

              {/* Account IDs */}
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300">
                    Cloud Account ID(s)
                    <span className="ml-1.5 text-[10px] font-normal text-gray-400">(AWS / GCP / Azure)</span>
                  </label>
                  <button
                    type="button"
                    onClick={addAccountId}
                    className="flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-700 font-medium transition-colors"
                  >
                    <i className="fas fa-plus text-[9px]" />
                    Add another
                  </button>
                </div>
                <div className="space-y-2">
                  {accountIds.map((entry, idx) => (
                    <div key={entry.key} className="flex items-center gap-2">
                      <div className="w-5 h-5 bg-gray-100 dark:bg-gray-700 rounded text-[10px] text-gray-400 flex items-center justify-center font-medium flex-shrink-0">
                        {idx + 1}
                      </div>
                      <input
                        type="text"
                        value={entry.value}
                        onChange={e => updateAccountId(entry.key, e.target.value)}
                        placeholder="e.g. 123456789012 or my-gcp-project"
                        className="flex-1 px-3 py-2 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all font-mono"
                      />
                      {accountIds.length > 1 && (
                        <button
                          type="button"
                          onClick={() => removeAccountId(entry.key)}
                          className="w-7 h-7 flex items-center justify-center text-gray-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-md transition-all flex-shrink-0"
                        >
                          <i className="fas fa-times text-xs" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>

              {/* Notes */}
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5">
                  Additional Notes
                  <span className="ml-1.5 text-[10px] font-normal text-gray-400">(optional)</span>
                </label>
                <textarea
                  value={form.notes}
                  onChange={e => handleFieldChange('notes', e.target.value)}
                  placeholder="Any additional information..."
                  rows={2}
                  className="w-full px-3 py-2.5 text-sm border border-gray-200 dark:border-gray-700 rounded-lg bg-white dark:bg-gray-800 text-gray-900 dark:text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all resize-none"
                />
              </div>
            </div>
          )}

          {/* ── Step: Success ── */}
          {step === 'success' && (
            <div className="px-6 py-8">
              <div className="flex flex-col items-center text-center">
                <div className="w-16 h-16 bg-green-50 rounded-2xl flex items-center justify-center mb-5">
                  <i className="fas fa-check-circle text-green-500 text-3xl" />
                </div>
                <h3 className="text-lg font-bold text-gray-900 dark:text-white mb-2">Setup Complete!</h3>
                <button
                  onClick={onClose}
                  className="mt-6 px-8 py-2.5 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold rounded-xl transition-all text-sm shadow-md shadow-indigo-200"
                >
                  Go to Dashboard
                </button>
              </div>
            </div>
          )}

        </div>

        {/* Footer */}
        {step === 'form' && (
          <div className="px-6 py-4 border-t border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-800/30 flex items-center justify-between gap-3 flex-shrink-0 rounded-b-2xl">
            <button
              onClick={() => setStep('billing-question')}
              className="text-sm text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 flex items-center gap-1.5 transition-colors"
            >
              <i className="fas fa-arrow-left text-xs" />
              Back
            </button>
            <button
              onClick={handleSubmit}
              disabled={submitting}
              className="flex items-center gap-2 px-6 py-2.5 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-60 text-white font-semibold rounded-xl transition-all text-sm shadow-md shadow-indigo-200"
            >
              {submitting ? (
                <><i className="fas fa-spinner animate-spin text-xs" /> Submitting...</>
              ) : (
                <><i className="fas fa-paper-plane text-xs" /> Submit</>
              )}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default OnboardingModal;
