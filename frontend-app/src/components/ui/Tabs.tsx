/**
 * Accessible Tabs primitive built on react-aria-components.
 * Provides: arrow key navigation, tablist/tab/tabpanel roles,
 * roving tabindex, keyboard Home/End support.
 *
 * Usage:
 *   <AriaTabGroup
 *     tabs={[
 *       { id: 'dashboard', label: 'Dashboard' },
 *       { id: 'history',   label: 'History',  icon: 'fas fa-history' },
 *     ]}
 *     selectedKey={activeTab}
 *     onSelectionChange={(key) => setActiveTab(key as string)}
 *   />
 *
 *   // Render panels separately with your existing content:
 *   {activeTab === 'dashboard' && <DashboardContent />}
 */

import React from 'react';
import { Tabs, TabList, Tab } from 'react-aria-components';
import { Icon } from '@iconify/react';

export interface TabItem {
  id: string;
  label: React.ReactNode;
  /** Icon: either an iconify id (e.g. "mdi:chart-bar") or a legacy FA class (e.g. "fas fa-chart-bar") */
  icon?: string;
  /** Disable this tab */
  disabled?: boolean;
}

interface AriaTabGroupProps {
  tabs: TabItem[];
  selectedKey: string;
  onSelectionChange: (key: string) => void;
  /** Extra class on the tablist container */
  className?: string;
  /**
   * Visual style variant.
   *
   * `neutral` is the black/greyscale house style used by pages whose colour budget is
   * already committed to meaning (WAR spends red/amber/emerald on security status), so
   * an indigo accent there would read as a sixth status rather than as navigation.
   */
  variant?: 'underline' | 'pill' | 'neutral';
}

export function AriaTabGroup({
  tabs,
  selectedKey,
  onSelectionChange,
  className = '',
  variant = 'underline',
}: AriaTabGroupProps) {
  return (
    <Tabs
      selectedKey={selectedKey}
      onSelectionChange={(key) => onSelectionChange(String(key))}
    >
      <TabList
        aria-label="Page sections"
        className={
          variant === 'pill'
            ? `flex gap-1 p-1 bg-slate-100 rounded-xl ${className}`
            : variant === 'neutral'
              ? `flex gap-1 border-b border-[#e5e7eb] overflow-x-auto ${className}`
              : `flex gap-1 border-b border-gray-200 ${className}`
        }
      >
        {tabs.map((tab) => (
          <Tab
            key={tab.id}
            id={tab.id}
            isDisabled={tab.disabled}
            className={({ isSelected, isFocusVisible }) =>
              variant === 'pill'
                ? [
                    'flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all outline-none cursor-pointer',
                    isSelected
                      ? 'bg-white text-indigo-700 shadow-sm'
                      : 'text-slate-500 hover:text-slate-700',
                    isFocusVisible ? 'ring-2 ring-indigo-500 ring-offset-1' : '',
                    tab.disabled ? 'opacity-40 cursor-not-allowed' : '',
                  ].join(' ')
              : variant === 'neutral'
                ? [
                    'flex items-center gap-2 px-3.5 py-2.5 text-[12.5px] font-semibold',
                    'border-b-2 transition-all outline-none cursor-pointer -mb-px whitespace-nowrap',
                    isSelected
                      ? 'border-[rgba(0,0,0,0.88)] text-[rgba(0,0,0,0.88)]'
                      : 'border-transparent text-[rgba(0,0,0,0.55)] hover:text-[rgba(0,0,0,0.88)]'
                        + ' hover:border-[rgba(0,0,0,0.25)]',
                    // Ring rather than an outline so the 2px underline is not clipped.
                    isFocusVisible ? 'ring-2 ring-inset ring-[rgba(0,0,0,0.55)]' : '',
                    tab.disabled ? 'opacity-40 cursor-not-allowed' : '',
                  ].join(' ')
                : [
                    'flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition-all outline-none cursor-pointer -mb-px',
                    isSelected
                      ? 'border-indigo-600 text-indigo-700'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300',
                    isFocusVisible ? 'ring-2 ring-indigo-500 ring-inset' : '',
                    tab.disabled ? 'opacity-40 cursor-not-allowed' : '',
                  ].join(' ')
            }
          >
            {tab.icon && (
              tab.icon.startsWith('mdi:') || tab.icon.includes(':')
                ? <Icon icon={tab.icon} width={14} />
                : <i className={`${tab.icon} text-xs`} />
            )}
            {tab.label}
          </Tab>
        ))}
      </TabList>
    </Tabs>
  );
}
