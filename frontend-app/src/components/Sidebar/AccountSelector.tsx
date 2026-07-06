import React, { useRef, useEffect } from 'react';
import { useAccounts } from '../../contexts/AccountContext';
import { Account, CloudProvider } from '../../types/account';
import { useTheme } from '../../contexts/ThemeContext';
import clsx from 'clsx';

interface AccountSelectorProps {
  isOpen: boolean;
  onToggle: () => void;
  onClose: () => void;
}

const providerDot: Record<CloudProvider, string> = {
  AWS: '#FF9900',
  GCP: '#34A853',
  Azure: '#0078D4',
};

const AccountSelector: React.FC<AccountSelectorProps> = ({ isOpen, onToggle, onClose }) => {
  const { theme } = useTheme();
  const dark = theme === 'dark';
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
    multiAccountMode,
    searchTerm,
    setSearchTerm,
    isLoading,
    isDashboardPage,
    selectedAccountDisplay,
  } = useAccounts();

  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [isOpen, onClose]);

  const renderAccountGroup = (title: string, groupAccounts: Account[], selectAllFn?: () => void) => {
    if (groupAccounts.length === 0) return null;

    return (
      <section className="mb-3">
        <div className="flex items-center justify-between px-2 py-1 mb-0.5">
          <h4 className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)]">{title}</h4>
          {multiAccountMode && isDashboardPage && selectAllFn && (
            <button
              onClick={(e) => { e.stopPropagation(); selectAllFn(); }}
              className="text-[10px] text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] font-semibold uppercase tracking-wider transition-colors"
            >
              Select All
            </button>
          )}
        </div>
        <div className="space-y-0.5">
          {groupAccounts.map(account => {
            const selected = isAccountSelected(account.id);
            const disabled = isAccountDisabled(account.id);
            const dot = providerDot[account.provider as CloudProvider] || '#9ca3af';

            return (
              <button
                key={account.id}
                onClick={() => !disabled && toggleAccount(account.id)}
                className={clsx(
                  'w-full flex items-center justify-between px-2 py-2 rounded-lg transition-colors duration-150 group',
                  disabled && 'opacity-40 cursor-not-allowed',
                  !disabled && (selected
                    ? 'bg-[rgba(0,0,0,0.06)] text-[rgba(0,0,0,0.88)]'
                    : 'text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.88)]')
                )}
                disabled={disabled}
              >
                <div className="flex items-center gap-2.5 overflow-hidden">
                  {multiAccountMode && isDashboardPage && (
                    <div className={clsx(
                      'w-3.5 h-3.5 rounded border flex-shrink-0 flex items-center justify-center transition-colors',
                      selected
                        ? 'bg-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.88)]'
                        : 'border-[rgba(0,0,0,0.2)] bg-white'
                    )}>
                      {selected && (
                        <svg className="w-2 h-2 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </div>
                  )}
                  {/* Provider dot */}
                  <span
                    className="w-1.5 h-1.5 rounded-full flex-shrink-0"
                    style={{ backgroundColor: dot }}
                  />
                  <div className="flex flex-col items-start overflow-hidden text-left">
                    <span className="text-[12px] font-medium truncate w-full leading-tight">
                      {account.name}
                    </span>
                    <span className="text-[10px] text-[rgba(0,0,0,0.35)] font-medium leading-tight">
                      {account.id?.toString().substring(0, 4) ?? 'N/A'}...{account.id?.toString().slice(-4) ?? ''}
                    </span>
                  </div>
                </div>
                {selected && !multiAccountMode && (
                  <svg className="w-3 h-3 text-[rgba(0,0,0,0.5)] flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
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
      {/* Selector Button */}
      <button
        onClick={onToggle}
        className={clsx(
          'w-full flex items-center justify-between px-2.5 py-2 border rounded-lg transition-colors duration-150',
          dark
            ? isOpen
              ? 'bg-[rgba(255,255,255,0.1)] border-[rgba(255,255,255,0.15)]'
              : 'bg-[rgba(255,255,255,0.06)] border-[rgba(255,255,255,0.08)] hover:bg-[rgba(255,255,255,0.1)] hover:border-[rgba(255,255,255,0.15)]'
            : isOpen
              ? 'bg-[rgba(0,0,0,0.04)] border-[rgba(0,0,0,0.12)]'
              : 'bg-white border-[rgba(0,0,0,0.08)] hover:bg-[rgba(0,0,0,0.03)] hover:border-[rgba(0,0,0,0.12)]'
        )}
      >
        <div className="flex items-center gap-2.5 overflow-hidden">
          {/* Letter avatar */}
          <div className={clsx(
            'flex-shrink-0 w-7 h-7 rounded-md flex items-center justify-center text-[11px] font-semibold',
            dark ? 'bg-[rgba(255,255,255,0.15)] text-white' : 'bg-[rgba(0,0,0,0.88)] text-white'
          )}>
            {selectedAccountDisplay.provider ? (
              <i className={clsx('fab text-[10px]', (() => {
                switch (selectedAccountDisplay.provider) {
                  case 'AWS': return 'fa-aws';
                  case 'GCP': return 'fa-google';
                  case 'Azure': return 'fa-microsoft';
                  default: return 'fa-cloud';
                }
              })())}></i>
            ) : (
              selectedAccountDisplay.name.charAt(0).toUpperCase()
            )}
          </div>
          <div className="flex flex-col items-start overflow-hidden text-left">
            <span className={clsx('text-[12px] font-semibold truncate w-full leading-tight', dark ? 'text-white' : 'text-[rgba(0,0,0,0.88)]')}>
              {selectedAccountDisplay.name}
            </span>
            <span className={clsx('text-[10px] font-medium leading-tight', dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]')}>
              {selectedAccountDisplay.id.length > 20
                ? `${selectedAccountDisplay.id.substring(0, 8)}...${selectedAccountDisplay.id.slice(-4)}`
                : selectedAccountDisplay.id}
            </span>
          </div>
        </div>
        <svg
          className={clsx('w-3 h-3 flex-shrink-0 transition-transform duration-200', dark ? 'text-[rgba(255,255,255,0.3)]' : 'text-[rgba(0,0,0,0.35)]', isOpen && 'rotate-180')}
          fill="none" stroke="currentColor" viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Dropdown */}
      {isOpen && (
        <div
          ref={dropdownRef}
          className="absolute left-0 mt-1.5 w-full rounded-xl shadow-lg border border-[rgba(0,0,0,0.08)] bg-white z-[60] top-full"
        >
          {/* Multi-Account Mode Toggle */}
          {isDashboardPage && (
            <div className="px-3 py-2.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
              <div className="flex flex-col">
                <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.88)] leading-none">Multi-Account</span>
                <span className="text-[10px] text-[rgba(0,0,0,0.4)] mt-0.5">Up to 10 accounts</span>
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); toggleMultiAccountMode(); }}
                className={clsx(
                  'w-8 h-4 rounded-full relative transition-colors duration-200 flex-shrink-0',
                  multiAccountMode ? 'bg-[rgba(0,0,0,0.88)]' : 'bg-[rgba(0,0,0,0.15)]'
                )}
              >
                <div className={clsx(
                  'absolute top-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-200',
                  multiAccountMode ? 'translate-x-[18px]' : 'translate-x-0.5'
                )}></div>
              </button>
            </div>
          )}

          {/* Search */}
          <div className="p-2.5 border-b border-[rgba(0,0,0,0.06)]">
            <div className="relative">
              <svg className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3 h-3 text-[rgba(0,0,0,0.3)]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
              <input
                type="text"
                placeholder="Search accounts..."
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
                className="w-full pl-8 pr-3 py-1.5 border border-[rgba(0,0,0,0.08)] rounded-md text-[12px] bg-[rgba(0,0,0,0.02)] text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
                autoFocus
              />
            </div>
          </div>

          {/* Account Lists */}
          <div className="max-h-64 overflow-y-auto inventory-hide-scrollbar p-2">
            {isLoading ? (
              <div className="py-8 flex flex-col items-center justify-center gap-2">
                <div className="w-5 h-5 border-2 border-[rgba(0,0,0,0.1)] border-t-[rgba(0,0,0,0.5)] rounded-full animate-spin"></div>
                <span className="text-[11px] text-[rgba(0,0,0,0.4)]">Loading accounts...</span>
              </div>
            ) : filteredAccounts.length === 0 ? (
              <div className="py-8 flex flex-col items-center justify-center gap-1.5">
                <p className="text-[12px] text-[rgba(0,0,0,0.35)]">No accounts found</p>
              </div>
            ) : (
              <>
                {renderAccountGroup('AWS', awsAccounts, selectAllAws)}
                {renderAccountGroup('GCP', gcpAccounts, selectAllGcp)}
                {renderAccountGroup('Azure', azureAccounts, selectAllAzure)}
              </>
            )}
          </div>

          {/* Footer actions */}
          <div className="px-2.5 py-2.5 border-t border-[rgba(0,0,0,0.06)] flex gap-2">
            {multiAccountMode && (
              <button
                onClick={clearAllSelections}
                className="flex-1 px-3 py-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.6)] bg-white border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.03)] transition-colors"
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
              className="flex-1 px-3 py-1.5 text-[12px] font-medium text-white bg-[rgba(0,0,0,0.88)] rounded-md hover:bg-black transition-colors"
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
