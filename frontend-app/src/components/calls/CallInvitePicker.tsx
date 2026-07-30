/**
 * "Add participant" picker for a live call.
 *
 * The eligible list is decided server-side by role — a customer only sees their
 * own client's users, an admin sees the ticket client's users plus the BillOps
 * admin pool — so this component just renders whatever it's given and posts the
 * selected ids back. Invited users ring (if online) and get a join email.
 */

import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import { fetchInvitableUsers, inviteToCall, readableCallError } from '../../utils/callsApi';
import type { InvitableUser } from '../../types/calls';

interface CallInvitePickerProps {
  callId: number;
  onClose: () => void;
}

const CallInvitePicker: React.FC<CallInvitePickerProps> = ({ callId, onClose }) => {
  const [loading, setLoading] = useState(true);
  const [users, setUsers] = useState<InvitableUser[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [inviting, setInviting] = useState(false);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await fetchInvitableUsers(callId);
        if (!cancelled) setUsers(list);
      } catch (err) {
        if (!cancelled) {
          gooeyToast.error('Could not load people to add', {
            description: readableCallError(err, 'Please try again'),
          });
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [callId]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return users;
    return users.filter(
      u => u.name.toLowerCase().includes(q) || (u.email ?? '').toLowerCase().includes(q)
    );
  }, [users, query]);

  const toggle = (id: number) =>
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const invite = async () => {
    if (selected.size === 0) return;
    setInviting(true);
    try {
      await inviteToCall(callId, Array.from(selected));
      gooeyToast.success(
        selected.size === 1 ? 'Invited 1 person' : `Invited ${selected.size} people`,
        { description: 'They will ring and get an email to join.' }
      );
      onClose();
    } catch (err) {
      gooeyToast.error('Could not add participants', {
        description: readableCallError(err, 'Please try again'),
      });
      setInviting(false);
    }
  };

  return (
    <div className="absolute inset-0 z-[73] flex items-center justify-center bg-black/50 p-3">
      <div className="w-full max-w-sm rounded-xl bg-slate-800 border border-slate-600 shadow-2xl flex flex-col max-h-[80%]">
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-700">
          <span className="text-sm font-semibold text-white">Add to call</span>
          <button onClick={onClose} className="p-1 rounded hover:bg-white/10" aria-label="Close">
            <Icon icon="mdi:close" width={16} className="text-slate-300" />
          </button>
        </div>

        <div className="p-3 border-b border-slate-700">
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search people…"
            className="w-full text-[13px] rounded-md bg-slate-900 border border-slate-600 text-white px-3 py-2 focus:outline-none focus:ring-1 focus:ring-indigo-400"
          />
        </div>

        <div className="flex-1 overflow-y-auto min-h-[120px]">
          {loading ? (
            <div className="flex items-center justify-center py-8 text-slate-400 text-[13px] gap-2">
              <Icon icon="mdi:loading" width={16} className="animate-spin" />
              Loading…
            </div>
          ) : filtered.length === 0 ? (
            <div className="px-4 py-8 text-center text-slate-400 text-[13px]">
              {users.length === 0 ? 'Nobody available to add.' : 'No matches.'}
            </div>
          ) : (
            filtered.map(u => {
              const on = selected.has(u.userId);
              return (
                <button
                  key={u.userId}
                  onClick={() => toggle(u.userId)}
                  className="w-full flex items-center gap-3 px-4 py-2.5 hover:bg-slate-700/60 transition-colors text-left"
                >
                  <div className="w-8 h-8 rounded-full bg-slate-600 flex items-center justify-center text-white text-xs font-semibold flex-shrink-0">
                    {(u.name || '?').charAt(0).toUpperCase()}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="text-[13px] text-white truncate">{u.name}</div>
                    {u.email && (
                      <div className="text-[11px] text-slate-400 truncate">{u.email}</div>
                    )}
                  </div>
                  {u.kind === 'ADMIN' && (
                    <span className="text-[10px] font-medium text-indigo-300 bg-indigo-500/20 rounded px-1.5 py-0.5 flex-shrink-0">
                      Admin
                    </span>
                  )}
                  <span
                    className={`w-5 h-5 rounded-md border flex items-center justify-center flex-shrink-0 ${
                      on ? 'bg-emerald-600 border-emerald-600' : 'border-slate-500'
                    }`}
                  >
                    {on && <Icon icon="mdi:check" width={13} className="text-white" />}
                  </span>
                </button>
              );
            })
          )}
        </div>

        <div className="flex items-center justify-between gap-2 px-4 py-3 border-t border-slate-700">
          <span className="text-[12px] text-slate-400">
            {selected.size > 0 ? `${selected.size} selected` : 'Select people to add'}
          </span>
          <button
            onClick={invite}
            disabled={selected.size === 0 || inviting}
            className="inline-flex items-center gap-1.5 rounded-md bg-emerald-600 hover:bg-emerald-700 text-white text-[13px] font-medium px-3 py-1.5 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <Icon icon={inviting ? 'mdi:loading' : 'mdi:phone-plus'} width={15} className={inviting ? 'animate-spin' : ''} />
            {inviting ? 'Adding…' : 'Add to call'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default CallInvitePicker;
