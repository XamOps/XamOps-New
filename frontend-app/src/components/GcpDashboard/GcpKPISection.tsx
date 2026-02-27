import React, { useRef, useCallback } from 'react';

interface GcpKPIData {
    mtdSpend: number;
    forecastedSpend: number;
    lastMonthSpend: number;
    potentialSavings: number;
    potentialSavingsBreakdown?: {
        optimization: number;
        autoSpotting: number;
    };
}

interface GcpKPISectionProps {
    kpiData: GcpKPIData;
    costHistory?: {
        costs: number[];
        labels: string[];
        anomalies?: boolean[];
    }; // Compatible structure
    isLoading: boolean;
}

interface KPICardProps {
    title: string;
    value: string;
    icon: string;
    gradientClass: string;
    chartColor: string;
    trendData: number[];
    change: number;
    sparklineId: string;
    subtitle?: string;
}

// Helper to convert hex to rgba
const hexToRgba = (hex: string, alpha: number): string => {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

const KPICard: React.FC<KPICardProps> = ({
    title,
    value,
    icon,
    gradientClass,
    chartColor,
    trendData,
    change,
    sparklineId,
    subtitle
}) => {
    const chartInstanceRef = useRef<any>(null);

    // Render sparkline on mouse enter
    const handleMouseEnter = useCallback(() => {
        if (!window.Chart || !trendData || trendData.length === 0) return;

        const canvas = document.getElementById(sparklineId) as HTMLCanvasElement;
        if (!canvas) return;

        // Destroy existing chart if any
        if (chartInstanceRef.current) {
            chartInstanceRef.current.destroy();
            chartInstanceRef.current = null;
        }

        const ctx = canvas.getContext('2d');
        if (!ctx) return;

        chartInstanceRef.current = new window.Chart(ctx, {
            type: 'line',
            data: {
                labels: trendData.map((_, i) => i.toString()),
                datasets: [{
                    data: trendData,
                    borderColor: chartColor,
                    backgroundColor: hexToRgba(chartColor, 0.1),
                    borderWidth: 3,
                    pointRadius: 0,
                    pointHoverRadius: 6,
                    pointHoverBackgroundColor: chartColor,
                    pointHoverBorderColor: '#ffffff',
                    pointHoverBorderWidth: 2,
                    tension: 0.4,
                    fill: true
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    x: { display: false },
                    y: { display: false }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        enabled: true,
                        backgroundColor: 'rgba(0, 0, 0, 0.8)',
                        titleColor: 'white',
                        bodyColor: 'white',
                        borderColor: chartColor,
                        borderWidth: 1,
                        cornerRadius: 6,
                        callbacks: {
                            label: (context: any) => `Value: ${context.parsed.y?.toLocaleString() || 0}`
                        }
                    }
                },
                interaction: {
                    intersect: false,
                    mode: 'index'
                }
            }
        });
    }, [sparklineId, trendData, chartColor]);

    return (
        <div
            className="glass-card gcp-kpi-card p-6 rounded-xl group relative overflow-hidden transition-all duration-300 hover:-translate-y-1 hover:shadow-xl"
            onMouseEnter={handleMouseEnter}
            style={{ borderTop: `4px solid ${chartColor}` }}
        >
            {/* Main Content */}
            <div className="main-content relative z-10">
                <div className="flex justify-between items-start h-full">
                    <div className="flex-1">
                        <h3 className="text-[10px] font-black text-gray-400 uppercase tracking-widest mb-1">{title}</h3>
                        <p className="text-3xl font-black text-gray-900 tracking-tight mb-2 metric-value">{value}</p>
                        {subtitle && (
                            <p className="text-[10px] text-gray-500 font-medium mb-2 leading-relaxed">{subtitle}</p>
                        )}
                        <div className="flex items-center text-[11px] font-bold">
                            <span className={`flex items-center gap-1 ${change >= 0 ? 'text-rose-500' : 'text-emerald-500'}`}>
                                {change !== 0 && (
                                    <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d={change >= 0 ? "M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" : "M13 17h8m0 0v-8m0 8l-8-8-4 4-6-6"} />
                                    </svg>
                                )}
                                {Math.abs(change).toFixed(1)}%
                            </span>
                            <span className="text-gray-400 ml-1.5 uppercase font-black">vs last period</span>
                        </div>
                    </div>
                    <div className={`w-12 h-12 bg-gradient-to-br ${gradientClass} rounded-2xl flex items-center justify-center shadow-lg transform group-hover:scale-110 transition-transform duration-300`}>
                        {icon === 'fa-dollar-sign' && (
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                        )}
                        {icon === 'fa-chart-line' && (
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                            </svg>
                        )}
                        {icon === 'fa-calendar-alt' && (
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
                            </svg>
                        )}
                        {icon === 'fa-piggy-bank' && (
                            <svg className="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                        )}
                    </div>
                </div>
            </div>

            {/* Sparkline Container - hidden by default, visible on hover */}
            <div className="sparkline-container absolute inset-0 bg-white/95 backdrop-blur-sm opacity-0 group-hover:opacity-100 transition-opacity duration-300 z-0">
                <div className="w-full h-full p-4">
                    <canvas id={sparklineId}></canvas>
                </div>
            </div>
        </div>
    );
};

const GcpKPISection: React.FC<GcpKPISectionProps> = ({ kpiData, costHistory, isLoading }) => {
    const formatCurrency = (num: number): string => {
        return '₹' + (num != null ? num.toLocaleString('en-IN', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2
        }) : '0.00');
    };

    const costs = costHistory?.costs || [];

    // Calculate specific trends
    const mtdSpend = kpiData.mtdSpend || 0;
    const lastMonthSpend = kpiData.lastMonthSpend || 0;
    const forecastedSpend = kpiData.forecastedSpend || 0;

    const mtdChange = lastMonthSpend > 0 ? ((mtdSpend - lastMonthSpend) / lastMonthSpend * 100) : 0;
    const forecastChange = mtdSpend > 0 ? ((forecastedSpend - mtdSpend) / mtdSpend * 100) : 0;
    // For last month change, we need previous data, assuming 0 if not available
    const lastMonthChange = 0;

    // Build breakdown subtitle for Potential Savings
    const buildSavingsSubtitle = () => {
        const breakdown = kpiData.potentialSavingsBreakdown;
        if (!breakdown) return undefined;

        // Always show breakdown even if values are 0
        const parts: string[] = [
            `Optimization: ${formatCurrency(breakdown.optimization)}`,
            `AutoSpotting: ${formatCurrency(breakdown.autoSpotting)}`
        ];

        return parts.join(' • ');
    };

    const kpiCards: KPICardProps[] = [
        {
            title: 'Month-to-Date Spend',
            value: formatCurrency(mtdSpend),
            icon: 'fa-dollar-sign',
            gradientClass: 'from-blue-500 to-blue-600',
            chartColor: '#4285F4',
            trendData: costs.length > 0 ? costs : [0],
            change: mtdChange,
            sparklineId: 'sparkline-mtdSpend',
        },
        {
            title: 'Forecasted Spend',
            value: formatCurrency(forecastedSpend),
            icon: 'fa-chart-line',
            gradientClass: 'from-emerald-500 to-emerald-600',
            chartColor: '#34A853',
            trendData: costs.length > 0 ? [...costs, forecastedSpend] : [forecastedSpend],
            change: forecastChange,
            sparklineId: 'sparkline-forecastedSpend',
        },
        {
            title: 'Last Month Spend',
            value: formatCurrency(lastMonthSpend),
            icon: 'fa-calendar-alt',
            gradientClass: 'from-amber-400 to-amber-500',
            chartColor: '#FBBC04',
            trendData: costs.length > 1 ? costs.slice(0, -1) : [lastMonthSpend],
            change: lastMonthChange,
            sparklineId: 'sparkline-lastMonthSpend',
        },
        {
            title: 'Potential Savings',
            value: formatCurrency(kpiData.potentialSavings),
            icon: 'fa-piggy-bank',
            gradientClass: 'from-red-500 to-red-600',
            chartColor: '#EA4335',
            trendData: [0, kpiData.potentialSavings],
            change: 0,
            sparklineId: 'sparkline-potentialSavings',
            subtitle: buildSavingsSubtitle(),
        },
    ];

    return (
        <>

            {/* Loading Skeleton */}
            {isLoading && (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="glass-card p-6 rounded-xl h-40 animate-pulse">
                            <div className="flex justify-between items-start h-full">
                                <div className="flex-1 space-y-3">
                                    <div className="h-4 w-28 shimmer rounded"></div>
                                    <div className="h-8 w-32 shimmer rounded-lg"></div>
                                    <div className="h-3 w-24 shimmer rounded"></div>
                                </div>
                                <div className="h-12 w-12 shimmer rounded-xl"></div>
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {/* KPI Cards */}
            {!isLoading && (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 fade-in">
                    {kpiCards.map(card => (
                        <KPICard key={card.sparklineId} {...card} />
                    ))}
                </div>
            )}
        </>
    );
};

export default GcpKPISection;
