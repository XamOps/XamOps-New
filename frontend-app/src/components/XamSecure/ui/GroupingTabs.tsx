import { Icon } from '@iconify/react';

export type Grouping = 'severity' | 'framework';

interface Props {
  grouping: Grouping;
  onChange: (grouping: Grouping) => void;
  /** Count of non-empty severity buckets in the filtered set. */
  severityCount: number;
  /** Count of distinct framework keys (incl. "No framework") in the filtered set. */
  frameworkCount: number;
}

const SEGMENTS: Array<{ value: Grouping; icon: string; label: (p: Props) => string }> = [
  { value: 'severity', icon: 'mdi:alert-circle-outline', label: (p) => `By severity (${p.severityCount})` },
  { value: 'framework', icon: 'mdi:shield-check-outline', label: (p) => `By framework (${p.frameworkCount})` },
];

/**
 * The grouping segmented control: "By severity (N) | By framework (M)". Two real toggle buttons
 * (`aria-pressed`) inside a pill-bordered outer frame with a 1px divider between segments. The
 * active segment reads on a white fill with blue text/icon; the inactive on the secondary fill.
 * Keyboard: native buttons (Enter/Space); focus ring via `.xs-issues *:focus-visible`.
 */
const GroupingTabs: React.FC<Props> = (props) => {
  const { grouping, onChange } = props;
  return (
    <div
      role="group"
      aria-label="Group issues by"
      style={{
        display: 'inline-flex',
        alignItems: 'stretch',
        border: '1px solid var(--xs-border-subtle)',
        borderRadius: 'var(--xs-radius-pill)',
        overflow: 'hidden',
        background: 'var(--xs-bg-secondary)',
      }}
    >
      {SEGMENTS.map((seg, i) => {
        const active = grouping === seg.value;
        return (
          <button
            key={seg.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(seg.value)}
            style={{
              appearance: 'none',
              font: 'inherit',
              fontSize: 14,
              fontWeight: 500,
              cursor: 'pointer',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              height: 30,
              padding: '0 14px',
              border: 0,
              borderLeft: i === 0 ? 0 : '1px solid var(--xs-border-subtle)',
              background: active ? 'var(--xs-bg-primary)' : 'var(--xs-bg-secondary)',
              color: active ? 'var(--xs-blue-02)' : 'var(--xs-text-secondary)',
              transition: 'background-color .15s ease-out, color .15s ease-out',
            }}
          >
            <Icon
              icon={seg.icon}
              width={16}
              height={16}
              aria-hidden
              style={{ color: active ? 'var(--xs-blue-02)' : 'var(--xs-text-secondary)' }}
            />
            <span>{seg.label(props)}</span>
          </button>
        );
      })}
    </div>
  );
};

export default GroupingTabs;
