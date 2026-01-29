/**
 * Billing Page
 *
 * Billing Dashboard with cost charts and detailed breakdown table
 * Converted from: billops/billing.html
 *
 * Features:
 * - Cost Over Time bar chart
 * - Service Breakdown doughnut chart
 * - Expandable detailed cost breakdown table (Service > Region > Resource)
 * - Year/Month filters
 * - Refresh functionality
 *
 * What stayed the same:
 * - Same API endpoints: /api/billops/billing and /api/billops/detailed-breakdown
 * - Same data structure and expandable table behavior
 * - All business logic preserved
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Chart.js integration via useEffect and refs
 * - Enhanced UI with smoother animations
 * - Uses React sidebar
 */

import React, { useEffect, useRef, useState, useCallback } from 'react';
import Chart from 'chart.js/auto';
import { useBilling } from '../hooks/useBilling';
import type {
  DetailedServiceBreakdown,
  RegionCost,
  ResourceItem,
  ExpandedState,
} from '../types/billing';

const BillingPage: React.FC = () => {
  const {
    billingData,
    detailedBreakdown,
    loading,
    isUpdating,
    error,
    updateError,
    selectedYear,
    selectedMonth,
    availableYears,
    availableMonths,
    hasData,
    setSelectedYear,
    setSelectedMonth,
    fetchData,
  } = useBilling();

  // Chart refs
  const costHistoryChartRef = useRef<HTMLCanvasElement>(null);
  const serviceBreakdownChartRef = useRef<HTMLCanvasElement>(null);
  const costHistoryChartInstance = useRef<Chart | null>(null);
  const serviceBreakdownChartInstance = useRef<Chart | null>(null);

  // Expanded state for table rows
  const [expandedServices, setExpandedServices] = useState<ExpandedState>({});
  const [expandedRegions, setExpandedRegions] = useState<ExpandedState>({});

  /**
   * Format currency value
   */
  const formatCurrency = (value: number | null | undefined): string => {
    if (typeof value !== 'number') return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  /**
   * Format number with commas
   */
  const formatNumber = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '0';
    const num = Number(value);
    if (isNaN(num)) return '0';
    return num.toLocaleString('en-US', { maximumFractionDigits: 3 });
  };

  /**
   * Toggle service expansion
   */
  const toggleService = useCallback((serviceName: string) => {
    setExpandedServices((prev) => ({
      ...prev,
      [serviceName]: !prev[serviceName],
    }));
  }, []);

  /**
   * Toggle region expansion
   */
  const toggleRegion = useCallback((serviceName: string, regionName: string) => {
    const key = `${serviceName}-${regionName}`;
    setExpandedRegions((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  }, []);

  /**
   * Handle filter change
   */
  const handleFilterChange = useCallback(() => {
    setExpandedServices({});
    setExpandedRegions({});
    fetchData(false);
  }, [fetchData]);

  /**
   * Render Cost History Chart
   */
  useEffect(() => {
    if (!costHistoryChartRef.current || !billingData.costHistory?.length) return;

    // Destroy existing chart
    if (costHistoryChartInstance.current) {
      costHistoryChartInstance.current.destroy();
    }

    const ctx = costHistoryChartRef.current.getContext('2d');
    if (!ctx) return;

    const labels = billingData.costHistory.map((h) =>
      new Date(h.date).toLocaleString('default', { month: 'short' })
    );
    const data = billingData.costHistory.map((h) => (h.cost != null ? h.cost : 0));

    costHistoryChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels,
        datasets: [
          {
            label: 'Monthly Cost',
            data,
            backgroundColor: (context) => {
              const { ctx: chartCtx, chartArea } = context.chart;
              if (!chartArea) return '#6366f1';
              const gradient = chartCtx.createLinearGradient(0, chartArea.bottom, 0, chartArea.top);
              gradient.addColorStop(0, '#6366f1');
              gradient.addColorStop(1, '#8b5cf6');
              return gradient;
            },
            borderColor: '#6366f1',
            borderWidth: 2,
            borderRadius: 6,
            borderSkipped: false,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            titleColor: '#1f2937',
            bodyColor: '#1f2937',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 12,
            displayColors: false,
            callbacks: {
              label: (context) => `Cost: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: '#f3f4f6' },
            ticks: {
              color: '#9ca3af',
              callback: (value) => {
                const num = Number(value);
                if (num >= 1000) return '$' + (num / 1000).toFixed(1) + 'k';
                return '$' + num;
              },
            },
          },
          x: {
            grid: { display: false },
            ticks: { color: '#9ca3af' },
          },
        },
      },
    });

    return () => {
      if (costHistoryChartInstance.current) {
        costHistoryChartInstance.current.destroy();
      }
    };
  }, [billingData.costHistory]);

  /**
   * Render Service Breakdown Chart
   */
  useEffect(() => {
    if (!serviceBreakdownChartRef.current || !billingData.serviceBreakdown?.length) return;

    // Destroy existing chart
    if (serviceBreakdownChartInstance.current) {
      serviceBreakdownChartInstance.current.destroy();
    }

    const ctx = serviceBreakdownChartRef.current.getContext('2d');
    if (!ctx) return;

    const labels = billingData.serviceBreakdown.map((s) => s.name || 'Unknown');
    const data = billingData.serviceBreakdown.map((s) => (s.cost != null ? s.cost : 0));

    serviceBreakdownChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels,
        datasets: [
          {
            label: 'Cost',
            data,
            backgroundColor: [
              '#6366f1',
              '#8b5cf6',
              '#10b981',
              '#f59e0b',
              '#ef4444',
              '#3b82f6',
              '#ec4899',
              '#14b8a6',
            ],
            borderWidth: 3,
            borderColor: '#ffffff',
            hoverBorderWidth: 4,
            hoverOffset: 8,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'right',
            labels: {
              usePointStyle: true,
              padding: 16,
              font: { weight: '500' },
            },
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            titleColor: '#1f2937',
            bodyColor: '#1f2937',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 12,
            callbacks: {
              label: (context) => `${context.label}: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
      },
    });

    return () => {
      if (serviceBreakdownChartInstance.current) {
        serviceBreakdownChartInstance.current.destroy();
      }
    };
  }, [billingData.serviceBreakdown]);

  return (
    <div className="billing-page flex-1 flex flex-col h-screen overflow-hidden">
      <main className="flex-1 overflow-y-auto p-10">
        <div className="max-w-7xl mx-auto">
          {/* Loading Header Skeleton */}
          {loading && (
            <div className="animate-pulse mb-6">
              <div className="billing-card">
                <div className="billing-header h-[85px]">
                  <div className="flex justify-between items-center h-full">
                    <div className="billing-skeleton-dark h-6 w-48"></div>
                    <div className="flex items-center space-x-3">
                      <div className="billing-skeleton-dark h-9 w-20 rounded-lg"></div>
                      <div className="billing-skeleton-dark h-9 w-20 rounded-lg"></div>
                      <div className="billing-skeleton-dark h-9 w-24 rounded-lg"></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Header */}
          {!loading && (
            <div className="billing-card mb-6">
              <div className="billing-header">
                <div className="flex justify-between items-center">
                  <h1 className="billing-title flex items-center">
                    Billing Dashboard
                    {isUpdating && (
                      <span className="billing-spinner ml-3" title="Refreshing data..." />
                    )}
                  </h1>
                  <div className="flex items-center space-x-3">
                    <select
                      value={selectedYear}
                      onChange={(e) => {
                        setSelectedYear(Number(e.target.value));
                        handleFilterChange();
                      }}
                      className="billing-filter-select"
                      disabled={loading || isUpdating}
                    >
                      {availableYears.map((year) => (
                        <option key={year} value={year}>
                          {year}
                        </option>
                      ))}
                    </select>
                    <select
                      value={selectedMonth}
                      onChange={(e) => {
                        setSelectedMonth(Number(e.target.value));
                        handleFilterChange();
                      }}
                      className="billing-filter-select"
                      disabled={loading || isUpdating}
                    >
                      {availableMonths.map((month) => (
                        <option key={month.value} value={month.value}>
                          {month.name}
                        </option>
                      ))}
                    </select>
                    <button
                      onClick={() => fetchData(true)}
                      className="billing-refresh-btn"
                      disabled={loading || isUpdating}
                    >
                      <i
                        className={`fas ${isUpdating ? 'fa-sync-alt fa-spin' : 'fa-sync-alt'}`}
                      />
                      <span className="ml-2">{isUpdating ? 'Refreshing...' : 'Refresh'}</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Loading Skeleton for Charts and Table */}
          {loading && (
            <div className="animate-pulse space-y-8">
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <div className="billing-card">
                  <div className="p-6 border-b border-gray-100">
                    <div className="billing-skeleton h-5 w-3/5 mb-2"></div>
                    <div className="billing-skeleton h-3 w-2/5"></div>
                  </div>
                  <div className="p-4">
                    <div className="h-[280px] billing-skeleton rounded-lg"></div>
                  </div>
                </div>
                <div className="billing-card">
                  <div className="p-6 border-b border-gray-100">
                    <div className="billing-skeleton h-5 w-3/5 mb-2"></div>
                    <div className="billing-skeleton h-3 w-2/5"></div>
                  </div>
                  <div className="p-4">
                    <div className="h-[280px] billing-skeleton rounded-lg"></div>
                  </div>
                </div>
              </div>
              <div className="billing-card">
                <div className="p-6 border-b border-gray-100">
                  <div className="billing-skeleton h-5 w-2/5 mb-2"></div>
                  <div className="billing-skeleton h-3 w-3/5"></div>
                </div>
                <div className="overflow-x-auto">
                  <table className="billing-table">
                    <thead>
                      <tr>
                        <th style={{ width: '60px' }}>
                          <div className="billing-skeleton h-4 w-4 mx-auto"></div>
                        </th>
                        <th>
                          <div className="billing-skeleton h-4 w-24"></div>
                        </th>
                        <th>
                          <div className="billing-skeleton h-4 w-20 ml-auto"></div>
                        </th>
                        <th>
                          <div className="billing-skeleton h-4 w-20 ml-auto"></div>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {[1, 2, 3, 4, 5].map((i) => (
                        <tr key={i}>
                          <td>
                            <div className="billing-skeleton h-6 w-6 mx-auto rounded-md"></div>
                          </td>
                          <td>
                            <div className="billing-skeleton h-4 w-3/4"></div>
                          </td>
                          <td>
                            <div className="billing-skeleton h-4 w-1/2 ml-auto"></div>
                          </td>
                          <td>
                            <div className="billing-skeleton h-4 w-1/2 ml-auto"></div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* Error State */}
          {error && !hasData && !loading && (
            <div className="billing-card p-8 text-center border-red-200 bg-red-50">
              <i className="fas fa-exclamation-triangle text-red-500 text-2xl mb-3"></i>
              <p className="text-red-700 font-medium">{error}</p>
            </div>
          )}

          {/* Update Error Warning */}
          {updateError && hasData && !loading && (
            <div className="mb-4 p-3 text-sm text-yellow-800 rounded-lg bg-yellow-50 border border-yellow-200">
              <i className="fas fa-exclamation-circle mr-2"></i>
              <span>{updateError}</span>
            </div>
          )}

          {/* Main Content */}
          {!loading && (hasData || isUpdating) && (
            <div className="space-y-8">
              {/* Charts Row */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Cost Over Time Chart */}
                <div className="billing-card">
                  <div className="p-6 border-b border-gray-100">
                    <h2 className="text-xl font-semibold text-gray-800 flex items-center">
                      <i className="fas fa-chart-bar text-blue-500 mr-3"></i>
                      Cost Over Time
                    </h2>
                    <p className="text-sm text-gray-500 mt-1">Monthly spending trends</p>
                  </div>
                  <div className="p-4">
                    {billingData.costHistory.length > 0 ? (
                      <div className="billing-chart-container">
                        <canvas ref={costHistoryChartRef}></canvas>
                      </div>
                    ) : (
                      <div className="text-center py-10 text-gray-500">
                        No cost history data available.
                      </div>
                    )}
                  </div>
                </div>

                {/* Service Breakdown Chart */}
                <div className="billing-card">
                  <div className="p-6 border-b border-gray-100">
                    <h2 className="text-xl font-semibold text-gray-800 flex items-center">
                      <i className="fas fa-chart-pie text-purple-500 mr-3"></i>
                      Service Breakdown
                    </h2>
                    <p className="text-sm text-gray-500 mt-1">Distribution by AWS services</p>
                  </div>
                  <div className="p-4">
                    {billingData.serviceBreakdown.length > 0 ? (
                      <div className="billing-chart-container">
                        <canvas ref={serviceBreakdownChartRef}></canvas>
                      </div>
                    ) : (
                      <div className="text-center py-10 text-gray-500">
                        No service breakdown data available.
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Detailed Cost Breakdown Table */}
              <div className="billing-card">
                <div className="p-6 border-b border-gray-100">
                  <h2 className="text-xl font-semibold text-gray-800 flex items-center">
                    <i className="fas fa-table text-green-500 mr-3"></i>
                    Detailed Cost Breakdown
                  </h2>
                  <p className="text-sm text-gray-500 mt-1">
                    Expandable view of all services, regions, and resources
                  </p>
                </div>

                <div className="overflow-x-auto">
                  <table className="billing-table">
                    <colgroup>
                      <col style={{ width: '60px' }} />
                      <col style={{ width: '45%' }} />
                      <col style={{ width: '25%' }} />
                      <col style={{ width: '25%' }} />
                    </colgroup>
                    <thead>
                      <tr>
                        <th></th>
                        <th>Service / Resource</th>
                        <th className="text-right">Usage Quantity</th>
                        <th className="text-right">Amount (USD)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detailedBreakdown.length > 0 ? (
                        detailedBreakdown.map((service: DetailedServiceBreakdown) => (
                          <React.Fragment key={service.serviceName}>
                            {/* Service Row */}
                            <tr
                              onClick={() => toggleService(service.serviceName)}
                              className="billing-service-row cursor-pointer"
                            >
                              <td>
                                <div
                                  className={`billing-expand-icon ${
                                    expandedServices[service.serviceName] ? 'billing-rotate-90' : ''
                                  }`}
                                >
                                  <i className="fas fa-chevron-right text-sm"></i>
                                </div>
                              </td>
                              <td>
                                <div className="billing-service-name font-semibold text-gray-800">
                                  {service.serviceName}
                                </div>
                              </td>
                              <td className="text-right">
                                <span className="text-gray-400">-</span>
                              </td>
                              <td>
                                <div className="billing-amount text-blue-600">
                                  {formatCurrency(service.totalCost)}
                                </div>
                              </td>
                            </tr>

                            {/* Region Rows */}
                            {expandedServices[service.serviceName] &&
                              service.regionCosts.map((region: RegionCost) => (
                                <React.Fragment key={`${service.serviceName}-${region.regionName}`}>
                                  <tr
                                    onClick={() => toggleRegion(service.serviceName, region.regionName)}
                                    className="billing-region-row cursor-pointer"
                                  >
                                    <td>
                                      <div
                                        className={`billing-expand-icon ${
                                          expandedRegions[`${service.serviceName}-${region.regionName}`]
                                            ? 'billing-rotate-90'
                                            : ''
                                        }`}
                                      >
                                        <i className="fas fa-chevron-right text-xs"></i>
                                      </div>
                                    </td>
                                    <td>
                                      <div className="billing-region-name pl-4 text-gray-700">
                                        {region.regionName}
                                      </div>
                                    </td>
                                    <td className="text-right">
                                      <span className="text-gray-400">-</span>
                                    </td>
                                    <td>
                                      <div className="billing-amount text-blue-600">
                                        {formatCurrency(region.cost)}
                                      </div>
                                    </td>
                                  </tr>

                                  {/* Resource Rows */}
                                  {expandedRegions[`${service.serviceName}-${region.regionName}`] &&
                                    region.resources.map((resource: ResourceItem, idx: number) => (
                                      <tr
                                        key={`${resource.resourceId}-${resource.cost}-${idx}`}
                                        className="billing-resource-row"
                                      >
                                        <td></td>
                                        <td>
                                          <div className="billing-resource-name pl-8 text-gray-600">
                                            {resource.resourceName}
                                          </div>
                                        </td>
                                        <td className="text-right text-gray-600 text-sm">
                                          {resource.quantity != null
                                            ? `${formatNumber(resource.quantity)} ${resource.unit || ''}`
                                            : 'N/A'}
                                        </td>
                                        <td>
                                          <div className="billing-amount text-purple-600">
                                            {formatCurrency(resource.cost)}
                                          </div>
                                        </td>
                                      </tr>
                                    ))}
                                </React.Fragment>
                              ))}
                          </React.Fragment>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={4} className="text-center py-12">
                            <i className="fas fa-inbox text-gray-300 text-3xl mb-3"></i>
                            <p className="text-gray-500 font-medium">
                              No detailed cost data available for the selected period.
                            </p>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
};

export default BillingPage;
