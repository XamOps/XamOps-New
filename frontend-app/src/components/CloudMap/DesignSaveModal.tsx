import React, { useState, useEffect, useRef } from 'react';

interface Props {
  isOpen: boolean;
  initialName: string;
  saving: boolean;
  onSave: (name: string) => void;
  onClose: () => void;
}

const DesignSaveModal: React.FC<Props> = ({ isOpen, initialName, saving, onSave, onClose }) => {
  const [name, setName] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isOpen) {
      setName(initialName || 'My Architecture');
      // Focus after transition
      const t = setTimeout(() => inputRef.current?.focus(), 60);
      return () => clearTimeout(t);
    }
  }, [isOpen, initialName]);

  if (!isOpen) return null;

  const handleSave = () => {
    const trimmed = name.trim();
    if (trimmed && !saving) onSave(trimmed);
  };

  return (
    <div className="fixed inset-0 z-[300] flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={onClose} />

      {/* Modal */}
      <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-sm mx-4 overflow-hidden">
        {/* Header */}
        <div className="px-5 py-4 border-b border-gray-100 flex items-center gap-3">
          <div className="w-9 h-9 bg-indigo-100 rounded-xl flex items-center justify-center shrink-0">
            <i className="fas fa-save text-indigo-600 text-sm" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-gray-900">Save Design</h2>
            <p className="text-xs text-gray-500">Save your architecture blueprint</p>
          </div>
          <button
            onClick={onClose}
            className="ml-auto w-7 h-7 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-600 transition-colors"
          >
            <i className="fas fa-times text-xs" />
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4">
          <label className="text-xs font-semibold text-gray-700 block mb-1.5">Design Name</label>
          <input
            ref={inputRef}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleSave();
              if (e.key === 'Escape') onClose();
              e.stopPropagation();
            }}
            className="w-full px-3 py-2.5 border border-gray-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
            placeholder="e.g. Production Architecture"
          />
        </div>

        {/* Footer */}
        <div className="px-5 pb-5 flex gap-2.5">
          <button
            onClick={onClose}
            className="flex-1 px-4 py-2.5 border border-gray-200 text-gray-700 text-sm font-semibold rounded-xl hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={!name.trim() || saving}
            className="flex-1 px-4 py-2.5 bg-indigo-600 text-white text-sm font-semibold rounded-xl hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
          >
            {saving ? (
              <>
                <i className="fas fa-spinner animate-spin text-xs" />
                Saving...
              </>
            ) : (
              <>
                <i className="fas fa-save text-xs" />
                Save
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};

export default DesignSaveModal;
