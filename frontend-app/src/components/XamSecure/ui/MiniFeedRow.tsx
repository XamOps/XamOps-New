import { Icon } from '@iconify/react';
import SeverityLetterBadge from './SeverityLetterBadge';

interface Props {
  severity: string | null | undefined;
  title: string;
  /** Right-aligned slot: a CountChip, resource text, or a chevron. */
  rightSlot?: React.ReactNode;
  onClick?: () => void;
  /** When true, the row is not interactive (no pointer, no handler fired). */
  disabled?: boolean;
}

/**
 * One row in a feed list: severity letter badge + single-line truncated title + optional right
 * slot. Hover fill on clickable rows; the title shows a dark tooltip when it truncates.
 */
const MiniFeedRow: React.FC<Props> = ({ severity, title, rightSlot, onClick, disabled }) => {
  const clickable = !!onClick && !disabled;
  const base: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    height: 42,
    padding: '10px 0 10px 8px',
    borderRadius: 'var(--xs-radius-chip)',
    width: '100%',
    textAlign: 'left',
    background: 'transparent',
    border: 'none',
    color: 'inherit',
  };

  const inner = (
    <>
      <SeverityLetterBadge severity={severity} />
      <span
        className="xs-tip"
        data-tip={title}
        style={{
          flex: '1 1 auto',
          minWidth: 0,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          fontSize: 14,
          fontWeight: 400,
          color: 'var(--xs-text-primary)',
        }}
      >
        {title}
      </span>
      {rightSlot != null && <span style={{ flexShrink: 0, paddingRight: 8 }}>{rightSlot}</span>}
    </>
  );

  if (clickable) {
    return (
      <button type="button" className="xs-hoverable" onClick={onClick} style={{ ...base, cursor: 'pointer' }}>
        {inner}
      </button>
    );
  }
  return <div style={base}>{inner}</div>;
};

/** Convenience chevron used as a MiniFeedRow rightSlot (e.g. story rows). */
export const RowChevron: React.FC = () => (
  <Icon icon="mdi:chevron-right" width={16} height={16} color="var(--xs-text-tertiary)" aria-hidden />
);

export default MiniFeedRow;
