/**
 * EKS Tabs Component
 *
 * Tab navigation for switching between different views
 */

import React from 'react';
import { TabType } from '../../types/eksTypes';
import { AriaTabGroup } from '../ui';
import type { TabItem } from '../ui';

interface EksTabsProps {
  activeTab: TabType;
  onTabChange: (tab: TabType) => void;
}

const TABS: TabItem[] = [
  { id: 'overview',    label: 'Overview',    icon: 'fas fa-tachometer-alt' },
  { id: 'cost',        label: 'Cost',        icon: 'fas fa-dollar-sign' },
  { id: 'performance', label: 'Performance', icon: 'fas fa-chart-line' },
  { id: 'security',    label: 'Security',    icon: 'fas fa-shield-alt' },
  { id: 'workloads',   label: 'Workloads',   icon: 'fas fa-cubes' },
  { id: 'pods',        label: 'Pods',        icon: 'fas fa-box' },
  { id: 'containers',  label: 'Containers',  icon: 'fas fa-layer-group' },
  { id: 'nodes',       label: 'Nodes',       icon: 'fas fa-server' },
  { id: 'resources',   label: 'Resources',   icon: 'fas fa-layer-group' },
  { id: 'karpenter',   label: 'Karpenter',   icon: 'fas fa-rocket' },
  // { id: 'autoscaling', label: 'Autoscaling', icon: 'fas fa-expand-arrows-alt' },
];

const EksTabs: React.FC<EksTabsProps> = ({ activeTab, onTabChange }) => (
  <div className="mb-6 overflow-x-auto">
    <AriaTabGroup
      tabs={TABS}
      selectedKey={activeTab}
      onSelectionChange={(key) => onTabChange(key as TabType)}
      className="min-w-max"
    />
  </div>
);

export default EksTabs;
