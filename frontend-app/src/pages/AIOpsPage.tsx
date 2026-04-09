import React, { useState, useCallback, useEffect } from 'react';
import { useTopbar } from '../contexts/TopbarContext';
import { useAIOpsData } from '../hooks/useAIOpsData';
import { useUsageAnalyticsData } from '../hooks/useUsageAnalyticsData';
import { useBudgetData } from '../hooks/useBudgetData';
import { AriaTabGroup } from '../components/ui/Tabs';
import AIOpsKPISection from '../components/AIOps/AIOpsKPISection';
import AIOpsCharts from '../components/AIOps/AIOpsCharts';
import AIOpsModelTable from '../components/AIOps/AIOpsModelTable';
import RequestLogsTable from '../components/AIOps/RequestLogsTable';
import AgentBreakdownSection from '../components/AIOps/AgentBreakdownSection';
import ModelComparisonSection from '../components/AIOps/ModelComparisonSection';
import TokenHeatmap from '../components/AIOps/TokenHeatmap';
import LLMProviderSetupModal from '../components/AIOps/LLMProviderSetupModal';
import BudgetOverview from '../components/AIOps/BudgetOverview';
import BudgetConfigModal from '../components/AIOps/BudgetConfigModal';
import type { LlmProviderConfig, CostAnomaly, BudgetConfig } from '../types/aiops';
import { PROVIDER_COLORS } from '../types/aiops';
import '../styles/aiops.css';

const PERIODS = [
  { key: 'today', label: 'Today' },
  { key: '7d', label: '7 Days' },
  { key: '30d', label: '30 Days' },
  { key: '90d', label: '90 Days' },
  { key: '365d', label: '1 Year' },
] as const;

const TOP_TABS = [
  { id: 'dashboard', label: 'LLM Dashboard', icon: 'fas fa-chart-line' },
  { id: 'usage-analytics', label: 'Usage Analytics', icon: 'fas fa-microscope' },
  { id: 'budget-controls', label: 'Budget & Controls', icon: 'fas fa-wallet' },
];

const USAGE_SUB_TABS = [
  { id: 'request-logs', label: 'Request Logs', icon: 'fas fa-list-alt' },
  { id: 'agents', label: 'Agents', icon: 'fas fa-robot' },
  { id: 'models', label: 'Models', icon: 'fas fa-balance-scale' },
  { id: 'heatmap', label: 'Token Heatmap', icon: 'fas fa-th' },
];

