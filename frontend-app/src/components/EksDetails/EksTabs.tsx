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
      <div className="tab-container flex flex-wrap justify-start gap-2 border-b-2 border-gray-200 pb-0">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => onTabChange(tab.id)}
            className={`tab-button px-5 py-3 font-semibold text-sm rounded-t-lg border-b-3 transition-all ${
              activeTab === tab.id
                ? 'bg-gradient-to-b from-indigo-600 to-indigo-700 text-white border-indigo-800 shadow-lg'
                : 'bg-transparent text-gray-600 border-transparent hover:bg-indigo-50 hover:text-indigo-600 hover:border-indigo-200'
            }`}
          >
            <i className={`fas ${tab.icon} mr-2`}></i>
            {tab.label}
          </button>
        ))}
      </div>
    </div>
  );
};

export default EksTabs;
