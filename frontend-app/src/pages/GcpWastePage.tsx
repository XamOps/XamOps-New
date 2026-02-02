/**
 * GCP Waste Management Page
 *
 * Converted from: gcp_waste.html
 *
 * Features:
 * - KPI cards (Potential Savings, Wasted Resources, Savings by Category)
 * - Grouped resource display by type
 * - Expandable resource groups
 * - Real-time data fetching from backend
 * - Resource scanning with force refresh
 * - LocalStorage caching for 24 hours
 * - Improved UI with modern GCP styling
 */

import React, { useState, useMemo } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpWasteData } from '../hooks/useGcpWasteData';
import type { GcpWasteGroup } from '../types/gcpWaste';

// Component imports
import {
  GcpWasteHeader,
  GcpWasteKPICards,
  GcpWasteResourceGroup,
} from '../components/GcpWaste';

// Import styles
import '../styles/gcpWaste.css';

const GcpWastePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Get waste data using custom hook
  const { wasteItems, isLoading, isRefreshing, loadWasteReport } = useGcpWasteData({
    selectedAccountId,
  });

  // Track which groups are expanded (all open by default)
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  // Group resources by type
  const groupedResources = useMemo((): GcpWasteGroup[] => {
    if (!Array.isArray(wasteItems)) return [];

    const groups = wasteItems.reduce((acc, resource) => {
      const key = resource.type || 'Other';
      if (!acc[key]) {
        acc[key] = {
          type: key,
          resources: [],
          open: true, // All groups open by default
          totalSavings: 0,
        };
      }
      acc[key].resources.push(resource);
      acc[key].totalSavings += resource.monthlySavings || 0;
      return acc;
    }, {} as Record<string, GcpWasteGroup>);

    // Sort by total savings (highest first)
    return Object.values(groups).sort((a, b) => b.totalSavings - a.totalSavings);
  }, [wasteItems]);

  // Initialize expanded groups when data loads
  React.useEffect(() => {
    if (groupedResources.length > 0 && expandedGroups.size === 0) {
      setExpandedGroups(new Set(groupedResources.map(g => g.type)));
    }
  }, [groupedResources]);

  // Toggle group expansion
  const toggleGroup = (type: string) => {
    setExpandedGroups(prev => {
      const newSet = new Set(prev);
      if (newSet.has(type)) {
        newSet.delete(type);
      } else {
        newSet.add(type);
      }
      return newSet;
    });
  };

  // Refresh handler
  const handleRefresh = () => {
    loadWasteReport(true);
  };

  return (
    <main className="gcp-waste-page">
      <GcpWasteHeader isRefreshing={isRefreshing} onRefresh={handleRefresh} />

      <div className="gcp-waste-content">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="gcp-waste-no-account">
            <div className="gcp-waste-no-account-icon">
              <i className="fas fa-recycle"></i>
            </div>
            <h4>No Account Selected</h4>
            <p>Please select a GCP account from the sidebar to view waste management data.</p>
          </div>
        )}

        {/* Loading State */}
        {selectedAccountId && isLoading && wasteItems.length === 0 && (
          <div className="gcp-waste-loading">
            <div className="gcp-waste-kpi-cards">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="gcp-waste-skeleton gcp-waste-skeleton-card"></div>
              ))}
            </div>
            <div className="gcp-waste-loading-groups">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="gcp-waste-skeleton gcp-waste-skeleton-group"></div>
              ))}
            </div>
          </div>
        )}

        {/* Waste Data Content */}
        {selectedAccountId && !isLoading && (
          <>
            {/* KPI Cards */}
            <GcpWasteKPICards wasteItems={wasteItems} isLoading={isLoading} />

            {/* No Waste Found State */}
            {groupedResources.length === 0 && (
              <div className="gcp-waste-no-waste">
                <div className="gcp-waste-no-waste-icon">
                  <i className="fas fa-leaf"></i>
                </div>
                <h3>No Waste Found!</h3>
                <p>Your GCP environment is looking clean and optimized.</p>
              </div>
            )}

            {/* Resource Groups */}
            <div className="gcp-waste-groups">
              {groupedResources.map(group => (
                <GcpWasteResourceGroup
                  key={group.type}
                  type={group.type}
                  resources={group.resources}
                  isOpen={expandedGroups.has(group.type)}
                  onToggle={() => toggleGroup(group.type)}
                />
              ))}
            </div>
          </>
        )}
      </div>
    </main>
  );
};

export default GcpWastePage;
