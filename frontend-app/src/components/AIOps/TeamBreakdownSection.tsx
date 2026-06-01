import React, { useRef, useEffect } from 'react';
import { Chart, registerables } from 'chart.js';
import { Icon } from '@iconify/react';
import type { TeamAnalyticsSummary } from '../../types/aiops';
import { InfoTooltip } from '../ui/Tooltip';

Chart.register(...registerables);

interface TeamBreakdownSectionProps {
  data: TeamAnalyticsSummary | null;
  loading: boolean;
}

function formatCurrency(value: number): string {
  if (value >= 1000) return '$' + (value / 1000).toFixed(1) + 'k';
  if (value < 0.01 && value > 0) return '$' + value.toFixed(6);
  return '$' + value.toFixed(2);
}

function formatNumber(value: number): string {
  if (value >= 1_000_000) return (value / 1_000_000).toFixed(1) + 'M';
  if (value >= 1_000) return (value / 1_000).toFixed(1) + 'k';
  return value.toLocaleString();
}

function formatLatency(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's';
  return Math.round(ms) + 'ms';
}

const TEAM_COLORS = [
  '#6366f1', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981',
  '#3b82f6', '#ef4444', '#14b8a6', '#f97316', '#06b6d4',
];

const BEBAS: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '28px',
  fontWeight: 400,
  lineHeight: '28px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

