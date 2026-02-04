/**
 * GcpWasteKPICards Component
 * Displays KPI metrics and savings chart for GCP waste
 */

import React, { useEffect, useRef } from 'react';
import Chart from 'chart.js/auto';
import type { GcpWasteResource } from '../../types/gcpWaste';

interface GcpWasteKPICardsProps {
  wasteItems: GcpWasteResource[];
  isLoading: boolean;
}

const GcpWasteKPICards: React.FC<GcpWasteKPICardsProps> = ({ wasteItems, isLoading }) => {
  const chartRef = useRef<HTMLCanvasElement>(null);
  const chartInstanceRef = useRef<Chart<'doughnut'> | null>(null);

  // Calculate total savings
  const totalSavings = wasteItems.reduce((sum, item) => sum + (item.monthlySavings || 0), 0);

  // Format currency
  const formatCurrency = (value: number): string => {
    if (value === null || value === undefined || isNaN(value)) return '₹0.00';
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  // Render savings chart
  useEffect(() => {
    if (!chartRef.current || wasteItems.length === 0) return;

    // Calculate savings by category
    const savingsByCategory = wasteItems.reduce((acc, resource) => {
      const type = resource.type || 'Other';
      acc[type] = (acc[type] || 0) + (resource.monthlySavings || 0);
      return acc;
    }, {} as Record<string, number>);

    const labels = Object.keys(savingsByCategory);
    const data = Object.values(savingsByCategory);

    // Destroy previous chart
    if (chartInstanceRef.current) {
      chartInstanceRef.current.destroy();
    }

    // Create new chart
    const ctx = chartRef.current.getContext('2d');
    if (ctx) {
      chartInstanceRef.current = new Chart(ctx, {
        type: 'doughnut',
        data: {
          labels: labels,
          datasets: [
            {
              data: data,
              backgroundColor: [
                '#EA4335',
                '#FBBC05',
                '#34A853',
                '#4285F4',
                '#9333ea',
                '#ec4899',
              ],
              borderWidth: 0,
              hoverOffset: 6,
            },
          ],
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: {
              position: 'bottom',
              labels: {
                boxWidth: 12,
                font: { size: 11 },
              },
            },
            tooltip: {
              callbacks: {
                label: function (context) {
                  const label = context.label || '';
                  const value = context.parsed || 0;
                  return (
                    label +
                    ': ' +
                    new Intl.NumberFormat('en-IN', {
                      style: 'currency',
                      currency: 'INR',
                    }).format(value)
                  );
                },
              },
            },
          },
        },
      });
    }

    return () => {
      if (chartInstanceRef.current) {
        chartInstanceRef.current.destroy();
      }
    };
  }, [wasteItems]);

  return (
    <div className="gcp-waste-kpi-cards">
      {/* Potential Savings Card */}
      <div className="gcp-waste-kpi-card">
        <h3>Potential Savings</h3>
        {isLoading ? (
          <div className="gcp-waste-skeleton gcp-waste-skeleton-text"></div>
        ) : (
          <>
            <p className="gcp-waste-kpi-value">{formatCurrency(totalSavings)}</p>
            <p className="gcp-waste-kpi-label">per month</p>
          </>
        )}
      </div>

      {/* Wasted Resources Card */}
      <div className="gcp-waste-kpi-card">
        <h3>Wasted Resources</h3>
        {isLoading ? (
          <div className="gcp-waste-skeleton gcp-waste-skeleton-text"></div>
        ) : (
          <>
            <p className="gcp-waste-kpi-value">{wasteItems.length}</p>
            <p className="gcp-waste-kpi-label">items found</p>
          </>
        )}
      </div>

      {/* Savings by Category Card */}
      <div className="gcp-waste-kpi-card">
        <h3>Savings by Category</h3>
        <div className="gcp-waste-chart-container">
          <canvas ref={chartRef} id="savingsChart"></canvas>
        </div>
      </div>
    </div>
  );
};

export default GcpWasteKPICards;
