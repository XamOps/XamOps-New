import React, { useEffect, useRef } from 'react';

interface Props {
  x: number;
  y: number;
  elementType: 'node' | 'edge';
  locked: boolean;
  onEditLabel: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
  onBringToFront: () => void;
  onSendToBack: () => void;
  onBringForward: () => void;
  onSendBackward: () => void;
  onLockToggle: () => void;
  onClose: () => void;
  onReverseDirection?: () => void;
  onSendToScratchpad?: () => void;
}

const DesignContextMenu: React.FC<Props> = ({
  x, y, elementType, locked,
  onEditLabel, onDuplicate, onDelete,
  onBringToFront, onSendToBack, onBringForward, onSendBackward,
  onLockToggle, onClose, onReverseDirection, onSendToScratchpad,
}) => {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onMouseDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [onClose]);

  // Clamp to viewport so menu doesn't overflow
  const left = Math.min(x, window.innerWidth - 200);
  const top  = Math.min(y, window.innerHeight - 280);

  type MenuItem =
    | { kind: 'sep' }
    | { kind: 'item'; icon: string; label: string; action: () => void; danger?: boolean; accent?: boolean };

  const items: MenuItem[] = [
    { kind: 'item', icon: 'fa-edit', label: 'Edit Label', action: onEditLabel },
    ...(elementType === 'node'
      ? [{ kind: 'item' as const, icon: 'fa-copy', label: 'Duplicate  Ctrl+D', action: onDuplicate }]
      : []),
    ...(elementType === 'edge' && onReverseDirection
      ? [{ kind: 'item' as const, icon: 'fa-exchange-alt', label: 'Reverse Direction', action: onReverseDirection }]
      : []),
    ...(elementType === 'node' && onSendToScratchpad
      ? [{ kind: 'item' as const, icon: 'fa-clipboard', label: 'Send to Scratchpad', action: onSendToScratchpad }]
      : []),
    { kind: 'sep' },
    { kind: 'item', icon: 'fa-angle-double-up',   label: 'Bring to Front',   action: onBringToFront  },
    { kind: 'item', icon: 'fa-angle-up',           label: 'Bring Forward',    action: onBringForward  },
    { kind: 'item', icon: 'fa-angle-down',         label: 'Send Backward',    action: onSendBackward  },
    { kind: 'item', icon: 'fa-angle-double-down',  label: 'Send to Back',     action: onSendToBack    },
    { kind: 'sep' },
    {
      kind: 'item',
      icon: locked ? 'fa-unlock' : 'fa-lock',
      label: locked ? 'Unlock  Ctrl+L' : 'Lock  Ctrl+L',
      action: onLockToggle,
      accent: true,
    },
    { kind: 'sep' },
    { kind: 'item', icon: 'fa-trash', label: 'Delete  Del', action: onDelete, danger: true },
  ];

  return (
    <div
      ref={ref}
      className="fixed z-[300] bg-white rounded-xl shadow-2xl border border-gray-100 py-1.5 min-w-[190px] overflow-hidden"
      style={{ left, top }}
    >
      {items.map((item, i) => {
        if (item.kind === 'sep') {
          return <div key={i} className="my-1 border-t border-gray-100" />;
        }
        return (
          <button
            key={i}
            onClick={() => { item.action(); onClose(); }}
            className={`w-full px-3.5 py-1.5 text-left text-xs flex items-center gap-2.5 transition-colors font-medium ${
              item.danger
                ? 'text-red-600 hover:bg-red-50'
                : item.accent
                  ? 'text-amber-600 hover:bg-amber-50'
                  : 'text-gray-700 hover:bg-gray-50'
            }`}
          >
            <i className={`fas ${item.icon} w-3.5 text-center opacity-70 shrink-0`} />
            <span>{item.label}</span>
          </button>
        );
      })}
    </div>
  );
};

export default DesignContextMenu;
