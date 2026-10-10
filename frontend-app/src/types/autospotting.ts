/**
 * AutoSpotting Type Definitions
 */

export interface CustomerSavings {
    currentMonthlyCost: number;
    onDemandMonthlyCost: number;
    actualMonthlySavings: number;
    potentialMonthlySavings: number;
    totalAsgs: number;
    enabledAsgs: number;
}

export interface SpotInstance {
    instanceId: string;
    instanceType: string;
    availabilityZone: string;
    lifecycle: string; // "spot" or "on-demand"
    state: string;
    asgName: string;
    region: string;
    estimatedHourlySavings: number;
    launchTime: string;
}

export interface ActivityEvent {
    id: string;
    timestamp: string;
    description: string;
    eventType: string;
    savings?: number;
    region?: string;
    asgName?: string;
}

export interface Recommendation {
    id: number;
    title: string;
    description: string;
    potentialSavings: number;
    priority: 'high' | 'medium' | 'low';
}

export interface CustomerDashboardData {
    savings: CustomerSavings;
    spotInstances: SpotInstance[];
    recentActivity: ActivityEvent[];
    recommendations: Recommendation[];
}

export interface SavingsMetrics {
    /** Verified (non-legacy) savings only: 0 until the savings ledger exists. */
    totalSavings: number;
    /** Legacy savings, reported apart and shown only as "Legacy (unverified)". */
    legacyUnverifiedTotal: number;
    activeSpotInstances: number;
}

export interface SpotActivity {
    id: number;
    timestamp: string;
    accountId: string;
    accountName: string;
    instanceId: string;
    instanceType: string;
    spotInstanceType: string;
    eventType: 'LAUNCH' | 'TERMINATE';
    /** Null on events that record no savings, such as ASG config saves. */
    hourlySavings: number | null;
    onDemandPrice: number;
    spotPrice: number;
    region: string;
}

export interface HistoricalSavings {
    date: string;
    /** Verified savings that day; null when there is none. */
    savings: number | null;
    /** Legacy savings that day, a separate series; null when there is none. */
    legacySavings: number | null;
    instanceCount: number;
}

export interface AccountSavings {
    accountId: string;
    accountName: string;
    /** Verified savings only. */
    totalSavings: number;
    legacyUnverifiedSavings: number;
    instanceCount: number;
}

export interface AdminDashboardData {
    metrics: SavingsMetrics;
    recentActivity: SpotActivity[];
    historicalData: HistoricalSavings[];
    accountBreakdown: AccountSavings[];
}

export interface SpotAutomationCacheData {
    asgs: any[];
    costs: {
        currentHourly: number;
        currentMonthlyCost: number;
        onDemandHourly: number;
        hourlyActualSavings: number;
    };
    savings: {
        actualMonthlySavings: number;
        potentialMonthlySavings: number;
    };
    enabledRegionsCount: number;
    totalInstances: number;
    historyData: any[];
    historySummary: {
        total_savings: number;
        average_hourly_savings: number;
        peak_savings: number;
        legacy_total_savings?: number;
    };
}
