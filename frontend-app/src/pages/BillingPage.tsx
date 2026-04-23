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
import { useAccounts } from '../contexts/AccountContext';
import type {
  DetailedServiceBreakdown,
  RegionCost,
  ResourceItem,
  ExpandedState,
} from '../types/billing';

const BillingPage: React.FC = () => {
  const { accounts, isLoading: accountsLoading } = useAccounts();
  const hasAccounts = accounts.length > 0;

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

    // Create gradient
    const gradient = ctx.createLinearGradient(0, 0, 0, 400);
    gradient.addColorStop(0, 'rgba(99, 102, 241, 0.8)'); // Indigo-500
    gradient.addColorStop(0.5, 'rgba(99, 102, 241, 0.4)');
    gradient.addColorStop(1, 'rgba(99, 102, 241, 0.1)');

    const labels = billingData.costHistory.map((h) =>
      new Date(h.date).toLocaleString('default', { month: 'short' })
    );
    const data = billingData.costHistory.map((h) => (h.cost != null ? h.cost : 0));

    // Cast to any to avoid complex Chart.js generic type mismatch
    costHistoryChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels,
        datasets: [
          {
            label: 'Monthly Cost',
            data,
            backgroundColor: gradient,
            borderColor: '#6366f1',
            borderWidth: 1,
            borderRadius: 6,
            borderSkipped: false,
            barPercentage: 0.6,
            hoverBackgroundColor: '#4f46e5',
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
            titleFont: { size: 13, weight: '600' },
            bodyFont: { size: 13 },
            displayColors: false,
            callbacks: {
              label: (context: any) => `Cost: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: {
              color: '#f3f4f6',
              drawBorder: false,
            } as any,
            ticks: {
              color: '#9ca3af',
              font: { size: 11 },
              padding: 10,
              callback: (value: any) => {
                const num = Number(value);
                if (num >= 1000) return '$' + (num / 1000).toFixed(1) + 'k';
                return '$' + num;
              },
            },
          },
          x: {
            grid: { display: false, drawBorder: false } as any,
            ticks: {
              color: '#9ca3af',
              font: { size: 11 },
            },
          },
        },
      },
    } as any);

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
    const totalCost = data.reduce((acc, curr) => acc + curr, 0);

    // Create dynamic gradients for each segment
    const backgroundColors = data.map((_, i) => {
      const colors = [
        ['#6366f1', '#4f46e5'], // Indigo
        ['#8b5cf6', '#7c3aed'], // Violet
        ['#d946ef', '#c026d3'], // Fuchsia
        ['#ec4899', '#db2777'], // Pink
        ['#f43f5e', '#e11d48'], // Rose
        ['#f97316', '#ea580c'], // Orange
        ['#10b981', '#059669'], // Emerald
        ['#0ea5e9', '#0284c7'], // Sky
      ];
      const colorPair = colors[i % colors.length];
      const gradient = ctx.createLinearGradient(0, 0, 0, 150);
      gradient.addColorStop(0, colorPair[0]);
      gradient.addColorStop(1, colorPair[1]);
      return gradient;
    });

    // Custom plugin for center text
    const centerTextPlugin = {
      id: 'centerText',
      afterDraw: (chart: any) => {
        const { ctx, chartArea: { left, right, top, bottom } } = chart;
        ctx.save();

        const centerX = (left + right) / 2;
        const centerY = (top + bottom) / 2;

        // Label
        ctx.font = '600 12px Inter, sans-serif';
        ctx.fillStyle = '#64748b';
        ctx.textAlign = 'center';
        ctx.fillText('TOTAL COST', centerX, centerY - 15);

        // Value
        ctx.font = 'bold 22px Inter, sans-serif';
        ctx.fillStyle = '#1e293b';
        ctx.textAlign = 'center';
        ctx.fillText(formatCurrency(totalCost), centerX, centerY + 15);

        ctx.restore();
      }
    };

    // Cast to any to avoid complex Chart.js generic type mismatch
    serviceBreakdownChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels,
        datasets: [
          {
            label: 'Cost',
            data,
            backgroundColor: backgroundColors,
            borderWidth: 0,
            hoverOffset: 12,
            borderRadius: 8,
            spacing: 2,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '78%',
        plugins: {
          legend: {
            position: 'right',
            labels: {
              usePointStyle: true,
              pointStyle: 'rectRounded',
              padding: 25,
              font: { weight: '600', size: 12, family: 'Inter, sans-serif' },
              color: '#475569',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.98)',
            titleColor: '#1e293b',
            bodyColor: '#475569',
            borderColor: '#f1f5f9',
            borderWidth: 1,
            padding: 14,
            usePointStyle: true,
            boxPadding: 8,
            cornerRadius: 12,
            titleFont: { weight: 'bold', size: 13 },
            callbacks: {
              label: (context: any) => ` ${context.label}: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
      },
      plugins: [centerTextPlugin],
    } as any);

    return () => {
      if (serviceBreakdownChartInstance.current) {
        serviceBreakdownChartInstance.current.destroy();
      }
    };
  }, [billingData.serviceBreakdown]);

  return (
    <div className="billing-page flex-1 flex flex-col h-screen overflow-hidden bg-gray-50/50">
      <main className="flex-1 overflow-y-auto p-8 custom-scrollbar">
        <div className="w-full">

          {/* No Accounts Empty State */}
          {!accountsLoading && !hasAccounts && (
            <div className="text-center py-24 bg-white border border-amber-100 rounded-2xl shadow-sm">
              <div className="inline-flex items-center justify-center w-20 h-20 rounded-full bg-amber-50 mb-6">
                <i className="fas fa-plug text-amber-400 text-4xl" />
              </div>
              <h3 className="text-xl font-bold text-gray-900 mb-2">No Accounts Connected</h3>
              <p className="text-gray-500 max-w-sm mx-auto mb-6">
                Connect a cloud account to view your billing data.
              </p>
              <a
                href="/account-manager"
                className="inline-flex items-center gap-2 px-6 py-3 bg-indigo-600 text-white rounded-xl font-bold text-sm hover:bg-indigo-700 transition-all shadow-lg shadow-indigo-500/20"
              >
                <i className="fas fa-plus" />
                Add Account
              </a>
            </div>
          )}

          {/* Billing content — only when accounts exist */}
          {(accountsLoading || hasAccounts) && <>

          {/* Loading Header Skeleton */}
          {loading && (
            <div className="animate-pulse mb-8">
              <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm">
                <div className="h-[50px] flex justify-between items-center">
                  <div className="bg-gray-200 h-8 w-48 rounded-lg"></div>
                  <div className="flex items-center space-x-3">
                    <div className="bg-gray-200 h-10 w-24 rounded-lg"></div>
                    <div className="bg-gray-200 h-10 w-24 rounded-lg"></div>
                    <div className="bg-gray-200 h-10 w-28 rounded-lg"></div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Header */}
          {!loading && (
            <div className="flex flex-col md:flex-row justify-between items-start md:items-center mb-8 gap-4">
              <div>
                <h1 className="text-2xl font-bold text-gray-900 flex items-center">
                  <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center text-white mr-3 shadow-sm">
                    <i className="fas fa-file-invoice-dollar text-lg"></i>
                  </div>
                  Billing Dashboard
                </h1>
                <p className="text-gray-500 mt-1 ml-13">Overview of your AWS cloud spending and cost distribution</p>
              </div>

              <div className="flex items-center gap-3 bg-white p-1.5 rounded-xl border border-gray-200 shadow-sm">
                <select
                  value={selectedYear}
                  onChange={(e) => {
                    setSelectedYear(Number(e.target.value));
                    handleFilterChange();
                  }}
                  className="bg-transparent border-none text-sm font-medium text-gray-700 focus:ring-0 cursor-pointer py-1.5 pl-3 pr-8 rounded-lg hover:bg-gray-50 transition-colors"
                  disabled={loading || isUpdating}
                >
                  {availableYears.map((year) => (
                    <option key={year} value={year}>
                      {year}
                    </option>
                  ))}
                </select>
                <div className="w-px h-5 bg-gray-200"></div>
                <select
                  value={selectedMonth}
                  onChange={(e) => {
                    setSelectedMonth(Number(e.target.value));
                    handleFilterChange();
                  }}
                  className="bg-transparent border-none text-sm font-medium text-gray-700 focus:ring-0 cursor-pointer py-1.5 pl-3 pr-8 rounded-lg hover:bg-gray-50 transition-colors"
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
                  className="p-2 text-gray-500 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-all duration-200 ml-1"
                  disabled={loading || isUpdating}
                  title="Refresh Data"
                >
                  <i
                    className={`fas ${isUpdating ? 'fa-sync-alt fa-spin' : 'fa-sync-alt'}`}
                  />
                </button>
              </div>
            </div>
          )}

          {/* Loading Skeleton for Charts and Table */}
          {loading && (
            <div className="animate-pulse space-y-8">
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm">
                  <div className="flex justify-between mb-6">
                    <div className="bg-gray-200 h-6 w-1/3 rounded"></div>
                  </div>
                  <div className="bg-gray-100 h-[280px] rounded-xl"></div>
                </div>
                <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm">
                  <div className="flex justify-between mb-6">
                    <div className="bg-gray-200 h-6 w-1/3 rounded"></div>
                  </div>
                  <div className="bg-gray-100 h-[280px] rounded-xl"></div>
                </div>
              </div>
              <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm">
                <div className="bg-gray-200 h-6 w-1/4 mb-6 rounded"></div>
                <div className="space-y-4">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <div key={i} className="flex gap-4">
                      <div className="bg-gray-100 h-10 w-full rounded-lg"></div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* Error State */}
          {error && !hasData && !loading && (
            <div className="bg-red-50 border border-red-200 p-8 rounded-xl text-center max-w-2xl mx-auto mt-10">
              <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4 text-red-600">
                <i className="fas fa-exclamation-triangle text-2xl"></i>
              </div>
              <h3 className="text-xl font-bold text-red-900 mb-2">Failed to Load Data</h3>
              <p className="text-red-700 font-medium mb-6">{error}</p>
              <button
                onClick={() => fetchData(true)}
                className="px-6 py-2.5 bg-white border border-red-200 text-red-700 rounded-lg font-semibold hover:bg-red-50 transition-colors shadow-sm"
              >
                Try Again
              </button>
            </div>
          )}

          {/* Update Error Warning */}
          {updateError && hasData && !loading && (
            <div className="mb-6 p-4 rounded-xl bg-orange-50 border border-orange-200 flex items-start gap-3 animate-fade-in">
              <i className="fas fa-exclamation-circle text-orange-500 mt-0.5"></i>
              <div>
                <h4 className="text-sm font-semibold text-orange-900">Background Update Failed</h4>
                <p className="text-sm text-orange-800 mt-1">{updateError}</p>
              </div>
            </div>
          )}

          {/* Main Content */}
          {!loading && (hasData || isUpdating) && (
            <div className="space-y-6">
              {/* Charts Row */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Cost Over Time Chart */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex flex-col">
                  <div className="flex justify-between items-center mb-6">
                    <div>
                      <h2 className="text-lg font-bold text-gray-800">Cost Over Time</h2>
                      <p className="text-sm text-gray-500">Monthly spending trends</p>
                    </div>
                    <div className="bg-indigo-50 p-2 rounded-lg text-indigo-600">
                      <i className="fas fa-chart-bar"></i>
                    </div>
                  </div>
                  <div className="flex-1 min-h-[300px] relative">
                    {billingData.costHistory.length > 0 ? (
                      <canvas ref={costHistoryChartRef}></canvas>
                    ) : (
                      <div className="absolute inset-0 flex items-center justify-center text-gray-400 flex-col">
                        <i className="fas fa-chart-bar text-4xl mb-2 opacity-50"></i>
                        <p>No cost history data available.</p>
                      </div>
                    )}
                  </div>
                </div>

                {/* Service Breakdown Chart */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex flex-col">
                  <div className="flex justify-between items-center mb-6">
                    <div>
                      <h2 className="text-lg font-bold text-gray-800">Service Breakdown</h2>
                      <p className="text-sm text-gray-500">Distribution by AWS services</p>
                    </div>
                    <div className="bg-purple-50 p-2 rounded-lg text-purple-600">
                      <i className="fas fa-chart-pie"></i>
                    </div>
                  </div>
                  <div className="flex-1 min-h-[300px] relative">
                    {billingData.serviceBreakdown.length > 0 ? (
                      <canvas ref={serviceBreakdownChartRef}></canvas>
                    ) : (
                      <div className="absolute inset-0 flex items-center justify-center text-gray-400 flex-col">
                        <i className="fas fa-chart-pie text-4xl mb-2 opacity-50"></i>
                        <p>No service breakdown data available.</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Detailed Cost Breakdown Table */}
              <div className="bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden">
                <div className="p-6 border-b border-gray-100 bg-gray-50/30">
                  <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2">
                    <i className="fas fa-table text-indigo-500"></i>
                    Detailed Cost Breakdown
                  </h2>
                  <p className="text-sm text-gray-500 mt-1 ml-6">
                    Expandable view of all services, regions, and resources
                  </p>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <colgroup>
                      <col style={{ width: '60px' }} />
                      <col style={{ width: '45%' }} />
                      <col style={{ width: '25%' }} />
                      <col style={{ width: '25%' }} />
                    </colgroup>
                    <thead>
                      <tr className="bg-gray-50/50 border-b border-gray-100 text-xs uppercase tracking-wider">
                        <th className="px-4 py-4"></th>
                        <th className="px-4 py-4 font-semibold text-gray-500">Service / Resource</th>
                        <th className="px-4 py-4 font-semibold text-gray-500 text-right">Usage Quantity</th>
                        <th className="px-4 py-4 font-semibold text-gray-500 text-right">Amount (USD)</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                      {detailedBreakdown.length > 0 ? (
                        detailedBreakdown.map((service: DetailedServiceBreakdown) => (
                          <React.Fragment key={service.serviceName}>
                            {/* Service Row */}
                            <tr
                              onClick={() => toggleService(service.serviceName)}
                              className={`cursor-pointer transition-colors hover:bg-gray-50/80 ${expandedServices[service.serviceName] ? 'bg-indigo-50/30' : ''}`}
                            >
                              <td className="px-4 py-4 text-center text-gray-400">
                                <i className={`fas fa-chevron-right text-sm transition-transform duration-200 ${expandedServices[service.serviceName] ? 'rotate-90 text-indigo-500' : ''
                                  }`}></i>
                              </td>
                              <td className="px-4 py-4">
                                <div className="flex items-center">
                                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center mr-3 transition-colors ${expandedServices[service.serviceName] ? 'bg-indigo-100 text-indigo-600' : 'bg-gray-100 text-gray-500'}`}>
                                    <i className="fas fa-cube text-xs"></i>
                                  </div>
                                  <span className="font-semibold text-gray-900">
                                    {service.serviceName}
                                  </span>
                                </div>
                              </td>
                              <td className="px-4 py-4 text-right">
                                <span className="text-gray-300 text-sm">-</span>
                              </td>
                              <td className="px-4 py-4 text-right">
                                <span className="font-bold text-gray-900">
                                  {formatCurrency(service.totalCost)}
                                </span>
                              </td>
                            </tr>

                            {/* Region Rows */}
                            {expandedServices[service.serviceName] &&
                              service.regionCosts.map((region: RegionCost) => (
                                <React.Fragment key={`${service.serviceName}-${region.regionName}`}>
                                  <tr
                                    onClick={() => toggleRegion(service.serviceName, region.regionName)}
                                    className={`cursor-pointer transition-colors hover:bg-gray-50 ${expandedRegions[`${service.serviceName}-${region.regionName}`] ? 'bg-gray-50' : ''}`}
                                  >
                                    <td className="px-4 py-3 text-center text-gray-300">
                                      <i className={`fas fa-chevron-right text-xs transition-transform duration-200 ml-2 ${expandedRegions[`${service.serviceName}-${region.regionName}`]
                                        ? 'rotate-90 text-gray-500'
                                        : ''
                                        }`}></i>
                                    </td>
                                    <td className="px-4 py-3">
                                      <div className="flex items-center pl-10">
                                        <i className="fas fa-globe-americas text-gray-400 mr-2 text-xs"></i>
                                        <span className="text-gray-700 font-medium text-sm">
                                          {region.regionName}
                                        </span>
                                      </div>
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                      <span className="text-gray-300 text-xs">-</span>
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                      <span className="font-medium text-gray-700 text-sm">
                                        {formatCurrency(region.cost)}
                                      </span>
                                    </td>
                                  </tr>

                                  {/* Resource Rows */}
                                  {expandedRegions[`${service.serviceName}-${region.regionName}`] &&
                                    region.resources.map((resource: ResourceItem, idx: number) => (
                                      <tr
                                        key={`${resource.resourceId}-${resource.cost}-${idx}`}
                                        className="hover:bg-gray-50/50 transition-colors"
                                      >
                                        <td className="px-4 py-2"></td>
                                        <td className="px-4 py-2">
                                          <div className="pl-16 flex items-center">
                                            <div className="w-1.5 h-1.5 bg-gray-300 rounded-full mr-3"></div>
                                            <span className="text-gray-600 text-xs font-mono truncate max-w-md" title={resource.resourceName}>
                                              {resource.resourceName}
                                            </span>
                                          </div>
                                        </td>
                                        <td className="px-4 py-2 text-right text-gray-500 text-xs font-mono">
                                          {resource.quantity != null
                                            ? `${formatNumber(resource.quantity)} ${resource.unit || ''}`
                                            : 'N/A'}
                                        </td>
                                        <td className="px-4 py-2 text-right">
                                          <span className="text-gray-600 text-sm font-medium">
                                            {formatCurrency(resource.cost)}
                                          </span>
                                        </td>
                                      </tr>
                                    ))}
                                </React.Fragment>
                              ))}
                          </React.Fragment>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={4} className="text-center py-16">
                            <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-3">
                              <i className="fas fa-inbox text-gray-300 text-2xl"></i>
                            </div>
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

          </> /* end accountsLoading || hasAccounts */
          }
        </div>
      </main>
    </div>
  );
};

export default BillingPage;
