import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import { createLabel } from '../../services/resourceLabelsApi';
import type { LabelShare, ResourceLabel } from '../../types/resourceLabels';
import LabelHistoryList from './LabelHistoryList';

/**
 * Edits every label on one resource or one service, each with its share of the cost.
 *
 * <p>The shares may add up to less than 100 — the rest is unallocated, and the footer says how much
 * — but never more. A label added while others are present starts with whatever is still
 * unallocated, so adding a second label to a resource already at 40% offers 60% and the reader
 * only changes the number if they want a different split.
 *
 * <p>Nothing is written until Save, and the whole set is written at once. That is what lets the
 * reader move 20% from one label to another without passing through an over-100% state the
 * server would refuse.
 *
 * <p>Rendered into a portal with fixed positioning, because both places it opens from scroll: the
 * Charges by service table sits in an overflow container that clipped an absolutely positioned
 * popover to the table's height, and the resource panel scrolls its own list.
 */

const QUICK_COLORS = ['#6366f1', '#8b5cf6', '#ec4899', '#ef4444', '#f97316', '#eab308', '#22c55e', '#06b6d4', '#3b82f6', '#64748b'];
const WIDTH = 320;
/** Below this much room under the anchor, the popover opens upwards instead. */
const MIN_ROOM_BELOW = 380;

interface Row {
  label: ResourceLabel;
  /** As typed, so a half-entered "4" or "40." is not rewritten under the reader's cursor. */
  percent: string;
}

const round2 = (n: number) => Math.round(n * 100) / 100;
const fmt = (n: number) => String(round2(n));

/** Null when the text is a valid share, otherwise what is wrong with it. */
const problemWith = (text: string): string | null => {
  const t = text.trim();
  if (t === '') return 'Enter a share';
  const n = Number(t);
  if (!Number.isFinite(n)) return 'Not a number';
  if (n <= 0 || n > 100) return 'Between 0 and 100';
  if (Math.abs(n * 100 - Math.round(n * 100)) > 1e-6) return 'Two decimals at most';
  return null;
};

/** A stored share as the editor shows it. Null — the whole cost — reads as 100. */
const initialText = (p: number | null | undefined) => fmt(p ?? 100);

interface Props {
  /** The element the popover hangs from; usually the "+" button. */
  anchor: HTMLElement;
  /** "resource" or "service", for the copy. */
  kind: 'resource' | 'service';
  /** What is being labelled, shown in the header. */
  subject: string;
  /** Labels on it now, each with its share. */
  labels: ResourceLabel[];
  /** Every label the client has, for the add list. */
  allLabels: ResourceLabel[];
  /** Writes the full set. Throws to keep the popover open and show the message. */
  onSave: (shares: LabelShare[], next: ResourceLabel[]) => Promise<void>;
  onLabelCreated: (label: ResourceLabel) => void;
  onClose: () => void;
  /** Where this item's audit trail is read from. Omit to leave the History view out. */
  history?: { accountId: string; type: 'RESOURCE' | 'SERVICE'; id: string };
}

