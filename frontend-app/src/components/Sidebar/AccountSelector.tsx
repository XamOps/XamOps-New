import React, { useRef, useEffect } from 'react';
import { IoCloseCircle } from 'react-icons/io5';
import { useAccounts } from '../../contexts/AccountContext';
import { useTheme } from '../../contexts/ThemeContext';
import { Account, CloudProvider } from '../../types/account';
import clsx from 'clsx';

interface AccountSelectorProps {
  isOpen: boolean;
  onToggle: () => void;
  onClose: () => void;
}

const AccountSelector: React.FC<AccountSelectorProps> = ({ isOpen, onToggle, onClose }) => {
  const {
    filteredAccounts,
    awsAccounts,
    azureAccounts,
    gcpAccounts,
    isAccountSelected,
    isAccountDisabled,
    toggleAccount,
    selectAllAws,
    selectAllGcp,
    selectAllAzure,
    clearAllSelections,
    applySelection,
    toggleMultiAccountMode,
    getProviderIcon,
    multiAccountMode,
    searchTerm,
    setSearchTerm,
    isLoading,
    isDashboardPage,
    selectedAccountDisplay,
  } = useAccounts();

  const { theme } = useTheme();
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Close on Escape key
  useEffect(() => {
    if (!isOpen) return;
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [isOpen, onClose]);


  // Helper to get provider theme colors
  const getProviderColor = (provider?: CloudProvider) => {
    switch (provider) {
      case 'AWS': return 'bg-orange-500';
      case 'GCP': return 'bg-blue-500';
      case 'Azure': return 'bg-blue-600';
      default: return 'bg-gray-500';
    }
  };

  const renderAccountGroup = (title: string, groupAccounts: Account[], selectAllFn?: () => void) => {
    if (groupAccounts.length === 0) return null;

    return (
      <section className="mb-4">
        <div className={clsx(
          'flex items-center justify-between px-3 py-1.5 rounded-lg mb-1',
          theme === 'dark' ? 'bg-slate-800/40' : 'bg-gray-50'
        )}>
          <h4 className={clsx(
            'text-[10px] font-bold uppercase tracking-widest',
            theme === 'dark' ? 'text-slate-500' : 'text-gray-500'
          )}>{title}</h4>
          {multiAccountMode && isDashboardPage && selectAllFn && (
            <button
              onClick={(e) => { e.stopPropagation(); selectAllFn(); }}
              className="text-[10px] text-indigo-400 hover:text-indigo-300 font-bold uppercase tracking-wider transition-colors"
            >
              Select All
            </button>
          )}
        </div>
        <div className="space-y-0.5">
          {groupAccounts.map(account => {
            const selected = isAccountSelected(account.id);
            const disabled = isAccountDisabled(account.id);

            const itemButtonStyles = selected
              ? (theme === 'dark' ? 'bg-indigo-500/10 text-indigo-400 ring-1 ring-indigo-500/20' : 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-500/10')
              : (theme === 'dark' ? 'hover:bg-slate-800 text-slate-400 hover:text-slate-200' : 'hover:bg-slate-50 text-slate-600 hover:text-slate-900');

            return (
              <button
                key={account.id}
                onClick={() => !disabled && toggleAccount(account.id)}
                className={clsx(
                  'w-full flex items-center justify-between p-2 rounded-xl transition-all duration-200 group relative',
                  disabled && 'opacity-40 cursor-not-allowed',
                  !disabled && itemButtonStyles
                )}
                disabled={disabled}
              >
                <div className="flex items-center gap-3 overflow-hidden">
                  {multiAccountMode && isDashboardPage && (
                    <div className={clsx(
                      'w-4 h-4 rounded border transition-colors flex items-center justify-center',
                      selected ? 'bg-indigo-500 border-indigo-500 shadow-sm shadow-indigo-500/20' : (theme === 'dark' ? 'border-slate-700 bg-slate-800' : 'border-gray-300 bg-white')
                    )}>
                      {selected && <i className="fas fa-check text-[8px] text-white"></i>}
                    </div>
                  )}
                  <div className={clsx(
                    'w-7 h-7 rounded-lg flex items-center justify-center text-white text-[10px] transition-transform group-hover:scale-105 shadow-sm',
                    getProviderColor(account.provider)
                  )}>
                    <i className={clsx('fab', getProviderIcon(account.provider))}></i>
                  </div>
                  <div className="flex flex-col items-start overflow-hidden text-left">
                    <span className="text-xs font-semibold truncate w-full">
                      {account.name}
                    </span>
                    <span className="text-[10px] opacity-60 font-medium">
                      {account.id?.toString().substring(0, 4) ?? 'N/A'}...{account.id?.toString().slice(-4) ?? ''}
                    </span>
                  </div>
                </div>
                {selected && !multiAccountMode && (
                  <div className="w-4 h-4 bg-indigo-500 rounded-full flex items-center justify-center shadow-lg shadow-indigo-500/20">
                    <i className="fas fa-check text-[8px] text-white"></i>
                  </div>
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
      {/* Selector Button */}
      <button
        onClick={onToggle}
        className={clsx(
          'w-full flex items-center justify-between p-3 border rounded-xl transition-all duration-300 shadow-sm',
          isOpen
            ? (theme === 'dark' ? 'bg-slate-800 border-indigo-500/50 shadow-indigo-500/10' : 'bg-white border-indigo-200 shadow-indigo-500/10')
            : (theme === 'dark' ? 'bg-slate-800/40 border-slate-700/50 hover:bg-slate-800 hover:border-slate-600' : 'bg-white border-gray-100 hover:border-indigo-100 shadow-sm')
        )}
      >
        <div className="flex items-center gap-3 overflow-hidden">
          <div className={clsx(
            'flex-shrink-0 w-10 h-10 rounded-full flex items-center justify-center text-white font-bold text-sm shadow-sm transition-all duration-300',
            selectedAccountDisplay.provider ? getProviderColor(selectedAccountDisplay.provider) : (theme === 'dark' ? 'bg-indigo-600' : 'bg-[#704fe6]')
          )}>
            {selectedAccountDisplay.provider ? (
              <i className={clsx('fab', getProviderIcon(selectedAccountDisplay.provider))}></i>
            ) : (
              selectedAccountDisplay.name.charAt(0).toUpperCase()
            )}
          </div>
          <div className="flex flex-col items-start overflow-hidden text-left">
            <span className={clsx(
              'text-sm font-bold truncate w-full',
              theme === 'dark' ? 'text-slate-100' : 'text-slate-800'
            )}>
              {selectedAccountDisplay.name}
            </span>
            <span className="text-xs text-slate-400 font-medium">
              {selectedAccountDisplay.id.length > 20
                ? `${selectedAccountDisplay.id.substring(0, 8)}...${selectedAccountDisplay.id.slice(-4)}`
                : selectedAccountDisplay.id}
            </span>
          </div>
        </div>
        <i className={clsx(
          'fas fa-chevron-down text-[10px] transition-transform duration-300 mr-1',
          theme === 'dark' ? 'text-slate-500' : 'text-slate-400',
          isOpen ? 'rotate-180 text-indigo-500' : ''
        )}></i>
      </button>

      {/* Dropdown Menu */}
      {isOpen && (
        <div
          ref={dropdownRef}
          className={clsx(
            'absolute left-0 mt-3 w-full rounded-2xl shadow-2xl border z-[60] animate-in fade-in slide-in-from-top-4 duration-300 top-full',
            theme === 'dark' ? 'bg-slate-900 border-slate-800' : 'bg-white border-slate-100'
          )}
        >
          {/* Multi-Account Mode Toggle (Dashboard Only) */}
          {isDashboardPage && (
            <div className={clsx(
              'px-4 py-3 border-b flex items-center justify-between transition-colors',
              theme === 'dark' ? 'bg-slate-800/20 border-slate-800' : 'bg-indigo-50/30 border-slate-50'
            )}>
              <div className="flex flex-col">
                <span className={clsx(
                  'text-xs font-bold leading-none',
                  theme === 'dark' ? 'text-slate-300' : 'text-slate-700'
                )}>Multi-Account Mode</span>
                <span className="text-[9px] text-slate-400 mt-1">Select up to 10 accounts</span>
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); toggleMultiAccountMode(); }}
                className={clsx(
                  'w-8 h-4 rounded-full relative transition-colors duration-200',
                  multiAccountMode ? 'bg-indigo-600' : (theme === 'dark' ? 'bg-slate-700' : 'bg-gray-300')
                )}
              >
                <div className={clsx(
                  'absolute top-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-200',
                  multiAccountMode ? 'left-4.5' : 'left-0.5'
                )}></div>
              </button>
            </div>
          )}

          {/* Search Box */}
          <div className={clsx(
            'p-4 border-b transition-colors',
            theme === 'dark' ? 'border-slate-800' : 'border-slate-50'
          )}>
            <div className="relative group">
              <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-[10px] transition-colors group-focus-within:text-indigo-500"></i>
              <input
                type="text"
                placeholder="Search accounts..."
                aria-label="Search accounts"
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
                className={clsx(
                  'w-full pl-9 pr-9 py-2 border rounded-xl text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500/20 transition-all',
                  theme === 'dark' ? 'bg-slate-800 border-slate-700 text-slate-200 placeholder-slate-600 focus:border-indigo-500/50' : 'bg-slate-50 border-gray-100 text-slate-700 placeholder-slate-400 focus:border-indigo-300'
                )}
                autoFocus
              />
              {searchTerm && (
                <button
                  type="button"
                  onClick={() => setSearchTerm('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-indigo-500 transition-colors p-1 flex items-center justify-center"
                  aria-label="Clear account search"
                >
                  <IoCloseCircle className="text-lg" />
                </button>
              )}
            </div>
          </div>

          {/* Global Actions (Multi-Account Mode) */}
          {multiAccountMode && isDashboardPage && (
            <div className="px-4 py-2 border-b border-gray-200 bg-gray-50 flex gap-2">
              <button
                onClick={clearAllSelections}
                className="w-full px-3 py-1.5 text-xs font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 transition-colors"
              >
                Clear All
              </button>
            </div>
          )}

          {/* Account Lists */}
          <div className="max-h-72 overflow-y-auto p-2 custom-scrollbar">
            {isLoading ? (
              <div className="py-12 flex flex-col items-center justify-center gap-3">
                <div className="w-8 h-8 border-2 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin"></div>
                <span className="text-xs text-slate-400 font-medium">Loading accounts...</span>
              </div>
            ) : filteredAccounts.length === 0 ? (
              <div className="py-12 flex flex-col items-center justify-center gap-2">
                <i className="fas fa-search text-slate-200 dark:text-slate-800 text-2xl"></i>
                <p className="text-xs text-slate-400">No accounts found</p>
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
              onClick={(e) => {
                e.stopPropagation();
                applySelection();
                onClose();
              }}
              className="flex-1 px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 transition-colors"
            >
              Apply
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default AccountSelector;
