/**
 * Ticket Detail Page
 *
 * Detailed view of a support ticket with chat-like interface
 *
 * Features:
 * - IST timezone on all timestamps
 * - SLA status card (admin only)
 * - Ticket assignment to admins
 * - Internal note support (admin only)
 * - Rich text editor for replies (ReactQuill)
 * - File attachment support
 * - Status management (Open, In Progress, Closed)
 * - Admin vs User differentiation
 * - Auto-scroll to latest messages
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import { formatIST, getSlaStatus, getResponseSlaLabel } from '../utils/timeUtils';
import ReactQuill from 'react-quill-new';
import 'react-quill-new/dist/quill.snow.css';

interface AdminUser {
  id: number;
  username: string;
}

interface AttachmentInfo {
  url: string;
  name: string;
  type: string;
}

interface TicketReply {
  id: number;
  message: string;
  authorId: number;
  authorUsername: string;
  admin: boolean;
  isInternal?: boolean;
  createdAt: string;
  attachments?: AttachmentInfo[];
  // legacy single-file fields
  attachmentUrl?: string;
  fileName?: string;
  edited?: boolean;
  editedAt?: string;
  deleted?: boolean;
}

interface Ticket {
  id: number;
  subject: string;
  description: string;
  category: string;
  service: string;
  severity: string;
  status: string;
  accountId?: string;
  region?: string;
  createdAt: string;
  closedAt?: string;
  chatStartedAt?: string;
  assignedToId?: number;
  assignedToUsername?: string;
  creatorUsername?: string;
  replies: TicketReply[];
}

const TicketDetailPage: React.FC = () => {
  const { userProfile, hasAnyRole } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const ticketId = searchParams.get('id');
  const { setActions, clearActions } = useTopbarActions();

  const isAdmin = hasAnyRole('ROLE_BILLOPS_ADMIN');

  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [loading, setLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [newReply, setNewReply] = useState('');
  const [attachments, setAttachments] = useState<File[]>([]);

  // Internal note toggle (admin only)
  const [isInternalNote, setIsInternalNote] = useState(false);

  // Edit / Delete state
  const [editingReplyId, setEditingReplyId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');

  // Assignment state
  const [adminUsers, setAdminUsers] = useState<AdminUser[]>([]);
  const [showAssignDropdown, setShowAssignDropdown] = useState(false);
  const [isAssigning, setIsAssigning] = useState(false);

  // Email thread state
  const [showEmailModal, setShowEmailModal] = useState(false);
  const [emailRecipient, setEmailRecipient] = useState('');
  const [isSendingEmail, setIsSendingEmail] = useState(false);

  const chatContainerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /**
   * Fetch ticket data from API
   */
  const fetchTicket = useCallback(async () => {
    if (!ticketId) {
      navigate('/tickets');
      return;
    }

    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}`, {
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error('Failed to load ticket');
      }

      const data = await response.json();
      setTicket(data);

      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    } catch (err) {
      console.error('Error fetching ticket:', err);
      navigate('/tickets');
    } finally {
      setLoading(false);
    }
  }, [ticketId, navigate]);

  /**
   * Fetch admin users for assignment dropdown
   */
  const fetchAdminUsers = useCallback(async () => {
    try {
      const res = await fetch('/api/billops/admins', { credentials: 'include' });
      if (res.ok) {
        const data: AdminUser[] = await res.json();
        setAdminUsers(data);
      }
    } catch (err) {
      console.error('Error fetching admin users:', err);
    }
  }, []);

  /**
   * Assign ticket to an admin (or unassign with id=0)
   */
  const handleAssign = useCallback(async (adminId: number, adminUsername: string) => {
    if (!ticketId) return;
    setIsAssigning(true);
    setShowAssignDropdown(false);
    try {
      const res = await fetch(`/api/billops/tickets/${ticketId}/assign`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ assignedToId: adminId, assignedToUsername: adminUsername })
      });
      if (res.ok) {
        setTicket(prev => prev ? {
          ...prev,
          assignedToId: adminId || undefined,
          assignedToUsername: adminUsername || undefined
        } : prev);
        window.dispatchEvent(new CustomEvent('ticket:updated'));
      }
    } catch (err) {
      console.error('Error assigning ticket:', err);
    } finally {
      setIsAssigning(false);
    }
  }, [ticketId]);

  /**
   * Action Handlers
   */
  const goBackToTickets = useCallback(() => {
    if (isAdmin) {
      navigate('/billops-admin-tickets');
    } else {
      navigate('/tickets');
    }
  }, [navigate, isAdmin]);

  const closeTicket = useCallback(async () => {
    if (!ticketId) return;
    if (!globalThis.confirm('Are you sure you want to close this ticket?')) return;
    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}/close`, {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) throw new Error('Failed to close ticket');
      const updatedTicket = await response.json();
      setTicket(updatedTicket);
      window.dispatchEvent(new CustomEvent('ticket:updated'));
    } catch (err) {
      console.error('Error closing ticket:', err);
      globalThis.alert('Error closing ticket.');
    }
  }, [ticketId]);

  const handleSendEmail = async () => {
    if (!ticketId || !emailRecipient.trim()) return;
    setIsSendingEmail(true);
    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}/email`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ recipientEmail: emailRecipient.trim() })
      });
      if (!response.ok) throw new Error('Failed to send email');
      setShowEmailModal(false);
      setEmailRecipient('');
      globalThis.alert('Email thread sent successfully.');
    } catch (err) {
      console.error('Error sending email thread:', err);
      globalThis.alert('Failed to send email thread.');
    } finally {
      setIsSendingEmail(false);
    }
  };

  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault();
    const textContent = newReply.replace(/<[^>]*>/g, '').trim();

    if (!textContent && attachments.length === 0) {
      globalThis.alert('Reply cannot be empty.');
      return;
    }

    setIsSubmitting(true);
    const formData = new FormData();
    formData.append('message', newReply);
    formData.append('ticketId', ticketId!);
    formData.append('authorId', String(userProfile?.id || ''));
    attachments.forEach(f => formData.append('files', f));
    if (isAdmin) formData.append('isInternal', String(isInternalNote));

    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}/replies`, {
        credentials: 'include',
        method: 'POST',
        body: formData
      });
      if (!response.ok) throw new Error('Failed to send reply');
      const updatedTicket = await response.json();
      setTicket(updatedTicket);
      setNewReply('');
      setAttachments([]);
      setIsInternalNote(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
      window.dispatchEvent(new CustomEvent('ticket:updated'));

      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    } catch (err) {
      console.error('Error submitting reply:', err);
      globalThis.alert('Error sending reply.');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * Edit a reply (within 15 min)
   */
  const editReply = useCallback(async (replyId: number, message: string) => {
    try {
      const res = await fetch(`/api/xamops/tickets/replies/${replyId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ message })
      });
      if (!res.ok) throw new Error('Edit failed');
      await fetchTicket();
    } catch (err) {
      console.error('Error editing reply:', err);
      throw err;
    }
  }, [fetchTicket]);

  /**
   * Delete a reply (soft-delete)
   */
  const deleteReply = useCallback(async (replyId: number) => {
    try {
      const res = await fetch(`/api/xamops/tickets/replies/${replyId}`, {
        method: 'DELETE',
        credentials: 'include'
      });
      if (!res.ok) throw new Error('Delete failed');
      // Optimistic update
      setTicket(prev => prev ? {
        ...prev,
        replies: prev.replies.map(r => r.id === replyId ? { ...r, deleted: true } : r)
      } : prev);
    } catch (err) {
      console.error('Error deleting reply:', err);
    }
  }, []);

  /**
   * Effects
   */
  useEffect(() => {
    fetchTicket();
  }, [fetchTicket]);

  useEffect(() => {
    if (isAdmin) fetchAdminUsers();
  }, [isAdmin, fetchAdminUsers]);

  // Close assign dropdown when clicking outside
  useEffect(() => {
    if (!showAssignDropdown) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as HTMLElement;
      if (!target.closest('[data-assign-dropdown]')) setShowAssignDropdown(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showAssignDropdown]);

  useEffect(() => {
    if (!ticket) return;

    setActions(
      <div className="flex items-center gap-6">
        {/* Title */}
        <div className="flex items-center gap-4 pr-6 border-r border-slate-200/50">
          <button
            onClick={goBackToTickets}
            className="w-9 h-9 flex items-center justify-center rounded-xl bg-slate-100 text-slate-500 hover:text-indigo-600 hover:bg-slate-200 transition-all font-bold"
            title="Back to List"
          >
            <i className="fas fa-arrow-left"></i>
          </button>
          <div>
            <h1 className="text-sm font-bold text-slate-900 leading-none truncate max-w-[200px] lg:max-w-[400px]">
              {ticket.subject}
            </h1>
            <p className="text-[10px] text-slate-500 font-medium mt-1">
              Ticket #{ticket.id} • {ticket.category}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          {/* Status badge */}
          <span className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm transition-all ${
            ticket.status === 'OPEN'
              ? 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10'
              : ticket.status === 'IN_PROGRESS'
                ? 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10'
                : 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10'
          }`}>
            <span className={`w-1.5 h-1.5 rounded-full bg-white shadow-[0_0_8px_white] ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}></span>
            {ticket.status}
          </span>

          {/* Assign dropdown (admin only) */}
          {isAdmin && (
            <div className="relative" data-assign-dropdown>
              <button
                onClick={() => setShowAssignDropdown(v => !v)}
                disabled={isAssigning || ticket.status === 'CLOSED'}
                className={`flex items-center gap-2 px-3 py-2 rounded-xl text-[10px] font-black uppercase tracking-widest border transition-all ${
                  ticket.assignedToUsername
                    ? 'bg-indigo-50 text-indigo-700 border-indigo-200 hover:bg-indigo-100'
                    : 'bg-slate-50 text-slate-500 border-slate-200 hover:border-indigo-300 hover:text-indigo-600'
                } disabled:opacity-50 disabled:cursor-not-allowed`}
              >
                {ticket.assignedToUsername ? (
                  <>
                    <div className="w-4 h-4 rounded-full bg-indigo-600 flex items-center justify-center text-white text-[7px] font-black">
                      {ticket.assignedToUsername.charAt(0).toUpperCase()}
                    </div>
                    {ticket.assignedToUsername}
                  </>
                ) : (
                  <>
                    <i className="fas fa-user-plus text-[10px]"></i>
                    Assign
                  </>
                )}
                {!isAssigning && ticket.status !== 'CLOSED' && (
                  <i className="fas fa-chevron-down text-[8px] opacity-50"></i>
                )}
                {isAssigning && <i className="fas fa-circle-notch fa-spin text-[8px]"></i>}
              </button>

              {showAssignDropdown && adminUsers.length > 0 && (
                <div className="absolute top-full right-0 mt-1 w-48 bg-white rounded-xl shadow-xl border border-slate-100 py-1 z-50 overflow-hidden">
                  <div className="px-3 py-1.5 text-[9px] font-black text-slate-400 uppercase tracking-widest border-b border-slate-50">
                    Assign to
                  </div>
                  {adminUsers.map(admin => (
                    <button
                      key={admin.id}
                      onClick={() => handleAssign(admin.id, admin.username)}
                      className={`w-full text-left flex items-center gap-2 px-3 py-2 text-[11px] font-bold hover:bg-indigo-50 hover:text-indigo-700 transition-colors ${
                        ticket.assignedToId === admin.id ? 'bg-indigo-50 text-indigo-700' : 'text-slate-700'
                      }`}
                    >
                      <div className="w-5 h-5 rounded-full bg-indigo-600 flex items-center justify-center text-white text-[8px] font-black flex-shrink-0">
                        {admin.username.charAt(0).toUpperCase()}
                      </div>
                      {admin.username}
                      {ticket.assignedToId === admin.id && (
                        <i className="fas fa-check text-indigo-500 ml-auto text-[9px]"></i>
                      )}
                    </button>
                  ))}
                  {ticket.assignedToUsername && (
                    <button
                      onClick={() => handleAssign(0, '')}
                      className="w-full text-left flex items-center gap-2 px-3 py-2 text-[11px] font-bold text-rose-500 hover:bg-rose-50 transition-colors border-t border-slate-50"
                    >
                      <i className="fas fa-user-times text-[10px]"></i>
                      Unassign
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Email Thread (admin only, ticket has replies) */}
          {isAdmin && (ticket.replies?.length ?? 0) > 0 && (
            <button
              onClick={() => setShowEmailModal(true)}
              className="flex items-center gap-2 px-4 py-2 bg-sky-50 text-sky-600 rounded-xl text-xs font-bold border border-sky-100 hover:bg-sky-100 transition-all active:scale-95"
            >
              <i className="fas fa-envelope text-xs"></i>
              <span>Email Thread</span>
            </button>
          )}

          {/* Close ticket */}
          {ticket.status !== 'CLOSED' && (
            <button
              onClick={closeTicket}
              className="flex items-center gap-2 px-4 py-2 bg-rose-50 text-rose-600 rounded-xl text-xs font-bold border border-rose-100 hover:bg-rose-100 transition-all active:scale-95"
            >
              <i className="far fa-times-circle"></i>
              <span>Close Ticket</span>
            </button>
          )}
        </div>
      </div>
    );

    return () => clearActions();
  }, [setActions, clearActions, ticket, closeTicket, goBackToTickets, isAdmin, adminUsers,
      showAssignDropdown, isAssigning, handleAssign, showEmailModal]);

  /**
   * Helpers
   */
  // Position logic (mirrors support dashboard):
  // - Admin viewer: admin replies → RIGHT (with real username), customer replies → LEFT
  // - Customer viewer: own replies → RIGHT ("You"), admin replies → LEFT ("Support Team")
  const isMe = (reply: TicketReply) => {
    if (isAdmin) return reply.admin === true;
    return !reply.admin && String(reply.authorId) === String(userProfile?.id);
  };
  const getInitials = (name: string) => name ? name.charAt(0).toUpperCase() : 'U';
  const getSeverityClass = (sev: string) => {
    if (['High', 'Critical', 'Urgent'].includes(sev)) return 'text-rose-600';
    if (['Normal', 'Medium'].includes(sev)) return 'text-amber-500';
    return 'text-indigo-500';
  };

  if (loading) return (
    <div className="flex-1 flex items-center justify-center">
      <i className="fas fa-spinner fa-spin text-5xl text-indigo-600"></i>
    </div>
  );
  if (!ticket) return null;

  const isClosed = ticket.status === 'CLOSED';
  const slaStatus = getSlaStatus(ticket.createdAt, ticket.severity, isClosed, ticket.closedAt);
  const slaColorMap = { ok: 'bg-emerald-100 text-emerald-700 border-emerald-200', warning: 'bg-amber-100 text-amber-700 border-amber-200', breached: 'bg-red-100 text-red-700 border-red-200' };
  const slaDotMap = { ok: 'bg-emerald-500', warning: 'bg-amber-500 animate-pulse', breached: 'bg-red-500 animate-pulse' };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <div ref={chatContainerRef} className="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50">
        <div className="max-w-4xl mx-auto p-6 lg:p-10 space-y-8">

          {/* Header Card */}
          <div className="relative group bg-white/70 backdrop-blur-xl border border-white p-8 rounded-[2.5rem] shadow-[0_8px_40px_rgba(0,0,0,0.03)] hover:shadow-[0_24px_48px_rgba(0,0,0,0.06)] transition-all">
            <div className="relative flex gap-6">
              <div className="flex-shrink-0">
                <div className="w-12 h-12 rounded-2xl bg-indigo-600 flex items-center justify-center text-white shadow-lg shadow-indigo-200">
                  <i className="fas fa-user text-lg"></i>
                </div>
              </div>
              <div className="flex-1 space-y-4">
                <div className="flex justify-between items-start gap-4">
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest leading-none">Original Request</span>
                  <span className="text-[10px] font-bold text-slate-400 uppercase tracking-tighter whitespace-nowrap">
                    {formatIST(ticket.createdAt)}
                  </span>
                </div>
                <div className="text-base text-slate-700 font-medium whitespace-pre-wrap leading-relaxed">{ticket.description}</div>
                <div className="flex flex-wrap gap-4 pt-4 border-t border-slate-100 text-[10px] font-bold uppercase tracking-widest">
                  <div>Region: <span className="text-slate-600 font-black">{ticket.region || 'Global'}</span></div>
                  <div>Severity: <span className={`font-black ${getSeverityClass(ticket.severity)}`}>{ticket.severity}</span></div>
                  {ticket.accountId && (
                    <div>Account / Project: <span className="text-indigo-600 font-black font-mono">{ticket.accountId}</span></div>
                  )}
                  {ticket.creatorUsername && (
                    <div>Raised By: <span className="text-slate-600 font-black">{ticket.creatorUsername}</span></div>
                  )}
                  {isAdmin && ticket.assignedToUsername && (
                    <div>Assigned: <span className="text-indigo-600 font-black">{ticket.assignedToUsername}</span></div>
                  )}
                  {ticket.chatStartedAt && (
                    <div>Chat Started: <span className="text-indigo-600 font-black">{formatIST(ticket.chatStartedAt)}</span></div>
                  )}
                  {ticket.closedAt && (
                    <div>Closed: <span className="text-slate-600 font-black">{formatIST(ticket.closedAt)}</span></div>
                  )}
                </div>

                {/* SLA card (admin only) */}
                {isAdmin && (
                  <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl border ${slaColorMap[slaStatus.status]}`}>
                    <span className={`w-2 h-2 rounded-full flex-shrink-0 ${slaDotMap[slaStatus.status]}`}></span>
                    <div className="flex-1 min-w-0">
                      <div className="text-[10px] font-black uppercase tracking-widest leading-none mb-1">
                        Resolution SLA — {slaStatus.status === 'breached' ? 'Breached' : slaStatus.status === 'warning' ? 'At Risk' : 'On Track'}
                      </div>
                      <div className="text-[11px] font-bold">{slaStatus.label}</div>
                    </div>
                    <div className="text-[10px] font-black opacity-70 whitespace-nowrap">
                      {getResponseSlaLabel(ticket.createdAt, ticket.severity, ticket.chatStartedAt)}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Correspondence divider */}
          <div className="flex items-center gap-4 text-slate-400 pt-4">
            <span className="text-[10px] font-black uppercase tracking-[0.2em] whitespace-nowrap">Correspondence</span>
            <div className="h-px bg-slate-200 flex-1"></div>
          </div>

          {/* Messages */}
          <div className="space-y-6">
            {ticket.replies.map((reply) => {
              const isInternalReply = reply.isInternal === true;

              if (isInternalReply) {
                // Internal note bubble (admin only, amber)
                return (
                  <div key={reply.id} className="flex w-full justify-end">
                    <div className="flex max-w-[85%] md:max-w-[70%] gap-4 flex-row-reverse">
                      <div className="flex-shrink-0 mt-auto">
                        <div className="w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm bg-amber-500 text-white">
                          <i className="fas fa-lock text-xs"></i>
                        </div>
                      </div>
                      <div className="space-y-2 text-right">
                        <div className="flex items-center justify-end gap-2 px-1">
                          <span className="px-2 py-0.5 text-[8px] font-black bg-amber-100 text-amber-600 border border-amber-200 rounded-full uppercase tracking-widest">Internal Note</span>
                          <span className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">{formatIST(reply.createdAt)}</span>
                        </div>
                        <div className="p-4 rounded-[1.5rem] rounded-tr-none shadow-sm text-sm font-medium leading-relaxed bg-amber-50 text-amber-900 border border-amber-200">
                          <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat" />
                        </div>
                      </div>
                    </div>
                  </div>
                );
              }

              return (
                <div key={reply.id} className={`flex w-full ${isMe(reply) ? 'justify-end' : 'justify-start'}`}>
                  <div className={`flex max-w-[85%] md:max-w-[70%] gap-4 ${isMe(reply) ? 'flex-row-reverse' : ''}`}>
                    <div className="flex-shrink-0 mt-auto">
                      <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm ${reply.admin ? 'bg-indigo-600 text-white' : 'bg-slate-200 text-slate-600'}`}>
                        {reply.admin ? <i className="fas fa-shield-alt"></i> : <span>{getInitials(reply.authorUsername)}</span>}
                      </div>
                    </div>
                    <div className={`space-y-2 ${isMe(reply) ? 'text-right' : 'text-left'}`}>
                      <div className="flex items-center gap-2 px-1 text-[9px] font-bold text-slate-400 uppercase tracking-tighter">
                        {!isMe(reply) && (
                          <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">
                            {reply.admin && !isAdmin ? 'Support Team' : reply.authorUsername}
                          </span>
                        )}
                        <span>{formatIST(reply.createdAt)}</span>
                        {isMe(reply) && (
                          <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">
                            {isAdmin ? reply.authorUsername : 'You'}
                          </span>
                        )}
                      </div>
                      <div className={`group/msg relative p-4 rounded-[1.5rem] shadow-sm text-sm font-medium leading-relaxed overflow-visible ${isMe(reply) ? 'bg-indigo-600 text-white rounded-tr-none' : 'bg-white text-slate-700 border border-slate-100 rounded-tl-none'}`}>

                        {/* ── Edit / Delete hover buttons ── */}
                        {isMe(reply) && !reply.deleted && ticket.status !== 'CLOSED' && (
                          <div className={`absolute top-2 ${isMe(reply) ? 'left-2' : 'right-2'} opacity-0 group-hover/msg:opacity-100 transition-opacity flex items-center gap-1 z-10`}>
                            {(Date.now() - new Date(reply.createdAt).getTime()) < 15 * 60 * 1000 && (
                              <button
                                onClick={() => {
                                  setEditingReplyId(reply.id);
                                  const tmp = document.createElement('div');
                                  tmp.innerHTML = reply.message;
                                  setEditContent(tmp.textContent || tmp.innerText || '');
                                }}
                                className="p-1.5 rounded-lg bg-white/20 hover:bg-white/30 transition-colors"
                                title="Edit (within 15 min)"
                              >
                                <i className="fas fa-pencil-alt text-[10px] text-white/70 hover:text-white"></i>
                              </button>
                            )}
                            <button
                              onClick={() => { if (globalThis.confirm('Delete this message?')) deleteReply(reply.id); }}
                              className="p-1.5 rounded-lg bg-white/20 hover:bg-red-500/40 transition-colors"
                              title="Delete message"
                            >
                              <i className="fas fa-trash text-[10px] text-white/70 hover:text-red-200"></i>
                            </button>
                          </div>
                        )}

                        {reply.deleted ? (
                          <div className={`flex items-center gap-2 italic ${isMe(reply) ? 'text-white/50' : 'text-slate-400'}`}>
                            <i className="fas fa-ban text-xs"></i>
                            <span className="text-sm">This message was deleted</span>
                          </div>
                        ) : editingReplyId === reply.id ? (
                          <div className="flex flex-col gap-2 min-w-[200px]">
                            <textarea
                              value={editContent}
                              onChange={e => setEditContent(e.target.value)}
                              className="w-full rounded-lg border border-indigo-300 p-2 text-sm text-gray-800 resize-none focus:outline-none focus:ring-2 focus:ring-indigo-400"
                              rows={3}
                              autoFocus
                            />
                            <div className="flex gap-2 justify-end">
                              <button
                                onClick={() => setEditingReplyId(null)}
                                className="text-xs px-3 py-1 rounded-lg bg-white/20 hover:bg-white/30 text-white border border-white/30"
                              >Cancel</button>
                              <button
                                onClick={async () => {
                                  if (!editContent.trim()) return;
                                  try {
                                    const htmlMsg = editContent.split('\n').map(line => `<p>${line || '<br>'}</p>`).join('');
                                    await editReply(reply.id, htmlMsg);
                                    setEditingReplyId(null);
                                  } catch { globalThis.alert('Failed to edit. The 15-minute window may have passed.'); }
                                }}
                                className="text-xs px-3 py-1 rounded-lg bg-white text-indigo-700 font-semibold hover:bg-indigo-50"
                              >Save</button>
                            </div>
                          </div>
                        ) : (
                          <>
                            <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat" />
                            {reply.edited && (
                              <span className={`text-[9px] font-bold italic mt-1 inline-block ${isMe(reply) ? 'text-white/40' : 'text-slate-400'}`}>Edited</span>
                            )}
                          </>
                        )}

                        {/* Attachments (only if not deleted) */}
                        {!reply.deleted && reply.attachments && reply.attachments.length > 0 && (
                          <div className="mt-4 flex flex-col gap-2">
                            {reply.attachments.map((att, i) => (
                              <div
                                key={i}
                                className={`flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all ${isMe(reply) ? 'bg-white/10 border border-white/20 hover:bg-white/20' : 'bg-slate-50 border border-slate-100 hover:bg-slate-100'}`}
                                onClick={() => globalThis.open(att.url, '_blank')}
                              >
                                <div className={`w-8 h-8 flex items-center justify-center rounded-lg ${isMe(reply) ? 'bg-white/20' : 'bg-white shadow-sm text-indigo-500'}`}>
                                  <i className="fas fa-file-invoice"></i>
                                </div>
                                <div className="flex-1 min-w-0">
                                  <p className="text-[10px] font-bold truncate leading-tight">{att.name || 'Attachment'}</p>
                                  <p className="text-[8px] font-bold opacity-70 uppercase tracking-widest mt-0.5">Click to view</p>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Closed banner */}
          {isClosed && (
            <div className="flex items-center gap-3 px-6 py-4 bg-slate-100 rounded-2xl border border-slate-200 text-[11px] font-bold text-slate-500">
              <i className="fas fa-lock-open text-slate-400"></i>
              This ticket was closed
              {ticket.closedAt && <span className="text-slate-400">· {formatIST(ticket.closedAt)}</span>}
            </div>
          )}
        </div>
      </div>

      {/* Email Thread Modal */}
      {showEmailModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="bg-white rounded-[2rem] shadow-2xl border border-slate-100 w-full max-w-md mx-4 overflow-hidden">
            <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
              <div>
                <h3 className="text-sm font-black text-slate-800 leading-none">Send Email Thread</h3>
                <p className="text-[10px] text-slate-400 font-medium mt-1">Send the full conversation to any email address</p>
              </div>
              <button
                onClick={() => { setShowEmailModal(false); setEmailRecipient(''); }}
                className="w-8 h-8 flex items-center justify-center rounded-xl text-slate-400 hover:text-slate-700 hover:bg-slate-100 transition-all"
              >
                <i className="fas fa-times text-xs"></i>
              </button>
            </div>
            <div className="p-6 space-y-4">
              <div className="flex items-start gap-3 p-3 bg-sky-50 rounded-xl border border-sky-100 text-[11px] text-sky-700 font-medium">
                <i className="fas fa-info-circle text-sky-500 mt-0.5 flex-shrink-0"></i>
                <span>Admin usernames will appear as <strong>Support Team</strong> in the email for confidentiality.</span>
              </div>
              <div>
                <label className="block text-[10px] font-black text-slate-500 uppercase tracking-widest mb-1.5">Recipient Email</label>
                <input
                  type="email"
                  value={emailRecipient}
                  onChange={e => setEmailRecipient(e.target.value)}
                  placeholder="recipient@example.com"
                  className="w-full px-4 py-2.5 bg-slate-50 border border-slate-200 rounded-xl text-sm font-medium focus:ring-4 focus:ring-sky-500/10 focus:border-sky-400 outline-none transition-all"
                  autoFocus
                />
              </div>
            </div>
            <div className="px-6 py-4 border-t border-slate-100 flex justify-end gap-3">
              <button
                onClick={() => { setShowEmailModal(false); setEmailRecipient(''); }}
                className="px-4 py-2 text-slate-500 font-bold text-xs rounded-xl hover:bg-slate-100 transition-all"
              >
                Cancel
              </button>
              <button
                onClick={handleSendEmail}
                disabled={isSendingEmail || !emailRecipient.trim()}
                className="flex items-center gap-2 px-5 py-2 bg-sky-600 text-white rounded-xl text-xs font-black uppercase tracking-widest shadow-lg shadow-sky-500/20 hover:bg-sky-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed active:scale-95"
              >
                <i className={`fas ${isSendingEmail ? 'fa-circle-notch fa-spin' : 'fa-paper-plane'} text-xs`}></i>
                {isSendingEmail ? 'Sending...' : 'Send Email'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Reply form */}
      {ticket.status !== 'CLOSED' && (
        <div className="p-6 bg-white/70 backdrop-blur-xl border-t border-slate-200/50">
          <div className="max-w-4xl mx-auto">
            <form onSubmit={handleSubmitReply} className="space-y-4">
              <div className={`relative bg-white rounded-3xl border overflow-hidden transition-all shadow-sm ${
                isInternalNote
                  ? 'border-amber-300 focus-within:ring-4 focus-within:ring-amber-500/10'
                  : 'border-slate-200 focus-within:ring-4 focus-within:ring-indigo-500/10'
              }`}>
                {/* Form header */}
                <div className={`px-5 py-3 border-b flex items-center justify-between text-[10px] font-black uppercase tracking-widest ${
                  isInternalNote ? 'bg-amber-50/50 border-amber-100' : 'bg-slate-50/30 border-slate-50'
                }`}>
                  <div className={`flex items-center gap-2 ${isInternalNote ? 'text-amber-600' : 'text-slate-500'}`}>
                    <i className={isInternalNote ? 'fas fa-lock text-amber-500' : 'fas fa-reply text-indigo-500'}></i>
                    {isInternalNote ? 'Internal Note (Admin Only)' : 'Official Response'}
                  </div>
                  <div className="flex items-center gap-2">
                    {attachments.map((f, i) => (
                      <div key={i} className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-full border border-indigo-100">
                        <span className="max-w-[120px] truncate">{f.name}</span>
                        <button type="button" onClick={() => setAttachments(prev => prev.filter((_, idx) => idx !== i))} className="hover:text-rose-500">
                          <i className="fas fa-times"></i>
                        </button>
                      </div>
                    ))}
                    {/* Internal note toggle (admin only) */}
                    {isAdmin && (
                      <button
                        type="button"
                        onClick={() => setIsInternalNote(v => !v)}
                        title={isInternalNote ? 'Switch to public reply' : 'Switch to internal note'}
                        className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[9px] font-black uppercase tracking-widest border transition-all ${
                          isInternalNote
                            ? 'bg-amber-500 text-white border-amber-500'
                            : 'bg-white text-slate-500 border-slate-200 hover:border-amber-300 hover:text-amber-600'
                        }`}
                      >
                        <i className="fas fa-lock text-[9px]"></i>
                        {isInternalNote ? 'Internal' : 'Note'}
                      </button>
                    )}
                  </div>
                </div>

                <ReactQuill
                  value={newReply}
                  onChange={setNewReply}
                  modules={{ toolbar: [['bold', 'italic', 'underline'], ['link'], ['clean']] }}
                  placeholder={isInternalNote ? 'Write an internal note (only visible to admins)...' : 'Type your message here...'}
                  theme="snow"
                  className="quill-minimal"
                />

                {/* Form footer */}
                <div className="px-5 py-3 border-t border-slate-50 flex items-center justify-between bg-slate-50/10">
                  <label className="flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-600 rounded-xl text-[10px] font-black uppercase tracking-widest cursor-pointer transition-all">
                    <i className="fas fa-paperclip text-sm"></i>
                    <span>Attach Files</span>
                    <input
                      ref={fileInputRef}
                      type="file"
                      multiple
                      className="hidden"
                      onChange={(e) => {
                        const selected = Array.from(e.target.files || []);
                        if (selected.length) setAttachments(prev => [...prev, ...selected]);
                        if (fileInputRef.current) fileInputRef.current.value = '';
                      }}
                    />
                  </label>
                  <button
                    type="submit"
                    disabled={isSubmitting || (!newReply.replace(/<[^>]*>/g, '').trim() && attachments.length === 0)}
                    className={`px-6 py-2.5 text-white rounded-xl text-[11px] font-black uppercase tracking-widest shadow-lg transition-all disabled:opacity-50 active:scale-95 ${
                      isInternalNote
                        ? 'bg-amber-500 hover:bg-amber-600 shadow-amber-200'
                        : 'bg-indigo-600 hover:bg-indigo-700 shadow-indigo-200'
                    }`}
                  >
                    {isSubmitting ? 'Sending...' : isInternalNote ? 'Save Note' : 'Send Response'}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default TicketDetailPage;
