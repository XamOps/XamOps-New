/**
 * Resource Labels Page
 *
 * Lets users:
 *  - Create / edit / delete custom labels (any name: team, dept, project, env…)
 *  - See all resources assigned to a label (paginated)
 *
 * Labels are XamOps-internal only — nothing is written to AWS / GCP / Azure.
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  fetchLabels,
  createLabel,
  updateLabel,
  deleteLabel,
  fetchResourcesByLabel,
} from '../services/resourceLabelsApi';
import type { ResourceLabel, LabeledResource, PagedResponse } from '../types/resourceLabels';
import LabelBadge from '../components/Labels/LabelBadge';
import { useTopbar } from '../contexts/TopbarContext';

// Preset palette for the color picker
const COLOR_PALETTE = [
  '#6366f1', '#8b5cf6', '#ec4899', '#ef4444',
  '#f97316', '#eab308', '#22c55e', '#14b8a6',
  '#3b82f6', '#06b6d4', '#64748b', '#1d4ed8',
];

// ─── Label Form Modal ────────────────────────────────────────────────────────

interface LabelFormProps {
  initial?: ResourceLabel;
  onSave: (name: string, color: string, description: string) => Promise<void>;
  onCancel: () => void;
}

const LabelForm: React.FC<LabelFormProps> = ({ initial, onSave, onCancel }) => {
  const [name, setName] = useState(initial?.name ?? '');
  const [color, setColor] = useState(initial?.color ?? '#6366f1');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) { setError('Label name is required.'); return; }
    setSaving(true);
    setError('');
    try {
      await onSave(name.trim(), color, description.trim());
    } catch (err: any) {
      setError(err?.message ?? 'Failed to save label.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md p-6" onClick={e => e.stopPropagation()}>
        <h2 className="text-lg font-bold text-gray-800 mb-5">
          {initial ? 'Edit Label' : 'New Label'}
        </h2>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Name */}
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">Label Name *</label>
            <input
              autoFocus
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="e.g. Engineering, Team-A, Project-X…"
              className="w-full border border-gray-300 rounded-xl px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            />
          </div>

          {/* Color */}
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-2">Color</label>
            <div className="flex flex-wrap gap-2">
              {COLOR_PALETTE.map(c => (
                <button
                  key={c}
                  type="button"
                  onClick={() => setColor(c)}
                  className="w-7 h-7 rounded-full border-2 transition-all"
                  style={{
                    backgroundColor: c,
                    borderColor: color === c ? '#1e293b' : 'transparent',
                    transform: color === c ? 'scale(1.15)' : 'scale(1)',
                  }}
                />
              ))}
              {/* Custom hex input */}
              <input
                type="color"
                value={color}
                onChange={e => setColor(e.target.value)}
                className="w-7 h-7 rounded-full cursor-pointer border border-gray-300"
                title="Custom color"
              />
            </div>
            {/* Preview */}
            <div className="mt-2">
              <LabelBadge label={{ id: 0, name: name || 'Preview', color, resourceCount: 0, createdAt: '' }} size="md" />
            </div>
          </div>

          {/* Description */}
          <div>
            <label className="block text-xs font-semibold text-gray-600 mb-1">Description (optional)</label>
            <input
              type="text"
              value={description}
              onChange={e => setDescription(e.target.value)}
              placeholder="What does this label represent?"
              className="w-full border border-gray-300 rounded-xl px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            />
          </div>

          {error && <p className="text-xs text-red-600 font-medium">{error}</p>}

          <div className="flex justify-end gap-3 pt-2">
            <button type="button" onClick={onCancel} className="px-5 py-2 text-sm font-semibold text-gray-600 hover:bg-gray-100 rounded-xl transition-colors">
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="px-5 py-2 text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-xl disabled:opacity-50 transition-colors"
            >
              {saving ? 'Saving…' : initial ? 'Save Changes' : 'Create Label'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

// ─── Resource Table ──────────────────────────────────────────────────────────

interface ResourceTableProps {
  label: ResourceLabel;
  onClose: () => void;
}

const ResourceTable: React.FC<ResourceTableProps> = ({ label, onClose }) => {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PagedResponse<LabeledResource> | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetchResourcesByLabel(label.id, page, 50)
      .then(setData)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [label.id, page]);

  return (
    <div className="bg-white rounded-2xl shadow-lg border border-gray-200/50 overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
        <div className="flex items-center gap-3">
          <LabelBadge label={label} size="md" />
          <span className="text-sm text-gray-500 font-medium">
            {data ? `${data.totalElements} resource${data.totalElements !== 1 ? 's' : ''}` : '…'}
          </span>
        </div>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-xl leading-none" aria-label="Close">×</button>
      </div>

      {/* Table */}
      {loading ? (
        <div className="flex items-center justify-center py-12 text-gray-400 text-sm">
          <i className="fas fa-spinner fa-spin mr-2" /> Loading resources…
        </div>
      ) : !data || data.content.length === 0 ? (
        <div className="text-center py-12 text-gray-500 text-sm">
          No resources assigned to this label yet.<br />
          Go to the <strong>Cloud List</strong> page and use the Labels column to assign resources.
        </div>
      ) : (
        <>
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-100">
              <tr>
                <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wide">Resource ID</th>
                <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wide">Type</th>
                <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wide">Name</th>
                <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wide">Region</th>
                <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wide">Tagged By</th>
                <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wide">Tagged At</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {data.content.map(res => (
                <tr key={`${res.resourceId}-${res.taggedAt}`} className="hover:bg-gray-50/50 transition-colors">
                  <td className="px-6 py-3 font-mono text-xs text-gray-600">{res.resourceId}</td>
                  <td className="px-6 py-3 text-gray-600">
                    <span className="bg-gray-100 text-gray-700 text-xs font-semibold px-2 py-0.5 rounded-md">{res.resourceType}</span>
                  </td>
                  <td className="px-6 py-3 text-gray-700 font-medium">{res.resourceName || '—'}</td>
                  <td className="px-6 py-3 text-gray-500 text-xs">{res.region || '—'}</td>
                  <td className="px-6 py-3 text-gray-500 text-xs">{res.taggedBy || '—'}</td>
                  <td className="px-6 py-3 text-gray-500 text-xs">
                    {res.taggedAt ? new Date(res.taggedAt).toLocaleDateString() : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination */}
          {data.totalPages > 1 && (
            <div className="flex items-center justify-between px-6 py-3 border-t border-gray-100">
              <span className="text-xs text-gray-500">
                Page {data.number + 1} of {data.totalPages}
              </span>
              <div className="flex gap-2">
                <button
                  disabled={data.number === 0}
                  onClick={() => setPage(p => p - 1)}
                  className="px-3 py-1 text-xs font-semibold border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 transition-colors"
                >
                  Previous
                </button>
                <button
                  disabled={data.last}
                  onClick={() => setPage(p => p + 1)}
                  className="px-3 py-1 text-xs font-semibold border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-40 transition-colors"
                >
                  Next
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
};

// ─── Main Page ───────────────────────────────────────────────────────────────

const ResourceLabelsPage: React.FC = () => {
  const { clearActions } = useTopbar();
  const [labels, setLabels] = useState<ResourceLabel[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<ResourceLabel | undefined>();
  const [expandedLabelId, setExpandedLabelId] = useState<number | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);

  useEffect(() => { clearActions(); }, [clearActions]);

  const load = useCallback(() => {
    setLoading(true);
    fetchLabels()
      .then(setLabels)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async (name: string, color: string, description: string) => {
    await createLabel({ name, color, description });
    setShowForm(false);
    load();
  };

  const handleUpdate = async (name: string, color: string, description: string) => {
    if (!editing) return;
    await updateLabel(editing.id, { name, color, description });
    setEditing(undefined);
    load();
  };

  const handleDelete = async (id: number) => {
    await deleteLabel(id);
    setDeleteConfirmId(null);
    if (expandedLabelId === id) setExpandedLabelId(null);
    load();
  };

  return (
    <main className="flex-1 overflow-y-auto p-8" style={{ minHeight: '100vh', background: '#f8fafc' }}>
      {/* Page header */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">Resource Labels</h1>
          <p className="text-sm text-gray-500 mt-1">
            Virtual labels for grouping resources — not written to AWS / GCP / Azure.
            Name them anything: team, department, project, environment…
          </p>
        </div>
        <button
          onClick={() => { setEditing(undefined); setShowForm(true); }}
          className="flex items-center gap-2 px-5 py-2.5 bg-indigo-600 text-white text-sm font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all active:scale-95"
        >
          <i className="fas fa-plus" />
          New Label
        </button>
      </div>

      {/* Label cards grid */}
      {loading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map(i => (
            <div key={i} className="h-28 bg-white/60 rounded-2xl animate-pulse border border-gray-200/50" />
          ))}
        </div>
      ) : labels.length === 0 ? (
        <div className="text-center py-20">
          <div className="w-16 h-16 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-tags text-white text-2xl" />
          </div>
          <h3 className="text-xl font-bold text-gray-700 mb-2">No labels yet</h3>
          <p className="text-gray-500 mb-6 text-sm">
            Create your first label and start assigning resources to it from the Cloud List page.
          </p>
          <button
            onClick={() => setShowForm(true)}
            className="px-6 py-3 bg-indigo-600 text-white text-sm font-bold rounded-xl hover:bg-indigo-700 transition-all"
          >
            <i className="fas fa-plus mr-2" />Create Label
          </button>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 mb-8">
            {labels.map(label => (
              <div
                key={label.id}
                className="bg-white rounded-2xl border border-gray-200/50 shadow-sm hover:shadow-md transition-shadow cursor-pointer group"
                style={{ borderLeft: `4px solid ${label.color}` }}
                onClick={() => setExpandedLabelId(expandedLabelId === label.id ? null : label.id)}
              >
                <div className="p-5">
                  <div className="flex items-start justify-between gap-2 mb-3">
                    <LabelBadge label={label} size="md" />
                    {/* Actions — visible on hover */}
                    <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity" onClick={e => e.stopPropagation()}>
                      <button
                        onClick={() => { setEditing(label); setShowForm(true); }}
                        className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400 hover:text-indigo-600 transition-colors"
                        title="Edit label"
                      >
                        <i className="fas fa-pencil-alt text-xs" />
                      </button>
                      <button
                        onClick={() => setDeleteConfirmId(label.id)}
                        className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-red-50 text-gray-400 hover:text-red-500 transition-colors"
                        title="Delete label"
                      >
                        <i className="fas fa-trash-alt text-xs" />
                      </button>
                    </div>
                  </div>

                  {label.description && (
                    <p className="text-xs text-gray-500 mb-3 line-clamp-2">{label.description}</p>
                  )}

                  <div className="flex items-center justify-between">
                    <span className="text-2xl font-bold" style={{ color: label.color }}>
                      {label.resourceCount}
                    </span>
                    <span className="text-xs text-gray-400">
                      {label.resourceCount === 1 ? 'resource' : 'resources'}
                    </span>
                  </div>
                </div>

                {/* Expand indicator */}
                <div className="border-t border-gray-100 px-5 py-2 flex items-center justify-center">
                  <i className={`fas fa-chevron-down text-gray-300 text-xs transition-transform duration-200 ${expandedLabelId === label.id ? 'rotate-180' : ''}`} />
                </div>
              </div>
            ))}
          </div>

          {/* Expanded resource table */}
          {expandedLabelId !== null && (() => {
            const label = labels.find(l => l.id === expandedLabelId);
            if (!label) return null;
            return (
              <ResourceTable
                key={expandedLabelId}
                label={label}
                onClose={() => setExpandedLabelId(null)}
              />
            );
          })()}
        </>
      )}

      {/* Create / Edit form modal */}
      {showForm && (
        <LabelForm
          initial={editing}
          onSave={editing ? handleUpdate : handleCreate}
          onCancel={() => { setShowForm(false); setEditing(undefined); }}
        />
      )}

      {/* Delete confirmation dialog */}
      {deleteConfirmId !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-sm p-6 text-center">
            <div className="w-12 h-12 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-trash-alt text-red-500 text-lg" />
            </div>
            <h3 className="text-base font-bold text-gray-800 mb-2">Delete label?</h3>
            <p className="text-sm text-gray-500 mb-6">
              This will remove the label and all its resource assignments. Resources themselves are unaffected.
            </p>
            <div className="flex gap-3 justify-center">
              <button
                onClick={() => setDeleteConfirmId(null)}
                className="px-5 py-2 text-sm font-semibold text-gray-600 hover:bg-gray-100 rounded-xl transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => handleDelete(deleteConfirmId)}
                className="px-5 py-2 text-sm font-semibold text-white bg-red-500 hover:bg-red-600 rounded-xl transition-colors"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
};

export default ResourceLabelsPage;
