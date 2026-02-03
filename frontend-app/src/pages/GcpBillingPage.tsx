/**
 * GCP Billing Page
 *
 * GCP Billing Dashboard with cost charts and service breakdown
 * Converted from: billops/gcp-billing.html
 *
 * Features:
 * - Cost History line chart
 * - Service Breakdown doughnut chart
 * - Detailed service costs table
 * - Year/Month filters
 * - Refresh functionality with cache-first loading
 * - Export to Excel (placeholder)
 *
 * What stayed the same:
 * - Same API endpoints: /api/billops/billing/gcp/dashboard/cached and /api/billops/billing/gcp/dashboard/{accountId}
 * - Same data structure
 * - All business logic preserved
 * - Cache-first strategy with background refresh
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Chart.js integration via useEffect and refs
 * - Enhanced UI with professional design
 * - Uses React sidebar
 * - Better gradient metric cards
 */

import React, { useEffect, useRef, useCallback } from 'react';
import Chart from 'chart.js/auto';
import { useGcpBilling } from '../hooks/useGcpBilling';
import type { GcpServiceBreakdownItem } from '../types/gcpBilling';

const GcpBillingPage: React.FC = () => {
  const {
    dashboardData,
    loading,
    isUpdating,
    error,
    updateError,
    selectedYear,
    selectedMonth,
    availableYears,
    months,
    hasData,
    setSelectedYear,
    setSelectedMonth,
    fetchFreshData,
    loadInitialData,
  } = useGcpBilling();

  // Chart refs
  const costHistoryChartRef = useRef<HTMLCanvasElement>(null);
  const serviceBreakdownChartRef = useRef<HTMLCanvasElement>(null);
  const costHistoryChartInstance = useRef<Chart | null>(null);
  const serviceBreakdownChartInstance = useRef<Chart<'doughnut'> | null>(null);

  /**
   * Format currency value (INR)
   */
  const formatCurrency = (value: number | null | undefined): string => {
    if (typeof value !== 'number') return '₹0.00';
    return new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: 'INR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  /**
   * Get percentage of total cost
   */
  const getPercentage = (amount: number): string => {
    const total = Number(dashboardData.totalCost);
    const numericAmount = Number(amount);
    if (isNaN(total) || total === 0 || isNaN(numericAmount)) return '0.0%';
    return ((numericAmount / total) * 100).toFixed(1) + '%';
  };

  /**
   * Get top service
   */
  const getTopService = (): string => {
    const services = dashboardData.serviceBreakdown || [];
    if (services.length === 0) return 'N/A';
    const top = services.reduce(
      (max, s) => (Number(s.amount) > Number(max?.amount || 0) ? s : max),
      services[0]
    );
    return top?.serviceName || 'N/A';
  };

  /**
   * Get top service cost
   */
  const getTopServiceCost = (): number => {
    const services = dashboardData.serviceBreakdown || [];
    if (services.length === 0) return 0;
    const top = services.reduce(
      (max, s) => (Number(s.amount) > Number(max?.amount || 0) ? s : max),
      services[0]
    );
    return Number(top?.amount || 0);
  };

  /**
   * Export to Excel (placeholder)
   */
  const exportToExcel = () => {
    alert('Export functionality - implement with SheetJS');
  };

  /**
   * Handle filter change
   */
  const handleFilterChange = useCallback(() => {
    loadInitialData();
  }, [loadInitialData]);

  /**
   * Render Cost History Chart
   */
  useEffect(() => {
    if (!costHistoryChartRef.current || !dashboardData.costHistory?.length) return;

    // Destroy existing chart
    if (costHistoryChartInstance.current) {
      costHistoryChartInstance.current.destroy();
    }

    const ctx = costHistoryChartRef.current.getContext('2d');
    if (!ctx) return;

    // Create gradient
    const gradient = ctx.createLinearGradient(0, 0, 0, 400);
    gradient.addColorStop(0, 'rgba(99, 102, 241, 0.5)'); // Indigo-500
    gradient.addColorStop(0.5, 'rgba(99, 102, 241, 0.1)');
    gradient.addColorStop(1, 'rgba(99, 102, 241, 0)');

    const history = dashboardData.costHistory || [];
    const labels = history.map((h) => h.date || h.month || h.name || '');
    const data = history.map((h) => h.amount || h.cost || 0);

    costHistoryChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Monthly Cost',
            data,
            borderColor: '#6366f1', // Indigo-500
            backgroundColor: gradient,
            borderWidth: 3,
            fill: true,
            tension: 0.4,
            pointRadius: 4,
            pointHoverRadius: 8,
            pointBackgroundColor: '#fff',
            pointBorderColor: '#6366f1',
            pointBorderWidth: 2,
            pointHoverBackgroundColor: '#6366f1',
            pointHoverBorderColor: '#fff',
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            display: false,
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            titleColor: '#1f2937',
            bodyColor: '#1f2937',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 12,
            boxPadding: 6,
            usePointStyle: true,
            titleFont: { size: 13, weight: '600' },
            bodyFont: { size: 13 },
            callbacks: {
              label: (context) => `Cost: ${formatCurrency(context.raw as number)}`,
            },
            displayColors: true,
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: {
              color: '#f3f4f6',
              drawBorder: false,
            } as any, // Type cast for older types
            ticks: {
              color: '#9ca3af',
              font: { size: 11 },
              padding: 10,
              callback: (value) => {
                const num = Number(value);
                if (num >= 1000) return '₹' + (num / 1000).toFixed(1) + 'k';
                return '₹' + num;
              },
            },
          },
          x: {
            grid: { display: false },
            ticks: {
              color: '#9ca3af',
              font: { size: 11 },
            },
          },
        },
        interaction: {
          intersect: false,
          mode: 'index',
        },
      },
    });

    return () => {
      if (costHistoryChartInstance.current) {
        costHistoryChartInstance.current.destroy();
      }
    };
  }, [dashboardData.costHistory]);

  /**
   * Render Service Breakdown Chart
   */
  useEffect(() => {
    if (!serviceBreakdownChartRef.current || !dashboardData.serviceBreakdown?.length) return;

    // Destroy existing chart
    if (serviceBreakdownChartInstance.current) {
      serviceBreakdownChartInstance.current.destroy();
    }

    const ctx = serviceBreakdownChartRef.current.getContext('2d');
    if (!ctx) return;

    const services = dashboardData.serviceBreakdown || [];
    const topServices = services.slice(0, 8); // Top 8 only for cleaner look
    const labels = topServices.map((item) => item.serviceName || item.name || 'Unknown');
    const data = topServices.map((item) => item.amount || 0);
    const colors = [
      '#6366f1', // Indigo
      '#8b5cf6', // Violet
      '#d946ef', // Fuchsia
      '#ec4899', // Pink
      '#f43f5e', // Rose
      '#f97316', // Orange
      '#eab308', // Yellow
      '#22c55e', // Green
    ];

    serviceBreakdownChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels,
        datasets: [
          {
            data,
            backgroundColor: colors,
            borderWidth: 2,
            borderColor: '#ffffff',
            hoverBorderWidth: 0,
            hoverOffset: 10,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '75%',
        plugins: {
          legend: {
            position: 'right',
            labels: {
              usePointStyle: true,
              pointStyle: 'circle',
              padding: 20,
              font: { weight: '500', size: 12 },
              color: '#4b5563',
            },
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            titleColor: '#1f2937',
            bodyColor: '#1f2937',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 12,
            usePointStyle: true,
            callbacks: {
              label: (context) => {
                const value = context.raw as number;
                return ` ${context.label}: ${formatCurrency(value)} (${getPercentage(value)})`;
              },
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
  }, [dashboardData.serviceBreakdown]);

  return (
    <div className="gcp-billing-page flex-1 flex flex-col h-screen overflow-hidden bg-gray-50/50">
      <main className="flex-1 overflow-y-auto p-8 custom-scrollbar">
        <div className="w-full">
          {/* Header */}
          <div className="flex flex-col md:flex-row justify-between items-start md:items-center mb-8 gap-4">
            <div>
              <h1 className="text-2xl font-bold text-gray-900 flex items-center">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center text-white mr-3 shadow-sm">
                  <i className="fas fa-file-invoice-dollar text-lg"></i>
                </div>
                GCP Billing
              </h1>
              <p className="text-gray-500 mt-1 ml-13">Monitor your Google Cloud Platform spending and trends</p>
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
                  setSelectedMonth(e.target.value);
                  handleFilterChange();
                }}
                className="bg-transparent border-none text-sm font-medium text-gray-700 focus:ring-0 cursor-pointer py-1.5 pl-3 pr-8 rounded-lg hover:bg-gray-50 transition-colors"
                disabled={loading || isUpdating}
              >
                <option value="">All Months</option>
                {months.map((month) => (
                  <option key={month.value} value={month.value}>
                    {month.label}
                  </option>
                ))}
              </select>
              <button
                onClick={fetchFreshData}
                disabled={loading || isUpdating}
                className="p-2 text-gray-500 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-all duration-200 ml-1"
                title="Refresh Data"
              >
                <i className={`fas ${isUpdating ? 'fa-sync-alt fa-spin' : 'fa-sync-alt'}`} />
              </button>
            </div>
          </div>

          {/* Loading State */}
          {loading && (
            <div className="flex flex-col items-center justify-center py-20">
              <div className="w-12 h-12 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
              <p className="text-gray-500 font-medium">Loading GCP billing data...</p>
            </div>
          )}

          {/* Error State */}
          {error && !hasData && !loading && (
            <div className="bg-red-50 border border-red-200 p-6 rounded-xl text-center max-w-2xl mx-auto mt-10">
              <div className="w-12 h-12 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4 text-red-600">
                <i className="fas fa-exclamation-triangle text-xl"></i>
              </div>
              <h3 className="text-lg font-semibold text-red-900 mb-2">Failed to Load Data</h3>
              <p className="text-red-700 mb-4">{error}</p>
              <button onClick={loadInitialData} className="px-4 py-2 bg-white border border-red-200 text-red-700 rounded-lg text-sm font-medium hover:bg-red-50 transition-colors">
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
          {!loading && hasData && (
            <div className="space-y-6">
              {/* Metric Cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {/* Total Cost Card */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 relative overflow-hidden group hover:shadow-md transition-all duration-300">
                  <div className="absolute top-0 right-0 w-24 h-24 bg-gradient-to-br from-indigo-50 to-blue-50 rounded-bl-full -mr-4 -mt-4 transition-transform group-hover:scale-110"></div>
                  <div className="relative font-medium text-gray-500 text-xs uppercase tracking-wider mb-2">Total Cost (MTD)</div>
                  <div className="relative text-3xl font-bold text-gray-900 mb-1">{formatCurrency(dashboardData.totalCost)}</div>
                  <div className="relative flex items-center">
                    <span className="text-xs text-green-600 bg-green-50 px-2 py-0.5 rounded-full font-medium">
                      <i className="fas fa-arrow-up mr-1"></i>0%
                    </span>
                    <span className="text-xs text-gray-400 ml-2">vs last month</span>
                  </div>
                </div>

                {/* Last Month Cost Card */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 relative overflow-hidden group hover:shadow-md transition-all duration-300">
                  <div className="absolute top-0 right-0 w-24 h-24 bg-gradient-to-br from-purple-50 to-pink-50 rounded-bl-full -mr-4 -mt-4 transition-transform group-hover:scale-110"></div>
                  <div className="relative font-medium text-gray-500 text-xs uppercase tracking-wider mb-2">Last Month</div>
                  <div className="relative text-3xl font-bold text-gray-900 mb-1">{formatCurrency(dashboardData.costLastMonth)}</div>
                  <div className="relative text-xs text-gray-400">Previous billing cycle</div>
                </div>

                {/* Active Services Card */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 relative overflow-hidden group hover:shadow-md transition-all duration-300">
                  <div className="absolute top-0 right-0 w-24 h-24 bg-gradient-to-br from-green-50 to-emerald-50 rounded-bl-full -mr-4 -mt-4 transition-transform group-hover:scale-110"></div>
                  <div className="relative font-medium text-gray-500 text-xs uppercase tracking-wider mb-2">Active Services</div>
                  <div className="relative text-3xl font-bold text-gray-900 mb-1">{dashboardData.serviceBreakdown?.length || 0}</div>
                  <div className="relative text-xs text-gray-400">Contributing to cost</div>
                </div>

                {/* Top Service Card */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 relative overflow-hidden group hover:shadow-md transition-all duration-300">
                  <div className="absolute top-0 right-0 w-24 h-24 bg-gradient-to-br from-orange-50 to-amber-50 rounded-bl-full -mr-4 -mt-4 transition-transform group-hover:scale-110"></div>
                  <div className="relative font-medium text-gray-500 text-xs uppercase tracking-wider mb-2">Top Service</div>
                  <div className="relative text-lg font-bold text-gray-900 truncate mb-1" title={getTopService()}>{getTopService()}</div>
                  <div className="relative text-sm font-semibold text-indigo-600">{formatCurrency(getTopServiceCost())}</div>
                </div>
              </div>

              {/* Charts Row */}
              <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                {/* Cost History Chart - Spans 2 cols */}
                <div className="lg:col-span-2 bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex flex-col">
                  <div className="flex justify-between items-center mb-6">
                    <div>
                      <h2 className="text-lg font-bold text-gray-800">Cost History</h2>
                      <p className="text-sm text-gray-500">Monthly spending trends over time</p>
                    </div>
                    <div className="bg-indigo-50 p-2 rounded-lg text-indigo-600">
                      <i className="fas fa-chart-line"></i>
                    </div>
                  </div>
                  <div className="flex-1 min-h-[300px] relative">
                    {dashboardData.costHistory.length > 0 ? (
                      <canvas ref={costHistoryChartRef}></canvas>
                    ) : (
                      <div className="absolute inset-0 flex items-center justify-center text-gray-400 flex-col">
                        <i className="fas fa-chart-area text-4xl mb-2 opacity-50"></i>
                        <p>No cost history data available</p>
                      </div>
                    )}
                  </div>
                </div>

                {/* Service Breakdown Chart */}
                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex flex-col">
                  <div className="flex justify-between items-center mb-6">
                    <div>
                      <h2 className="text-lg font-bold text-gray-800">Service Breakdown</h2>
                      <p className="text-sm text-gray-500">Cost distribution by service</p>
                    </div>
                    <div className="bg-purple-50 p-2 rounded-lg text-purple-600">
                      <i className="fas fa-chart-pie"></i>
                    </div>
                  </div>
                  <div className="flex-1 min-h-[300px] relative">
                    {dashboardData.serviceBreakdown.length > 0 ? (
                      <canvas ref={serviceBreakdownChartRef}></canvas>
                    ) : (
                      <div className="absolute inset-0 flex items-center justify-center text-gray-400 flex-col">
                        <i className="fas fa-chart-pie text-4xl mb-2 opacity-50"></i>
                        <p>No service breakdown data</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              {/* Detailed Service Costs Table */}
              <div className="bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden">
                <div className="p-6 border-b border-gray-100 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 bg-gray-50/30">
                  <div>
                    <h2 className="text-lg font-bold text-gray-800 flex items-center gap-2">
                      Detailed Service Costs
                      <span className="text-xs font-normal text-gray-500 bg-gray-100 px-2 py-0.5 rounded-full">{dashboardData.serviceBreakdown.length} Services</span>
                    </h2>
                  </div>
                  <button
                    onClick={exportToExcel}
                    className="px-4 py-2 bg-white border border-gray-200 text-gray-700 rounded-lg hover:bg-gray-50 hover:text-green-700 hover:border-green-200 transition-all text-sm font-medium flex items-center shadow-sm"
                  >
                    <i className="fas fa-file-excel mr-2 text-green-600"></i>
                    Export to Excel
                  </button>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-gray-50/50 border-b border-gray-100 text-xs uppercase tracking-wider">
                        <th className="px-6 py-4 font-semibold text-gray-500">Service</th>
                        <th className="px-6 py-4 font-semibold text-gray-500 text-right">Cost (INR)</th>
                        <th className="px-6 py-4 font-semibold text-gray-500 text-right w-32">Percentage</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                      {dashboardData.serviceBreakdown && dashboardData.serviceBreakdown.length > 0 ? (
                        dashboardData.serviceBreakdown.map(
                          (service: GcpServiceBreakdownItem, index: number) => (
                            <tr
                              key={service.serviceName + index}
                              className="hover:bg-gray-50/80 transition-colors group"
                            >
                              <td className="px-6 py-4">
                                <div className="flex items-center">
                                  <div className="w-8 h-8 rounded-lg bg-gray-100 flex items-center justify-center mr-3 text-gray-500 group-hover:bg-indigo-50 group-hover:text-indigo-600 transition-colors">
                                    <i className="fas fa-cube text-xs"></i>
                                  </div>
                                  <span className="font-medium text-gray-900">
                                    {service.serviceName}
                                  </span>
                                </div>
                              </td>
                              <td className="px-6 py-4 text-right">
                                <span className="font-semibold text-gray-900">
                                  {formatCurrency(service.amount)}
                                </span>
                              </td>
                              <td className="px-6 py-4 text-right">
                                <div className="flex items-center justify-end gap-3">
                                  <div className="w-16 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                                    <div
                                      className="h-full bg-indigo-500 rounded-full"
                                      style={{ width: getPercentage(service.amount) }}
                                    ></div>
                                  </div>
                                  <span className="text-xs font-medium text-gray-500 w-10">
                                    {getPercentage(service.amount)}
                                  </span>
                                </div>
                              </td>
                            </tr>
                          )
                        )
                      ) : (
                        <tr>
                          <td colSpan={3} className="text-center py-16">
                            <div className="w-16 h-16 bg-gray-50 rounded-full flex items-center justify-center mx-auto mb-3">
                              <i className="fas fa-inbox text-gray-300 text-2xl"></i>
                            </div>
                            <p className="text-gray-500 font-medium">No service cost data available</p>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          {/* No Data State */}
          {!loading && !hasData && !error && (
            <div className="flex flex-col items-center justify-center py-20 bg-white rounded-2xl border border-gray-100 shadow-sm">
              <div className="w-20 h-20 bg-gray-50 rounded-full flex items-center justify-center mb-6">
                <i className="fas fa-inbox text-gray-300 text-4xl"></i>
              </div>
              <h3 className="text-xl font-bold text-gray-900 mb-2">No Data Found</h3>
              <p className="text-gray-500 max-w-sm text-center mb-6"> We couldn't find any billing data for the selected period. Try selecting a different date range.</p>
              <button
                onClick={handleFilterChange}
                className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors shadow-sm font-medium"
              >
                Reset Filters
              </button>
            </div>
          )}
        </div>
      </main>
    </div>
  );
};


export default GcpBillingPage;
