import React from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import { CicdDailyStat } from '../../types/cicdPipelines';

interface Props {
  dailyStats: CicdDailyStat[];
  isLoading: boolean;
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

export const CicdTrendCharts: React.FC<Props> = ({ dailyStats, isLoading }) => {
  if (isLoading) {
    return <div className="h-36 bg-gray-100 rounded-lg animate-pulse mb-4" />;
  }

  if (dailyStats.length === 0) {
    return (
      <div className="h-36 bg-white border border-gray-200 rounded-lg flex items-center justify-center mb-4">
        <p className="text-gray-400 text-sm">No data for selected period</p>
      </div>
    );
  }

  const data = dailyStats.map(s => ({
    date: s.date.slice(5),
    successRate: s.successCount + s.failureCount === 0
      ? 0
      : Math.round((s.successCount / (s.successCount + s.failureCount)) * 100),
    avgDuration: s.avgDuration,
  }));

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
      <div className="bg-white rounded-lg p-4 border border-gray-200 shadow-sm">
        <p className="text-xs text-gray-500 uppercase tracking-wide mb-2">Success Rate Over Time</p>
        <ResponsiveContainer width="100%" height={110}>
          <LineChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
            <XAxis dataKey="date" tick={{ fontSize: 10, fill: '#9ca3af' }} />
            <YAxis domain={[0, 100]} tick={{ fontSize: 10, fill: '#9ca3af' }} unit="%" />
            <Tooltip
              contentStyle={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 4 }}
              labelStyle={{ color: '#111827' }}
              formatter={(v: any) => [`${v}%`, 'Success Rate']}
            />
            <Line type="monotone" dataKey="successRate" stroke="#22c55e" dot={false} strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <div className="bg-white rounded-lg p-4 border border-gray-200 shadow-sm">
        <p className="text-xs text-gray-500 uppercase tracking-wide mb-2">Avg Build Duration</p>
        <ResponsiveContainer width="100%" height={110}>
          <LineChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
            <XAxis dataKey="date" tick={{ fontSize: 10, fill: '#9ca3af' }} />
            <YAxis tick={{ fontSize: 10, fill: '#9ca3af' }} />
            <Tooltip
              contentStyle={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 4 }}
              labelStyle={{ color: '#111827' }}
              formatter={(v: any) => [formatDuration(Number(v)), 'Avg Duration']}
            />
            <Line type="monotone" dataKey="avgDuration" stroke="#6366f1" dot={false} strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
};
