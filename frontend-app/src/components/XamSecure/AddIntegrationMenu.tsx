import React, { useEffect, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { useNavigate } from 'react-router-dom';

/**
 * "+" menu in the XamSecure topbar, for adding a security integration.
 *
 * Every one of these already existed on the Onboarding tab, which nothing linked to and which is
 * deliberately absent from the sidebar (see xamsecureTabs). So the whole onboarding surface was
 * reachable only by typing the URL. This is the entry point.
 *
 * Navigation carries `?add=<kind>` rather than opening anything itself, so a menu item and a pasted
 * link behave identically and the onboarding tab stays the single owner of each flow.
 */

export type IntegrationKind = 'account' | 'host' | 'kubernetes' | 'ecs';

interface Item {
  kind: IntegrationKind;
  label: string;
  hint: string;
  icon: string;
}

const ITEMS: Item[] = [
  {
    kind: 'account',
    label: 'Cloud account',
    hint: 'Configuration, compliance and agentless inventory',
    icon: 'mdi:cloud-plus-outline',
  },
  {
    kind: 'host',
    label: 'Host sensor',
    hint: 'Runtime protection for a virtual machine',
    icon: 'mdi:server-plus',
  },
  {
    kind: 'kubernetes',
    label: 'Kubernetes cluster',
    hint: 'Runtime protection for EKS, AKS or GKE workloads',
    icon: 'mdi:kubernetes',
  },
  {
    kind: 'ecs',
    label: 'ECS cluster',
    hint: 'Runtime protection for containers on ECS',
    icon: 'mdi:docker',
  },
];

const AddIntegrationMenu: React.FC = () => {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const ref = useRef<HTMLDivElement>(null);

  // Close on outside click and on Escape. Without the keyboard path the menu can only be
  // dismissed with a mouse, which strands anyone navigating by keyboard.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const go = (kind: IntegrationKind) => {
    setOpen(false);
    navigate(`/xamsecure/onboarding?add=${kind}`);
  };

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Add a security integration"
        title="Add a security integration"
        className="flex items-center gap-1.5 rounded-xl border border-gray-200 bg-white px-3 py-2
                   text-xs font-bold text-gray-800 transition-all hover:bg-gray-50 active:scale-95"
      >
        <Icon icon="mdi:plus" width={15} />
        <span className="hidden sm:inline">Add</span>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-50 mt-2 w-72 overflow-hidden rounded-xl border
                     border-gray-200 bg-white shadow-lg"
        >
          <p className="border-b border-gray-100 px-4 py-2 text-[10px] font-semibold uppercase
                        tracking-wide text-gray-400">
            Add integration
          </p>
          {ITEMS.map((it) => (
            <button
              key={it.kind}
              type="button"
              role="menuitem"
              onClick={() => go(it.kind)}
              className="flex w-full items-start gap-3 px-4 py-2.5 text-left transition-colors
                         hover:bg-gray-50"
            >
              <Icon icon={it.icon} width={17} className="mt-0.5 shrink-0 text-gray-500" />
              <span className="min-w-0">
                <span className="block text-xs font-semibold text-gray-900">{it.label}</span>
                <span className="block text-[10px] leading-snug text-gray-500">{it.hint}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export default AddIntegrationMenu;
