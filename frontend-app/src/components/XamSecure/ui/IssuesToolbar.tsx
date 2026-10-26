import { Icon } from '@iconify/react';

interface Props {
  /** Real total issue count after filtering — drives "Security issues (N)". */
  totalIssues: number;
}

/** A visual-only pill toggle for the Issues | Feed switch (Feed is a disabled placeholder). */
const ViewToggle: React.FC = () => (
  <div
    role="group"
    aria-label="View"
    style={{
      display: 'inline-flex',
      alignItems: 'stretch',
      border: '1px solid var(--xs-border-subtle)',
      borderRadius: 'var(--xs-radius-pill)',
      overflow: 'hidden',
      background: 'var(--xs-bg-secondary)',
    }}
  >
    <button
      type="button"
      aria-pressed
      style={{
        appearance: 'none', font: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'default',
        height: 28, padding: '0 16px', border: 0,
        background: 'var(--xs-bg-primary)', color: 'var(--xs-blue-02)',
      }}
    >
      Issues
    </button>
    <button
      type="button"
      aria-pressed={false}
      aria-disabled
      title="Coming soon"
      onClick={(e) => e.preventDefault()}
      style={{
        appearance: 'none', font: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'not-allowed',
        height: 28, padding: '0 16px', border: 0, borderLeft: '1px solid var(--xs-border-subtle)',
        background: 'var(--xs-bg-secondary)', color: 'var(--xs-text-tertiary)',
      }}
    >
      Feed
    </button>
  </div>
);

/** A ghost action button whose border animates on hover (visual-only; disabled/no-op). */
const GhostButton: React.FC<{ label: string; primary?: boolean }> = ({ label, primary }) => (
  <button
    type="button"
    aria-disabled
    title="Coming soon"
    onClick={(e) => e.preventDefault()}
    className="xs-ghost-btn"
    style={{
      appearance: 'none', font: 'inherit', fontSize: 13, fontWeight: 500, cursor: 'not-allowed',
      display: 'inline-flex', alignItems: 'center', gap: 4,
      height: 30, padding: '0 12px', boxSizing: 'border-box',
      borderRadius: 'var(--xs-radius-chip)',
      border: '1px solid var(--xs-border-subtle)',
      background: primary ? 'var(--xs-blue-05)' : 'var(--xs-bg-primary)',
      color: primary ? 'var(--xs-blue-02)' : 'var(--xs-text-secondary)',
      transition: 'border-color .15s ease-out',
    }}
  >
    <span>{label}</span>
    <Icon icon="mdi:chevron-down" width={14} height={14} aria-hidden />
  </button>
);

/**
 * The page-top chrome (spec §1). Pure UI: the Issues|Feed toggle, the Score-journey sub-tab, the
 * Whitelist/Share/Create actions and Learn-more are VISUAL-ONLY placeholders — disabled or no-op
 * with a "Coming soon" affordance and never carry or fabricate data. The only real datum is the
 * active "Security issues (N)" sub-tab, whose N is the live filtered issue count.
 */
const IssuesToolbar: React.FC<Props> = ({ totalIssues }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
    {/* Row 1: title · centered view toggle · Learn more */}
    <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
      <p
        style={{
          margin: 0, fontSize: 24, lineHeight: '36px', fontWeight: 500, color: 'var(--xs-text-primary)',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}
      >
        Issues
      </p>
      <div style={{ flex: '1 1 auto', display: 'flex', justifyContent: 'center' }}>
        <ViewToggle />
      </div>
      <button
        type="button"
        aria-disabled
        title="Coming soon"
        onClick={(e) => e.preventDefault()}
        style={{
          appearance: 'none', border: 0, background: 'transparent', padding: 0, margin: 0,
          display: 'inline-flex', alignItems: 'center', gap: 4, cursor: 'not-allowed',
          fontSize: 13, fontWeight: 500, color: 'var(--xs-blue-02)',
        }}
      >
        <Icon icon="mdi:open-in-new" width={14} height={14} aria-hidden />
        <span>Learn more</span>
      </button>
    </div>

    {/* Row 2: sub-tabs (left) · action cluster (right) */}
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, borderBottom: '1px solid var(--xs-border-subtle)' }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 20, flex: '1 1 auto' }}>
        <span
          style={{
            display: 'inline-flex', alignItems: 'center', height: 34,
            fontSize: 14, fontWeight: 600, color: 'var(--xs-blue-02)',
            borderBottom: '2px solid var(--xs-blue-05)', marginBottom: -1,
          }}
        >
          Security issues ({totalIssues})
        </span>
        <button
          type="button"
          aria-disabled
          title="Coming soon"
          onClick={(e) => e.preventDefault()}
          style={{
            appearance: 'none', border: 0, background: 'transparent', padding: 0, margin: 0,
            display: 'inline-flex', alignItems: 'center', height: 34, cursor: 'not-allowed',
            fontSize: 14, fontWeight: 500, color: 'var(--xs-text-tertiary)',
          }}
        >
          Score journey
        </button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 4 }}>
        <GhostButton label="Whitelist" />
        <GhostButton label="Share" />
        <GhostButton label="Create" primary />
      </div>
    </div>
  </div>
);

export default IssuesToolbar;
