import React, { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreAccessGroup } from '../../../../types/dspmDatastores';
import { MAX_SEARCH } from '../datastoreFilters';
import { CopyButton } from '../ui/CopyButton';
import { SearchInput } from '../ui/SearchInput';
import { PermissionChips, principalKind } from './AccessGraphCanvas';
import { useSidePanel } from './EscapeStack';

interface Props {
  group: DatastoreAccessGroup;
  /** The Permissions box or type box that opened the panel; focus goes back to it on close. */
  returnFocus: HTMLElement | null;
  /** Should be stable: the panel is on the drawer's Escape stack under it. */
  onClose: () => void;
}

/**
 * One permission group in full, in a 320 px panel on the right of the Access Graph tab: "Permissions",
 * the principal type with its count, the permission chips, then the group's principals (sorted by the
 * backend) with a search and a Copy button each.
 *
 * The panel is on the drawer's Escape stack, so Escape closes it before the drawer; Escape and its X
 * both return focus to the box that opened it.
 */
const PermissionsPanel: React.FC<Props> = ({ group, returnFocus, onClose }) => {
  const { closeButton, close } = useSidePanel(returnFocus, onClose);
  const [search, setSearch] = useState('');
  const titleId = useId();
  const needle = search.trim().toLowerCase();
  const names = needle ? group.principals.filter(name => name.toLowerCase().includes(needle)) : group.principals;

  return (
    <section
      aria-labelledby={titleId}
      className="absolute inset-y-0 right-0 z-30 flex w-80 flex-col rounded-l-xl border-l border-[rgba(0,0,0,0.08)] bg-white shadow-2xl"
    >
      <header className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-3">
        <div className="min-w-0 flex-1">
          <h3 id={titleId} className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Permissions</h3>
          <p className="mt-0.5 text-[11px] text-[rgba(0,0,0,0.5)]">
            {principalKind(group.principalType).label} - {group.count} principals
          </p>
        </div>
        <button
          ref={closeButton}
          type="button"
          onClick={close}
          aria-label="Close permissions"
          className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
        >
          <Icon icon="mdi:close" width={14} />
        </button>
      </header>
      <div className="space-y-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-3">
        <PermissionChips group={group} />
        <SearchInput value={search} onChange={setSearch} placeholder="Search principals" maxLength={MAX_SEARCH} />
      </div>
      <ul className="flex-1 overflow-y-auto px-2 py-2">
        {names.length === 0 && <li className="px-2 py-4 text-center text-xs text-[rgba(0,0,0,0.4)]">No matches</li>}
        {names.map((name, index) => (
          <li key={`${index}:${name}`} className="flex items-center gap-2 rounded-md px-2 py-1 hover:bg-[rgba(0,0,0,0.03)]">
            <span className="min-w-0 flex-1 truncate text-xs text-[rgba(0,0,0,0.75)]" title={name}>{name}</span>
            <CopyButton text={name} label={`Copy ${name}`} />
          </li>
        ))}
      </ul>
    </section>
  );
};

export default PermissionsPanel;
