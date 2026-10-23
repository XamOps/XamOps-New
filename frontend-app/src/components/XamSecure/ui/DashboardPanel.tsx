import { Icon } from '@iconify/react';

interface Props {
  title?: string;
  /** mdi icon name shown as a small colored glyph before the title. */
  glyph?: string;
  glyphColor?: string;
  /** Optional `(n)` count after the title. */
  count?: number;
  /** Right-aligned header slot (e.g. a TrendDelta, a legend, or a link). */
  action?: React.ReactNode;
  /** Optional legend row rendered under the header. */
  legend?: React.ReactNode;
  children?: React.ReactNode;
  /** Makes the whole card clickable (hero variant). Card never lifts; it only fills on hover. */
  onClick?: () => void;
  className?: string;
  /** When false, the body does not scroll (e.g. a chart that fills the card). Default true. */
  scrollBody?: boolean;
}

/**
 * The card shell. Owns the fixed geometry — 356px height, radius 24, no border + no shadow at rest
 * — so no caller can opt out. The card itself never lifts or shadows on hover; only clickable
 * sub-areas fill.
 */
const DashboardPanel: React.FC<Props> = ({
  title, glyph, glyphColor, count, action, legend, children, onClick, className, scrollBody = true,
}) => {
  const clickable = !!onClick;
  const header = (title || glyph || action) && (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 24,
        minHeight: 28,
        marginBottom: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
        {glyph && <Icon icon={glyph} width={16} height={16} color={glyphColor ?? 'var(--xs-text-secondary)'} aria-hidden />}
        {title && (
          <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--xs-text-primary)' }}>
            {title}
            {typeof count === 'number' && (
              <span style={{ fontWeight: 400, color: 'var(--xs-text-secondary)' }}> ({count})</span>
            )}
          </span>
        )}
      </div>
      {action != null && <div style={{ flexShrink: 0 }}>{action}</div>}
    </div>
  );

  const content = (
    <>
      {header}
      {legend != null && <div style={{ marginBottom: 8 }}>{legend}</div>}
      <div className={scrollBody ? 'xs-card-body' : undefined} style={scrollBody ? undefined : { flex: '1 1 auto', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        {children}
      </div>
    </>
  );

  if (clickable) {
    return (
      <div
        className={`xs-card xs-hoverable ${className ?? ''}`}
        role="button"
        tabIndex={0}
        onClick={onClick}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick?.(); } }}
      >
        {content}
      </div>
    );
  }

  return <div className={`xs-card ${className ?? ''}`}>{content}</div>;
};

export default DashboardPanel;
