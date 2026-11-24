import React from 'react';
import type { ResourceLabel } from '../../types/resourceLabels';

interface Props {
  label: ResourceLabel;
  onRemove?: () => void; // if provided, shows an × button
  size?: 'sm' | 'md';
  /**
   * Show the label's share after its name, e.g. "prod 40%". Only a partial share is shown: a
   * label that takes the whole cost reads as before.
   */
  showPercent?: boolean;
}

/**
 * Colored label chip. Used everywhere a label needs to be displayed inline.
 * Color is set as a CSS custom property so Tailwind's JIT doesn't need to know
 * the hex value at build time.
 */
const LabelBadge: React.FC<Props> = ({ label, onRemove, size = 'sm', showPercent = false }) => {
  const pad = size === 'sm' ? 'px-2 py-0.5 text-xs' : 'px-3 py-1 text-sm';
  const partial =
    showPercent && label.percent != null && label.percent < 100
      ? `${Number(label.percent.toFixed(2))}%`
      : null;

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full font-semibold border ${pad}`}
      style={{
        backgroundColor: `${label.color}20`,  // 12 % opacity background
        borderColor: `${label.color}60`,
        color: label.color,
      }}
      title={
        partial
          ? `${label.name} takes ${partial} of the cost`
          : label.description || label.name
      }
    >
      {label.name}
      {partial && <span className="font-medium opacity-75 tabular-nums">{partial}</span>}
      {onRemove && (
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); onRemove(); }}
          className="ml-0.5 hover:opacity-70 leading-none"
          aria-label={`Remove label ${label.name}`}
        >
          ×
        </button>
      )}
    </span>
  );
};

export default LabelBadge;
