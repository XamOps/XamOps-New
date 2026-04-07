import React, { useEffect, useRef, useState } from 'react';
import type { ResourceLabel, ResourceRef } from '../../types/resourceLabels';
import { assignLabel, removeLabel, createLabel } from '../../services/resourceLabelsApi';

interface Props {
  accountId: string;
  resource: ResourceRef;
  assignedLabels: ResourceLabel[];
  allLabels: ResourceLabel[];
  onChanged: (resourceId: string, updatedLabels: ResourceLabel[]) => void;
  onClose: () => void;
  onLabelCreated?: (label: ResourceLabel) => void;
}

const QUICK_COLORS = ['#6366f1','#8b5cf6','#ec4899','#ef4444','#f97316','#eab308','#22c55e','#06b6d4','#3b82f6','#64748b'];

const LabelAssignPopover: React.FC<Props> = ({
  accountId,
  resource,
  assignedLabels,
  allLabels,
  onChanged,
  onClose,
  onLabelCreated,
}) => {
  const ref = useRef<HTMLDivElement>(null);
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState<number | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newColor, setNewColor] = useState('#6366f1');
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose]);

  const assignedIds = new Set(assignedLabels.map(l => l.id));

  const filtered = allLabels.filter(l =>
    l.name.toLowerCase().includes(search.toLowerCase())
  );

  const toggle = async (label: ResourceLabel) => {
    setBusy(label.id);
    try {
      if (assignedIds.has(label.id)) {
        await removeLabel({ accountId, labelId: label.id, resources: [resource] });
        onChanged(resource.resourceId, assignedLabels.filter(l => l.id !== label.id));
      } else {
        await assignLabel({ accountId, labelId: label.id, resources: [resource] });
        onChanged(resource.resourceId, [...assignedLabels, label]);
      }
    } finally {
      setBusy(null);
    }
  };

  const handleCreate = async () => {
    if (!newName.trim()) { setCreateError('Name is required'); return; }
    setCreating(true);
    setCreateError('');
    try {
      const created = await createLabel({ name: newName.trim(), color: newColor });
      onLabelCreated?.(created);
      // Immediately assign the new label to this resource
      await assignLabel({ accountId, labelId: created.id, resources: [resource] });
      onChanged(resource.resourceId, [...assignedLabels, created]);
      setNewName('');
      setNewColor('#6366f1');
      setShowCreate(false);
    } catch (err: any) {
      setCreateError(err?.message || 'Failed to create label');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div
      ref={ref}
      className="absolute z-50 right-0 top-full mt-2 w-72 bg-white rounded-2xl border border-gray-200"
      style={{ boxShadow: '0 12px 40px rgba(99,102,241,0.22), 0 2px 10px rgba(0,0,0,0.10)' }}
      onClick={e => e.stopPropagation()}
    >
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 rounded-t-2xl border-b border-indigo-100 bg-indigo-600">
        <div className="flex items-center gap-2">
          <i className="fas fa-tags text-indigo-200 text-xs" />
          <span className="text-xs font-bold text-white uppercase tracking-widest">Assign Labels</span>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="w-6 h-6 flex items-center justify-center rounded-full hover:bg-indigo-500 text-indigo-200 hover:text-white transition-colors"
        >
          <i className="fas fa-times text-xs" />
        </button>
      </div>

      <div className="p-3">
        {/* Search */}
        <div className="relative mb-2">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-300 text-xs" />
          <input
            autoFocus
            type="text"
            placeholder="Search labels..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="w-full pl-8 pr-3 py-2 text-xs border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-indigo-300 focus:border-transparent bg-gray-50"
          />
        </div>

        {/* Label list */}
        <div className="space-y-0.5 max-h-44 overflow-y-auto">
          {filtered.length === 0 && (
            <p className="text-xs text-gray-400 text-center py-3">
              {search ? 'No matching labels' : 'No labels yet'}
            </p>
          )}
          {filtered.map(label => {
            const isAssigned = assignedIds.has(label.id);
            const isBusy = busy === label.id;
            return (
              <button
                key={label.id}
                type="button"
                disabled={isBusy}
                onClick={() => toggle(label)}
                className={`w-full flex items-center gap-2 px-2 py-2 rounded-xl text-left transition-colors disabled:opacity-60 ${
                  isAssigned ? 'bg-indigo-50 hover:bg-indigo-100' : 'hover:bg-gray-50'
                }`}
              >
                {/* Color dot */}
                <span
                  className="w-3 h-3 rounded-full flex-shrink-0"
                  style={{ backgroundColor: label.color }}
                />
                <span className="flex-1 text-xs font-medium text-gray-700 truncate">{label.name}</span>
                {isBusy
                  ? <i className="fas fa-spinner fa-spin text-gray-400 text-xs" />
                  : isAssigned
                    ? <i className="fas fa-check text-indigo-500 text-xs" />
                    : null
                }
              </button>
            );
          })}
        </div>

        {/* Divider */}
        <div className="my-2 border-t border-gray-100" />

        {/* Create new label toggle */}
        {!showCreate ? (
          <button
            type="button"
            onClick={() => setShowCreate(true)}
            className="w-full flex items-center gap-2 px-2 py-2 rounded-xl text-xs font-semibold text-indigo-600 hover:bg-indigo-50 transition-colors"
          >
            <i className="fas fa-plus-circle text-indigo-500" />
            Create new label
          </button>
        ) : (
          <div className="bg-indigo-50/60 rounded-xl p-3 space-y-2">
            <p className="text-xs font-bold text-gray-600 mb-1">New Label</p>
            <input
              autoFocus
              type="text"
              placeholder="Label name"
              value={newName}
              maxLength={50}
              onChange={e => { setNewName(e.target.value); setCreateError(''); }}
              className="w-full px-3 py-1.5 text-xs border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-300 bg-white"
            />
            {/* Quick color picker */}
            <div className="flex gap-1.5 flex-wrap">
              {QUICK_COLORS.map(c => (
                <button
                  key={c}
                  type="button"
                  onClick={() => setNewColor(c)}
                  className={`w-5 h-5 rounded-full transition-transform hover:scale-110 ${newColor === c ? 'ring-2 ring-offset-1 ring-gray-500 scale-110' : ''}`}
                  style={{ backgroundColor: c }}
                />
              ))}
            </div>
            {createError && <p className="text-xs text-red-500">{createError}</p>}
            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleCreate}
                disabled={creating || !newName.trim()}
                className="flex-1 py-1.5 text-xs font-bold bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-40 transition-colors"
              >
                {creating ? <i className="fas fa-spinner fa-spin" /> : 'Create & Assign'}
              </button>
              <button
                type="button"
                onClick={() => { setShowCreate(false); setNewName(''); setCreateError(''); }}
                className="px-3 py-1.5 text-xs font-medium text-gray-500 hover:text-gray-700 bg-white border border-gray-200 rounded-lg transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default LabelAssignPopover;
