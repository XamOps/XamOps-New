/**
 * AccountSelector Component
 *
 * Dropdown for selecting cloud accounts with multi-account support
 * Converted from: _sidebar.html (account selector dropdown)
 */

import React from 'react';
import { useAccounts } from '../../contexts/AccountContext';
import { Account } from '../../types/account';
import clsx from 'clsx';

interface AccountSelectorProps {
  isOpen: boolean;
  onToggle: () => void;
  onClose: () => void;
}

const AccountSelector: React.FC<AccountSelectorProps> = ({ isOpen, onToggle, onClose }) => {
  const {
    selectedAccountDisplay,
    filteredAccounts,
    awsAccounts,
    gcpAccounts,
    azureAccounts,
    searchTerm,
    setSearchTerm,
    multiAccountMode,
    toggleMultiAccountMode,
    isDashboardPage,
    isLoading,
    toggleAccount,
    isAccountSelected,
    isAccountDisabled,
    selectAllAws,
    selectAllGcp,
    selectAllAzure,
    clearAllSelections,
    applySelection,
    getProviderIcon,
  } = useAccounts();

  React.useEffect(() => {
    if (!isOpen) return;

    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };

    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [isOpen, onClose]);

  const renderAccountGroup = (title: string, accounts: Account[], selectAllFn?: () => void) => {
    if (accounts.length === 0) return null;

    return (
      <section className="mb-4" aria-labelledby={`account-group-${title.replace(/\s+/g, '-').toLowerCase()}`}>
        <div className="flex items-center justify-between px-4 py-2 bg-gray-50">
          <h4 id={`account-group-${title.replace(/\s+/g, '-').toLowerCase()}`} className="text-xs font-semibold text-gray-700 uppercase tracking-wider">{title}</h4>
          {multiAccountMode && selectAllFn && (
            <button
              onClick={selectAllFn}
              className="text-xs text-indigo-600 hover:text-indigo-700 font-medium"
            >
              Select All
            </button>
          )}
        </div>
        <div className="max-h-48 overflow-y-auto" role="listbox">
          {accounts.map(account => {
            const selected = isAccountSelected(account.id);
            const disabled = isAccountDisabled(account.id);

            return (
              <button
                key={account.id}
                onClick={() => !disabled && toggleAccount(account.id)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    !disabled && toggleAccount(account.id);
                  }
                }}
                className={clsx(
                  'w-full flex items-center px-4 py-3 cursor-pointer transition-colors text-left',
                  disabled && 'opacity-50 cursor-not-allowed',
                  !disabled && 'hover:bg-gray-50',
                  selected && !disabled && 'bg-indigo-50'
                )}
                disabled={disabled}
                role="option"
                aria-selected={selected}
              >
                {multiAccountMode && isDashboardPage && (
                  <input
                    type="checkbox"
                    checked={selected}
                    disabled={disabled}
                    readOnly
                    className="mr-3 h-4 w-4 text-indigo-600 border-gray-300 rounded focus:ring-indigo-500"
                  />
                )}
                <img
                  src={getProviderIcon(account.provider)}
                  alt={account.provider}
                  className="w-6 h-6 mr-3"
                />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-900 truncate">{account.name}</p>
                  <p className="text-xs text-gray-500 truncate">{account.id}</p>
                </div>
                {selected && (
                  <svg className="w-5 h-5 text-indigo-600" fill="currentColor" viewBox="0 0 20 20">
                    <path
                      fillRule="evenodd"
                      d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                      clipRule="evenodd"
                    />
                  </svg>
                )}
              </button>
            );
          })}
        </div>
      </section>
    );
  };

  return (
    <div className="relative">
      {/* Account Selector Button */}
      <button
        onClick={onToggle}
        className="w-full flex items-center justify-between p-3 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500"
      >
        <div className="flex items-center flex-1 min-w-0">
          <div className="account-avatar w-10 h-10 rounded-full flex items-center justify-center text-white font-bold mr-3 flex-shrink-0">
            {selectedAccountDisplay.name === 'Select Accounts' ? (
              'X'
            ) : (
              <span>{selectedAccountDisplay.name.charAt(0).toUpperCase()}</span>
            )}
          </div>
          <div className="flex-1 min-w-0 text-left">
            <p className="text-sm font-semibold text-gray-900 truncate">
              {selectedAccountDisplay.name}
            </p>
            <p className="text-xs text-gray-500 truncate">{selectedAccountDisplay.id}</p>
          </div>
        </div>
        {isLoading ? (
          <svg
            className="animate-spin h-5 w-5 text-gray-400"
            xmlns="http://www.w3.org/2000/svg"
            fill="none"
            viewBox="0 0 24 24"
          >
            <circle
              className="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              strokeWidth="4"
            />
            <path
              className="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            />
          </svg>
        ) : (
          <svg
            className={clsx(
              'w-5 h-5 text-gray-400 transition-transform duration-200',
              isOpen && 'transform rotate-180'
            )}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        )}
      </button>

      {/* Dropdown Menu - Smooth Transition Wrapper */}
      <div className={clsx(
        'fixed inset-0 z-10 transition-opacity duration-200 ease-linear',
        isOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
      )}>
        {/* Backdrop */}
        <button
          className="absolute inset-0 bg-black/5 w-full h-full cursor-default"
          onClick={onClose}
          aria-label="Close Account Selector"
        />
      </div>

      <div className={clsx(
        'absolute left-0 right-0 mt-2 bg-white rounded-lg shadow-xl border border-gray-200 z-20 overflow-hidden grid transition-[grid-template-rows,opacity,transform] duration-200 ease-[cubic-bezier(0.2,1,0.2,1)] will-change-[grid-template-rows,opacity,transform]',
        isOpen
          ? 'grid-rows-[1fr] opacity-100 translate-y-0 scale-100'
          : 'grid-rows-[0fr] opacity-0 -translate-y-2 scale-95 pointer-events-none'
      )}>
        <div className="min-h-0 flex flex-col max-h-[600px]">
          {/* Multi-Account Mode Toggle (Dashboard Only) */}
          {isDashboardPage && (
            <div className="px-4 py-3 border-b border-gray-200 bg-gray-50">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700">Multi-Account Mode</span>
                <button
                  onClick={toggleMultiAccountMode}
                  className={clsx(
                    'toggle-switch focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2',
                    multiAccountMode && 'active'
                  )}
                  aria-pressed={multiAccountMode}
                  aria-label="Toggle Multi-Account Mode"
                >
                  <div className="toggle-slider" />
                </button>
              </div>
              <p className="text-xs text-gray-500 mt-1">
                Select up to 10 accounts from the same provider
              </p>
            </div>
          )}

          {/* Search Box */}
          <div className="p-4 border-b border-gray-200">
            <input
              type="text"
              placeholder="Search accounts..."
              value={searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
            />
          </div>

          {/* Account List */}
          <div className="flex-1 overflow-y-auto min-h-0">
            {filteredAccounts.length === 0 ? (
              <div className="px-4 py-8 text-center text-gray-500">
                <p className="text-sm">No accounts found</p>
              </div>
            ) : (
              <>
                {renderAccountGroup('AWS Accounts', awsAccounts, selectAllAws)}
                {renderAccountGroup('GCP Accounts', gcpAccounts, selectAllGcp)}
                {renderAccountGroup('Azure Accounts', azureAccounts, selectAllAzure)}
              </>
            )}
          </div>

          {/* Actions */}
          <div className="px-4 py-3 border-t border-gray-200 bg-gray-50 flex gap-2">
            {multiAccountMode && (
              <button
                onClick={clearAllSelections}
                className="flex-1 px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
              >
                Clear All
              </button>
            )}
            <button
              onClick={() => {
                applySelection();
                onClose();
              }}
              className="flex-1 px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 transition-colors"
            >
              Apply
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AccountSelector;
