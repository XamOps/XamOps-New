import { Icon } from '@iconify/react';

interface Props {
  value: string;
  onChange: (value: string) => void;
}

/**
 * The real title-search pill. A ~500×30 rounded container whose border brightens on focus-within
 * (`.xs-search:focus-within`, defined additively in tokens.css). A leading magnify icon, a native
 * `<input>` wired to `onChange`, and a clear button that appears only when `value` is non-empty.
 * Filtering happens upstream in IssuesTab — this is a controlled input, no local state.
 */
const IssuesSearch: React.FC<Props> = ({ value, onChange }) => (
  <div
    className="xs-search"
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 8,
      width: 500,
      maxWidth: '100%',
      height: 30,
      padding: '0 12px',
      boxSizing: 'border-box',
      borderRadius: 'var(--xs-radius-pill)',
      border: '1px solid var(--xs-blue-05)',
      background: 'var(--xs-bg-primary)',
      transition: 'border-color .15s ease-out',
    }}
  >
    <Icon icon="mdi:magnify" width={18} height={18} aria-hidden style={{ color: 'var(--xs-blue-03)', flexShrink: 0 }} />
    <input
      type="text"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder="Search anything"
      aria-label="Search issues by title"
      style={{
        appearance: 'none',
        border: 0,
        outline: 'none',
        background: 'transparent',
        flex: '1 1 auto',
        minWidth: 0,
        font: 'inherit',
        fontSize: 14,
        lineHeight: '21px',
        color: 'var(--xs-text-primary)',
      }}
    />
    {value !== '' && (
      <button
        type="button"
        onClick={() => onChange('')}
        aria-label="Clear search"
        style={{
          appearance: 'none',
          border: 0,
          background: 'transparent',
          padding: 0,
          margin: 0,
          display: 'inline-flex',
          alignItems: 'center',
          cursor: 'pointer',
          flexShrink: 0,
          color: 'var(--xs-text-secondary)',
        }}
      >
        <Icon icon="mdi:close" width={16} height={16} aria-hidden />
      </button>
    )}
  </div>
);

export default IssuesSearch;
