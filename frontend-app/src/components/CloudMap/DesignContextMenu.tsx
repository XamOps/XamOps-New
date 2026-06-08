import React, { useEffect, useRef } from 'react';

interface Props {
  x: number;
  y: number;
  elementType: 'node' | 'edge';
  onEditLabel: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
  onBringToFront: () => void;
  onSendToBack: () => void;
  onClose: () => void;
}

const DesignContextMenu: React.FC<Props> = ({
  x, y, elementType,
  onEditLabel, onDuplicate, onDelete,
  onBringToFront, onSendToBack, onClose,
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
  const left = Math.min(x, window.innerWidth - 180);
  const top = Math.min(y, window.innerHeight - 220);

  type MenuItem =
    | { kind: 'sep' }
    | { kind: 'item'; icon: string; label: string; action: () => void; danger?: boolean };

  const items: MenuItem[] = [
    { kind: 'item', icon: 'fa-edit', label: 'Edit Label', action: onEditLabel },
    ...(elementType === 'node'
      ? [
          { kind: 'item' as const, icon: 'fa-copy', label: 'Duplicate', action: onDuplicate },
          { kind: 'sep' as const },
          { kind: 'item' as const, icon: 'fa-arrow-up', label: 'Bring to Front', action: onBringToFront },
          { kind: 'item' as const, icon: 'fa-arrow-down', label: 'Send to Back', action: onSendToBack },
        ]
      : []),
    { kind: 'sep' },
    { kind: 'item', icon: 'fa-trash', label: 'Delete', action: onDelete, danger: true },
  ];

  return (
    <div
      ref={ref}
      className="fixed z-[200] bg-white rounded-xl shadow-2xl border border-gray-100 py-1.5 min-w-[160px] overflow-hidden"
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
                : 'text-gray-700 hover:bg-gray-50'
            }`}
          >
            <i className={`fas ${item.icon} w-3.5 text-center opacity-60`} />
            {item.label}
          </button>
        );
      })}
    </div>
  );
};

export default DesignContextMenu;
