/**
 * EKS Tabs Component
 *
 * Tab navigation for switching between different views
 */

import React from 'react';
import { TabType } from '../../types/eksTypes';

interface EksTabsProps {
  activeTab: TabType;
  onTabChange: (tab: TabType) => void;
}

interface TabConfig {
  id: TabType;
  label: string;
  icon: string;
}

const tabs: TabConfig[] = [
  { id: 'overview', label: 'Overview', icon: 'fa-tachometer-alt' },
  { id: 'cost', label: 'Cost', icon: 'fa-dollar-sign' },
  { id: 'performance', label: 'Performance', icon: 'fa-chart-line' },
  { id: 'security', label: 'Security', icon: 'fa-shield-alt' },
  { id: 'workloads', label: 'Workloads', icon: 'fa-cubes' },
  { id: 'pods', label: 'Pods', icon: 'fa-box' },
  { id: 'containers', label: 'Containers', icon: 'fa-layer-group' },
  { id: 'nodes', label: 'Nodes', icon: 'fa-server' },
  { id: 'karpenter', label: 'Karpenter', icon: 'fa-rocket' }
];

const EksTabs: React.FC<EksTabsProps> = ({ activeTab, onTabChange }) => {
  return (
    <div className="mb-6">
      <div className="tab-container flex flex-wrap justify-start gap-2 border-b border-gray-200 pb-0">
        {tabs.map(tab => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => onTabChange(tab.id)}
              className={`tab-button relative px-5 py-3 font-semibold text-sm transition-all duration-200 rounded-t-lg ${isActive
                ? 'text-purple-700 bg-white border-b-2 border-purple-600 shadow-sm z-10'
                : 'text-gray-600 bg-transparent hover:bg-gray-100 hover:text-gray-900 border-b-2 border-transparent'
                }`}
            >
              <i className={`fas ${tab.icon} mr-2 ${isActive ? 'text-purple-600' : 'text-gray-400'}`}></i>
              {tab.label}
              {isActive && (
                <span className="absolute bottom-[-2px] left-0 w-full h-[2px] bg-purple-600"></span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default EksTabs;
