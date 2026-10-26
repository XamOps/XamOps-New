import { Icon } from '@iconify/react';
import { FrameworkLogo, hasFrameworkLogo } from '../../../utils/frameworkBranding';
import { formatRelative, formatAbsolute } from './issueDates';

/**
 * Leaf cells for a FindingRow (spec §5.5–5.7). All colors resolve through `var(--xs-*)`
 * tokens under the `.xs-issues` scope; no stray hex.
 */

/** "Impacted resources" — a count plus a stacked-layers tile (§5.5). */
export const ResourcesCell: React.FC<{ count: number }> = ({ count }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
    <span style={{ width: 24, fontSize: 14, fontWeight: 400, color: 'var(--xs-text-primary)', textAlign: 'right' }}>
      {count}
    </span>
    <span
      title={`${count} impacted resources`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 36,
        height: 36,
        flexShrink: 0,
        padding: 4,
        borderRadius: 'var(--xs-radius-chip)',
        background: 'var(--xs-bg-secondary)',
      }}
    >
      <Icon icon="mdi:layers-outline" width={19} height={19} aria-hidden style={{ color: 'var(--xs-text-secondary)' }} />
    </span>
  </div>
);

/** First seen / Last evaluation — relative line over absolute line (§5.6). */
export const DateCell: React.FC<{ iso: string | null }> = ({ iso }) => {
  const relative = formatRelative(iso);
  const absolute = formatAbsolute(iso);
  if (relative === null || absolute === null) {
    return <span style={{ fontSize: 14, lineHeight: '21px', color: 'var(--xs-text-tertiary)' }}>—</span>;
  }
  const ellipsis = {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap' as const,
    lineHeight: '21px',
    fontSize: 14,
    fontWeight: 400,
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
      <span style={{ ...ellipsis, color: 'var(--xs-text-primary)' }}>{relative}</span>
      <span style={{ ...ellipsis, color: 'var(--xs-text-secondary)', maxWidth: 124 }}>{absolute}</span>
    </div>
  );
};

/** Framework logos — up to 4, stepped, with a "+N" overflow (§5.7). */
export const FrameworkCell: React.FC<{ frameworks: string[] }> = ({ frameworks }) => {
  if (frameworks.length === 0) {
    return <span style={{ fontSize: 14, lineHeight: '21px', color: 'var(--xs-text-tertiary)' }}>—</span>;
  }
  const shown = frameworks.slice(0, 4);
  const overflow = frameworks.length - shown.length;
  return (
    <div style={{ display: 'flex', alignItems: 'center', minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        {shown.map((f, i) => (
          <span key={`${f}-${i}`} style={{ marginLeft: i === 0 ? 0 : 4, display: 'inline-flex' }}>
            {hasFrameworkLogo(f)
              ? <FrameworkLogo framework={f} size={24} />
              : (
                <span
                  title={f}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: 24,
                    height: 24,
                    borderRadius: '999px',
                    background: 'var(--xs-bg-secondary)',
                    color: 'var(--xs-text-secondary)',
                    fontSize: 9,
                    fontWeight: 700,
                  }}
                >
                  {f.slice(0, 3).toUpperCase()}
                </span>
              )}
          </span>
        ))}
      </div>
      {overflow > 0 && (
        <span style={{ marginLeft: 6, fontSize: 14, fontWeight: 500, color: 'var(--xs-blue-02)' }}>
          +{overflow}
        </span>
      )}
    </div>
  );
};
