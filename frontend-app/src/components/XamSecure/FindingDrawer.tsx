import React from 'react';
import { Icon } from '@iconify/react';

interface Props {
  title: string;
  rawPayload: string | null;
  onClose: () => void;
  /** Optional footer action(s) (e.g. Archive button). Omit for no footer. */
  actions?: React.ReactNode;
}

/** Right-side drawer showing the full finding payload as a key/value tree. */
const FindingDrawer: React.FC<Props> = ({ title, rawPayload, onClose, actions }) => {
  let pretty = '';
  try {
    pretty = rawPayload ? JSON.stringify(JSON.parse(rawPayload), null, 2) : '';
  } catch {
    pretty = rawPayload ?? '';
  }
  return (
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="relative w-full max-w-xl h-full bg-white shadow-2xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <h3 className="text-sm font-bold text-gray-900 pr-4 line-clamp-2">{title}</h3>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100" aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>
        <pre className="flex-1 overflow-auto p-5 text-[11px] leading-relaxed text-gray-700 bg-gray-50">
          {pretty}
        </pre>
        {actions && (
          <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-gray-200 bg-white">
            {actions}
          </div>
        )}
      </div>
    </div>
  );
};

export default FindingDrawer;
