/**
 * GcpCostKPICards Component
 * Displays key metrics: Total Spend, Categories, Top Spender
 */

import React, { useMemo } from 'react';
import type { GcpCostDataItem } from '../../types/gcpCost';

interface GcpCostKPICardsProps {
  costData: GcpCostDataItem[];
  isLoading: boolean;
}

const GcpCostKPICards: React.FC<GcpCostKPICardsProps> = ({ costData, isLoading }) => {
  // Format currency in INR
  const formatCurrency = (value: number): string => {
    return (value || 0).toLocaleString('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 2,
    });
  };

  // Calculate metrics
  const metrics = useMemo(() => {
    const totalCost = costData.reduce((sum, item) => sum + item.amount, 0);
    const sortedData = [...costData].sort((a, b) => b.amount - a.amount);
    const topSpender = sortedData.length > 0 ? sortedData[0] : { name: 'N/A', amount: 0 };

    return {
      totalCost,
      categoriesCount: costData.length,
      topSpender,
    };
  }, [costData]);

  if (isLoading) {
    return (
      <div className="gcp-cost-kpi-grid">
        {[1, 2, 3].map((i) => (
          <div key={i} className="gcp-cost-kpi-card gcp-cost-kpi-skeleton">
            <div className="skeleton-line skeleton-sm"></div>
            <div className="skeleton-line skeleton-lg"></div>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="gcp-cost-kpi-grid">
      {/* Total Spend Card */}
      <div className="gcp-cost-kpi-card">
        <div className="gcp-cost-kpi-icon gcp-cost-kpi-icon-green">
          <i className="fas fa-wallet"></i>
        </div>
        <div className="gcp-cost-kpi-content">
          <p className="gcp-cost-kpi-label">Total Spend (Last 90 Days)</p>
          <p className="gcp-cost-kpi-value gcp-cost-gradient-text">
            {formatCurrency(metrics.totalCost)}
          </p>
        </div>
      </div>

      {/* Categories Card */}
      <div className="gcp-cost-kpi-card">
        <div className="gcp-cost-kpi-icon gcp-cost-kpi-icon-blue">
          <i className="fas fa-layer-group"></i>
        </div>
        <div className="gcp-cost-kpi-content">
          <p className="gcp-cost-kpi-label">Categories</p>
          <p className="gcp-cost-kpi-value gcp-cost-gradient-text">
            {metrics.categoriesCount}
          </p>
        </div>
      </div>

      {/* Top Spender Card */}
      <div className="gcp-cost-kpi-card">
        <div className="gcp-cost-kpi-icon gcp-cost-kpi-icon-purple">
          <i className="fas fa-trophy"></i>
        </div>
        <div className="gcp-cost-kpi-content">
          <p className="gcp-cost-kpi-label">Top Spender</p>
          <p className="gcp-cost-kpi-value-name" title={metrics.topSpender.name}>
            {metrics.topSpender.name}
          </p>
          <p className="gcp-cost-kpi-value-amount gcp-cost-gradient-text">
            {formatCurrency(metrics.topSpender.amount)}
          </p>
        </div>
      </div>
    </div>
  );
};

export default GcpCostKPICards;
