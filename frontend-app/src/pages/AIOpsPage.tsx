import React, { useState, useCallback, useEffect } from 'react';
import { useTopbar } from '../contexts/TopbarContext';
import { useAIOpsData } from '../hooks/useAIOpsData';
import { useUsageAnalyticsData } from '../hooks/useUsageAnalyticsData';
import { useBudgetData } from '../hooks/useBudgetData';
import { AriaTabGroup } from '../components/ui/Tabs';
import LLMDashboardTab from '../components/AIOps/LLMDashboardTab';
import RequestLogsTable from '../components/AIOps/RequestLogsTable';
import AgentBreakdownSection from '../components/AIOps/AgentBreakdownSection';
import ModelComparisonSection from '../components/AIOps/ModelComparisonSection';
import TokenHeatmap from '../components/AIOps/TokenHeatmap';
import LLMProviderSetupModal from '../components/AIOps/LLMProviderSetupModal';
import BudgetOverview from '../components/AIOps/BudgetOverview';
import BudgetConfigModal from '../components/AIOps/BudgetConfigModal';
import TeamBreakdownSection from '../components/AIOps/TeamBreakdownSection';
import PerformanceSection from '../components/AIOps/PerformanceSection';
import InsightsDashboard from '../components/AIOps/InsightsDashboard';
import CostOptimizerTab from '../components/AIOps/CostOptimizerTab';
import ApiKeyManagement from '../components/AIOps/ApiKeyManagement';
import { useTeamAnalyticsData } from '../hooks/useTeamAnalyticsData';
import { usePerformanceMonitoringData } from '../hooks/usePerformanceMonitoringData';
import { useInsightsData } from '../hooks/useInsightsData';
import { useRoutingData } from '../hooks/useRoutingData';
import { useApiKeysData } from '../hooks/useApiKeysData';
import { useComputeMetricsData } from '../hooks/useComputeMetricsData';
import ComputeMetricsSection from '../components/AIOps/ComputeMetricsSection';
import ManagedInferenceTab from '../components/AIOps/ManagedInferenceTab';
import { useManagedInferenceData } from '../hooks/useManagedInferenceData';
import LiteLLMTab from '../components/AIOps/LiteLLMTab';
import { useDashboardEnhancedData } from '../hooks/useDashboardEnhancedData';
import type { LlmProviderConfig, BudgetConfig } from '../types/aiops';
import { PROVIDER_COLORS } from '../types/aiops';
import '../styles/aiops.css';
import { useTabState } from '../hooks/useTabState';

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
  { id: 'cost-optimizer', label: 'Cost Optimizer', icon: 'fas fa-route' },
  { id: 'api-keys', label: 'API Keys', icon: 'fas fa-key' },
  { id: 'managed-inference', label: 'Inference', icon: 'fas fa-server' },
  { id: 'insights', label: 'Insights', icon: 'fas fa-lightbulb' },
  { id: 'litellm', label: 'AI Gateway', icon: 'fas fa-network-wired' },
];

const USAGE_SUB_TABS = [
  { id: 'request-logs', label: 'Request Logs', icon: 'fas fa-list-alt' },
  { id: 'agents', label: 'Agents', icon: 'fas fa-robot' },
  { id: 'models', label: 'Models', icon: 'fas fa-balance-scale' },
  { id: 'heatmap', label: 'Token Heatmap', icon: 'fas fa-th' },
  { id: 'teams', label: 'Teams', icon: 'fas fa-users' },
  { id: 'performance', label: 'Performance', icon: 'fas fa-tachometer-alt' },
  { id: 'compute', label: 'Compute', icon: 'fas fa-microchip' },
];

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

type AIOpsTab = 'dashboard' | 'usage-analytics' | 'budget-controls' | 'cost-optimizer' | 'api-keys' | 'managed-inference' | 'insights' | 'litellm';

