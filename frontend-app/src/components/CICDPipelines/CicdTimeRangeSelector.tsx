import React from 'react';
import { TimeRange } from '../../types/cicdPipelines';
import clsx from 'clsx';

interface Props {
  days: TimeRange;
  onChange: (d: TimeRange) => void;
}

const OPTIONS: TimeRange[] = [7, 30, 90];

export const CicdTimeRangeSelector: React.FC<Props> = ({ days, onChange }) => (
  <div className="flex gap-1">
    {OPTIONS.map(d => (
      <button
        key={d}
        onClick={() => onChange(d)}
        className={clsx(
          'px-3 py-1 text-xs font-medium rounded-full border transition-colors',
          days === d
            ? 'bg-blue-600 text-white border-blue-600'
            : 'bg-transparent text-gray-400 border-gray-600 hover:border-gray-400'
        )}
      >
        {d}d
      </button>
    ))}
  </div>
);
