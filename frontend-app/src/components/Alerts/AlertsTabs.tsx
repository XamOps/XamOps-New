/**
 * AlertsTabs Component
 *
 * Tab navigation for switching between CloudGuard and Grafana alerts
 */

import React from 'react';
import type { AlertTab } from '../../types/alerts';
import { AriaTabGroup } from '../ui';
import type { TabItem } from '../ui';

interface AlertsTabsProps {
  activeTab: AlertTab;
  onTabChange: (tab: AlertTab) => void;
}

const TABS: TabItem[] = [
  { id: 'cloudguard', label: 'CloudGuard Alerts', icon: 'fas fa-shield-alt' },
  { id: 'grafana',    label: 'Grafana Alerts',    icon: 'fas fa-chart-line' },
];

const AlertsTabs: React.FC<AlertsTabsProps> = ({ activeTab, onTabChange }) => (
  <div className="alerts-tabs-container">
    <AriaTabGroup
      tabs={TABS}
      selectedKey={activeTab}
      onSelectionChange={(key) => onTabChange(key as AlertTab)}
    />
  </div>
);

export default AlertsTabs;
