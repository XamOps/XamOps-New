// frontend-app/src/components/SRE/SreSeverityBadge.tsx
import React from 'react';

type Severity = 'critical' | 'high' | 'medium' | 'low';

const config: Record<Severity, { bg: string; text: string; dot: string; label: string }> = {
  critical: { bg: 'bg-red-100',    text: 'text-red-700',    dot: 'bg-red-500',    label: 'Critical' },
  high:     { bg: 'bg-orange-100', text: 'text-orange-700', dot: 'bg-orange-500', label: 'High' },
  medium:   { bg: 'bg-yellow-100', text: 'text-yellow-700', dot: 'bg-yellow-500', label: 'Medium' },
  low:      { bg: 'bg-blue-100',   text: 'text-blue-700',   dot: 'bg-blue-400',   label: 'Low' },
};

interface Props {
  severity: Severity;
  size?: 'sm' | 'md';
}

const SreSeverityBadge: React.FC<Props> = ({ severity, size = 'md' }) => {
  const c = config[severity];
  return (
    <span className={`inline-flex items-center gap-1 font-semibold rounded-full ${c.bg} ${c.text} ${
      size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-2.5 py-1 text-xs'
    }`}>
      <span className={`w-1.5 h-1.5 rounded-full ${c.dot}`} />
      {c.label}
    </span>
  );
};

export default SreSeverityBadge;
