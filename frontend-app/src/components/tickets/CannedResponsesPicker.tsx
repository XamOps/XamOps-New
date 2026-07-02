/**
 * Canned Responses picker — a compact popover that lets an agent insert a saved
 * reply template into the composer with one click, and manage the shared library
 * (create / edit / delete) inline.
 */
import React, { useMemo, useRef, useState, useEffect } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import { useCannedResponses, CannedResponse } from '../../hooks/useCannedResponses';

interface Props {
  onInsert: (body: string) => void;
}

const stripHtml = (html: string): string => {
  const tmp = document.createElement('div');
  tmp.innerHTML = html || '';
  return (tmp.textContent || '').trim();
};

const CannedResponsesPicker: React.FC<Props> = ({ onInsert }) => {
  const [open, setOpen] = useState(false);
  const { responses, loading, create, update, remove } = useCannedResponses(open);
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<CannedResponse | null>(null);
  const [creating, setCreating] = useState(false);
  const [formTitle, setFormTitle] = useState('');
  const [formBody, setFormBody] = useState('');
  const [saving, setSaving] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  // Fixed-position coords so the popover escapes the composer's overflow-hidden box.
  const [coords, setCoords] = useState<{ bottom: number; right: number } | null>(null);

  const computeCoords = () => {
    const r = buttonRef.current?.getBoundingClientRect();
    if (!r) return;
    setCoords({
      bottom: window.innerHeight - r.top + 8,
      right: window.innerWidth - r.right,
    });
  };

  const toggle = () => {
    if (!open) computeCoords();
    setOpen((v) => !v);
  };

  // Close on outside click; reposition on scroll/resize while open
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const t = e.target as Node;
      if (
        popoverRef.current && !popoverRef.current.contains(t) &&
        buttonRef.current && !buttonRef.current.contains(t)
      ) {
        setOpen(false);
      }
    };
    const reposition = () => computeCoords();
    document.addEventListener('mousedown', handler);
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    return () => {
      document.removeEventListener('mousedown', handler);
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition, true);
    };
  }, [open]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return responses;
    return responses.filter(
      (r) => r.title.toLowerCase().includes(q) || stripHtml(r.body).toLowerCase().includes(q)
    );
  }, [responses, query]);

  const resetForm = () => {
    setCreating(false);
    setEditing(null);
    setFormTitle('');
    setFormBody('');
  };

  const startCreate = () => {
    setEditing(null);
    setCreating(true);
    setFormTitle('');
    setFormBody('');
  };

  const startEdit = (r: CannedResponse) => {
    setCreating(false);
    setEditing(r);
    setFormTitle(r.title);
    setFormBody(stripHtml(r.body));
  };

  const saveForm = async () => {
    if (!formTitle.trim() || !formBody.trim()) return;
    setSaving(true);
    const ok = editing
      ? await update(editing.id, formTitle.trim(), formBody.trim())
      : await create(formTitle.trim(), formBody.trim());
    setSaving(false);
    if (ok) resetForm();
  };

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={toggle}
        title="Insert a saved reply"
        className="flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[11px] font-medium border border-[rgba(0,0,0,0.12)] bg-white text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.88)] transition-colors"
      >
        <Icon icon="mdi:message-text-fast-outline" width={13} />
        Canned
      </button>

      {open && coords && ReactDOM.createPortal(
        <div
          ref={popoverRef}
          style={{ position: 'fixed', bottom: coords.bottom, right: coords.right, width: 320, zIndex: 9999 }}
          className="bg-white rounded-xl shadow-2xl border border-[rgba(0,0,0,0.1)] overflow-hidden"
        >
          <div className="px-3 py-2.5 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between">
            <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wider">
              Saved Replies
            </span>
            <button
              type="button"
              onClick={creating || editing ? resetForm : startCreate}
              className="flex items-center gap-1 text-[11px] font-medium text-indigo-600 hover:text-indigo-700"
            >
              <Icon icon={creating || editing ? 'mdi:close' : 'mdi:plus'} width={13} />
              {creating || editing ? 'Cancel' : 'New'}
            </button>
          </div>

          {(creating || editing) ? (
            <div className="p-3 space-y-2">
              <input
                value={formTitle}
                onChange={(e) => setFormTitle(e.target.value)}
                placeholder="Template title"
                className="w-full px-2.5 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none focus:ring-1 focus:ring-indigo-300 placeholder:text-[rgba(0,0,0,0.3)]"
              />
              <textarea
                value={formBody}
                onChange={(e) => setFormBody(e.target.value)}
                placeholder="Reply text…"
                rows={5}
                className="w-full px-2.5 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:outline-none focus:ring-1 focus:ring-indigo-300 resize-y placeholder:text-[rgba(0,0,0,0.3)]"
              />
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={saveForm}
                  disabled={saving || !formTitle.trim() || !formBody.trim()}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-[11px] font-medium rounded-md hover:bg-black disabled:opacity-50"
                >
                  {saving ? <Icon icon="mdi:loading" width={12} className="animate-spin" /> : <Icon icon="mdi:content-save-outline" width={12} />}
                  {editing ? 'Update' : 'Save'}
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="px-3 py-2 border-b border-[rgba(0,0,0,0.04)]">
                <div className="relative">
                  <Icon icon="mdi:magnify" width={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
                  <input
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Search templates…"
                    className="w-full pl-8 pr-3 py-1.5 text-[12px] border border-[rgba(0,0,0,0.1)] rounded-lg focus:outline-none focus:ring-1 focus:ring-indigo-300 placeholder:text-[rgba(0,0,0,0.3)]"
                  />
                </div>
              </div>
              <div className="max-h-72 overflow-y-auto custom-scrollbar">
                {loading ? (
                  <div className="px-3 py-6 text-center text-[12px] text-[rgba(0,0,0,0.4)]">Loading…</div>
                ) : filtered.length === 0 ? (
                  <div className="px-3 py-6 text-center text-[12px] text-[rgba(0,0,0,0.4)]">
                    {responses.length === 0 ? 'No saved replies yet. Click “New” to add one.' : 'No matches.'}
                  </div>
                ) : (
                  filtered.map((r) => (
                    <div key={r.id} className="group px-3 py-2 border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                      <div className="flex items-start justify-between gap-2">
                        <button
                          type="button"
                          onClick={() => { onInsert(r.body); setOpen(false); }}
                          className="flex-1 min-w-0 text-left"
                          title="Insert this reply"
                        >
                          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.85)] truncate">{r.title}</p>
                          <p className="text-[11px] text-[rgba(0,0,0,0.45)] line-clamp-2 mt-0.5">{stripHtml(r.body)}</p>
                        </button>
                        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                          <button type="button" onClick={() => startEdit(r)} title="Edit" className="w-6 h-6 flex items-center justify-center rounded-md text-[rgba(0,0,0,0.4)] hover:bg-[rgba(0,0,0,0.06)] hover:text-[rgba(0,0,0,0.7)]">
                            <Icon icon="mdi:pencil-outline" width={13} />
                          </button>
                          <button type="button" onClick={() => remove(r.id)} title="Delete" className="w-6 h-6 flex items-center justify-center rounded-md text-[rgba(0,0,0,0.4)] hover:bg-red-50 hover:text-red-500">
                            <Icon icon="mdi:trash-can-outline" width={13} />
                          </button>
                        </div>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </>
          )}
        </div>,
        document.body
      )}
    </div>
  );
};

export default CannedResponsesPicker;
