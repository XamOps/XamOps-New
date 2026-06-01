import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Icon } from '@iconify/react';
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

  const formatCurrency = (value: number | null | undefined): string => {
    if (typeof value !== 'number') return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  const formatNumber = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '0';
    const num = Number(value);
    if (isNaN(num)) return '0';
    return num.toLocaleString('en-US', { maximumFractionDigits: 3 });
  };

  const toggleService = useCallback((serviceName: string) => {
    setExpandedServices((prev) => ({
      ...prev,
      [serviceName]: !prev[serviceName],
    }));
  }, []);

  const toggleRegion = useCallback((serviceName: string, regionName: string) => {
    const key = `${serviceName}-${regionName}`;
    setExpandedRegions((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  }, []);

  const handleFilterChange = useCallback(() => {
    setExpandedServices({});
    setExpandedRegions({});
    fetchData(false);
  }, [fetchData]);

  // Cost History Chart
  useEffect(() => {
    if (!costHistoryChartRef.current || !billingData.costHistory?.length) return;

    if (costHistoryChartInstance.current) {
      costHistoryChartInstance.current.destroy();
    }

    const ctx = costHistoryChartRef.current.getContext('2d');
    if (!ctx) return;

    const gradient = ctx.createLinearGradient(0, 0, 0, 400);
    gradient.addColorStop(0, 'rgba(99, 102, 241, 0.8)');
    gradient.addColorStop(0.5, 'rgba(99, 102, 241, 0.4)');
    gradient.addColorStop(1, 'rgba(99, 102, 241, 0.1)');

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

  // Service Breakdown Chart
  useEffect(() => {
    if (!serviceBreakdownChartRef.current || !billingData.serviceBreakdown?.length) return;

    if (serviceBreakdownChartInstance.current) {
      serviceBreakdownChartInstance.current.destroy();
    }

    const ctx = serviceBreakdownChartRef.current.getContext('2d');
    if (!ctx) return;

    const labels = billingData.serviceBreakdown.map((s) => s.name || 'Unknown');
    const data = billingData.serviceBreakdown.map((s) => (s.cost != null ? s.cost : 0));
    const totalCost = data.reduce((acc, curr) => acc + curr, 0);

    const backgroundColors = data.map((_, i) => {
      const colors = [
        ['#6366f1', '#4f46e5'],
        ['#8b5cf6', '#7c3aed'],
        ['#d946ef', '#c026d3'],
        ['#ec4899', '#db2777'],
        ['#f43f5e', '#e11d48'],
        ['#f97316', '#ea580c'],
        ['#10b981', '#059669'],
        ['#0ea5e9', '#0284c7'],
      ];
      const colorPair = colors[i % colors.length];
      const gradient = ctx.createLinearGradient(0, 0, 0, 150);
      gradient.addColorStop(0, colorPair[0]);
      gradient.addColorStop(1, colorPair[1]);
      return gradient;
    });

    const centerTextPlugin = {
      id: 'centerText',
      afterDraw: (chart: any) => {
        const { ctx, chartArea: { left, right, top, bottom } } = chart;
        ctx.save();

        const centerX = (left + right) / 2;
        const centerY = (top + bottom) / 2;

        ctx.font = '600 12px Inter, sans-serif';
        ctx.fillStyle = '#64748b';
        ctx.textAlign = 'center';
        ctx.fillText('TOTAL COST', centerX, centerY - 15);

        ctx.font = 'bold 22px Inter, sans-serif';
        ctx.fillStyle = '#1e293b';
        ctx.textAlign = 'center';
        ctx.fillText(formatCurrency(totalCost), centerX, centerY + 15);

        ctx.restore();
      }
    };

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
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen space-y-6">

      {/* No Accounts Empty State */}
      {!accountsLoading && !hasAccounts && (
        <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:power-plug-outline" width={28} className="text-amber-500" />
          </div>
          <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No Accounts Connected</h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] max-w-sm mx-auto mb-5">
            Connect a cloud account to view your billing data.
          </p>
          <a
            href="/account-manager"
            className="inline-flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            <Icon icon="mdi:plus" width={14} />
            Add Account
          </a>
        </div>
      )}

      {/* Billing content — only when accounts exist */}
      {(accountsLoading || hasAccounts) && <>

      {/* Loading Header Skeleton */}
      {loading && (
        <div className="animate-pulse">
          <div className="bg-white rounded-xl p-5 border border-[rgba(0,0,0,0.12)] shadow-sm">
            <div className="h-[50px] flex justify-between items-center">
              <div className="bg-[rgba(0,0,0,0.06)] h-7 w-48 rounded-lg" />
              <div className="flex items-center gap-3">
                <div className="bg-[rgba(0,0,0,0.06)] h-9 w-24 rounded-lg" />
                <div className="bg-[rgba(0,0,0,0.06)] h-9 w-24 rounded-lg" />
                <div className="bg-[rgba(0,0,0,0.06)] h-9 w-28 rounded-lg" />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Header */}
      {!loading && (
        <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
              <Icon icon="mdi:file-document-multiple-outline" width={18} className="text-indigo-500" />
            </div>
            <div>
              <h1 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Billing Dashboard</h1>
              <p className="text-xs text-[rgba(0,0,0,0.4)]">Overview of your cloud spending and cost distribution</p>
            </div>
          </div>

          <div className="flex items-center gap-1 bg-white p-1 rounded-lg border border-[rgba(0,0,0,0.12)] shadow-sm">
            <select
              value={selectedYear}
              onChange={(e) => {
                setSelectedYear(Number(e.target.value));
                handleFilterChange();
              }}
              className="bg-transparent border-none text-xs font-medium text-[rgba(0,0,0,0.88)] focus:ring-0 cursor-pointer py-1.5 pl-3 pr-7 rounded-md hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              disabled={loading || isUpdating}
            >
              {availableYears.map((year) => (
                <option key={year} value={year}>{year}</option>
              ))}
            </select>
            <div className="w-px h-4 bg-[rgba(0,0,0,0.12)]" />
            <select
              value={selectedMonth}
              onChange={(e) => {
                setSelectedMonth(Number(e.target.value));
                handleFilterChange();
              }}
              className="bg-transparent border-none text-xs font-medium text-[rgba(0,0,0,0.88)] focus:ring-0 cursor-pointer py-1.5 pl-3 pr-7 rounded-md hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              disabled={loading || isUpdating}
            >
              {availableMonths.map((month) => (
                <option key={month.value} value={month.value}>{month.name}</option>
              ))}
            </select>
            <button
              onClick={() => fetchData(true)}
              className="p-1.5 text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] rounded-md transition-all ml-0.5"
              disabled={loading || isUpdating}
            >
              <Icon icon="mdi:refresh" width={15} className={isUpdating ? 'animate-spin' : ''} />
            </button>
          </div>
        </div>
      )}

      {/* Loading Skeleton for Charts and Table */}
      {loading && (
        <div className="animate-pulse space-y-4">
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <div className="bg-white rounded-xl p-5 border border-[rgba(0,0,0,0.12)] shadow-sm">
              <div className="bg-[rgba(0,0,0,0.06)] h-5 w-1/3 rounded mb-4" />
              <div className="bg-[rgba(0,0,0,0.04)] h-[280px] rounded-xl" />
            </div>
            <div className="bg-white rounded-xl p-5 border border-[rgba(0,0,0,0.12)] shadow-sm">
              <div className="bg-[rgba(0,0,0,0.06)] h-5 w-1/3 rounded mb-4" />
              <div className="bg-[rgba(0,0,0,0.04)] h-[280px] rounded-xl" />
            </div>
          </div>
          <div className="bg-white rounded-xl p-5 border border-[rgba(0,0,0,0.12)] shadow-sm">
            <div className="bg-[rgba(0,0,0,0.06)] h-5 w-1/4 mb-5 rounded" />
            <div className="space-y-3">
              {[1, 2, 3, 4, 5].map((i) => (
                <div key={i} className="bg-[rgba(0,0,0,0.04)] h-10 w-full rounded-lg" />
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Error State */}
      {error && !hasData && !loading && (
        <div className="text-center py-12 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm max-w-lg mx-auto">
          <div className="w-14 h-14 bg-red-50 border border-red-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:alert-circle-outline" width={28} className="text-red-500" />
          </div>
          <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">Failed to Load Data</h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] mb-5">{error}</p>
          <button
            onClick={() => fetchData(true)}
            className="px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            Try Again
          </button>
        </div>
      )}

      {/* Update Error Warning */}
      {updateError && hasData && !loading && (
        <div className="p-4 rounded-xl bg-amber-50 border border-amber-100 flex items-start gap-3">
          <Icon icon="mdi:alert-circle-outline" width={18} className="text-amber-500 mt-0.5 shrink-0" />
          <div>
            <h4 className="text-sm font-semibold text-[rgba(0,0,0,0.88)]">Background Update Failed</h4>
            <p className="text-sm text-[rgba(0,0,0,0.5)] mt-0.5">{updateError}</p>
          </div>
        </div>
      )}

      {/* Main Content */}
      {!loading && (hasData || isUpdating) && (
        <div className="space-y-4">

          {/* Charts Row */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

            {/* Cost Over Time Chart */}
            <div className="bg-white p-5 rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)] flex flex-col">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <div className="flex items-center gap-2 mb-0.5">
                    <Icon icon="mdi:chart-bar" width={16} className="text-indigo-500" />
                    <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Cost Over Time</span>
                  </div>
                  <p className="text-xs text-[rgba(0,0,0,0.4)]">Monthly spending trends</p>
                </div>
              </div>
              <div className="flex-1 min-h-[300px] relative">
                {billingData.costHistory.length > 0 ? (
                  <canvas ref={costHistoryChartRef} />
                ) : (
                  <div className="absolute inset-0 flex items-center justify-center flex-col">
                    <Icon icon="mdi:chart-bar" width={36} className="text-[rgba(0,0,0,0.2)] mb-2" />
                    <p className="text-sm text-[rgba(0,0,0,0.4)]">No cost history data available.</p>
                  </div>
                )}
              </div>
            </div>

            {/* Service Breakdown Chart */}
            <div className="bg-white p-5 rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)] flex flex-col">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <div className="flex items-center gap-2 mb-0.5">
                    <Icon icon="mdi:chart-pie" width={16} className="text-purple-500" />
                    <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Service Breakdown</span>
                  </div>
                  <p className="text-xs text-[rgba(0,0,0,0.4)]">Distribution by AWS services</p>
                </div>
              </div>
              <div className="flex-1 min-h-[300px] relative">
                {billingData.serviceBreakdown.length > 0 ? (
                  <canvas ref={serviceBreakdownChartRef} />
                ) : (
                  <div className="absolute inset-0 flex items-center justify-center flex-col">
                    <Icon icon="mdi:chart-pie" width={36} className="text-[rgba(0,0,0,0.2)] mb-2" />
                    <p className="text-sm text-[rgba(0,0,0,0.4)]">No service breakdown data available.</p>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Detailed Cost Breakdown Table */}
          <div className="bg-white rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)] overflow-hidden">
            <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
              <div className="flex items-center gap-2">
                <Icon icon="mdi:table" width={16} className="text-indigo-500" />
                <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Detailed Cost Breakdown</span>
              </div>
              <p className="text-xs text-[rgba(0,0,0,0.4)] mt-0.5 ml-6">
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
                  <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-xs uppercase tracking-wider">
                    <th className="px-4 py-3" />
                    <th className="px-4 py-3 font-bold text-[rgba(0,0,0,0.5)]">Service / Resource</th>
                    <th className="px-4 py-3 font-bold text-[rgba(0,0,0,0.5)] text-right">Usage Quantity</th>
                    <th className="px-4 py-3 font-bold text-[rgba(0,0,0,0.5)] text-right">Amount (USD)</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                  {detailedBreakdown.length > 0 ? (
                    detailedBreakdown.map((service: DetailedServiceBreakdown) => (
                      <React.Fragment key={service.serviceName}>

                        {/* Service Row */}
                        <tr
                          onClick={() => toggleService(service.serviceName)}
                          className={`cursor-pointer transition-colors hover:bg-[rgba(0,0,0,0.025)] ${expandedServices[service.serviceName] ? 'bg-indigo-50/40' : ''}`}
                        >
                          <td className="px-4 py-3.5 text-center text-[rgba(0,0,0,0.3)]">
                            <Icon
                              icon="mdi:chevron-right"
                              width={16}
                              className={`transition-transform duration-200 ${expandedServices[service.serviceName] ? 'rotate-90 text-indigo-500' : ''}`}
                            />
                          </td>
                          <td className="px-4 py-3.5">
                            <div className="flex items-center gap-3">
                              <div className={`w-7 h-7 rounded-lg flex items-center justify-center transition-colors ${expandedServices[service.serviceName] ? 'bg-indigo-100 text-indigo-600' : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.4)]'}`}>
                                <Icon icon="mdi:cube-outline" width={14} />
                              </div>
                              <span className="font-semibold text-[rgba(0,0,0,0.88)] text-sm">
                                {service.serviceName}
                              </span>
                            </div>
                          </td>
                          <td className="px-4 py-3.5 text-right">
                            <span className="text-[rgba(0,0,0,0.2)] text-sm">—</span>
                          </td>
                          <td className="px-4 py-3.5 text-right">
                            <span className="font-bold text-[rgba(0,0,0,0.88)] text-sm">
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
                                className={`cursor-pointer transition-colors hover:bg-[rgba(0,0,0,0.025)] ${expandedRegions[`${service.serviceName}-${region.regionName}`] ? 'bg-[rgba(0,0,0,0.02)]' : ''}`}
                              >
                                <td className="px-4 py-2.5 text-center text-[rgba(0,0,0,0.2)]">
                                  <Icon
                                    icon="mdi:chevron-right"
                                    width={13}
                                    className={`ml-2 transition-transform duration-200 ${expandedRegions[`${service.serviceName}-${region.regionName}`] ? 'rotate-90 text-[rgba(0,0,0,0.5)]' : ''}`}
                                  />
                                </td>
                                <td className="px-4 py-2.5">
                                  <div className="flex items-center gap-2 pl-10">
                                    <Icon icon="mdi:earth" width={13} className="text-[rgba(0,0,0,0.35)]" />
                                    <span className="text-[rgba(0,0,0,0.7)] font-medium text-sm">
                                      {region.regionName}
                                    </span>
                                  </div>
                                </td>
                                <td className="px-4 py-2.5 text-right">
                                  <span className="text-[rgba(0,0,0,0.2)] text-xs">—</span>
                                </td>
                                <td className="px-4 py-2.5 text-right">
                                  <span className="font-medium text-[rgba(0,0,0,0.7)] text-sm">
                                    {formatCurrency(region.cost)}
                                  </span>
                                </td>
                              </tr>

                              {/* Resource Rows */}
                              {expandedRegions[`${service.serviceName}-${region.regionName}`] &&
                                region.resources.map((resource: ResourceItem, idx: number) => (
                                  <tr
                                    key={`${resource.resourceId}-${resource.cost}-${idx}`}
                                    className="hover:bg-[rgba(0,0,0,0.025)] transition-colors"
                                  >
                                    <td className="px-4 py-2" />
                                    <td className="px-4 py-2">
                                      <div className="pl-16 flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 bg-[rgba(0,0,0,0.2)] rounded-full shrink-0" />
                                        <span className="text-[rgba(0,0,0,0.6)] text-xs font-mono truncate max-w-md" title={resource.resourceName}>
                                          {resource.resourceName}
                                        </span>
                                      </div>
                                    </td>
                                    <td className="px-4 py-2 text-right text-[rgba(0,0,0,0.5)] text-xs font-mono">
                                      {resource.quantity != null
                                        ? `${formatNumber(resource.quantity)} ${resource.unit || ''}`
                                        : 'N/A'}
                                    </td>
                                    <td className="px-4 py-2 text-right">
                                      <span className="text-[rgba(0,0,0,0.7)] text-sm font-medium">
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
                      <td colSpan={4} className="text-center py-14">
                        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
                          <Icon icon="mdi:inbox-outline" width={24} className="text-[rgba(0,0,0,0.3)]" />
                        </div>
                        <p className="text-sm text-[rgba(0,0,0,0.4)]">
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
    </main>
  );
};

export default BillingPage;
