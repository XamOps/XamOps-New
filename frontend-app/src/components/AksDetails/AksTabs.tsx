/**
 * AKS Tabs Component
 * Mirrors GkeTabs.tsx exactly — same 8 tabs.
 */

import React from 'react';
import { GkeTabType } from '../../types/gkeTypes';

interface AksTabsProps {
  activeTab: GkeTabType;
  onTabChange: (tab: GkeTabType) => void;
}

const tabs: { id: GkeTabType; label: string; icon: string }[] = [
  { id: 'overview',     label: 'Overview',     icon: 'fa-tachometer-alt' },
  { id: 'cost',         label: 'Cost',         icon: 'fa-dollar-sign' },
  { id: 'performance',  label: 'Performance',  icon: 'fa-chart-line' },
  { id: 'security',     label: 'Security',     icon: 'fa-shield-alt' },
  { id: 'workloads',    label: 'Workloads',    icon: 'fa-cubes' },
  { id: 'pods',         label: 'Pods',         icon: 'fa-box' },
  { id: 'containers',   label: 'Containers',   icon: 'fa-layer-group' },
  { id: 'nodes',        label: 'Nodes',        icon: 'fa-server' },
];

const AksTabs: React.FC<AksTabsProps> = ({ activeTab, onTabChange }) => (
  <div className="mb-6">
    <div className="flex flex-wrap justify-start gap-2 border-b border-gray-200 pb-0">
      {tabs.map(tab => {
        const isActive = activeTab === tab.id;
        return (
          <button
            key={tab.id}
            onClick={() => onTabChange(tab.id)}
            className={`relative px-5 py-3 font-semibold text-sm transition-all duration-200 rounded-t-lg ${
              isActive
                ? 'text-blue-700 bg-white border-b-2 border-blue-600 shadow-sm z-10'
                : 'text-gray-600 bg-transparent hover:bg-gray-100 hover:text-gray-900 border-b-2 border-transparent'
            }`}
          >
            <i className={`fas ${tab.icon} mr-2 ${isActive ? 'text-blue-600' : 'text-gray-400'}`}></i>
            {tab.label}
            {isActive && (
              <span className="absolute bottom-[-2px] left-0 w-full h-[2px] bg-blue-600"></span>
            )}
          </button>
        );
      })}
    </div>
  </div>
);

export default AksTabs;
