import React from 'react';

const STYLES: Record<string, string> = {
  CRITICAL: 'bg-gray-900 text-white',
  HIGH: 'bg-gray-700 text-white',
  MEDIUM: 'bg-gray-400 text-white',
  LOW: 'bg-gray-200 text-gray-700',
};

const SeverityChip: React.FC<{ severity: string | null }> = ({ severity }) => {
  const sev = (severity ?? 'UNKNOWN').toUpperCase();
  return (
    <span className={`inline-block px-2 py-0.5 rounded-full text-[10px] font-bold tracking-wide ${STYLES[sev] ?? 'bg-gray-100 text-gray-500'}`}>
      {sev}
    </span>
  );
};

export default SeverityChip;