function TeamCostBarChart({ data }: { data: TeamAnalyticsSummary }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current || data.teams.length === 0) return;
    if (chartRef.current) chartRef.current.destroy();

    chartRef.current = new Chart(canvasRef.current, {
      type: 'bar',
      data: {
        labels: data.teams.map(t => t.teamName),
        datasets: [{
          label: 'Cost ($)',
          data: data.teams.map(t => t.cost),
          backgroundColor: data.teams.map((_, i) => TEAM_COLORS[i % TEAM_COLORS.length] + 'CC'),
          borderColor: data.teams.map((_, i) => TEAM_COLORS[i % TEAM_COLORS.length]),
          borderWidth: 1,
          borderRadius: 6,
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { callback: (v) => '$' + Number(v).toFixed(2) } },
          y: { grid: { display: false }, ticks: { font: { size: 11 } } },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

function TeamCostTrendChart({ data }: { data: TeamAnalyticsSummary }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart | null>(null);

  useEffect(() => {
    if (!canvasRef.current || !data.costTrend || data.costTrend.labels.length === 0) return;
    if (chartRef.current) chartRef.current.destroy();

    const teams = Object.keys(data.costTrend.seriesByTeam);
    const datasets = teams.map((team, i) => ({
      label: team,
      data: data.costTrend.seriesByTeam[team],
      borderColor: TEAM_COLORS[i % TEAM_COLORS.length],
      backgroundColor: TEAM_COLORS[i % TEAM_COLORS.length] + '20',
      fill: true,
      tension: 0.3,
      pointRadius: 2,
      borderWidth: 2,
    }));

    chartRef.current = new Chart(canvasRef.current, {
      type: 'line',
      data: { labels: data.costTrend.labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 12, font: { size: 11 } } } },
        scales: {
          x: { grid: { display: false }, ticks: { font: { size: 10 }, maxTicksLimit: 10 } },
          y: { grid: { color: 'rgba(0,0,0,0.05)' }, ticks: { callback: (v) => '$' + Number(v).toFixed(2) } },
        },
      },
    });

    return () => { chartRef.current?.destroy(); };
  }, [data]);

  return <canvas ref={canvasRef} />;
}

const TeamBreakdownSection: React.FC<TeamBreakdownSectionProps> = ({ data, loading }) => {
  if (loading) {
    return (
      <div className="space-y-4">
        <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-64" />
        <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded-xl h-48" />
      </div>
    );
  }

  if (!data || data.teams.length === 0) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-12 text-center shadow-sm">
        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-4">
          <Icon icon="mdi:account-group-outline" width={28} color="rgba(0,0,0,0.3)" />
        </div>
        <p className="text-[rgba(0,0,0,0.5)] font-medium">No team data available</p>
        <p className="text-xs text-[rgba(0,0,0,0.4)] mt-1">
          Use the <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded">X-XamOps-Agent</code> header to tag requests with team/agent names
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Summary KPIs */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Total Teams <InfoTooltip placement="bottom">Distinct teams/agents identified via request headers.</InfoTooltip></span>
          <p style={BEBAS} className="mt-1">{data.totalTeams}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Total Cost <InfoTooltip placement="bottom">Combined spend across all teams for the period.</InfoTooltip></span>
          <p style={BEBAS} className="mt-1">{formatCurrency(data.totalCost)}</p>
        </div>
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-5 shadow-sm">
          <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-[0.09em] leading-[15px] inline-flex items-center gap-1">Total Requests <InfoTooltip placement="bottom">Total API requests across all teams.</InfoTooltip></span>
          <p style={BEBAS} className="mt-1">{formatNumber(data.teams.reduce((s, t) => s + t.requests, 0))}</p>
        </div>
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
            <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:chart-bar" width={16} color="#4F46E5" />
            </div>
            <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Cost by Team</h3>
            <InfoTooltip placement="right">Total cost per team for the selected period.</InfoTooltip>
          </div>
          <div className="p-4" style={{ height: Math.max(200, data.teams.length * 40 + 40) }}>
            <TeamCostBarChart data={data} />
          </div>
        </div>

        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
            <div className="w-8 h-8 bg-purple-50 border border-purple-100 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:chart-line" width={16} color="#7C3AED" />
            </div>
            <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Daily Cost Trend by Team</h3>
            <InfoTooltip placement="right">Daily cost over time broken down by team.</InfoTooltip>
          </div>
          <div className="p-4" style={{ height: 280 }}>
            <TeamCostTrendChart data={data} />
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
          <div className="w-8 h-8 bg-teal-50 border border-teal-100 rounded-lg flex items-center justify-center">
            <Icon icon="mdi:table" width={16} color="#0F766E" />
          </div>
          <h3 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Team Breakdown</h3>
          <InfoTooltip placement="right">Detailed per-team metrics including cost share, models used, and latency.</InfoTooltip>
          <span className="text-xs text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded-full">
            {data.teams.length} teams
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)] text-left">
                <th className="px-6 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Team / Agent</th>
                <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Requests</th>
                <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Tokens</th>
                <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Cost</th>
                <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Cost %</th>
                <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Models</th>
                <th className="px-4 py-3 text-xs font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wider text-right">Avg Latency</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
              {data.teams.map((team, idx) => (
                <tr key={team.teamName} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                  <td className="px-6 py-3 font-medium text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                    <span
                      className="w-3 h-3 rounded-full flex-shrink-0"
                      style={{ backgroundColor: TEAM_COLORS[idx % TEAM_COLORS.length] }}
                    />
                    {team.teamName}
                  </td>
                  <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">{formatNumber(team.requests)}</td>
                  <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">{formatNumber(team.totalTokens)}</td>
                  <td className="px-4 py-3 text-right font-medium text-[rgba(0,0,0,0.88)]">{formatCurrency(team.cost)}</td>
                  <td className="px-4 py-3 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <div className="w-16 h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                        <div
                          className="h-full rounded-full"
                          style={{
                            width: `${Math.min(team.costPct, 100)}%`,
                            backgroundColor: TEAM_COLORS[idx % TEAM_COLORS.length],
                          }}
                        />
                      </div>
                      <span className="text-[rgba(0,0,0,0.6)] text-xs w-12 text-right">{team.costPct.toFixed(1)}%</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">{team.modelsUsed}</td>
                  <td className="px-4 py-3 text-right text-[rgba(0,0,0,0.7)]">{formatLatency(team.avgLatencyMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default TeamBreakdownSection;
