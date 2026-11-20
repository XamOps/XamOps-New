import React, { useCallback, useEffect, useId, useRef, useState, type RefObject } from 'react';
import { Icon } from '@iconify/react';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import { ConsoleOnlyMenuItem } from '../ui/ConsoleOnly';
import { usePopoverEscape } from '../ui/usePopoverEscape';
import { scanSettingsEligible } from './scanSettingsModel';

interface Props {
  row: Pick<DatastoreRow, 'category' | 'source'>;
  /** The trigger; the Scan Settings layer returns focus to it. */
  buttonRef: RefObject<HTMLButtonElement | null>;
  onScanSettings: () => void;
}

const menuItems = (menu: HTMLElement | null) =>
  Array.from(menu?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);

/**
 * The info card's "Actions ▾" menu (design 4.8), in the console's order: Sync Datastore, Download
 * Sensitives, Scan Settings (only for the datastores `scanSettingsEligible` admits) and Create Jira
 * Ticket. Every item but Scan Settings is console-only and sends nothing.
 *
 * Opening focuses the first enabled item (else the first item); ArrowUp / ArrowDown move between items.
 * Escape closes the menu and returns focus to the trigger, never the drawer: in the menu's own
 * `onKeyDown`, and through the drawer's Escape stack when focus is elsewhere. A mousedown outside the
 * menu and its trigger closes it too.
 */
const DrawerActionsMenu: React.FC<Props> = ({ row, buttonRef, onScanSettings }) => {
  const [open, setOpen] = useState(false);
  const menu = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const close = useCallback(() => setOpen(false), []);
  usePopoverEscape(open, close, buttonRef);

  useEffect(() => {
    if (!open) return;
    const items = menuItems(menu.current);
    (items.find(item => item.getAttribute('aria-disabled') !== 'true') ?? items[0])?.focus();
    const onDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (menu.current?.contains(target) || buttonRef.current?.contains(target)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open, buttonRef]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      setOpen(false);
      buttonRef.current?.focus();
      return;
    }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const items = menuItems(menu.current);
    if (items.length === 0) return;
    const at = items.indexOf(document.activeElement as HTMLElement);
    const step = e.key === 'ArrowDown' ? 1 : -1;
    items[(at + step + items.length) % items.length].focus();
  };

  const openScanSettings = () => {
    setOpen(false);
    onScanSettings();
  };

  return (
    <span className="relative inline-flex">
      <button
        ref={buttonRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen(value => !value)}
        className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.04)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]"
      >
        Actions
        <Icon icon="mdi:chevron-down" width={14} />
      </button>
      {open && (
        <div
          ref={menu}
          id={menuId}
          role="menu"
          aria-label="Actions"
          onKeyDown={onKeyDown}
          className="absolute right-0 top-full z-30 mt-1 w-52 rounded-lg border border-[rgba(0,0,0,0.1)] bg-white py-1 shadow-lg"
        >
          <ConsoleOnlyMenuItem>Sync Datastore</ConsoleOnlyMenuItem>
          <ConsoleOnlyMenuItem>Download Sensitives</ConsoleOnlyMenuItem>
          {scanSettingsEligible(row) && (
            <button
              type="button"
              role="menuitem"
              onClick={openScanSettings}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs text-[rgba(0,0,0,0.75)] transition-colors hover:bg-[rgba(0,0,0,0.04)] focus:bg-[rgba(0,0,0,0.04)] focus:outline-none"
            >
              Scan Settings
            </button>
          )}
          <ConsoleOnlyMenuItem>Create Jira Ticket</ConsoleOnlyMenuItem>
        </div>
      )}
    </span>
  );
};

export default DrawerActionsMenu;
