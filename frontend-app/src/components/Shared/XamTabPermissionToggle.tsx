/**
 * Permission keys nested under their sidebar tab in both user pickers (My Team and the BillOps user
 * manager), so the D2 rules live in one place (docs/cloudk8s/ui-revamp-design.md D2; plan P12).
 *
 * - The checkbox shows only while its parent tab is checked: a permission without its tab means
 *   nothing, and normalizeAllowedTabs removes it.
 * - With nothing selected the member is unrestricted, which includes the permission; the line says so
 *   where it applies.
 * - A tab without permissions renders nothing.
 */
import { useId } from 'react';
import { XAMOPS_TAB_PERMISSIONS, toggleAllowedTab } from '../../constants/xamopsTabs';

/** D2 copy per permission key. */
const COPY: Record<string, { hint: string; unrestricted: string }> = {
  'cloudops-cloudk8s-automation': {
    hint: 'Lets this member change automation settings. Recommendations are visible without it.',
    unrestricted: 'Unrestricted members can change CloudK8s automation.',
  },
};

export interface XamTabPermissionToggleProps {
  /** The sidebar tab the permissions belong to, e.g. 'cloudops-cloudk8s'. */
  parentKey: string;
  allowedTabs: readonly string[];
  /** Receives the whole new list, already normalized. */
  onChange: (next: string[]) => void;
}

export function XamTabPermissionToggle({ parentKey, allowedTabs, onChange }: XamTabPermissionToggleProps) {
  const baseId = useId();
  const permissions = XAMOPS_TAB_PERMISSIONS.filter(p => p.parent === parentKey);
  if (permissions.length === 0) return null;

  if (allowedTabs.length === 0) {
    return (
      <>
        {permissions.map(p => COPY[p.key] && (
          <p key={p.key} className="px-2.5 text-[10px] leading-snug text-[rgba(0,0,0,0.5)]">{COPY[p.key].unrestricted}</p>
        ))}
      </>
    );
  }
  if (!allowedTabs.includes(parentKey)) return null;

  return (
    <div className="ml-5 space-y-1">
      {permissions.map((p, index) => {
        const hintId = `${baseId}-${index}-hint`;
        const checked = allowedTabs.includes(p.key);
        return (
          <div key={p.key}>
            <label className="flex cursor-pointer items-center gap-2 rounded-lg border border-[rgba(0,0,0,0.06)] px-2.5 py-1.5 transition-colors hover:bg-[rgba(0,0,0,0.02)]">
              <input
                type="checkbox"
                checked={checked}
                aria-describedby={COPY[p.key] ? hintId : undefined}
                onChange={() => onChange(toggleAllowedTab(allowedTabs, p.key))}
                className="h-3.5 w-3.5 flex-shrink-0 cursor-pointer rounded accent-[rgba(0,0,0,0.88)]"
              />
              <span className="text-[11px] font-medium text-[rgba(0,0,0,0.7)]">{p.label}</span>
            </label>
            {COPY[p.key] && (
              <p id={hintId} className="mt-0.5 px-2.5 text-[10px] leading-snug text-[rgba(0,0,0,0.5)]">{COPY[p.key].hint}</p>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default XamTabPermissionToggle;