const LabelSharePopover: React.FC<Props> = ({
  anchor,
  kind,
  subject,
  labels,
  allLabels,
  onSave,
  onLabelCreated,
  onClose,
  history,
}) => {
  const ref = useRef<HTMLDivElement>(null);
  /** Editing the labels, or reading who changed them. Switching keeps unsaved edits. */
  const [view, setView] = useState<'edit' | 'history'>('edit');
  const [rows, setRows] = useState<Row[]>(() =>
    labels.map((label) => ({ label, percent: initialText(label.percent) })),
  );
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newColor, setNewColor] = useState(QUICK_COLORS[0]);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pos, setPos] = useState<{ top?: number; bottom?: number; left: number }>({ left: 0, top: 0 });

  // ── Position ──────────────────────────────────────────────────────────────────

  const place = useCallback(() => {
    const a = anchor.getBoundingClientRect();
    const left = Math.min(Math.max(8, a.right - WIDTH), window.innerWidth - WIDTH - 8);
    const below = window.innerHeight - a.bottom;
    if (below < MIN_ROOM_BELOW && a.top > below) {
      setPos({ bottom: window.innerHeight - a.top + 6, left });
    } else {
      setPos({ top: a.bottom + 6, left });
    }
  }, [anchor]);

  useLayoutEffect(() => {
    place();
  }, [place]);

  // Follows the anchor as either scroll container moves it, rather than closing on scroll — the
  // reader may scroll the panel to check a figure while the editor is open.
  useEffect(() => {
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [place]);

  // ── Dismissal ─────────────────────────────────────────────────────────────────

  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node;
      // The anchor toggles the popover itself; closing here as well would reopen it on the click.
      if (ref.current?.contains(t) || anchor.contains(t)) return;
      onClose();
    };
    // Capture, so Escape closes this and not the resource panel underneath, which listens on
    // window in the bubble phase.
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopImmediatePropagation();
      e.preventDefault();
      onClose();
    };
    document.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('keydown', onKey, true);
    };
  }, [anchor, onClose]);

  // ── Shares ────────────────────────────────────────────────────────────────────

  const problems = rows.map((r) => problemWith(r.percent));
  const allValid = problems.every((p) => p === null);
  const sum = round2(
    rows.reduce((acc, r, i) => (problems[i] === null ? acc + Number(r.percent) : acc), 0),
  );
  const over = sum > 100 + 1e-9;
  const unallocated = Math.max(0, round2(100 - sum));

  const changed = useMemo(() => {
    if (rows.length !== labels.length) return true;
    const before = new Map(labels.map((l) => [l.id, round2(l.percent ?? 100)]));
    return rows.some((r) => {
      const was = before.get(r.label.id);
      return was === undefined || problemWith(r.percent) !== null || round2(Number(r.percent)) !== was;
    });
  }, [rows, labels]);

  const addRow = (label: ResourceLabel) => {
    if (rows.some((r) => r.label.id === label.id)) return;
    // What is still unallocated, so the common case — split the rest to the new label — needs no
    // typing. Empty when nothing is left, which the row then flags until the reader decides.
    const rest = round2(100 - sum);
    setRows((prev) => [...prev, { label, percent: rest > 0 ? fmt(rest) : '' }]);
    setSearch('');
    setError(null);
  };

  const splitEvenly = () => {
    const n = rows.length;
    if (n === 0) return;
    const each = Math.floor((100 / n) * 100) / 100;
    setRows((prev) =>
      prev.map((r, i) => ({ ...r, percent: fmt(i === n - 1 ? 100 - each * (n - 1) : each) })),
    );
  };

  const available = allLabels.filter(
    (l) =>
      !rows.some((r) => r.label.id === l.id) &&
      l.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  const handleCreate = async () => {
    const name = newName.trim();
    if (!name) {
      setError('A label needs a name.');
      return;
    }
    setCreating(true);
    setError(null);
    try {
      const created = await createLabel({
        name,
        color: newColor,
        source: kind === 'service' ? 'COST_SERVICE_ROW' : 'COST_RESOURCE_PANEL',
      });
      onLabelCreated(created);
      addRow(created);
      setNewName('');
      setShowCreate(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the label.');
    } finally {
      setCreating(false);
    }
  };

  const handleSave = async () => {
    if (!allValid || over) return;
    setSaving(true);
    setError(null);
    const shares: LabelShare[] = rows.map((r) => ({
      labelId: r.label.id,
      percent: round2(Number(r.percent)),
    }));
    const next = rows.map((r) => ({ ...r.label, percent: round2(Number(r.percent)) }));
    try {
      await onSave(shares, next);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the labels.');
    } finally {
      setSaving(false);
    }
  };

  return createPortal(
    <div
      ref={ref}
      role="dialog"
      aria-label={`Labels on ${subject}`}
      style={{ position: 'fixed', top: pos.top, bottom: pos.bottom, left: pos.left, width: WIDTH }}
      className="z-[80] bg-white rounded-2xl border border-[rgba(0,0,0,0.12)] shadow-[0_12px_40px_rgba(0,0,0,0.18)] text-left"
      // The popover lives in the React tree of a clickable row — a service row opens the resource
      // panel, a resource row opens its detail — and React bubbles portal events to that tree.
      onClick={(e) => e.stopPropagation()}
    >
      <div className="flex items-start justify-between gap-2 px-4 pt-3 pb-2 border-b border-[rgba(0,0,0,0.07)]">
        <div className="min-w-0">
          <p className="text-[12px] font-semibold text-black">
            Labels on this {kind}
          </p>
          <p className="text-[11px] text-[rgba(0,0,0,0.45)] truncate" title={subject}>
            {subject}
          </p>
        </div>
        <div className="flex items-center gap-0.5 flex-shrink-0">
          {history && (
            <button
              type="button"
              onClick={() => setView((v) => (v === 'edit' ? 'history' : 'edit'))}
              aria-pressed={view === 'history'}
              title={view === 'history' ? 'Back to labels' : 'Who changed these labels, and when'}
              className={`h-6 px-1.5 inline-flex items-center gap-1 rounded-full text-[11px] font-medium ${
                view === 'history'
                  ? 'bg-[rgba(66,133,244,0.1)] text-[#4285F4]'
                  : 'text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.05)] hover:text-black'
              }`}
            >
              <Icon icon="mdi:history" width={14} />
              History
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="w-6 h-6 inline-flex items-center justify-center rounded-full text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.05)] hover:text-black"
          >
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
      </div>

      {view === 'edit' || !history ? (
        <>
          {/* Current labels and their shares */}
          <div className="px-4 py-3 space-y-1.5">
            {rows.length === 0 ? (
              <p className="text-[11.5px] text-[rgba(0,0,0,0.45)]">
                No labels yet. Add one below — give it the share of the cost it should take.
              </p>
            ) : (
              rows.map((r, i) => (
                <div key={r.label.id}>
                  <div className="flex items-center gap-2">
                    <span
                      className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                      style={{ backgroundColor: r.label.color }}
                    />
                    <span className="flex-1 min-w-0 truncate text-[12px] text-black">{r.label.name}</span>
                    <div className="relative">
                      <input
                        type="number"
                        inputMode="decimal"
                        min={0}
                        max={100}
                        step="0.01"
                        value={r.percent}
                        aria-label={`Share for ${r.label.name}`}
                        aria-invalid={problems[i] !== null}
                        onChange={(e) => {
                          const value = e.target.value;
                          setRows((prev) => prev.map((x, j) => (j === i ? { ...x, percent: value } : x)));
                          setError(null);
                        }}
                        className={`w-[76px] pl-2 pr-6 py-1 text-[12px] text-right tabular-nums border rounded-lg focus:outline-none focus:ring-2 focus:ring-[#4285F4]/30 ${
                          problems[i] ? 'border-[#F59E0B]' : 'border-[rgba(0,0,0,0.15)]'
                        }`}
                      />
                      <span className="absolute right-2 top-1/2 -translate-y-1/2 text-[11px] text-[rgba(0,0,0,0.4)]">
                        %
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setRows((prev) => prev.filter((_, j) => j !== i))}
                      aria-label={`Remove ${r.label.name}`}
                      title="Remove this label"
                      className="w-5 h-5 inline-flex items-center justify-center rounded text-[rgba(0,0,0,0.4)] hover:text-[#B91C1C] hover:bg-[rgba(185,28,28,0.06)]"
                    >
                      <Icon icon="mdi:close" width={13} />
                    </button>
                  </div>
                  {problems[i] && (
                    <p className="text-[10.5px] text-[#B45309] text-right pr-7 mt-0.5">{problems[i]}</p>
                  )}
                </div>
              ))
            )}

            {rows.length > 0 && (
              <div className="flex items-center justify-between gap-2 pt-1.5">
                <span
                  className={`text-[11.5px] tabular-nums ${
                    over ? 'text-[#B91C1C] font-medium' : 'text-[rgba(0,0,0,0.55)]'
                  }`}
                >
                  {over
                    ? `${fmt(sum)}% given out — lower a share to 100% or less`
                    : `Allocated ${fmt(sum)}% · ${fmt(unallocated)}% unallocated`}
                </span>
                {rows.length > 1 && (
                  <button
                    type="button"
                    onClick={splitEvenly}
                    className="text-[11px] font-medium text-[#4285F4] hover:underline"
                  >
                    Split evenly
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Add a label */}
          <div className="px-4 pb-3 border-t border-[rgba(0,0,0,0.07)] pt-3">
            <div className="relative mb-1.5">
              <Icon
                icon="mdi:magnify"
                width={13}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
              />
              <input
                autoFocus
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Add a label…"
                className="w-full pl-7 pr-2.5 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-[rgba(0,0,0,0.02)] focus:outline-none focus:ring-2 focus:ring-[#4285F4]/30"
              />
            </div>
            <div className="max-h-32 overflow-y-auto">
              {available.length === 0 ? (
                <p className="text-[11px] text-[rgba(0,0,0,0.4)] text-center py-2">
                  {search ? 'No matching labels' : 'Every label is already on it'}
                </p>
              ) : (
                available.map((l) => (
                  <button
                    key={l.id}
                    type="button"
                    onClick={() => addRow(l)}
                    className="w-full flex items-center gap-2 px-2 py-1.5 rounded-lg text-left text-[12px] hover:bg-[rgba(0,0,0,0.03)]"
                  >
                    <span className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: l.color }} />
                    <span className="flex-1 truncate text-black">{l.name}</span>
                    <Icon icon="mdi:plus" width={13} className="text-[rgba(0,0,0,0.35)]" />
                  </button>
                ))
              )}
            </div>

            {!showCreate ? (
              <button
                type="button"
                onClick={() => setShowCreate(true)}
                className="mt-1 w-full flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-[11.5px] font-medium text-[#4285F4] hover:bg-[rgba(66,133,244,0.06)]"
              >
                <Icon icon="mdi:plus-circle-outline" width={14} />
                Create new label
              </button>
            ) : (
              <div className="mt-2 rounded-xl bg-[rgba(0,0,0,0.025)] p-2.5 space-y-2">
                <input
                  autoFocus
                  type="text"
                  value={newName}
                  maxLength={50}
                  onChange={(e) => setNewName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void handleCreate();
                  }}
                  placeholder="Label name"
                  className="w-full px-2.5 py-1.5 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-[#4285F4]/30"
                />
                <div className="flex gap-1.5 flex-wrap">
                  {QUICK_COLORS.map((c) => (
                    <button
                      key={c}
                      type="button"
                      onClick={() => setNewColor(c)}
                      aria-label={`Colour ${c}`}
                      className={`w-[18px] h-[18px] rounded-full ${
                        newColor === c ? 'ring-2 ring-offset-1 ring-[rgba(0,0,0,0.5)]' : ''
                      }`}
                      style={{ backgroundColor: c }}
                    />
                  ))}
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => void handleCreate()}
                    disabled={creating || !newName.trim()}
                    className="flex-1 py-1.5 text-[11.5px] font-semibold rounded-lg bg-[#4285F4] text-white hover:bg-[#3367D6] disabled:opacity-40"
                  >
                    {creating ? 'Creating…' : 'Create and add'}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setShowCreate(false);
                      setNewName('');
                    }}
                    className="px-3 py-1.5 text-[11.5px] text-[rgba(0,0,0,0.55)] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white hover:text-black"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="px-4 py-2.5 border-t border-[rgba(0,0,0,0.07)] bg-[rgba(0,0,0,0.015)] rounded-b-2xl">
            {error && <p className="text-[11.5px] text-[#B91C1C] mb-2">{error}</p>}
            <div className="flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={onClose}
                className="px-3 py-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.55)] hover:text-black border border-[rgba(0,0,0,0.12)] rounded-lg bg-white"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleSave()}
                disabled={!changed || !allValid || over || saving}
                className="inline-flex items-center gap-1.5 px-4 py-1.5 text-[12px] font-semibold rounded-lg bg-[#4285F4] text-white hover:bg-[#3367D6] disabled:opacity-40 disabled:cursor-not-allowed"
              >
                {saving && <Icon icon="mdi:loading" width={13} className="animate-spin" />}
                {saving ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        </>
      ) : (
        history && (
          <>
            {/* Who changed this item's labels, and when. Edits in progress above are kept. */}
            <div className="px-4 py-1 max-h-80 overflow-y-auto">
              <LabelHistoryList
                accountId={history.accountId}
                target={{ type: history.type, id: history.id }}
                allLabels={allLabels}
                compact
              />
            </div>
            <div className="px-4 py-2.5 border-t border-[rgba(0,0,0,0.07)] bg-[rgba(0,0,0,0.015)] rounded-b-2xl flex justify-end">
              <button
                type="button"
                onClick={() => setView('edit')}
                className="inline-flex items-center gap-1 px-3 py-1.5 text-[12px] font-medium text-[rgba(0,0,0,0.6)] hover:text-black border border-[rgba(0,0,0,0.12)] rounded-lg bg-white"
              >
                <Icon icon="mdi:arrow-left" width={13} />
                Back to labels
              </button>
            </div>
          </>
        )
      )}
    </div>,
    document.body,
  );
};

export default LabelSharePopover;
