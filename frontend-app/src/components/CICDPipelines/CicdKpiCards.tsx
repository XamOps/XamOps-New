import React from 'react';
import { CicdMetrics } from '../../types/cicdPipelines';

interface Props {
  metrics: CicdMetrics;
  isLoading: boolean;
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return s === 0 ? `${m}m` : `${m}m ${s}s`;
}

function Delta({ current, prev, lowerIsBetter = false }: { current: number; prev: number; lowerIsBetter?: boolean }) {
  if (prev === 0) return null;
  const diff = current - prev;
  if (diff === 0) return null;
  const positive = lowerIsBetter ? diff < 0 : diff > 0;
  const sign = diff > 0 ? '+' : '';
  const formatted = Number.isInteger(diff) ? String(diff) : diff.toFixed(1);
  return (
    <span className={`text-xs ml-1 font-medium ${positive ? 'text-green-400' : 'text-red-400'}`}>
      {sign}{formatted}{diff > 0 ? ' ↑' : ' ↓'}
    </span>
  );
}

export const CicdKpiCards: React.FC<Props> = ({ metrics, isLoading }) => {
  const cards = [
    {
      label: 'Success Rate',
      value: isLoading ? '—' : `${metrics.successRate.toFixed(1)}%`,
      delta: <Delta current={metrics.successRate} prev={metrics.prevSuccessRate} />,
    },
    {
      label: 'Total Runs',
      value: isLoading ? '—' : String(metrics.totalRuns),
      delta: <Delta current={metrics.totalRuns} prev={metrics.prevTotalRuns} />,
    },
    {
      label: 'Failures',
      value: isLoading ? '—' : String(metrics.failureCount),
      delta: <Delta current={metrics.failureCount} prev={metrics.prevFailureCount} lowerIsBetter />,
    },
    {
      label: 'Avg Duration',
      value: isLoading ? '—' : formatDuration(metrics.avgDurationSeconds),
      delta: <Delta current={metrics.avgDurationSeconds} prev={metrics.prevAvgDurationSeconds} lowerIsBetter />,
    },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
      {cards.map(card => (
        <div key={card.label} className="bg-gray-800 rounded-lg p-4 border border-gray-700">
          <p className="text-xs text-gray-400 uppercase tracking-wide mb-1">{card.label}</p>
          <p className="text-xl font-bold text-white">
            {card.value}
            {card.delta}
          </p>
        </div>
      ))}
    </div>
  );
};
