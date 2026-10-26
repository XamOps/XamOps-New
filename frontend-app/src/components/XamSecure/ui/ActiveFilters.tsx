import { Icon } from '@iconify/react';

interface Props {
  /** Active severity value (e.g. "CRITICAL") or null. */
  severity: string | null;
  /** Active severity display label (e.g. "Critical"). */
  severityLabel?: string;
  /** Raw (trimmed) search query; empty string when none. */
  query: string;
  onClearSeverity: () => void;
  onClearSearch: () => void;
  onClearAll: () => void;
}

const pillStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  height: 30,
  padding: '0 10px',
  boxSizing: 'border-box',
  borderRadius: 'var(--xs-radius-pill)',
  background: 'var(--xs-blue-05)',
  border: '1px solid var(--xs-blue-04)',
  color: 'var(--xs-blue-02)',
  fontSize: 13,
  fontWeight: 500,
};

const removeBtnStyle: React.CSSProperties = {
  appearance: 'none',
  border: 0,
  background: 'transparent',
  padding: 0,
  margin: 0,
  display: 'inline-flex',
  alignItems: 'center',
  cursor: 'pointer',
  color: 'var(--xs-blue-02)',
};

/**
 * The "active filters" row. Renders a removable pill per live filter (severity chip, search
 * query) plus a "Clear" button that resets both. These reflect and clear REAL state in IssuesTab
 * — no placeholders here. When nothing is active, a muted "No active filters" is shown.
 */
const ActiveFilters: React.FC<Props> = ({
  severity, severityLabel, query, onClearSeverity, onClearSearch, onClearAll,
}) => {
  const hasSeverity = severity !== null;
  const hasQuery = query !== '';
  const any = hasSeverity || hasQuery;

  if (!any) {
    return (
      <span style={{ fontSize: 13, color: 'var(--xs-text-secondary)' }}>No active filters</span>
    );
  }

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
      {hasSeverity && (
        <span style={pillStyle}>
          <span>Severity: {severityLabel ?? severity}</span>
          <button
            type="button"
            onClick={onClearSeverity}
            aria-label="Remove severity filter"
            style={removeBtnStyle}
          >
            <Icon icon="mdi:close" width={14} height={14} aria-hidden />
          </button>
        </span>
      )}
      {hasQuery && (
        <span style={pillStyle}>
          <span>Search: "{query}"</span>
          <button
            type="button"
            onClick={onClearSearch}
            aria-label="Remove search filter"
            style={removeBtnStyle}
          >
            <Icon icon="mdi:close" width={14} height={14} aria-hidden />
          </button>
        </span>
      )}
      <button
        type="button"
        onClick={onClearAll}
        style={{
          appearance: 'none',
          border: 0,
          background: 'transparent',
          padding: '0 4px',
          margin: 0,
          cursor: 'pointer',
          fontSize: 13,
          fontWeight: 500,
          color: 'var(--xs-text-secondary)',
          textDecoration: 'underline',
        }}
      >
        Clear
      </button>
    </div>
  );
};

export default ActiveFilters;
