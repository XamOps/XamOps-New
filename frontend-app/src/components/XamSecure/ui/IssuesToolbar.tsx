interface Props {
  /** Real total issue count after filtering — drives "Security issues (N)". */
  totalIssues: number;
}

/**
 * The page-top chrome (spec §1). Pure UI. The only real datum is the active
 * "Security issues (N)" sub-tab, whose N is the live filtered issue count.
 *
 * The visual-only placeholders (Issues|Feed toggle, Whitelist/Share/Create, Learn more, Score
 * journey) were removed at the user's request — the toolbar now shows the title and the single
 * real "Security issues (N)" sub-tab only.
 */
const IssuesToolbar: React.FC<Props> = ({ totalIssues }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
    {/* Row 1: title */}
    <p
      style={{
        margin: 0, fontSize: 24, lineHeight: '36px', fontWeight: 500, color: 'var(--xs-text-primary)',
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
      }}
    >
      Issues
    </p>

    {/* Row 2: sub-tab */}
    <div style={{ display: 'flex', alignItems: 'flex-end', borderBottom: '1px solid var(--xs-border-subtle)' }}>
      <span
        style={{
          display: 'inline-flex', alignItems: 'center', height: 34,
          fontSize: 14, fontWeight: 600, color: 'var(--xs-blue-02)',
          borderBottom: '2px solid var(--xs-blue-05)', marginBottom: -1,
        }}
      >
        Security issues ({totalIssues})
      </span>
    </div>
  </div>
);

export default IssuesToolbar;
