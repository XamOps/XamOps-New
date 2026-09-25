/**
 * AlertsTabs Component
 *
 * Tab navigation for switching between CloudGuard and K8s alerts
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { AlertTab } from '../../types/alerts';

interface AlertsTabsProps {
  activeTab: AlertTab;
  onTabChange: (tab: AlertTab) => void;
}

const AlertsTabs: React.FC<AlertsTabsProps> = ({ activeTab, onTabChange }) => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm mb-5">
    <div className="border-b border-[rgba(0,0,0,0.06)] px-5">
      <nav className="flex gap-6">
        <button
          onClick={() => onTabChange('cloudguard')}
          className={`flex items-center gap-2 py-3.5 px-1 text-[13px] font-medium border-b-2 transition-colors ${
            activeTab === 'cloudguard'
              ? 'border-indigo-600 text-indigo-600'
              : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
          }`}
        >
          <Icon icon="mdi:shield-check-outline" width={15} />
          CloudGuard Alerts
        </button>
        <button
          onClick={() => onTabChange('k8s')}
          className={`flex items-center gap-2 py-3.5 px-1 text-[13px] font-medium border-b-2 transition-colors ${
            activeTab === 'k8s'
              ? 'border-indigo-600 text-indigo-600'
              : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)]'
          }`}
        >
          <Icon icon="mdi:kubernetes" width={15} />
          K8s Alerts
        </button>
      </nav>
    </div>
  </div>
);

export default AlertsTabs;
