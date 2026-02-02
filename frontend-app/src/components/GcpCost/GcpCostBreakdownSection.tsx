/**
 * GcpCostBreakdownSection Component
 * Displays cost breakdown with filters, donut chart, and detailed table
 */

import React, { useEffect, useRef, useMemo } from 'react';
import { Chart, ChartConfiguration, ArcElement, DoughnutController, Tooltip, Legend } from 'chart.js';
import type { GcpCostDataItem, GcpCostFilters, GcpGroupByType } from '../../types/gcpCost';

// Register Chart.js components
Chart.register(ArcElement, DoughnutController, Tooltip, Legend);

interface GcpCostBreakdownSectionProps {
  costData: GcpCostDataItem[];
  filters: GcpCostFilters;
  isLoading: boolean;
  onFiltersChange: (filters: Partial<GcpCostFilters>) => void;
}

const GcpCostBreakdownSection: React.FC<GcpCostBreakdownSectionProps> = ({
  costData,
  filters,
  isLoading,
  onFiltersChange,
}) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  // Format currency in INR
  const formatCurrency = (value: number): string => {
    return (value || 0).toLocaleString('en-IN', {
      style: 'currency',
      currency: 'INR',
      maximumFractionDigits: 2,
    });
  };

  const sortedData = useMemo(() =>
    [...costData].sort((a, b) => b.amount - a.amount),
    [costData]
  );

  const totalCost = useMemo(() =>
    costData.reduce((sum, item) => sum + item.amount, 0),
    [costData]
  );

  // Color palette
  const colors = [
    '#4285F4', '#34A853', '#FBBC04', '#EA4335', '#9AA0A6',
    '#5F6368', '#1A73E8', '#0D652D', '#E37400', '#B31412', '#fb923c'
  ];

  // Render chart
  useEffect(() => {
    if (!chartRef.current || costData.length === 0) return;

    // Destroy previous chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    const topN = 10;
    const chartData = sortedData.slice(0, topN);
    const otherData = sortedData.slice(topN);
    const labels = chartData.map(d => d.name || '(Not Set)');
    const data = chartData.map(d => d.amount);

    if (otherData.length > 0) {
      labels.push('Other');
      data.push(otherData.reduce((sum, item) => sum + item.amount, 0));
    }

    const config: ChartConfiguration<'doughnut'> = {
      type: 'doughnut',
      data: {
        labels,
        datasets: [{
          label: 'Cost',
          data,
          backgroundColor: colors,
          borderWidth: 0,
          hoverOffset: 8,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '60%',
        plugins: {
          legend: {
            position: 'bottom',
            labels: {
              boxWidth: 12,
              font: { size: 11, weight: '500' },
              usePointStyle: true,
              padding: 12,
              color: '#374151',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(31, 41, 55, 0.95)',
            titleColor: 'white',
            bodyColor: 'white',
            borderColor: 'rgba(66, 133, 244, 0.8)',
            borderWidth: 1,
            cornerRadius: 8,
            padding: 12,
            callbacks: {
              label: (context) => {
                const total = data.reduce((a, b) => a + b, 0);
                const pct = ((context.parsed / total) * 100).toFixed(1);
                return `${context.label}: ${formatCurrency(context.parsed)} (${pct}%)`;
              },
            },
          },
        },
      },
    };

    chartInstanceRef.current = new Chart(chartRef.current, config);

    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
      }
    };
  }, [costData, sortedData]);

  return (
    <div className="gcp-cost-breakdown-section">
      <div className="gcp-cost-breakdown-header">
        <div className="gcp-cost-breakdown-title">
          <div className="gcp-cost-breakdown-icon">
            <i className="fas fa-chart-pie"></i>
          </div>
          <div>
            <h2>Cost Breakdown</h2>
            <p>Spending analysis for the last 90 days</p>
          </div>
        </div>

        {/* Filters */}
        <div className="gcp-cost-filters">
          <span className="gcp-cost-filter-label">Group by:</span>
          <select
            value={filters.groupBy}
            onChange={(e) => onFiltersChange({ groupBy: e.target.value as GcpGroupByType })}
            className="gcp-cost-filter-select"
          >
            <option value="SERVICE">Service</option>
            <option value="PROJECT">Project</option>
            <option value="TAG">Tag Key</option>
          </select>

          {filters.groupBy === 'TAG' && (
            <input
              type="text"
              value={filters.tagKey}
              onChange={(e) => onFiltersChange({ tagKey: e.target.value })}
              placeholder="Enter tag key..."
              className="gcp-cost-filter-input"
            />
          )}
        </div>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="gcp-cost-breakdown-loading">
          <div className="gcp-cost-breakdown-chart-skeleton"></div>
          <div className="gcp-cost-breakdown-table-skeleton"></div>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && costData.length === 0 && (
        <div className="gcp-cost-empty-state">
          <div className="gcp-cost-empty-icon">
            <i className="fas fa-dollar-sign"></i>
          </div>
          <h3>No Cost Data Available</h3>
          <p>There is no cost data for the selected criteria.</p>
        </div>
      )}

      {/* Chart and Table */}
      {!isLoading && costData.length > 0 && (
        <div className="gcp-cost-breakdown-content">
          {/* Donut Chart */}
          <div className="gcp-cost-chart-container">
            <h3 className="gcp-cost-chart-title">
              <i className="fas fa-chart-pie"></i>
              Cost Distribution
            </h3>
            <div className="gcp-cost-chart-wrapper">
              <canvas ref={chartRef}></canvas>
            </div>
          </div>

          {/* Table */}
          <div className="gcp-cost-table-container">
            <div className="gcp-cost-table-header">
              <h3>
                <i className="fas fa-table"></i>
                Detailed Breakdown
              </h3>
            </div>
            <div className="gcp-cost-table-wrapper">
              <table className="gcp-cost-table">
                <thead>
                  <tr>
                    <th>
                      {filters.groupBy.charAt(0) + filters.groupBy.slice(1).toLowerCase()}
                    </th>
                    <th className="text-right">Cost</th>
                    <th className="text-right">% of Total</th>
                  </tr>
                </thead>
                <tbody>
                  {sortedData.map((item, index) => (
                    <tr key={item.name || `item-${index}`}>
                      <td>
                        <div className="gcp-cost-table-name">
                          <span
                            className="gcp-cost-color-dot"
                            style={{ backgroundColor: colors[index % colors.length] }}
                          ></span>
                          <span>{item.name || '(Not Set)'}</span>
                        </div>
                      </td>
                      <td className="text-right">
                        <span className="gcp-cost-amount">
                          {formatCurrency(item.amount)}
                        </span>
                      </td>
                      <td className="text-right">
                        <span className="gcp-cost-percentage">
                          {((item.amount / totalCost) * 100).toFixed(1)}%
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default GcpCostBreakdownSection;
