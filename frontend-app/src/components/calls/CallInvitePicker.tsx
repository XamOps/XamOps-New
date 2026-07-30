/**
 * "Add participant" picker for a live call.
 *
 * The eligible list is decided server-side by role — a customer only sees their
 * own client's users, an admin sees the ticket client's users plus the BillOps
 * admin pool — so this component just renders whatever it's given and posts the
 * selected ids back. Invited users ring (if online) and get a join email.
 *
 * Styled to match the premium glass/gradient language of the Add Account page:
 * frosted panel, indigo→purple accents, emerald success action, motion entrance.
 */

import React, { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import { fetchInvitableUsers, inviteToCall, readableCallError } from '../../utils/callsApi';
import type { InvitableUser } from '../../types/calls';

interface CallInvitePickerProps {
  callId: number;
  onClose: () => void;
}

const AVATAR_TINTS = [
  'from-indigo-500 to-violet-500',
  'from-sky-500 to-indigo-500',
  'from-emerald-500 to-teal-500',
  'from-fuchsia-500 to-pink-500',
  'from-amber-500 to-orange-500',
];

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
    <motion.div
      className="absolute inset-0 z-[73] flex items-center justify-center p-3 bg-slate-950/60 backdrop-blur-sm"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.15 }}
      onClick={onClose}
    >
      <motion.div
        onClick={e => e.stopPropagation()}
        initial={{ opacity: 0, y: 14, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        exit={{ opacity: 0, y: 8, scale: 0.98 }}
        transition={{ duration: 0.2, ease: 'easeOut' }}
        className="w-full max-w-sm rounded-2xl bg-slate-900/90 backdrop-blur-xl border border-indigo-400/20 shadow-[0_24px_48px_-8px_rgba(99,102,241,0.35)] flex flex-col max-h-[82%] overflow-hidden"
      >
        {/* Header with gradient accent */}
        <div className="relative px-4 py-3.5 border-b border-white/10">
          <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-indigo-400/60 to-transparent" />
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <span className="w-8 h-8 rounded-lg bg-gradient-to-br from-indigo-500 to-violet-500 flex items-center justify-center shadow-lg shadow-indigo-500/30">
                <Icon icon="mdi:account-multiple-plus" width={17} className="text-white" />
              </span>
              <div>
                <div className="text-sm font-semibold text-white leading-tight">Add to call</div>
                <div className="text-[11px] text-slate-400 leading-tight">Ring people into this room</div>
              </div>
            </div>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg hover:bg-white/10 transition-colors"
              aria-label="Close"
            >
              <Icon icon="mdi:close" width={16} className="text-slate-400" />
            </button>
          </div>
        </div>

        {/* Search */}
        <div className="px-4 pt-3.5 pb-2">
          <div className="relative">
            <Icon
              icon="mdi:magnify"
              width={16}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500"
            />
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Search people…"
              className="w-full text-[13px] rounded-xl bg-slate-950/60 border border-white/10 text-white pl-9 pr-3 py-2.5 placeholder:text-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-400/50 focus:border-indigo-400/40 transition-shadow"
            />
          </div>
        </div>

        {/* List */}
        <div className="flex-1 overflow-y-auto min-h-[140px] px-2 pb-2">
          {loading ? (
            <div className="flex items-center justify-center py-10 text-slate-400 text-[13px] gap-2">
              <Icon icon="mdi:loading" width={16} className="animate-spin" />
              Loading…
            </div>
          ) : filtered.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-10 text-center gap-2">
              <span className="w-11 h-11 rounded-full bg-white/5 flex items-center justify-center">
                <Icon icon="mdi:account-off-outline" width={20} className="text-slate-500" />
              </span>
              <span className="text-[13px] text-slate-400">
                {users.length === 0 ? 'Nobody available to add.' : 'No matches.'}
              </span>
            </div>
          ) : (
            <AnimatePresence initial={false}>
              {filtered.map(u => {
                const on = selected.has(u.userId);
                const tint = AVATAR_TINTS[u.userId % AVATAR_TINTS.length];
                return (
                  <motion.button
                    key={u.userId}
                    layout
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    onClick={() => toggle(u.userId)}
                    className={`w-full flex items-center gap-3 px-2.5 py-2.5 rounded-xl text-left transition-colors ${
                      on ? 'bg-indigo-500/15 ring-1 ring-inset ring-indigo-400/40' : 'hover:bg-white/5'
                    }`}
                  >
                    <div
                      className={`w-9 h-9 rounded-full bg-gradient-to-br ${tint} flex items-center justify-center text-white text-xs font-semibold flex-shrink-0 shadow-md`}
                    >
                      {(u.name || '?').charAt(0).toUpperCase()}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="text-[13px] text-white truncate font-medium">{u.name}</div>
                      {u.email && (
                        <div className="text-[11px] text-slate-400 truncate">{u.email}</div>
                      )}
                    </div>
                    {u.kind === 'ADMIN' && (
                      <span className="text-[10px] font-semibold text-indigo-200 bg-indigo-500/20 border border-indigo-400/20 rounded-full px-2 py-0.5 flex-shrink-0">
                        Admin
                      </span>
                    )}
                    <span
                      className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 transition-all ${
                        on
                          ? 'bg-gradient-to-br from-emerald-500 to-teal-500 shadow-md shadow-emerald-500/30'
                          : 'border-2 border-slate-600'
                      }`}
                    >
                      {on && <Icon icon="mdi:check" width={13} className="text-white" />}
                    </span>
                  </motion.button>
                );
              })}
            </AnimatePresence>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between gap-2 px-4 py-3 border-t border-white/10 bg-white/[0.02]">
          <span className="text-[12px] text-slate-400">
            {selected.size > 0 ? (
              <span className="text-indigo-300 font-medium">{selected.size} selected</span>
            ) : (
              'Select people to add'
            )}
          </span>
          <button
            onClick={invite}
            disabled={selected.size === 0 || inviting}
            className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-500 text-white text-[13px] font-semibold px-4 py-2 shadow-lg shadow-emerald-500/25 hover:shadow-emerald-500/40 hover:brightness-110 disabled:opacity-40 disabled:shadow-none disabled:cursor-not-allowed transition-all"
          >
            <Icon
              icon={inviting ? 'mdi:loading' : 'mdi:phone-plus'}
              width={15}
              className={inviting ? 'animate-spin' : ''}
            />
            {inviting ? 'Adding…' : 'Add to call'}
          </button>
        </div>
      </motion.div>
    </motion.div>
  );
};

export default CallInvitePicker;
