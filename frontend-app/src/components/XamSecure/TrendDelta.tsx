import React from 'react';
import { Icon } from '@iconify/react';

interface Props {
  /** Value at the start of the window. */
  from: number;
  /** Value at the end of the window. */
  to: number;
  /** Window description, e.g. "vs 7 days ago". */
  label?: string;
  /** When true, an increase is bad (findings). Default true. */
  riskUp?: boolean;
}

/**
 * Percentage change across a window, in Upwind's idiom: the counts alone don't say whether
 * a number is improving.
 *
 * Only rendered when a comparison is meaningful — a delta from a zero baseline is infinite,
 * not "100%", so that case shows the absolute change instead of a fabricated percentage.
 */
const TrendDelta: React.FC<Props> = ({ from, to, label = 'vs 7 days ago', riskUp = true }) => {
  const diff = to - from;
  if (diff === 0) {
    return (
      <span className="inline-flex items-center gap-1 text-[10px] font-semibold text-gray-400">
        <Icon icon="mdi:minus" width={11} />
        No change {label}
      </span>
    );
  }

  const worse = riskUp ? diff > 0 : diff < 0;
  const tone = worse ? 'text-red-600' : 'text-emerald-600';
  const icon = diff > 0 ? 'mdi:arrow-top-right' : 'mdi:arrow-bottom-right';
  // A percentage needs a non-zero baseline to mean anything.
  const text = from > 0
    ? `${diff > 0 ? '+' : ''}${Math.round((diff / from) * 100)}%`
    : `${diff > 0 ? '+' : ''}${diff}`;

  return (
    <span className={`inline-flex items-center gap-1 text-[10px] font-bold ${tone}`}>
      <Icon icon={icon} width={11} />
      {text}
      <span className="font-semibold text-gray-400">{label}</span>
    </span>
  );
};

export default TrendDelta;
