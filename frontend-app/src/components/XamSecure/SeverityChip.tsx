import React from 'react';

const STYLES: Record<string, string> = {
  CRITICAL: 'bg-red-500 text-white',
  HIGH: 'bg-orange-500 text-white',
  MEDIUM: 'bg-yellow-400 text-gray-900',
  LOW: 'bg-emerald-500 text-white',
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