// ─── Main Page ───────────────────────────────────────────────
const AIOpsPage: React.FC = () => {
  const [period, setPeriod] = useState('30d');
  const [activeTab, setActiveTab] = useTabState<AIOpsTab>('dashboard');
  const [activeSubTab, setActiveSubTab] = useState('request-logs');
  const [isProviderModalOpen, setIsProviderModalOpen] = useState(false);
  const [editingProvider, setEditingProvider] = useState<LlmProviderConfig | null>(null);
  const [copiedUrl, setCopiedUrl] = useState(false);

  const {
    kpis, allTimeMetrics, costTrend, providerBreakdown, modelUsageTable,
    anomalies, providers, sectionLoading, isLoading, error,
    loadDashboard, addProvider, updateProvider, deleteProvider,
  } = useAIOpsData({ period });

  const analytics = useUsageAnalyticsData(period);
  const budget = useBudgetData();
  const [isBudgetModalOpen, setIsBudgetModalOpen] = useState(false);
  const [editingBudget, setEditingBudget] = useState<BudgetConfig | null>(null);
  const teamAnalytics = useTeamAnalyticsData(period, analytics.filters.provider);
  const perfMetrics = usePerformanceMonitoringData(period, analytics.filters.provider, analytics.filters.model);
  const insights = useInsightsData(period);
  const routing = useRoutingData(period);
  const apiKeys = useApiKeysData();
  const computeMetrics = useComputeMetricsData(period, analytics.filters.provider);
  const inference = useManagedInferenceData(period);
  const dashEnhanced = useDashboardEnhancedData(period);

  // Load enhanced dashboard data when switching to Dashboard
  useEffect(() => {
    if (activeTab === 'dashboard') {
      dashEnhanced.loadAllDashboardData();
    }
  }, [activeTab, period]);

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

  // Load insights when switching to Insights tab
  useEffect(() => {
    if (activeTab === 'insights') {
      insights.loadInsights();
    }
  }, [activeTab, period]);

  // Load routing data when switching to Cost Optimizer tab
  useEffect(() => {
    if (activeTab === 'cost-optimizer') {
      routing.loadRules();
      routing.loadStats();
      routing.loadHistory();
    }
  }, [activeTab, period]);

  // Load managed inference when switching to Inference tab
  useEffect(() => {
    if (activeTab === 'managed-inference') {
      inference.loadInference();
    }
  }, [activeTab, period]);

  // Load API keys when switching to API Keys tab
  useEffect(() => {
    if (activeTab === 'api-keys') {
      apiKeys.loadKeys();
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
      case 'teams':
        teamAnalytics.loadTeamAnalytics();
        break;
      case 'performance':
        perfMetrics.loadPerformanceMetrics();
        break;
      case 'compute':
        computeMetrics.loadComputeMetrics();
        break;
    }
  }, [activeTab, activeSubTab, period, analytics.filters, analytics.logsPage, analytics.logsSize]);

  const hasProviders = providers.length > 0;

  // Topbar integration — period selector, provider badges, Add Provider, Refresh
  const { setActions, clearActions } = useTopbar();
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {hasProviders && (
          <div className="flex items-center bg-gray-100 rounded-lg p-0.5">
            {PERIODS.map(p => (
              <button
                key={p.key}
                onClick={() => setPeriod(p.key)}
                className={`aiops-period-btn px-2.5 py-1 text-[11px] font-medium rounded-md transition-all ${
                  period === p.key ? 'active' : 'text-gray-600 hover:text-gray-900'
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>
        )}
        {providers.map(p => (
          <ProviderBadge key={p.id} config={p} onCopy={handleCopyUrl} />
        ))}
        <button
          onClick={() => setIsProviderModalOpen(true)}
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-gradient-to-r from-indigo-500 to-purple-600 rounded-lg hover:from-indigo-600 hover:to-purple-700 transition-all shadow-sm"
        >
          <i className="fas fa-plus" />
          Add Provider
        </button>
        {hasProviders && (
          <button
            onClick={() => loadDashboard()}
            disabled={isLoading}
            className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
            title="Refresh"
          >
            <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`} />
          </button>
        )}
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, providers, isLoading, loadDashboard, period, hasProviders]);

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

  return (
    <main className="aiops-page flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Top-Level Tabs */}
      {hasProviders && (
        <div className="px-6 bg-white border-b border-gray-200">
          <div className="max-w-7xl mx-auto">
            <AriaTabGroup
              tabs={TOP_TABS}
              selectedKey={activeTab}
              onSelectionChange={setActiveTab as (key: string) => void}
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
              Tab 1: LLM Dashboard (Enhanced - CloudIDR style)
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'dashboard' && (hasProviders || isLoading) && (
            <LLMDashboardTab
              kpis={kpis}
              allTimeMetrics={allTimeMetrics}
              costTrend={costTrend}
              providerBreakdown={providerBreakdown}
              modelUsageTable={modelUsageTable}
              anomalies={anomalies}
              dashInsights={dashEnhanced.dashInsights}
              dashRoutingStats={dashEnhanced.dashRoutingStats}
              dashTeamData={dashEnhanced.dashTeamData}
              modelAnalysis={dashEnhanced.modelAnalysis}
              dashRecentLogs={dashEnhanced.dashRecentLogs}
              kpisLoading={sectionLoading.kpis}
              chartsLoading={sectionLoading.charts}
            />
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

              {activeSubTab === 'teams' && (
                <TeamBreakdownSection
                  data={teamAnalytics.data}
                  loading={teamAnalytics.loading}
                />
              )}

              {activeSubTab === 'performance' && (
                <PerformanceSection
                  data={perfMetrics.data}
                  loading={perfMetrics.loading}
                />
              )}

              {activeSubTab === 'compute' && (
                <ComputeMetricsSection
                  data={computeMetrics.data}
                  loading={computeMetrics.loading}
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

          {/* ═══════════════════════════════════════════════════════
              Tab 4: Cost Optimizer
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'cost-optimizer' && hasProviders && (
            <CostOptimizerTab
              rules={routing.rules}
              stats={routing.stats}
              history={routing.history}
              rulesLoading={routing.rulesLoading}
              statsLoading={routing.statsLoading}
              historyLoading={routing.historyLoading}
              saving={routing.saving}
              onCreateRule={routing.createRule}
              onUpdateRule={routing.updateRule}
              onToggleRule={routing.toggleRule}
              onDeleteRule={routing.deleteRule}
            />
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 5: API Keys
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'api-keys' && hasProviders && (
            <ApiKeyManagement
              keys={apiKeys.keys}
              loading={apiKeys.loading}
              onRotateKey={apiKeys.rotateKey}
              onUpdateUsageLimit={apiKeys.updateUsageLimit}
              onDeleteProvider={async (id: number) => {
                await deleteProvider(id);
                apiKeys.loadKeys();
              }}
              onCleanupOrphanedLogs={apiKeys.cleanupOrphanedLogs}
              onAssignTeam={apiKeys.assignTeam}
            />
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 6: Managed Inference
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'managed-inference' && hasProviders && (
            <ManagedInferenceTab
              data={inference.data}
              loading={inference.loading}
            />
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 7: Insights
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'insights' && hasProviders && (
            <InsightsDashboard
              data={insights.data}
              loading={insights.loading}
            />
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 8: LiteLLM
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'litellm' && (
            <LiteLLMTab
              providers={providers}
              serverBaseUrl={(typeof import.meta !== 'undefined' && (import.meta as any).env?.VITE_API_BASE_URL) || 'https://api.xamops.com'}
              onEditProvider={p => { setEditingProvider(p); setIsProviderModalOpen(true); }}
            />
          )}
        </div>
      </div>

      {/* Provider Setup Modal */}
      <LLMProviderSetupModal
        isOpen={isProviderModalOpen}
        onClose={() => { setIsProviderModalOpen(false); setEditingProvider(null); }}
        onSave={addProvider}
        onUpdate={(id, config) => updateProvider(id, config)}
        editProvider={editingProvider}
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
