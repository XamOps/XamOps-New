/**
 * Azure FinOps Report Page
 *
 * Converted from: azure-finops-report.html
 *
 * Features:
 * - Azure FinOps KPIs (MTD Spend, Forecasted Spend, etc.)
 * - Budget management with create/delete
 * - Report scheduling with email notifications
 * - Cost breakdown by service (doughnut chart)
 * - Cost breakdown by region (horizontal bar chart)
 * - Rightsizing recommendations
 * - Wasted resources identification
 * - Cost anomaly detection
 * - All data fetched from backend via REST APIs
 */

import React, { useState, useEffect, useRef, useMemo } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureFinOpsData } from '../hooks/useAzureFinOpsData';
import { AzureNewBudget, AzureNewSchedule } from '../types/azureFinops';
import { Chart, registerables } from 'chart.js';

// Register Chart.js components
Chart.register(...registerables);

const AzureFinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const subscriptionId = selectedAccountIds[0] || null;

  const {
    reportData,
    schedules,
    isLoading,
    isRefreshing,
    isCreatingBudget,
    isCreatingSchedule,
    error,
    loadReport,
    handleCreateBudget,
    handleDeleteBudget,
    handleCreateSchedule,
    handleDeleteSchedule,
  } = useAzureFinOpsData(subscriptionId);

  // Modal states
  const [isBudgetModalOpen, setIsBudgetModalOpen] = useState(false);
  const [isScheduleModalOpen, setIsScheduleModalOpen] = useState(false);
  const [showScheduleSuccessToast, setShowScheduleSuccessToast] = useState(false);
  const [lastScheduleFrequency, setLastScheduleFrequency] = useState('');

  // Form states
  const [newBudget, setNewBudget] = useState<AzureNewBudget>({
    budgetName: '',
    budgetLimit: null,
    budgetUnit: 'USD'
  });

  const [newSchedule, setNewSchedule] = useState<AzureNewSchedule>({
    email: '',
    frequency: '',
    cloudAccountId: subscriptionId
  });

  // Chart refs
  const serviceChartRef = useRef<HTMLCanvasElement>(null);
  const regionChartRef = useRef<HTMLCanvasElement>(null);
  const serviceChartInstance = useRef<Chart | null>(null);
  const regionChartInstance = useRef<Chart | null>(null);

  // Update cloudAccountId when subscriptionId changes
  useEffect(() => {
    setNewSchedule(prev => ({
      ...prev,
      cloudAccountId: subscriptionId
    }));
  }, [subscriptionId]);

  // Format currency
  const formatCurrency = (value: number): string => {
    return value.toLocaleString('en-US', {
      style: 'currency',
      currency: 'USD'
    });
  };

  // Compute KPIs for display
  const kpis = useMemo(() => {
    if (!reportData) return [];

    const mtdSpend = reportData.kpis?.totalSpend || 0;
    const forecast = reportData.kpis?.forecastedSpend || 0;
    const momChange = reportData.kpis?.momChange || 0;

    // Calculate potential savings from recommendations
    const potentialSavings =
      (reportData.rightsizingRecommendations || []).reduce((sum, rec) => sum + rec.estimatedMonthlySavings, 0) +
      (reportData.wastedResources || []).reduce((sum, res) => sum + res.monthlySavings, 0);

    return [
      {
        label: 'Month-to-Date Spend',
        value: formatCurrency(mtdSpend),
        icon: 'fa-dollar-sign',
        color: { bg: 'bg-blue-100', text: 'text-blue-500' },
        trendIcon: momChange >= 0 ? 'fa-arrow-up' : 'fa-arrow-down',
        trendColor: momChange >= 0 ? 'text-red-500' : 'text-green-500',
        trendText: momChange === 0 ? '' : `${Math.abs(momChange).toFixed(1)}% vs last month`
      },
      {
        label: 'Forecasted Spend',
        value: formatCurrency(forecast),
        icon: 'fa-chart-line',
        color: { bg: 'bg-yellow-100', text: 'text-yellow-500' },
        trendIcon: '',
        trendColor: '',
        trendText: ''
      },
      {
        label: 'YoY Change',
        value: reportData.kpis?.yoyChange ? `${reportData.kpis.yoyChange.toFixed(1)}%` : '0%',
        icon: 'fa-calendar-alt',
        color: { bg: 'bg-gray-100', text: 'text-gray-500' },
        trendIcon: '',
        trendColor: '',
        trendText: ''
      },
      {
        label: 'Potential Savings',
        value: formatCurrency(potentialSavings),
        icon: 'fa-piggy-bank',
        color: { bg: 'bg-green-100', text: 'text-green-500' },
        trendIcon: '',
        trendColor: '',
        trendText: ''
      }
    ];
  }, [reportData]);

  // Render Service Chart (Doughnut)
  useEffect(() => {
    if (!reportData || !serviceChartRef.current || isLoading) return;

    const data = reportData.costBreakdown || [];
    if (data.length === 0) return;

    // Destroy previous chart
    if (serviceChartInstance.current) {
      serviceChartInstance.current.destroy();
    }

    const azureColors = [
      '#0078D4', '#005A9E', '#50E6FF', '#00BCF2', '#8661C5',
      '#5C2D91', '#E81123', '#FFB900', '#00A4EF', '#7FBA00',
      '#00B294', '#F25022'
    ];

    const ctx = serviceChartRef.current.getContext('2d');
    if (!ctx) return;

    serviceChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: data.map(d => d.service),
        datasets: [{
          data: data.map(d => d.cost),
          backgroundColor: azureColors,
          borderWidth: 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'right',
            labels: {
              usePointStyle: true,
              font: { family: "'Inter', sans-serif" }
            }
          }
        }
      }
    });

    return () => {
      if (serviceChartInstance.current) {
        serviceChartInstance.current.destroy();
      }
    };
  }, [reportData, isLoading]);

  // Render Region Chart (Horizontal Bar)
  useEffect(() => {
    if (!reportData || !regionChartRef.current || isLoading) return;

    const data = reportData.costByRegion || [];
    if (data.length === 0) return;

    // Destroy previous chart
    if (regionChartInstance.current) {
      regionChartInstance.current.destroy();
    }

    const ctx = regionChartRef.current.getContext('2d');
    if (!ctx) return;

    regionChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: data.map(d => d.region),
        datasets: [{
          label: 'Cost by Region ($)',
          data: data.map(d => d.cost),
          backgroundColor: [
            '#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA07A', '#98D8C8',
            '#F7DC6F', '#BB8FCE', '#85C1E2', '#F8B88B', '#ABEBC6'
          ],
          borderColor: '#fff',
          borderWidth: 2,
          borderRadius: 8,
          borderSkipped: false
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'y',
        plugins: {
          legend: {
            display: true,
            position: 'top'
          }
        },
        scales: {
          x: {
            grid: {
              color: 'rgba(0, 0, 0, 0.05)'
            },
            ticks: {
              color: '#6b7280',
              callback: function(value) {
                return '$' + (value as number).toFixed(0);
              }
            }
          },
          y: {
            grid: {
              display: false
            },
            ticks: {
              color: '#6b7280'
            }
          }
        }
      }
    });

    return () => {
      if (regionChartInstance.current) {
        regionChartInstance.current.destroy();
      }
    };
  }, [reportData, isLoading]);

  // Handle budget creation
  const onCreateBudget = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!newBudget.budgetName || !newBudget.budgetLimit) {
      alert('Please fill out all fields.');
      return;
    }

    try {
      await handleCreateBudget(newBudget);
      setIsBudgetModalOpen(false);
      setNewBudget({
        budgetName: '',
        budgetLimit: null,
        budgetUnit: 'USD'
      });
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create budget');
    }
  };

  // Handle budget deletion
  const onDeleteBudget = async (budgetName: string) => {
    if (!confirm(`Are you sure you want to delete the budget "${budgetName}"?`)) {
      return;
    }

    try {
      await handleDeleteBudget(budgetName);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete budget');
    }
  };

  // Handle schedule creation
  const onCreateSchedule = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!newSchedule.email || !newSchedule.frequency || !newSchedule.cloudAccountId) {
      alert('Please fill out all fields and ensure a subscription is selected.');
      return;
    }

    try {
      await handleCreateSchedule(newSchedule);
      setIsScheduleModalOpen(false);
      setLastScheduleFrequency(newSchedule.frequency);
      setShowScheduleSuccessToast(true);
      setTimeout(() => setShowScheduleSuccessToast(false), 4000);
      setNewSchedule({
        email: '',
        frequency: '',
        cloudAccountId: subscriptionId
      });
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create schedule');
    }
  };

  // Handle schedule deletion
  const onDeleteSchedule = async (scheduleId: number) => {
    if (!confirm('Are you sure you want to delete this report schedule?')) {
      return;
    }

    try {
      await handleDeleteSchedule(scheduleId);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete schedule');
    }
  };

  // Show error state
  if (error && !reportData) {
    return (
      <div className="flex items-center justify-center h-screen">
        <div className="text-center">
          <i className="fas fa-exclamation-triangle text-red-500 text-5xl mb-4"></i>
          <h2 className="text-2xl font-bold text-gray-800 mb-2">Error Loading Report</h2>
          <p className="text-gray-600 mb-4">{error}</p>
          <button
            onClick={() => loadReport(true)}
            className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
          >
            <i className="fas fa-sync-alt mr-2"></i>
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="azure-finops-page">
      {/* Header */}
      <header className="header-gradient h-20 flex items-center justify-between px-8 sticky top-0 z-10">
        <div className="flex items-center space-x-4">
          <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-sky-600 rounded-xl flex items-center justify-center shadow-lg">
            <i className="fas fa-chart-pie text-white text-xl"></i>
          </div>
          <div>
            <h1 className="text-2xl font-bold text-gray-800">Azure FinOps Report</h1>
            <p className="text-sm text-gray-600">Azure cloud financial operations dashboard</p>
          </div>
        </div>
        <div className="flex items-center space-x-4">
          <div className="flex items-center space-x-2">
            <div className="w-2 h-2 bg-green-500 rounded-full pulse-dot"></div>
            <span className="text-sm text-gray-600">Live data</span>
          </div>
          <button
            onClick={() => loadReport(true)}
            disabled={isRefreshing}
            className="btn-primary flex items-center space-x-2 px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
          >
            <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh Data'}</span>
          </button>
        </div>
      </header>

      <div className="flex-1 p-8 space-y-8">
        {/* KPIs */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {kpis.map((kpi, index) => (
            <div key={index} className="kpi-card rounded-2xl p-6 fade-in">
              {isLoading ? (
                <div className="skeleton h-28 w-full rounded-lg"></div>
              ) : (
                <>
                  <div className="flex justify-between items-start mb-4">
                    <h3 className="text-gray-600 text-sm font-medium">{kpi.label}</h3>
                    <div className={`p-3 rounded-xl ${kpi.color.bg}`}>
                      <i className={`fas ${kpi.icon} text-xl ${kpi.color.text}`}></i>
                    </div>
                  </div>
                  <p className="text-3xl font-bold text-gray-900 mb-2">{kpi.value}</p>
                  {kpi.trendText && (
                    <div className={`trend-indicator ${kpi.trendColor.includes('red') ? 'trend-up' : 'trend-down'}`}>
                      <i className={`fas ${kpi.trendIcon} mr-1`}></i>
                      <span>{kpi.trendText}</span>
                    </div>
                  )}
                </>
              )}
            </div>
          ))}
        </div>

        {/* Budgets & Alerts */}
        <div className="glass-card rounded-2xl p-8 fade-in">
          <div className="flex justify-between items-center mb-6">
            <div className="flex items-center space-x-3">
              <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-wallet text-white"></i>
              </div>
              <div>
                <h3 className="text-xl font-bold text-gray-800">Budgets & Alerts</h3>
                <p className="text-sm text-gray-600">Monitor and control your Azure spending</p>
              </div>
            </div>
            <div className="flex space-x-3">
              <button
                onClick={() => setIsScheduleModalOpen(true)}
                className="btn-secondary px-6 py-3 text-sm font-semibold rounded-lg shadow-md text-gray-700 hover:bg-white transition-all"
              >
                <i className="fas fa-envelope mr-2"></i>Schedule Report
              </button>
              <button
                onClick={() => setIsBudgetModalOpen(true)}
                className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
              >
                <i className="fas fa-plus mr-2"></i>Create Budget
              </button>
            </div>
          </div>

          {isLoading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="skeleton h-32 w-full rounded-xl"></div>
              <div className="skeleton h-32 w-full rounded-xl"></div>
            </div>
          ) : (!reportData?.budgets || reportData.budgets.length === 0) ? (
            <div className="empty-state text-center py-12 rounded-2xl">
              <div className="w-16 h-16 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center mx-auto mb-4 shadow-lg">
                <i className="fas fa-wallet text-white text-2xl"></i>
              </div>
              <h4 className="text-lg font-bold text-gray-800 mb-2">No Budgets Found</h4>
              <p className="text-gray-600 mb-4">Set up budgets to track and control your Azure spending</p>
              <button
                onClick={() => setIsBudgetModalOpen(true)}
                className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg"
              >
                <i className="fas fa-plus mr-2"></i>Create Your First Budget
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {reportData.budgets.map((budget, index) => {
                const percentage = Math.min((budget.actualSpend / budget.budgetLimit) * 100, 100);
                const progressClass = percentage > 80 ? 'progress-bar-danger' : percentage > 60 ? 'progress-bar-warning' : '';

                return (
                  <div key={index} className="bg-white/70 backdrop-blur-sm border border-gray-200/50 rounded-xl p-6 hover:shadow-lg transition-all">
                    <div className="flex items-center justify-between mb-4">
                      <h4 className="font-bold text-gray-800">{budget.budgetName}</h4>
                      <button
                        onClick={() => onDeleteBudget(budget.budgetName)}
                        className="text-red-500 hover:text-red-700 transition-colors"
                      >
                        <i className="fas fa-trash-alt"></i>
                      </button>
                    </div>
                    <div className="space-y-3">
                      <div className="progress-bar h-6">
                        <div
                          className={`progress-bar-fill ${progressClass}`}
                          style={{ width: `${percentage}%` }}
                        ></div>
                      </div>
                      <div className="flex justify-between text-sm font-semibold">
                        <span className="text-gray-700">Spent: {formatCurrency(budget.actualSpend)}</span>
                        <span className="text-gray-600">{Math.round(percentage)}%</span>
                      </div>
                      <div className="flex justify-between text-xs text-gray-500">
                        <span>Limit: {formatCurrency(budget.budgetLimit)}</span>
                        <span>Forecast: {formatCurrency(budget.forecastedSpend)}</span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Scheduled Reports */}
        {!isLoading && schedules.length > 0 && (
          <div className="glass-card rounded-2xl p-8 fade-in">
            <div className="flex items-center space-x-3 mb-6">
              <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-pink-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-calendar-alt text-white"></i>
              </div>
              <div>
                <h3 className="text-xl font-bold text-gray-800">Scheduled Reports</h3>
                <p className="text-sm text-gray-600">Your automated email reports</p>
              </div>
            </div>

            <div className="table-enhanced overflow-x-auto">
              <table className="w-full text-sm text-left text-gray-600">
                <thead className="text-xs text-gray-700 uppercase sticky top-0">
                  <tr>
                    <th className="px-6 py-4">Subscription</th>
                    <th className="px-6 py-4">Email</th>
                    <th className="px-6 py-4">Frequency</th>
                    <th className="px-6 py-4">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200">
                  {schedules.map((schedule) => (
                    <tr key={schedule.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-6 py-4 font-semibold">{schedule.subscriptionName || 'Azure Subscription'}</td>
                      <td className="px-6 py-4">{schedule.email}</td>
                      <td className="px-6 py-4">{schedule.frequency}</td>
                      <td className="px-6 py-4">
                        <button
                          onClick={() => onDeleteSchedule(schedule.id)}
                          className="text-red-500 hover:text-red-700 transition-colors"
                        >
                          <i className="fas fa-trash-alt mr-1"></i>Delete
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* Charts */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
          {/* Cost by Service */}
          <div className="glass-card rounded-2xl p-8 fade-in">
            <div className="flex items-center space-x-3 mb-6">
              <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-chart-pie text-white"></i>
              </div>
              <div>
                <h3 className="text-lg font-bold text-gray-800">Cost by Service</h3>
                <p className="text-sm text-gray-600">Top Azure services</p>
              </div>
            </div>
            {isLoading ? (
              <div className="skeleton h-80 w-full rounded-lg"></div>
            ) : (
              <div className="chart-container h-80">
                <canvas ref={serviceChartRef}></canvas>
              </div>
            )}
          </div>

          {/* Cost by Region */}
          <div className="glass-card rounded-2xl p-8 fade-in">
            <div className="flex items-center space-x-3 mb-6">
              <div className="w-10 h-10 bg-gradient-to-br from-orange-500 to-red-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-globe text-white"></i>
              </div>
              <div>
                <h3 className="text-lg font-bold text-gray-800">Cost by Region</h3>
                <p className="text-sm text-gray-600">Geographic distribution</p>
              </div>
            </div>
            {isLoading ? (
              <div className="skeleton h-80 w-full rounded-lg"></div>
            ) : (
              <div className="chart-container h-80">
                <canvas ref={regionChartRef}></canvas>
              </div>
            )}
          </div>
        </div>

        {/* Insights & Opportunities */}
        {!isLoading && reportData && (
          (reportData.rightsizingRecommendations.length > 0 ||
            reportData.wastedResources.length > 0 ||
            reportData.costAnomalies.length > 0) && (
            <div className="glass-card rounded-2xl p-8 fade-in">
              <div className="flex items-center space-x-3 mb-6">
                <div className="w-10 h-10 bg-gradient-to-br from-yellow-500 to-orange-600 rounded-xl flex items-center justify-center shadow-lg">
                  <i className="fas fa-lightbulb text-white"></i>
                </div>
                <div>
                  <h3 className="text-xl font-bold text-gray-800">Insights & Opportunities</h3>
                  <p className="text-sm text-gray-600">Optimize your Azure spending</p>
                </div>
              </div>

              {/* Cost Anomalies */}
              {reportData.costAnomalies.length > 0 && (
                <div className="mb-8">
                  <h4 className="text-lg font-semibold text-gray-800 mb-4 flex items-center">
                    <i className="fas fa-exclamation-triangle text-red-500 mr-2"></i>
                    Cost Anomaly Detection
                  </h4>
                  <div className="table-enhanced overflow-x-auto">
                    <table className="w-full text-sm text-left text-gray-600">
                      <thead className="text-xs text-gray-700 uppercase sticky top-0">
                        <tr>
                          <th className="px-6 py-4">Service</th>
                          <th className="px-6 py-4">Date Range</th>
                          <th className="px-6 py-4">Unexpected Spend</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-200">
                        {reportData.costAnomalies.map((anomaly) => (
                          <tr key={anomaly.anomalyId} className="hover:bg-gray-50 transition-colors">
                            <td className="px-6 py-4 font-semibold">{anomaly.service}</td>
                            <td className="px-6 py-4">{`${anomaly.startDate} to ${anomaly.endDate}`}</td>
                            <td className="px-6 py-4 text-red-600 font-bold">{formatCurrency(anomaly.unexpectedSpend)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {/* Rightsizing Recommendations */}
              {reportData.rightsizingRecommendations.length > 0 && (
                <div className="mb-8">
                  <h4 className="text-lg font-semibold text-gray-800 mb-4 flex items-center">
                    <i className="fas fa-cogs text-green-500 mr-2"></i>
                    Rightsizing Recommendations
                  </h4>
                  <div className="table-enhanced overflow-x-auto">
                    <table className="w-full text-sm text-left text-gray-600">
                      <thead className="text-xs text-gray-700 uppercase sticky top-0">
                        <tr>
                          <th className="px-6 py-4">Resource ID</th>
                          <th className="px-6 py-4">Service</th>
                          <th className="px-6 py-4">Current</th>
                          <th className="px-6 py-4">Recommended</th>
                          <th className="px-6 py-4">Savings</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-200">
                        {reportData.rightsizingRecommendations.map((rec) => (
                          <tr key={rec.resourceId} className="hover:bg-gray-50 transition-colors">
                            <td className="px-6 py-4 font-mono text-xs">{rec.resourceId}</td>
                            <td className="px-6 py-4">{rec.service}</td>
                            <td className="px-6 py-4">{rec.currentType}</td>
                            <td className="px-6 py-4 text-green-700 font-semibold">{rec.recommendedType}</td>
                            <td className="px-6 py-4 text-green-700 font-bold">{formatCurrency(rec.estimatedMonthlySavings)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {/* Wasted Resources */}
              {reportData.wastedResources.length > 0 && (
                <div>
                  <h4 className="text-lg font-semibold text-gray-800 mb-4 flex items-center">
                    <i className="fas fa-trash-alt text-red-500 mr-2"></i>
                    Wasted Resources
                  </h4>
                  <div className="table-enhanced overflow-x-auto">
                    <table className="w-full text-sm text-left text-gray-600">
                      <thead className="text-xs text-gray-700 uppercase sticky top-0">
                        <tr>
                          <th className="px-6 py-4">Resource ID</th>
                          <th className="px-6 py-4">Type</th>
                          <th className="px-6 py-4">Region</th>
                          <th className="px-6 py-4">Reason</th>
                          <th className="px-6 py-4">Savings</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-200">
                        {reportData.wastedResources.map((res) => (
                          <tr key={res.resourceId} className="hover:bg-gray-50 transition-colors">
                            <td className="px-6 py-4 font-mono text-xs">{res.resourceId}</td>
                            <td className="px-6 py-4">{res.resourceType}</td>
                            <td className="px-6 py-4">{res.region}</td>
                            <td className="px-6 py-4">{res.reason}</td>
                            <td className="px-6 py-4 text-red-600 font-bold">{formatCurrency(res.monthlySavings)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          )
        )}
      </div>

      {/* Budget Modal */}
      {isBudgetModalOpen && (
        <div
          className="modal-overlay fixed inset-0 flex items-center justify-center z-50"
          onClick={() => setIsBudgetModalOpen(false)}
        >
          <div
            className="modal-content rounded-2xl p-8 w-full max-w-md mx-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center space-x-3 mb-6">
              <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-wallet text-white"></i>
              </div>
              <h3 className="text-xl font-bold text-gray-800">Create New Budget</h3>
            </div>

            <form onSubmit={onCreateBudget}>
              <div className="space-y-6">
                <div>
                  <label htmlFor="budgetName" className="block text-sm font-semibold text-gray-700 mb-2">
                    Budget Name
                  </label>
                  <input
                    type="text"
                    id="budgetName"
                    value={newBudget.budgetName}
                    onChange={(e) => setNewBudget({ ...newBudget, budgetName: e.target.value })}
                    className="tag-input w-full px-4 py-3 rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                    required
                  />
                </div>
                <div>
                  <label htmlFor="budgetLimit" className="block text-sm font-semibold text-gray-700 mb-2">
                    Budget Limit (USD)
                  </label>
                  <input
                    type="number"
                    id="budgetLimit"
                    value={newBudget.budgetLimit || ''}
                    onChange={(e) => setNewBudget({ ...newBudget, budgetLimit: parseFloat(e.target.value) })}
                    className="tag-input w-full px-4 py-3 rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                    required
                    min="1"
                  />
                </div>
              </div>

              <div className="flex justify-end space-x-3 mt-8">
                <button
                  type="button"
                  onClick={() => setIsBudgetModalOpen(false)}
                  className="btn-secondary px-6 py-3 text-sm font-semibold rounded-lg"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isCreatingBudget}
                  className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
                >
                  {isCreatingBudget ? (
                    <span className="flex items-center">
                      <div className="loading-spinner mr-2"></div>
                      Creating...
                    </span>
                  ) : (
                    'Create Budget'
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Schedule Modal */}
      {isScheduleModalOpen && (
        <div
          className="modal-overlay fixed inset-0 flex items-center justify-center z-50"
          onClick={() => setIsScheduleModalOpen(false)}
        >
          <div
            className="modal-content rounded-2xl p-8 w-full max-w-md mx-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center space-x-3 mb-6">
              <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-pink-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-envelope text-white"></i>
              </div>
              <h3 className="text-xl font-bold text-gray-800">Schedule Azure FinOps Report</h3>
            </div>

            <form onSubmit={onCreateSchedule}>
              <div className="space-y-6">
                <div>
                  <label htmlFor="scheduleEmail" className="block text-sm font-semibold text-gray-700 mb-2">
                    Email Address
                  </label>
                  <input
                    type="email"
                    id="scheduleEmail"
                    value={newSchedule.email}
                    onChange={(e) => setNewSchedule({ ...newSchedule, email: e.target.value })}
                    className="tag-input w-full px-4 py-3 rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                    placeholder="report-recipient@example.com"
                    required
                  />
                </div>
                <div>
                  <label htmlFor="scheduleFrequency" className="block text-sm font-semibold text-gray-700 mb-2">
                    Frequency
                  </label>
                  <select
                    id="scheduleFrequency"
                    value={newSchedule.frequency}
                    onChange={(e) => setNewSchedule({ ...newSchedule, frequency: e.target.value })}
                    className="tag-input w-full px-4 py-3 rounded-lg border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-200"
                    required
                  >
                    <option value="" disabled>Select a timeline</option>
                    <option value="DAILY">Daily (at 6 PM)</option>
                    <option value="WEEKLY">Weekly (Friday at 6 PM)</option>
                    <option value="MONTHLY">Monthly (30th at 6 PM)</option>
                  </select>
                </div>
              </div>

              <div className="flex justify-end space-x-3 mt-8">
                <button
                  type="button"
                  onClick={() => setIsScheduleModalOpen(false)}
                  className="btn-secondary px-6 py-3 text-sm font-semibold rounded-lg"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isCreatingSchedule}
                  className="btn-primary px-6 py-3 text-sm font-semibold rounded-lg shadow-md"
                >
                  {isCreatingSchedule ? (
                    <span className="flex items-center">
                      <div className="loading-spinner mr-2"></div>
                      Saving...
                    </span>
                  ) : (
                    'Create Schedule'
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Success Toast */}
      {showScheduleSuccessToast && (
        <div className="toast-notification show">
          <i className="fas fa-check-circle mr-3 text-2xl"></i>
          <div>
            <span className="font-bold">Schedule Created!</span>
            <span className="block text-sm">Your {lastScheduleFrequency.toLowerCase()} report is set up.</span>
          </div>
          <button onClick={() => setShowScheduleSuccessToast(false)} className="ml-4 text-lg">&times;</button>
        </div>
      )}
    </div>
  );
};

export default AzureFinOpsPage;
