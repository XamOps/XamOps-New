// frontend-app/src/components/SRE/SreStatusBadge.tsx
import React from 'react';
import { InvestigationStatus } from '../../hooks/useSreInvestigation';
import { statusColors } from './sreUtils';

interface Props {
  status: InvestigationStatus;
}

const label: Record<InvestigationStatus, string> = {
  PENDING: 'Analyzing',
  RUNNING: 'Analyzing',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

const SreStatusBadge: React.FC<Props> = ({ status }) => {
  const c = statusColors[status];
  const isActive = status === 'PENDING' || status === 'RUNNING';
  return (
    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold ${c.bg} ${c.text}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${c.dot} ${isActive ? 'animate-pulse' : ''}`} />
      {label[status]}
    </span>
  );
};

export default SreStatusBadge;
