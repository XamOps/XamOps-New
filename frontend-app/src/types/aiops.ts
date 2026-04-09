// ─── KPI Types ───────────────────────────────────────────────
export interface LlmKpis {
  totalSpend: number;
  requestsToday: number;
  requestsThisMonth: number;
  todayVsYesterdayPct: number;
  monthVsLastMonthPct: number;
  inputTokens: number;
  outputTokens: number;
  avgLatencyMs: number;
}

// ─── Chart Types ─────────────────────────────────────────────
export interface CostTrend {
  labels: string[];
  costs: number[];
}

export interface ProviderCostBreakdown {
  provider: string;
  cost: number;
}

// ─── Table Types ─────────────────────────────────────────────
export interface ModelUsageStat {
  model: string;
  provider: string;
  requests: number;
  totalTokens: number;
  cost: number;
  avgLatencyMs: number;
}

// ─── Anomaly Types ───────────────────────────────────────────
export interface CostAnomaly {
  date: string;
  provider: string;
  actualCost: number;
  expectedCost: number;
  deviationPct: number;
  severity: 'WARNING' | 'CRITICAL';
}

// ─── Provider Config Types ───────────────────────────────────
export type LlmProvider = 'OPENAI' | 'ANTHROPIC' | 'GEMINI' | 'BEDROCK';

export interface LlmProviderConfig {
  id?: number;
  provider: LlmProvider;
  displayName: string;
  status: string;
  proxyBaseUrl?: string;
  trackingToken?: string;
  createdAt?: string;
}

// ─── Section Loading State ───────────────────────────────────
export interface AIOPsSectionLoading {
  kpis: boolean;
  charts: boolean;
  table: boolean;
  anomalies: boolean;
}

// ─── Provider metadata ──────────────────────────────────────
export interface ProviderInfo {
  key: LlmProvider;
  name: string;
  icon: string;
  color: string;
  bgColor: string;
  borderColor: string;
  description: string;
}

export const LLM_PROVIDERS: ProviderInfo[] = [
  {
    key: 'OPENAI',
    name: 'OpenAI',
    icon: 'fas fa-robot',
    color: 'text-emerald-600',
    bgColor: 'bg-emerald-50',
    borderColor: 'border-emerald-500',
    description: 'GPT-4o, GPT-4, GPT-3.5 Turbo, o1, o3',
  },
  {
    key: 'ANTHROPIC',
    name: 'Anthropic',
    icon: 'fas fa-brain',
    color: 'text-orange-600',
    bgColor: 'bg-orange-50',
    borderColor: 'border-orange-500',
    description: 'Claude Opus, Sonnet, Haiku',
  },
  {
    key: 'GEMINI',
    name: 'Google Gemini',
    icon: 'fas fa-gem',
    color: 'text-blue-600',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-500',
    description: 'Gemini 2.0 Flash, 1.5 Pro, 1.5 Flash',
  },
  {
    key: 'BEDROCK',
    name: 'AWS Bedrock',
    icon: 'fab fa-aws',
    color: 'text-amber-600',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-500',
    description: 'Claude, Llama, Titan, Mistral via AWS',
  },
];

// Provider color map for charts
export const PROVIDER_COLORS: Record<string, string> = {
  OPENAI: '#10b981',
  ANTHROPIC: '#f97316',
  GEMINI: '#3b82f6',
  BEDROCK: '#f59e0b',
};

// ─── Usage Analytics Types ──────────────────────────────────

export interface RequestLogEntry {
  id: number;
  timestamp: string;
  provider: string;
  model: string;
  agentName: string | null;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  latencyMs: number;
  statusCode: number;
  error: boolean;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AgentUsageStat {
  agentName: string;
  requests: number;
  totalTokens: number;
  cost: number;
  avgLatencyMs: number;
}

export interface ModelComparisonStat {
  model: string;
  provider: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  avgLatencyMs: number;
  errorRate: number;
}

export interface HeatmapCell {
  dayOfWeek: number;
  hour: number;
  totalTokens: number;
}

export interface FilterOptions {
  providers: string[];
  models: string[];
  agents: string[];
}

// ─── All-Time Metrics ───────────────────────────────────────

export interface AllTimeMetrics {
  allTimeCost: number;
  allTimeTokens: number;
  allTimeRequests: number;
}

// ─── Daily Spend Entry ──────────────────────────────────────

export interface DailySpendEntry {
  date: string;
  spend: number;
}

// ─── Budget & Controls Types ────────────────────────────────

export interface BudgetConfig {
  id?: number;
  providerConfigId: number | null;
  providerName: string | null;
  budgetName: string;
  monthlyLimitUsd: number;
  alertThresholds: string;
  autoDisable: boolean;
  status: string;
  notificationEmail: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface BudgetStatus {
  budgetId: number;
  budgetName: string;
  providerConfigId: number | null;
  providerName: string | null;
  monthlyLimitUsd: number;
  currentSpendUsd: number;
  usagePct: number;
  autoDisable: boolean;
  status: string;
  thresholds: number[];
  breachedThresholds: number[];
  projectedSpend: number;
  burnRate: number;
  estimatedRunoutDay: number;
}

export interface BudgetAlert {
  id: number;
  budgetId: number;
  budgetName: string;
  providerName: string | null;
  thresholdPct: number;
  actualSpendUsd: number;
  budgetLimitUsd: number;
  budgetMonth: string;
  triggeredAt: string;
  dismissed: boolean;
}

export interface BudgetSummary {
  totalBudgetUsd: number;
  totalSpendUsd: number;
  overallUsagePct: number;
  activeBudgetCount: number;
  alertsThisMonth: number;
  budgets: BudgetStatus[];
  recentAlerts: BudgetAlert[];
}

export interface BudgetCreateRequest {
  providerConfigId: number | null;
  budgetName: string;
  monthlyLimitUsd: number;
  alertThresholds: string;
  autoDisable: boolean;
  notificationEmail: string | null;
}

export interface BudgetUpdateRequest {
  monthlyLimitUsd?: number;
  alertThresholds?: string;
  autoDisable?: boolean;
  status?: string;
  notificationEmail?: string;
}
