import React, { useState, useCallback, useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
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
import { Icon } from '@iconify/react';
import { useTabState } from '../hooks/useTabState';

const PERIODS = [
  { key: 'today', label: 'Today' },
  { key: '7d', label: '7 Days' },
  { key: '30d', label: '30 Days' },
  { key: '90d', label: '90 Days' },
  { key: '365d', label: '1 Year' },
] as const;

const TOP_TABS = [
  { id: 'dashboard', label: 'LLM Dashboard', icon: 'mdi:chart-line' },
  { id: 'usage-analytics', label: 'Usage Analytics', icon: 'mdi:microscope' },
  { id: 'budget-controls', label: 'Budget & Controls', icon: 'mdi:wallet-outline' },
  { id: 'cost-optimizer', label: 'Cost Optimizer', icon: 'mdi:directions-fork' },
  { id: 'api-keys', label: 'API Keys', icon: 'mdi:key-outline' },
  { id: 'managed-inference', label: 'Inference', icon: 'mdi:server-outline' },
  { id: 'insights', label: 'Insights', icon: 'mdi:lightbulb-outline' },
  { id: 'litellm', label: 'AI Gateway', icon: 'mdi:access-point-network' },
];

const USAGE_SUB_TABS = [
  { id: 'request-logs', label: 'Request Logs', icon: 'mdi:format-list-bulleted' },
  { id: 'agents', label: 'Agents', icon: 'mdi:robot-outline' },
  { id: 'models', label: 'Models', icon: 'mdi:scale-balance' },
  { id: 'heatmap', label: 'Token Heatmap', icon: 'mdi:grid' },
  { id: 'teams', label: 'Teams', icon: 'mdi:account-group-outline' },
  { id: 'performance', label: 'Performance', icon: 'mdi:speedometer' },
  { id: 'compute', label: 'Compute', icon: 'mdi:chip' },
];

// ─── Provider Badge ──────────────────────────────────────────
function ProviderBadge({ config, onCopy }: { config: LlmProviderConfig; onCopy: (text: string) => void }) {
  return (
    <div className="flex items-center gap-2 px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-xs">
      <span
        className="w-2 h-2 rounded-full"
        style={{ backgroundColor: PROVIDER_COLORS[config.provider] || '#94a3b8' }}
      />
      <span className="font-medium text-[rgba(0,0,0,0.7)]">{config.displayName || config.provider}</span>
      {config.trackingToken && (
        <button
          onClick={() => onCopy(config.trackingToken!)}
          className="text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
          title="Copy tracking key"
        >
          <Icon icon="mdi:key-outline" width={12} />
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
      <span className="text-xs text-[rgba(0,0,0,0.5)] font-medium">
        <Icon icon="mdi:filter-outline" width={12} className="inline mr-1" />Filters:
      </span>
      <select
        value={filters.provider}
        onChange={e => onFiltersChange({ ...filters, provider: e.target.value })}
        className="text-xs bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-1.5 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
      >
        <option value="">All Providers</option>
        {filterOptions.providers.map(p => (
          <option key={p} value={p}>{p}</option>
        ))}
      </select>
      <select
        value={filters.model}
        onChange={e => onFiltersChange({ ...filters, model: e.target.value })}
        className="text-xs bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-1.5 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
      >
        <option value="">All Models</option>
        {filterOptions.models.map(m => (
          <option key={m} value={m}>{m}</option>
        ))}
      </select>
      <select
        value={filters.agent}
        onChange={e => onFiltersChange({ ...filters, agent: e.target.value })}
        className="text-xs bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-1.5 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
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
          <div className="flex items-center bg-[rgba(0,0,0,0.04)] rounded-lg p-0.5">
            {PERIODS.map(p => (
              <button
                key={p.key}
                onClick={() => setPeriod(p.key)}
                className={`px-2.5 py-1 text-[11px] font-medium rounded-md transition-all ${
                  period === p.key ? 'bg-white shadow-sm text-[rgba(0,0,0,0.88)]' : 'text-[rgba(0,0,0,0.5)] hover:text-[rgba(0,0,0,0.88)]'
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
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-[rgba(0,0,0,0.88)] hover:bg-black rounded-md transition-colors"
        >
          <Icon icon="mdi:plus" width={14} />
          Add Provider
        </button>
        {hasProviders && (
          <button
            onClick={() => loadDashboard()}
            disabled={isLoading}
            className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-medium text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-md hover:bg-[rgba(0,0,0,0.06)] transition-colors"
            title="Refresh"
          >
            <Icon icon={isLoading ? "mdi:loading" : "mdi:refresh"} width={14} className={isLoading ? "animate-spin" : ""} />
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
    <main className="flex-1 flex flex-col min-h-screen bg-white">
      {/* Top-Level Tabs */}
      {hasProviders && (
        <div className="px-6 bg-white border-b border-[rgba(0,0,0,0.06)]">
          <AriaTabGroup
            tabs={TOP_TABS}
            selectedKey={activeTab}
            onSelectionChange={setActiveTab as (key: string) => void}
            variant="underline"
          />
        </div>
      )}

      {/* Copied toast */}
      {copiedUrl && (
        <div className="fixed top-20 right-6 z-50 flex items-center gap-2 px-4 py-2 bg-green-500 text-white rounded-lg shadow-xl text-sm font-medium">
          <Icon icon="mdi:check-circle" width={16} />
          Copied to clipboard!
        </div>
      )}

      {/* Main Content */}
      <div className="flex-1 p-6">
        <div className="space-y-6">
          {/* Error State */}
          {error && (
            <div className="bg-red-50 border border-red-100 rounded-lg p-4 flex items-center gap-3">
              <Icon icon="mdi:alert-circle-outline" width={18} color="#ef4444" />
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
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
              <div className="text-center py-20 px-8">
                <div className="mb-6 flex justify-center">
                  <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center">
                    <Icon icon="mdi:power-plug-outline" width={28} color="#4F46E5" />
                  </div>
                </div>
                <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">
                  Connect Your First LLM Provider
                </h2>
                <p className="text-[rgba(0,0,0,0.5)] mb-6 max-w-2xl mx-auto text-sm leading-relaxed">
                  Connect your OpenAI, Anthropic, Google Gemini, or AWS Bedrock provider to start
                  tracking costs, tokens, and latency across all your AI applications.
                  No API keys stored — just run a curl command.
                </p>

                <button
                  onClick={() => setIsProviderModalOpen(true)}
                  className="inline-flex items-center px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white rounded-md hover:bg-black transition-colors text-xs font-medium"
                >
                  <Icon icon="mdi:plus-circle-outline" width={16} className="mr-2" />
                  Add LLM Provider
                </button>

                <div className="mt-10 grid grid-cols-1 md:grid-cols-3 gap-6 max-w-3xl mx-auto">
                  {[
                    { icon: 'mdi:key-outline', title: '1. Get XamOps Key', desc: 'Select provider & get tracking key' },
                    { icon: 'mdi:terminal', title: '2. Run Curl Command', desc: 'Use our proxy + your API key' },
                    { icon: 'mdi:chart-bar', title: '3. See Insights', desc: 'Dashboard refreshes with usage' },
                  ].map(step => (
                    <div key={step.title} className="flex flex-col items-center p-4">
                      <div className="w-12 h-12 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mb-3">
                        <Icon icon={step.icon} width={20} color="#4F46E5" />
                      </div>
                      <p className="font-semibold text-[rgba(0,0,0,0.88)] text-sm">{step.title}</p>
                      <p className="text-xs text-[rgba(0,0,0,0.5)] mt-1">{step.desc}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          <AnimatePresence mode="wait">

          {/* ═══════════════════════════════════════════════════════
              Tab 1: LLM Dashboard (Enhanced - CloudIDR style)
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'dashboard' && (hasProviders || isLoading) && (
            <motion.div key="dashboard" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
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
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 2: Usage Analytics
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'usage-analytics' && hasProviders && (
            <motion.div key="usage-analytics" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
            <div className="space-y-4">
              {/* Filters + Sub-tabs header */}
              <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-4 space-y-3">
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
              <AnimatePresence mode="wait">
              {activeSubTab === 'request-logs' && (
                <motion.div key="request-logs" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <RequestLogsTable
                    data={analytics.requestLogs}
                    loading={analytics.logsLoading}
                    page={analytics.logsPage}
                    size={analytics.logsSize}
                    onPageChange={analytics.setLogsPage}
                    onSizeChange={analytics.setLogsSize}
                    onExport={handleExportCsv}
                  />
                </motion.div>
              )}
              {activeSubTab === 'agents' && (
                <motion.div key="agents" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <AgentBreakdownSection
                    data={analytics.agentBreakdown}
                    loading={analytics.agentLoading}
                    onAgentClick={handleAgentClick}
                  />
                </motion.div>
              )}
              {activeSubTab === 'models' && (
                <motion.div key="models" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <ModelComparisonSection
                    data={analytics.modelComparison}
                    loading={analytics.modelLoading}
                  />
                </motion.div>
              )}
              {activeSubTab === 'heatmap' && (
                <motion.div key="heatmap" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <TokenHeatmap
                    data={analytics.tokenHeatmap}
                    loading={analytics.heatmapLoading}
                  />
                </motion.div>
              )}
              {activeSubTab === 'teams' && (
                <motion.div key="teams" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <TeamBreakdownSection
                    data={teamAnalytics.data}
                    loading={teamAnalytics.loading}
                  />
                </motion.div>
              )}
              {activeSubTab === 'performance' && (
                <motion.div key="performance" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <PerformanceSection
                    data={perfMetrics.data}
                    loading={perfMetrics.loading}
                  />
                </motion.div>
              )}
              {activeSubTab === 'compute' && (
                <motion.div key="compute" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -3 }} transition={{ duration: 0.15, ease: 'easeOut' }}>
                  <ComputeMetricsSection
                    data={computeMetrics.data}
                    loading={computeMetrics.loading}
                  />
                </motion.div>
              )}
              </AnimatePresence>
            </div>
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 3: Budget & Controls
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'budget-controls' && hasProviders && (
            <motion.div key="budget-controls" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
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
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 4: Cost Optimizer
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'cost-optimizer' && hasProviders && (
            <motion.div key="cost-optimizer" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
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
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 5: API Keys
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'api-keys' && hasProviders && (
            <motion.div key="api-keys" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
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
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 6: Managed Inference
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'managed-inference' && hasProviders && (
            <motion.div key="managed-inference" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <ManagedInferenceTab
                data={inference.data}
                loading={inference.loading}
              />
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 7: Insights
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'insights' && hasProviders && (
            <motion.div key="insights" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <InsightsDashboard
                data={insights.data}
                loading={insights.loading}
              />
            </motion.div>
          )}

          {/* ═══════════════════════════════════════════════════════
              Tab 8: LiteLLM
              ═══════════════════════════════════════════════════════ */}
          {activeTab === 'litellm' && (
            <motion.div key="litellm" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <LiteLLMTab
                providers={providers}
                serverBaseUrl={(typeof import.meta !== 'undefined' && (import.meta as any).env?.VITE_API_BASE_URL) || 'https://api.xamops.com'}
                onEditProvider={p => { setEditingProvider(p); setIsProviderModalOpen(true); }}
              />
            </motion.div>
          )}

          </AnimatePresence>
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