// ─── Anomaly Alerts Component ────────────────────────────────
function AnomalyAlerts({ anomalies }: { anomalies: CostAnomaly[] }) {
  if (anomalies.length === 0) return null;

  return (
    <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
        <div className="w-8 h-8 bg-gradient-to-br from-red-500 to-rose-600 rounded-lg flex items-center justify-center">
          <i className="fas fa-exclamation-triangle text-white text-xs" />
        </div>
        <h3 className="text-sm font-bold text-gray-800">Cost Anomalies</h3>
        <span className="text-xs text-red-500 bg-red-50 px-2 py-0.5 rounded-full font-semibold">
          {anomalies.length} detected
        </span>
      </div>
      <div className="divide-y divide-gray-100">
        {anomalies.map((a, idx) => (
          <div key={idx} className="px-6 py-3 flex items-center justify-between hover:bg-red-50/30 transition-colors">
            <div className="flex items-center gap-4">
              <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${
                a.severity === 'CRITICAL'
                  ? 'bg-red-100 text-red-700'
                  : 'bg-amber-100 text-amber-700'
              }`}>
                {a.severity}
              </span>
              <div>
                <p className="text-sm font-medium text-gray-900">
                  {a.provider} cost spike on {a.date}
                </p>
                <p className="text-xs text-gray-500">
                  Actual: ${a.actualCost.toFixed(4)} | Expected: ${a.expectedCost.toFixed(4)} | +{a.deviationPct.toFixed(0)}% deviation
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── Skeleton loader ─────────────────────────────────────────
function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse bg-gray-200/60 rounded-2xl ${className}`} />;
}

// ─── Provider Badge ──────────────────────────────────────────
function ProviderBadge({ config, onCopy }: { config: LlmProviderConfig; onCopy: (text: string) => void }) {
  return (
    <div className="aiops-provider-badge flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg text-xs shadow-sm">
      <span
        className="w-2 h-2 rounded-full"
        style={{ backgroundColor: PROVIDER_COLORS[config.provider] || '#94a3b8' }}
      />
      <span className="font-medium text-gray-700">{config.displayName || config.provider}</span>
      {config.trackingToken && (
        <button
          onClick={() => onCopy(config.trackingToken!)}
          className="text-gray-400 hover:text-indigo-600 transition-colors"
          title="Copy tracking key"
        >
          <i className="fas fa-key" />
        </button>
      )}
    </div>
  );
}

// ─── Usage Analytics Filters ─────────────────────────────────
function UsageAnalyticsFilters({
  filterOptions,
  filters,
  onFiltersChange,
}: {
  filterOptions: { providers: string[]; models: string[]; agents: string[] } | null;
  filters: { provider: string; model: string; agent: string };
  onFiltersChange: (filters: { provider: string; model: string; agent: string }) => void;
}) {
  if (!filterOptions) return null;

  return (
    <div className="flex items-center gap-3 flex-wrap">
      <span className="text-xs text-gray-500 font-medium">
        <i className="fas fa-filter mr-1" />Filters:
      </span>
      <select
        value={filters.provider}
        onChange={e => onFiltersChange({ ...filters, provider: e.target.value })}
        className="text-xs border border-gray-200 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-indigo-500 outline-none bg-white"
      >
        <option value="">All Providers</option>
        {filterOptions.providers.map(p => (
          <option key={p} value={p}>{p}</option>
        ))}
      </select>
      <select
        value={filters.model}
        onChange={e => onFiltersChange({ ...filters, model: e.target.value })}
        className="text-xs border border-gray-200 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-indigo-500 outline-none bg-white"
      >
        <option value="">All Models</option>
        {filterOptions.models.map(m => (
          <option key={m} value={m}>{m}</option>
        ))}
      </select>
      <select
        value={filters.agent}
        onChange={e => onFiltersChange({ ...filters, agent: e.target.value })}
        className="text-xs border border-gray-200 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-indigo-500 outline-none bg-white"
      >
        <option value="">All Agents</option>
        {filterOptions.agents.map(a => (
          <option key={a} value={a}>{a}</option>
        ))}
      </select>
      {(filters.provider || filters.model || filters.agent) && (
        <button
          onClick={() => onFiltersChange({ provider: '', model: '', agent: '' })}
          className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
        >
          Clear all
        </button>
      )}
    </div>
  );
}

// ─── Main Page ───────────────────────────────────────────────
const AIOpsPage: React.FC = () => {
  const [period, setPeriod] = useState('30d');
  const [activeTab, setActiveTab] = useState('dashboard');
  const [activeSubTab, setActiveSubTab] = useState('request-logs');
  const [isProviderModalOpen, setIsProviderModalOpen] = useState(false);
  const [copiedUrl, setCopiedUrl] = useState(false);

  const {
    kpis, allTimeMetrics, costTrend, providerBreakdown, modelUsageTable,
    anomalies, providers, sectionLoading, isLoading, error,
    loadDashboard, addProvider, deleteProvider,
  } = useAIOpsData({ period });

  const analytics = useUsageAnalyticsData(period);
  const budget = useBudgetData();
  const [isBudgetModalOpen, setIsBudgetModalOpen] = useState(false);
  const [editingBudget, setEditingBudget] = useState<BudgetConfig | null>(null);

  // Load filter options when switching to Usage Analytics
  useEffect(() => {
    if (activeTab === 'usage-analytics') {
      analytics.loadFilterOptions();
    }
  }, [activeTab]);

  // Load budget summary when switching to Budget & Controls
  useEffect(() => {
    if (activeTab === 'budget-controls') {
      budget.loadSummary();
    }
  }, [activeTab]);

  // Load daily spend sparklines for each budget
  useEffect(() => {
    if (activeTab === 'budget-controls' && budget.summary) {
      budget.summary.budgets.forEach(b => {
        if (!budget.dailySpend[b.budgetId]) {
          budget.loadDailySpend(b.budgetId);
        }
      });
    }
  }, [activeTab, budget.summary]);

  // Load sub-tab data when switching sub-tabs or filters change
  useEffect(() => {
    if (activeTab !== 'usage-analytics') return;

    switch (activeSubTab) {
      case 'request-logs':
        analytics.loadRequestLogs();
        break;
      case 'agents':
        analytics.loadAgentBreakdown();
        break;
      case 'models':
        analytics.loadModelComparison();
        break;
      case 'heatmap':
        analytics.loadTokenHeatmap();
        break;
    }
  }, [activeTab, activeSubTab, period, analytics.filters, analytics.logsPage, analytics.logsSize]);

  // Topbar integration
  const { setActions, clearActions } = useTopbar();
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {providers.length > 0 && (
          <button
            onClick={() => loadDashboard()}
            disabled={isLoading}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
          >
            <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`} />
            Refresh
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, providers.length, isLoading, loadDashboard]);

  const handleCopyUrl = useCallback((url: string) => {
    navigator.clipboard.writeText(url);
    setCopiedUrl(true);
    setTimeout(() => setCopiedUrl(false), 2000);
  }, []);

  // Agent drill-down: click agent → switch to request-logs filtered by that agent
  const handleAgentClick = useCallback((agentName: string) => {
    analytics.setFilters({ ...analytics.filters, agent: agentName });
    setActiveSubTab('request-logs');
  }, [analytics.filters, analytics.setFilters]);

  // CSV export for request logs
  const handleExportCsv = useCallback(() => {
    const logs = analytics.requestLogs?.content;
    if (!logs || logs.length === 0) return;

    const headers = ['Timestamp', 'Provider', 'Model', 'Agent', 'Input Tokens', 'Output Tokens', 'Cost', 'Latency (ms)', 'Status'];
    const rows = logs.map(l => [
      l.timestamp,
      l.provider,
      l.model,
      l.agentName || '',
      l.inputTokens,
      l.outputTokens,
      l.cost,
      l.latencyMs,
      l.statusCode,
    ]);

    const csv = [headers.join(','), ...rows.map(r => r.join(','))].join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `llm-request-logs-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }, [analytics.requestLogs]);

  const hasProviders = providers.length > 0;

  return (
    <main className="aiops-page flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header */}
      <div className="sticky top-0 z-10 px-6 py-4 border-b border-gray-200 bg-white/80 backdrop-blur-md">
        <div className="max-w-7xl mx-auto flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-sm">
                <i className="fas fa-brain text-white text-sm" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-gray-900">AI Ops</h1>
                <p className="text-xs text-gray-500">Monitor your AI API usage & costs</p>
              </div>
            </div>

            {hasProviders && (
              <div className="flex items-center bg-gray-100 rounded-lg p-0.5 ml-4">
                {PERIODS.map(p => (
                  <button
                    key={p.key}
                    onClick={() => setPeriod(p.key)}
                    className={`aiops-period-btn px-3 py-1.5 text-xs font-medium rounded-md transition-all ${
                      period === p.key ? 'active' : 'text-gray-600 hover:text-gray-900'
                    }`}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="flex items-center gap-3">
            {providers.map(p => (
              <ProviderBadge key={p.id} config={p} onCopy={handleCopyUrl} />
            ))}
            <button
              onClick={() => setIsProviderModalOpen(true)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all shadow-sm hover:shadow-md"
            >
              <i className="fas fa-plus" />
              Add Provider
            </button>
          </div>
        </div>
      </div>

      {/* Top-Level Tabs */}
      {hasProviders && (
        <div className="px-6 bg-white border-b border-gray-200">
          <div className="max-w-7xl mx-auto">
            <AriaTabGroup
              tabs={TOP_TABS}
              selectedKey={activeTab}
              onSelectionChange={setActiveTab}
              variant="underline"
            />
          </div>
        </div>
      )}

      {/* Copied toast */}
      {copiedUrl && (
        <div className="fixed top-20 right-6 z-50 flex items-center gap-2 px-4 py-2 bg-green-500 text-white rounded-lg shadow-xl text-sm font-medium">
          <i className="fas fa-check-circle" />
          Copied to clipboard!
        </div>
      )}

      {/* Main Content */}
      <div className="flex-1 p-6">
        <div className="max-w-7xl mx-auto space-y-6">
          {/* Error State */}
          {error && (
            <div className="bg-red-50 border border-red-200 rounded-xl p-4 flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-500" />
              <p className="text-sm text-red-700">{error}</p>
              <button
                onClick={() => loadDashboard()}
                className="ml-auto text-sm text-red-600 hover:text-red-800 font-medium"
              >
                Retry
              </button>
            </div>
          )}

          {/* Empty State */}
          {!hasProviders && !isLoading && !error && (
            <div className="relative overflow-hidden bg-white rounded-2xl shadow-xl">
              <div className="absolute inset-0 opacity-5">
                <div className="absolute inset-0" style={{
                  backgroundImage: 'repeating-linear-gradient(45deg, transparent, transparent 10px, rgba(99, 102, 241, 0.1) 10px, rgba(99, 102, 241, 0.1) 20px)'
                }} />
              </div>
              <div className="absolute -top-24 -right-24 w-64 h-64 bg-gradient-to-br from-indigo-400/20 to-purple-400/20 rounded-full blur-3xl" />
              <div className="absolute -bottom-24 -left-24 w-64 h-64 bg-gradient-to-tr from-purple-400/20 to-pink-400/20 rounded-full blur-3xl" />

              <div className="relative z-10 text-center py-20 px-8">
                <div className="mb-8 flex justify-center">
                  <div className="w-24 h-24 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center shadow-2xl">
                    <i className="fas fa-plug text-white text-4xl" />
                  </div>
                </div>
                <h2 className="text-3xl font-bold text-gray-800 mb-3">
                  Connect Your First LLM Provider
                </h2>
                <p className="text-gray-600 mb-8 max-w-2xl mx-auto text-lg leading-relaxed">
                  Connect your OpenAI, Anthropic, Google Gemini, or AWS Bedrock provider to start
                  tracking costs, tokens, and latency across all your AI applications.
                  No API keys stored — just run a curl command.
                </p>

                <button
                  onClick={() => setIsProviderModalOpen(true)}
                  className="inline-flex items-center px-8 py-4 bg-gradient-to-r from-indigo-500 to-purple-600 text-white rounded-xl shadow-xl hover:shadow-2xl transform hover:scale-105 transition-all duration-300 text-lg font-semibold"
                >
                  <i className="fas fa-plus-circle mr-3 text-xl" />
                  Add LLM Provider
                </button>

                <div className="mt-12 grid grid-cols-1 md:grid-cols-3 gap-6 max-w-3xl mx-auto">
                  {[
                    { icon: 'fas fa-key', title: '1. Get XamOps Key', desc: 'Select provider & get tracking key' },
                    { icon: 'fas fa-terminal', title: '2. Run Curl Command', desc: 'Use our proxy + your API key' },
                    { icon: 'fas fa-chart-bar', title: '3. See Insights', desc: 'Dashboard refreshes with usage' },
                  ].map(step => (
                    <div key={step.title} className="flex flex-col items-center p-4">
                      <div className="w-12 h-12 bg-indigo-100 rounded-full flex items-center justify-center mb-3">
                        <i className={`${step.icon} text-indigo-600`} />
                      </div>
                      <p className="font-semibold text-gray-900 text-sm">{step.title}</p>
                      <p className="text-xs text-gray-500 mt-1">{step.desc}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 1: LLM Dashboard
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'dashboard' && (hasProviders || isLoading) && (
            <>
              {/* KPIs */}
              {sectionLoading.kpis ? (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                  {Array.from({ length: 4 }).map((_, i) => (
                    <Skeleton key={i} className="h-32" />
                  ))}
                </div>
              ) : kpis && (
                <AIOpsKPISection kpis={kpis} allTimeMetrics={allTimeMetrics} />
              )}

              {/* Charts */}
              {sectionLoading.charts ? (
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                  <Skeleton className="h-72" />
                  <Skeleton className="h-72" />
                </div>
              ) : costTrend && (
                <AIOpsCharts costTrend={costTrend} providerBreakdown={providerBreakdown} />
              )}

              {/* Model Usage Table */}
              {sectionLoading.table ? (
                <Skeleton className="h-64" />
              ) : modelUsageTable.length > 0 && (
                <AIOpsModelTable modelUsage={modelUsageTable} />
              )}

              {/* Anomaly Alerts */}
              {!sectionLoading.anomalies && (
                <AnomalyAlerts anomalies={anomalies} />
              )}

              {/* Proxy Endpoints Card */}
              {hasProviders && !sectionLoading.kpis && (
                <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm">
                  <div className="flex items-center gap-3 mb-4">
                    <div className="w-8 h-8 bg-gradient-to-br from-green-500 to-emerald-600 rounded-lg flex items-center justify-center">
                      <i className="fas fa-link text-white text-xs" />
                    </div>
                    <h3 className="text-sm font-bold text-gray-800">Your Providers</h3>
                    <span className="text-xs text-gray-400">Use X-XamOps-Key header + Authorization: Bearer YOUR_KEY</span>
                  </div>
                  <div className="space-y-3">
                    {providers.map(p => (
                      <div key={p.id} className="p-3 bg-gray-50 rounded-lg border border-gray-100">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-3">
                            <span
                              className="w-3 h-3 rounded-full"
                              style={{ backgroundColor: PROVIDER_COLORS[p.provider] || '#94a3b8' }}
                            />
                            <span className="text-sm font-medium text-gray-700">{p.displayName || p.provider}</span>
                          </div>
                          <div className="flex items-center gap-2">
                            <button
                              onClick={() => handleCopyUrl(p.trackingToken!)}
                              className="text-indigo-500 hover:text-indigo-700 transition-colors text-xs font-medium"
                              title="Copy tracking key"
                            >
                              <i className="fas fa-key mr-1" />Copy Key
                            </button>
                            <button
                              onClick={() => {
                                if (window.confirm(`Delete ${p.displayName || p.provider} provider?`)) {
                                  deleteProvider(p.id!);
                                }
                              }}
                              className="text-red-400 hover:text-red-600 transition-colors text-sm ml-1"
                              title="Delete"
                            >
                              <i className="fas fa-trash" />
                            </button>
                          </div>
                        </div>
                        <div className="mt-2 flex items-center gap-2">
                          <span className="text-xs text-gray-400">Key:</span>
                          <code className="text-xs text-gray-500 bg-white px-2 py-1 rounded border border-gray-200 truncate flex-1 font-mono">
                            {p.trackingToken}
                          </code>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="mt-4 p-3 bg-blue-50 rounded-lg border border-blue-100">
                    <p className="text-xs text-blue-700">
                      <i className="fas fa-info-circle mr-1" />
                      Proxy URL: <code className="bg-blue-100 px-1 rounded font-mono">{providers[0]?.proxyBaseUrl}</code> — pass <code className="bg-blue-100 px-1 rounded">X-XamOps-Key</code> header and <code className="bg-blue-100 px-1 rounded">Authorization: Bearer YOUR_API_KEY</code> in every request.
                    </p>
                  </div>
                </div>
              )}
            </>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 2: Usage Analytics
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'usage-analytics' && hasProviders && (
            <div className="space-y-4">
              {/* Filters + Sub-tabs header */}
              <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm p-4 space-y-3">
                <UsageAnalyticsFilters
                  filterOptions={analytics.filterOptions}
                  filters={analytics.filters}
                  onFiltersChange={analytics.setFilters}
                />
                <AriaTabGroup
                  tabs={USAGE_SUB_TABS}
                  selectedKey={activeSubTab}
                  onSelectionChange={setActiveSubTab}
                  variant="pill"
                />
              </div>

              {/* Sub-tab Content */}
              {activeSubTab === 'request-logs' && (
                <RequestLogsTable
                  data={analytics.requestLogs}
                  loading={analytics.logsLoading}
                  page={analytics.logsPage}
                  size={analytics.logsSize}
                  onPageChange={analytics.setLogsPage}
                  onSizeChange={analytics.setLogsSize}
                  onExport={handleExportCsv}
                />
              )}

              {activeSubTab === 'agents' && (
                <AgentBreakdownSection
                  data={analytics.agentBreakdown}
                  loading={analytics.agentLoading}
                  onAgentClick={handleAgentClick}
                />
              )}

              {activeSubTab === 'models' && (
                <ModelComparisonSection
                  data={analytics.modelComparison}
                  loading={analytics.modelLoading}
                />
              )}

              {activeSubTab === 'heatmap' && (
                <TokenHeatmap
                  data={analytics.tokenHeatmap}
                  loading={analytics.heatmapLoading}
                />
              )}
            </div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 3: Budget & Controls
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'budget-controls' && hasProviders && (
            <BudgetOverview
              summary={budget.summary}
              loading={budget.summaryLoading}
              onAdd={() => { setEditingBudget(null); setIsBudgetModalOpen(true); }}
              onEdit={(budgetId) => {
                const found = budget.summary?.budgets.find(b => b.budgetId === budgetId);
                if (found) {
                  setEditingBudget({
                    id: found.budgetId,
                    providerConfigId: found.providerConfigId ?? null,
                    providerName: found.providerName ?? null,
                    budgetName: found.budgetName,
                    monthlyLimitUsd: found.monthlyLimitUsd,
                    alertThresholds: found.thresholds.join(','),
                    autoDisable: found.autoDisable,
                    status: found.status,
                    notificationEmail: null,
                  });
                  setIsBudgetModalOpen(true);
                }
              }}
              onDelete={(budgetId) => budget.deleteBudget(budgetId)}
              onDismissAlert={(alertId) => budget.dismissAlert(alertId)}
              onToggleStatus={(budgetId, newStatus) => budget.toggleBudgetStatus(budgetId, newStatus)}
              dailySpend={budget.dailySpend}
            />
          )}
        </div>
      </div>

      {/* Provider Setup Modal */}
      <LLMProviderSetupModal
        isOpen={isProviderModalOpen}
        onClose={() => setIsProviderModalOpen(false)}
        onSave={addProvider}
        existingProviders={providers}
      />

      {/* Budget Config Modal */}
      <BudgetConfigModal
        isOpen={isBudgetModalOpen}
        onClose={() => { setIsBudgetModalOpen(false); setEditingBudget(null); }}
        onSave={budget.createBudget}
        onUpdate={budget.updateBudget}
        editBudget={editingBudget}
        providers={providers}
      />
    </main>
  );
};

export default AIOpsPage;
