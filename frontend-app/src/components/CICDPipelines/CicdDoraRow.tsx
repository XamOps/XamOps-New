import React from 'react';
import { CicdDora } from '../../types/cicdPipelines';

interface Props {
  dora: CicdDora | null;
  isLoading: boolean;
}

function formatMttr(seconds: number): string {
  if (seconds === 0) return '—';
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
  return `${(seconds / 3600).toFixed(1)}h`;
}

export const CicdDoraRow: React.FC<Props> = ({ dora, isLoading }) => {
  const cards = [
    {
      label: 'Deploy Frequency',
      value: isLoading ? '—' : dora ? `${dora.deploymentFrequency.toFixed(1)}/day` : '—',
      hint: 'Deployments per day',
    },
    {
      label: 'MTTR',
      value: isLoading ? '—' : dora ? formatMttr(dora.mttr) : '—',
      hint: 'Mean time to recovery',
    },
    {
      label: 'Change Failure Rate',
      value: isLoading ? '—' : dora ? `${dora.changeFailureRate.toFixed(1)}%` : '—',
      hint: '% of deploys that failed',
    },
  ];

  return (
    <div className="mb-4">
      <p className="text-xs text-gray-500 uppercase tracking-wide mb-2">DORA Metrics</p>
      <div className="grid grid-cols-3 gap-3">
        {cards.map(card => (
          <div key={card.label} className="bg-gray-800 rounded-lg p-3 border border-gray-700">
            <p className="text-xs text-gray-400 mb-1">{card.label}</p>
            <p className="text-lg font-bold text-purple-400">{card.value}</p>
            <p className="text-xs text-gray-500 mt-1">{card.hint}</p>
          </div>
        ))}
      </div>
    </div>
  );
};
