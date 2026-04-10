import React, { useRef, useEffect } from 'react';
import { Chart, registerables } from 'chart.js';
import type { TeamAnalyticsSummary } from '../../types/aiops';

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
          x: {
            grid: { color: 'rgba(0,0,0,0.05)' },
            ticks: { callback: (v) => '$' + Number(v).toFixed(2) },
          },
          y: {
            grid: { display: false },
            ticks: { font: { size: 11 } },
          },
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
      data: {
        labels: data.costTrend.labels,
        datasets,
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'bottom',
            labels: { boxWidth: 12, padding: 12, font: { size: 11 } },
          },
        },
        scales: {
          x: {
            grid: { display: false },
            ticks: { font: { size: 10 }, maxTicksLimit: 10 },
          },
          y: {
            grid: { color: 'rgba(0,0,0,0.05)' },
            ticks: { callback: (v) => '$' + Number(v).toFixed(2) },
          },
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
        <div className="animate-pulse bg-gray-200/60 rounded-2xl h-64" />
        <div className="animate-pulse bg-gray-200/60 rounded-2xl h-48" />
      </div>
    );
  }

  if (!data || data.teams.length === 0) {
    return (
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-12 text-center shadow-sm">
        <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-users text-gray-400 text-2xl" />
        </div>
        <p className="text-gray-500 font-medium">No team data available</p>
        <p className="text-xs text-gray-400 mt-1">
          Use the <code className="bg-gray-100 px-1 rounded">X-XamOps-Agent</code> header to tag requests with team/agent names
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Summary KPIs */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Teams</span>
          <p className="text-2xl font-bold text-gray-900 mt-1">{data.totalTeams}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Cost</span>
          <p className="text-2xl font-bold text-gray-900 mt-1">{formatCurrency(data.totalCost)}</p>
        </div>
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl p-5 shadow-sm">
          <span className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Total Requests</span>
          <p className="text-2xl font-bold text-gray-900 mt-1">{formatNumber(data.teams.reduce((s, t) => s + t.requests, 0))}</p>
        </div>
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-chart-bar text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Cost by Team</h3>
          </div>
          <div className="p-4" style={{ height: Math.max(200, data.teams.length * 40 + 40) }}>
            <TeamCostBarChart data={data} />
          </div>
        </div>

        <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
            <div className="w-8 h-8 bg-gradient-to-br from-purple-500 to-pink-600 rounded-lg flex items-center justify-center">
              <i className="fas fa-chart-line text-white text-xs" />
            </div>
            <h3 className="text-sm font-bold text-gray-800">Daily Cost Trend by Team</h3>
          </div>
          <div className="p-4" style={{ height: 280 }}>
            <TeamCostTrendChart data={data} />
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white/95 backdrop-blur border border-gray-200 rounded-2xl shadow-sm overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100 flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-teal-500 to-emerald-600 rounded-lg flex items-center justify-center">
            <i className="fas fa-table text-white text-xs" />
          </div>
          <h3 className="text-sm font-bold text-gray-800">Team Breakdown</h3>
          <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">
            {data.teams.length} teams
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 text-left">
                <th className="px-6 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Team / Agent</th>
                <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Requests</th>
                <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Tokens</th>
                <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Cost</th>
                <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Cost %</th>
                <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Models</th>
                <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider text-right">Avg Latency</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {data.teams.map((team, idx) => (
                <tr key={team.teamName} className="hover:bg-gray-50/50 transition-colors">
                  <td className="px-6 py-3 font-medium text-gray-900 flex items-center gap-2">
                    <span
                      className="w-3 h-3 rounded-full flex-shrink-0"
                      style={{ backgroundColor: TEAM_COLORS[idx % TEAM_COLORS.length] }}
                    />
                    {team.teamName}
                  </td>
                  <td className="px-4 py-3 text-right text-gray-700">{formatNumber(team.requests)}</td>
                  <td className="px-4 py-3 text-right text-gray-700">{formatNumber(team.totalTokens)}</td>
                  <td className="px-4 py-3 text-right font-medium text-gray-900">{formatCurrency(team.cost)}</td>
                  <td className="px-4 py-3 text-right">
                    <div className="flex items-center justify-end gap-2">
                      <div className="w-16 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div
                          className="h-full rounded-full"
                          style={{
                            width: `${Math.min(team.costPct, 100)}%`,
                            backgroundColor: TEAM_COLORS[idx % TEAM_COLORS.length],
                          }}
                        />
                      </div>
                      <span className="text-gray-600 text-xs w-12 text-right">{team.costPct.toFixed(1)}%</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 text-right text-gray-700">{team.modelsUsed}</td>
                  <td className="px-4 py-3 text-right text-gray-700">{formatLatency(team.avgLatencyMs)}</td>
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
