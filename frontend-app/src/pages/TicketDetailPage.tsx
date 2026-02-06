/**
 * Ticket Detail Page
 *
 * Detailed view of a support ticket with chat-like interface
 *
 * Converted from: xamops_ticket_detail.html
 *
 * Features:
 * - View ticket details and conversation history
 * - Rich text editor for replies (Quill)
 * - File attachment support
 * - Status management (Open, In Progress, Closed)
 * - Admin vs User differentiation
 * - Auto-scroll to latest messages
 * - Professional chat-like UI
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import ReactQuill from 'react-quill-new';
import 'react-quill-new/dist/quill.snow.css';

interface TicketReply {
  id: number;
  message: string;
  authorId: number;
  authorUsername: string;
  admin: boolean;
  createdAt: string;
  attachmentUrl?: string;
  fileName?: string;
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
  replies: TicketReply[];
}

const TicketDetailPage: React.FC = () => {
  const { userProfile } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const ticketId = searchParams.get('id');
  const { setActions, clearActions } = useTopbarActions();

  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [loading, setLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [newReply, setNewReply] = useState('');
  const [attachment, setAttachment] = useState<File | null>(null);
  const [fileName, setFileName] = useState('');

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

      // Auto-scroll to bottom after loading
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
   * Action Handlers
   */
  const goBackToTickets = useCallback(() => {
    navigate('/tickets');
  }, [navigate]);

  const updateTicketStatus = useCallback(async (status: string) => {
    if (!ticketId) return;
    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}/status`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status }),
      });
      if (!response.ok) throw new Error('Failed to update status');
      const updatedTicket = await response.json();
      setTicket(updatedTicket);
    } catch (err) {
      console.error('Error updating status:', err);
      globalThis.alert('Error updating ticket status.');
    }
  }, [ticketId]);

  const closeTicket = useCallback(async () => {
    if (globalThis.confirm('Are you sure you want to close this ticket?')) {
      await updateTicketStatus('CLOSED');
    }
  }, [updateTicketStatus]);

  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault();
    const textContent = newReply.replace(/<[^>]*>/g, '').trim();

    if (!textContent && !attachment) {
      globalThis.alert('Reply cannot be empty.');
      return;
    }

    setIsSubmitting(true);
    const formData = new FormData();
    formData.append('message', newReply);
    formData.append('ticketId', ticketId!);
    formData.append('authorId', String(userProfile?.id || ''));
    if (attachment) formData.append('file', attachment);

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
      setAttachment(null);
      setFileName('');
      if (fileInputRef.current) fileInputRef.current.value = '';

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
   * Effects
   */
  useEffect(() => {
    fetchTicket();
  }, [fetchTicket]);

  useEffect(() => {
    if (!ticket) return;

    setActions(
      <div className="flex items-center gap-6">
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
          <span
            className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm transition-all ${ticket.status === 'OPEN'
                ? 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10'
                : ticket.status === 'IN_PROGRESS'
                  ? 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10'
                  : 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10'
              }`}
          >
            <span className={`w-1.5 h-1.5 rounded-full bg-white shadow-[0_0_8px_white] ${ticket.status === 'OPEN' ? 'animate-pulse' : ''}`}></span>
            {ticket.status}
          </span>

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
  }, [setActions, clearActions, ticket, closeTicket, goBackToTickets]);

  /**
   * Formatting
   */
  const isMe = (reply: TicketReply) => reply.admin || String(reply.authorId) === String(userProfile?.id);
  const formatDate = (ds: string) => ds ? new Date(ds).toLocaleString() : '';
  const formatDateSimple = (ds: string) => ds ? new Date(ds).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }) : '';
  const getInitials = (name: string) => name ? name.charAt(0).toUpperCase() : 'U';
  const getSeverityClass = (sev: string) => {
    if (['High', 'Critical', 'Urgent'].includes(sev)) return 'text-rose-600';
    if (['Normal', 'Medium'].includes(sev)) return 'text-amber-500';
    return 'text-indigo-500';
  };

  if (loading) return <div className="flex-1 flex items-center justify-center"><i className="fas fa-spinner fa-spin text-5xl text-indigo-600"></i></div>;
  if (!ticket) return null;

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
                <div className="flex justify-between items-center">
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest leading-none">Original Request</span>
                  <span className="text-[10px] font-bold text-slate-400 uppercase tracking-tighter">{formatDate(ticket.createdAt)}</span>
                </div>
                <div className="text-base text-slate-700 font-medium whitespace-pre-wrap leading-relaxed">{ticket.description}</div>
                <div className="flex flex-wrap gap-4 pt-4 border-t border-slate-100 text-[10px] font-bold uppercase tracking-widest">
                  <div>Region: <span className="text-slate-600 font-black">{ticket.region || 'Global'}</span></div>
                  <div>Severity: <span className={`font-black ${getSeverityClass(ticket.severity)}`}>{ticket.severity}</span></div>
                </div>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-4 text-slate-400 pt-4">
            <span className="text-[10px] font-black uppercase tracking-[0.2em] whitespace-nowrap">Correspondence</span>
            <div className="h-px bg-slate-200 flex-1"></div>
          </div>

          <div className="space-y-6">
            {ticket.replies.map((reply) => (
              <div key={reply.id} className={`flex w-full ${isMe(reply) ? 'justify-end' : 'justify-start'}`}>
                <div className={`flex max-w-[85%] md:max-w-[70%] gap-4 ${isMe(reply) ? 'flex-row-reverse' : ''}`}>
                  <div className="flex-shrink-0 mt-auto">
                    <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm ${reply.admin ? 'bg-indigo-600 text-white' : 'bg-slate-200 text-slate-600'}`}>
                      {reply.admin ? <i className="fas fa-shield-alt"></i> : <span>{getInitials(reply.authorUsername)}</span>}
                    </div>
                  </div>
                  <div className={`space-y-2 ${isMe(reply) ? 'text-right' : 'text-left'}`}>
                    <div className="flex items-center gap-2 px-1 text-[9px] font-bold text-slate-400 uppercase tracking-tighter">
                      {!isMe(reply) && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">{reply.authorUsername}</span>}
                      <span>{formatDateSimple(reply.createdAt)}</span>
                      {isMe(reply) && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">You</span>}
                    </div>
                    <div className={`p-4 rounded-[1.5rem] shadow-sm text-sm font-medium leading-relaxed ${isMe(reply) ? 'bg-indigo-600 text-white rounded-tr-none' : 'bg-white text-slate-700 border border-slate-100 rounded-tl-none'}`}>
                      <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat" />
                      {(reply.attachmentUrl || reply.fileName) && (
                        <div
                          className={`mt-4 flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all ${isMe(reply) ? 'bg-white/10 border border-white/20 hover:bg-white/20' : 'bg-slate-50 border border-slate-100 hover:bg-slate-100'}`}
                          onClick={() => globalThis.open(reply.attachmentUrl as string, '_blank')}
                        >
                          <div className={`w-8 h-8 flex items-center justify-center rounded-lg ${isMe(reply) ? 'bg-white/20' : 'bg-white shadow-sm text-indigo-500'}`}><i className="fas fa-file-invoice"></i></div>
                          <div className="flex-1 min-w-0">
                            <p className="text-[10px] font-bold truncate leading-tight">{reply.fileName || 'Attachment'}</p>
                            <p className="text-[8px] font-bold opacity-70 uppercase tracking-widest mt-0.5">Click to view</p>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {ticket.status !== 'CLOSED' && (
        <div className="p-6 bg-white/70 backdrop-blur-xl border-t border-slate-200/50">
          <div className="max-w-4xl mx-auto">
            <form onSubmit={handleSubmitReply} className="space-y-4">
              <div className="relative bg-white rounded-3xl border border-slate-200 overflow-hidden focus-within:ring-4 focus-within:ring-indigo-500/10 transition-all shadow-sm">
                <div className="px-5 py-3 border-b border-slate-50 flex items-center justify-between bg-slate-50/30 text-[10px] font-black text-slate-500 uppercase tracking-widest">
                  <div className="flex items-center gap-2"><i className="fas fa-reply text-indigo-500"></i> Official Response</div>
                  {attachment && (
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-full border border-indigo-100"><span className="max-w-[120px] truncate">{fileName}</span><button type="button" onClick={() => { setAttachment(null); setFileName(''); }} className="hover:text-rose-500"><i className="fas fa-times"></i></button></div>
                  )}
                </div>
                <ReactQuill value={newReply} onChange={setNewReply} modules={{ toolbar: [['bold', 'italic', 'underline'], ['link'], ['clean']] }} placeholder="Type your message here..." theme="snow" className="quill-minimal" />
                <div className="px-5 py-3 border-t border-slate-50 flex items-center justify-between bg-slate-50/10">
                  <label className="flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-600 rounded-xl text-[10px] font-black uppercase tracking-widest cursor-pointer transition-all">
                    <i className="fas fa-paperclip text-sm"></i>
                    <span>Attach Files</span>
                    <input ref={fileInputRef} type="file" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) { setAttachment(f); setFileName(f.name); } }} />
                  </label>
                  <button type="submit" disabled={isSubmitting || (!newReply.replace(/<[^>]*>/g, '').trim() && !attachment)} className="px-6 py-2.5 bg-indigo-600 text-white rounded-xl text-[11px] font-black uppercase tracking-widest shadow-lg hover:bg-indigo-700 transition-all">
                    {isSubmitting ? 'Sending...' : 'Send Response'}
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
