/**
 * DataSourcesGrid — Vantage-style provider card grid
 * Shows all data sources and apps as clean cards.
 * Clicking a card triggers the integration flow.
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { Provider } from '../../types/addAccount';

interface DataSourcesGridProps {
  onSelectProvider: (provider: Provider) => void;
}

const DATA_SOURCES: { id: Provider; name: string; icon?: string; iconImg?: string; color: string }[] = [
  { id: 'aws', name: 'AWS', iconImg: '/icons/aws-icon.png', color: '#FF9900' },
  { id: 'gcp', name: 'GCP', iconImg: '/icons/gcp-icon.png', color: '#4285F4' },
  { id: 'azure', name: 'Azure', iconImg: '/icons/azure-icon.png', color: '#0078D4' },
  { id: 'jenkins', name: 'Jenkins', iconImg: '/icons/jenkins-icon.png', color: '#D33833' },
  { id: 'github', name: 'GitHub', icon: 'mdi:github', color: '#181717' },
];

const DataSourcesGrid: React.FC<DataSourcesGridProps> = ({ onSelectProvider }) => {
  return (
    <div>
      <h2 className="text-[18px] font-bold text-black mb-1">Data Sources</h2>
      <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-5">Connect your cloud accounts and services.</p>

      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
        {DATA_SOURCES.map((source) => (
          <button
            key={source.id}
            onClick={() => onSelectProvider(source.id)}
            className="flex flex-col items-center justify-center gap-2.5 p-6 rounded-xl border border-[rgba(0,0,0,0.08)] hover:border-[rgba(0,0,0,0.2)] hover:shadow-sm transition-all cursor-pointer bg-white"
          >
            {source.iconImg ? (
              <img src={source.iconImg} alt={source.name} className="w-8 h-8 object-contain" />
            ) : (
              <Icon icon={source.icon!} width={32} style={{ color: source.color }} />
            )}
            <span className="text-[12px] font-medium text-[rgba(0,0,0,0.75)]">{source.name}</span>
          </button>
        ))}
      </div>
    </div>
  );
};

export default DataSourcesGrid;
