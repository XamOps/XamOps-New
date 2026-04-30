/**
 * GKE Tabs Component
 */

import React from 'react';
import { GkeTabType } from '../../types/gkeTypes';
import { AriaTabGroup } from '../ui';
import type { TabItem } from '../ui';

interface GkeTabsProps {
  activeTab: GkeTabType;
  onTabChange: (tab: GkeTabType) => void;
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
  { id: 'nodepools',   label: 'Node Pools',  icon: 'fas fa-expand-arrows-alt' },
];

const GkeTabs: React.FC<GkeTabsProps> = ({ activeTab, onTabChange }) => (
  <div className="mb-6">
    <AriaTabGroup
      tabs={TABS}
      selectedKey={activeTab}
      onSelectionChange={(key) => onTabChange(key as GkeTabType)}
    />
  </div>
);

export default GkeTabs;
