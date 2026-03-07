/**
 * BillOps Ticket Detail Page Component
 *
 * Advanced ticket detail view with:
 * - IST timezone on all timestamps
 * - Internal admin notes (amber, lock icon, hidden from customers)
 * - Ticket assignment to admins
 * - SLA status card in header
 * - RCA generation (admin only)
 * - Rich text replies with file attachments
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useBillOpsTicketDetail } from '../hooks/useBillOpsTicketDetail';
import { RCAFormData, RCAFormSection } from '../types/billopsTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import { formatIST, getSlaStatus, getResponseSlaLabel } from '../utils/timeUtils';
import Quill from 'quill';
import 'quill/dist/quill.snow.css';
import { DialogRoot, DialogPanel } from '../components/ui';

const BillOpsTicketDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const ticketId = searchParams.get('id');
  const { setActions, clearActions } = useTopbarActions();

  const {
    ticket,
    currentUser,
    loading,
    isSubmitting,
    error,
    isAdmin,
    adminUsers,
    submitReply,
    closeTicket,
    submitRCA,
    assignTicket
  } = useBillOpsTicketDetail(ticketId);

  // Quill editor
  const quillRef = useRef<HTMLDivElement>(null);
  const quillInstance = useRef<Quill | null>(null);
  const [newReplyHasContent, setNewReplyHasContent] = useState(false);

  // Internal note toggle
  const [isInternalNote, setIsInternalNote] = useState(false);

  // File attachment
  const [attachment, setAttachment] = useState<File | null>(null);
  const [fileName, setFileName] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Assignment dropdown state
  const [showAssignDropdown, setShowAssignDropdown] = useState(false);
  const [isAssigning, setIsAssigning] = useState(false);

  // RCA Modal
  const [showRcaModal, setShowRcaModal] = useState(false);
  const [rcaSubmitting, setRcaSubmitting] = useState(false);
  const [rcaFormData, setRcaFormData] = useState<RCAFormData>({
    problemStatement: '',
    conclusion: '',
    incidentOverview: [{ heading: 'Incident Name', content: '' }],
    descriptionSections: [{ heading: 'Symptoms', content: '' }],
    rootCauseSections: [{ heading: 'Primary Cause', content: '' }],
    resolutionSections: [{ heading: 'Steps Taken', content: '' }],
    preventionSections: [{ heading: 'Action Item', content: '' }]
  });

  // Chat container ref for auto-scroll
  const chatContainerRef = useRef<HTMLDivElement>(null);

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') return 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10';
    if (status === 'IN_PROGRESS') return 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10';
    return 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10';
  };

  const getInitials = useCallback((name: string): string => {
    if (!name) return 'U';
    return name.charAt(0).toUpperCase();
  }, []);

  const isMe = useCallback((reply: any): boolean => {
    if (!currentUser?.id) return false;
    return String(reply.authorId) === String(currentUser.id);
  }, [currentUser]);

  const handleCloseTicket = useCallback(async () => {
    if (globalThis.confirm('Are you sure you want to close this ticket?')) {
      try {
        await closeTicket();
      } catch (err) {
        globalThis.alert('Error closing ticket. Please try again.');
      }
    }
  }, [closeTicket]);

  const goBackToTickets = useCallback(() => {
    if (isAdmin) {
      navigate('/billops-admin-tickets');
    } else {
      navigate('/billops-tickets');
    }
  }, [navigate, isAdmin]);

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      if (file.size > 10 * 1024 * 1024) {
        globalThis.alert('File is too large. Max size is 10MB.');
        return;
      }
      setAttachment(file);
      setFileName(file.name);
    }
  };

  const clearFile = useCallback(() => {
    setAttachment(null);
    setFileName('');
    if (fileInputRef.current) fileInputRef.current.value = '';
  }, []);

  const handleAssign = async (adminId: number, adminUsername: string) => {
    setIsAssigning(true);
    try {
      await assignTicket(adminId, adminUsername);
      setShowAssignDropdown(false);
    } catch {
      globalThis.alert('Failed to assign ticket.');
    } finally {
      setIsAssigning(false);
    }
  };

  /**
   * Quill initialization — runs ONCE when ticket loads (not on isInternalNote change)
   * to prevent the double-toolbar bug caused by re-initializing on the same DOM node.
   */
  useEffect(() => {
    if (!quillRef.current || quillInstance.current || !ticket || ticket.status === 'CLOSED') return;

    quillInstance.current = new Quill(quillRef.current, {
      theme: 'snow',
      placeholder: 'Type your message here...',
      modules: {
        toolbar: [
          ['bold', 'italic', 'underline', 'strike'],
          ['blockquote', 'code-block'],
          [{ list: 'ordered' }, { list: 'bullet' }],
          ['clean']
        ]
      }
    });

    quillInstance.current.on('text-change', () => {
      if (quillInstance.current) {
        const text = quillInstance.current.getText().trim();
        setNewReplyHasContent(text.length > 0);
      }
    });

    return () => {
      // Clean up toolbar DOM so re-mounting doesn't produce a double toolbar
      if (quillRef.current) {
        const toolbar = quillRef.current.parentElement?.querySelector('.ql-toolbar');
        if (toolbar) toolbar.remove();
        quillRef.current.innerHTML = '';
        quillRef.current.className = '';
      }
      quillInstance.current = null;
    };
  }, [ticket]); // intentionally excludes isInternalNote

  /**
   * Update Quill placeholder when internal-note mode toggles (no re-init needed)
   */
  useEffect(() => {
    if (!quillInstance.current) return;
    quillInstance.current.root.setAttribute(
      'data-placeholder',
      isInternalNote ? 'Write an internal note (only visible to admins)...' : 'Type your message here...'
    );
  }, [isInternalNote]);

  /**
   * Scroll to bottom when replies change
   */
  useEffect(() => {
    if (chatContainerRef.current && ticket) {
      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    }
  }, [ticket?.replies]);

  /**
   * Redirect if no ticket ID
   */
  useEffect(() => {
    if (!ticketId) navigate('/billops-tickets');
  }, [ticketId, navigate]);

  /**
   * Set Topbar Actions
   */
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
              Case #{ticket.id} • {ticket.service}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <span className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[10px] font-bold uppercase tracking-wider shadow-sm transition-all ${getStatusBadgeClasses(ticket.status)}`}>
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
                  <><i className="fas fa-user-plus text-[10px]"></i> Assign</>
                )}
                {!isAssigning && ticket.status !== 'CLOSED' && <i className="fas fa-chevron-down text-[8px] opacity-50 ml-1"></i>}
                {isAssigning && <i className="fas fa-circle-notch fa-spin text-[8px] ml-1"></i>}
              </button>

              {showAssignDropdown && adminUsers.length > 0 && (
                <div className="absolute top-full right-0 mt-1 w-48 bg-white rounded-xl shadow-xl border border-slate-100 py-1 z-50 overflow-hidden">
                  <div className="px-3 py-1.5 text-[9px] font-black text-slate-400 uppercase tracking-widest border-b border-slate-50">Assign to</div>
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
                      {ticket.assignedToId === admin.id && <i className="fas fa-check text-indigo-500 ml-auto text-[9px]"></i>}
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

          {isAdmin && ticket.status !== 'CLOSED' && (
            <button
              onClick={() => setShowRcaModal(true)}
              className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-purple-500/20 hover:bg-purple-700 transition-all hover:-translate-y-0.5"
            >
              <i className="fas fa-file-medical-alt"></i>
              <span>Generate RCA</span>
            </button>
          )}

          {ticket.status !== 'CLOSED' && (
            <button
              onClick={handleCloseTicket}
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
  }, [setActions, clearActions, ticket, isAdmin, handleCloseTicket, goBackToTickets,
      adminUsers, showAssignDropdown, isAssigning, handleAssign]);

  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!quillInstance.current) return;

    const textContent = quillInstance.current.getText().trim();
    if (!textContent && !attachment) return;

    try {
      const htmlMessage = textContent ? quillInstance.current.root.innerHTML : '';
      await submitReply(htmlMessage, attachment, isInternalNote);

      quillInstance.current.root.innerHTML = '';
      setNewReplyHasContent(false);
      clearFile();
      setIsInternalNote(false);

      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    } catch (err) {
      globalThis.alert('Error sending reply. Please try again.');
    }
  };

  // RCA form helpers
  const addRcaSection = (arrayName: keyof RCAFormData) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      setRcaFormData({
        ...rcaFormData,
        [arrayName]: [...(rcaFormData[arrayName] as RCAFormSection[]), { heading: '', content: '' }]
      });
    }
  };

  const removeRcaSection = (arrayName: keyof RCAFormData, index: number) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      const sections = [...(rcaFormData[arrayName] as RCAFormSection[])];
      sections.splice(index, 1);
      setRcaFormData({ ...rcaFormData, [arrayName]: sections });
    }
  };

  const updateRcaSection = (arrayName: keyof RCAFormData, index: number, field: 'heading' | 'content', value: string) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      const sections = [...(rcaFormData[arrayName] as RCAFormSection[])];
      sections[index][field] = value;
      setRcaFormData({ ...rcaFormData, [arrayName]: sections });
    }
  };

  const handleSubmitRca = async () => {
    if (!rcaFormData.problemStatement) {
      globalThis.alert('Problem Statement is required.');
      return;
    }
    if (!globalThis.confirm('This will generate an RCA report and CLOSE the ticket. Continue?')) return;

    setRcaSubmitting(true);
    try {
      await submitRCA(rcaFormData);
      globalThis.alert('RCA Generated successfully. Ticket Closed.');
      setShowRcaModal(false);
    } catch (err: any) {
      globalThis.alert('Failed to submit RCA: ' + (err.message || 'Unknown error'));
    } finally {
      setRcaSubmitting(false);
    }
  };

  const renderRcaSection = (arrayName: keyof RCAFormData, sections: RCAFormSection[]) => (
    <>
      {sections.map((section, index) => (
        <div key={index} className="mb-3 bg-gray-50 p-4 rounded-lg border border-gray-200 relative group">
          <button type="button" onClick={() => removeRcaSection(arrayName, index)} className="absolute top-2 right-2 text-gray-400 hover:text-red-500 transition-colors">
            <i className="fas fa-times"></i>
          </button>
          <div className="mb-2">
            <input value={section.heading} onChange={e => updateRcaSection(arrayName, index, 'heading', e.target.value)} type="text" className="block w-full bg-transparent border-0 border-b border-gray-300 focus:border-purple-500 focus:ring-0 text-sm font-semibold placeholder-gray-400 px-0" placeholder="Sub-heading" />
          </div>
          <textarea value={section.content} onChange={e => updateRcaSection(arrayName, index, 'content', e.target.value)} className="w-full bg-white border-gray-300 rounded-md shadow-sm focus:ring-purple-500 focus:border-purple-500 text-sm" rows={2} placeholder="Details..."></textarea>
        </div>
      ))}
      <button type="button" onClick={() => addRcaSection(arrayName)} className="text-sm text-purple-600 hover:text-purple-800 flex items-center font-medium">
        <i className="fas fa-plus-circle mr-1"></i> Add Sub-section
      </button>
    </>
  );

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <i className="fas fa-spinner fa-spin text-5xl text-indigo-600"></i>
      </div>
    );
  }

  if (error && !ticket) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-center">
          <i className="fas fa-exclamation-circle text-5xl text-red-500 mb-4"></i>
          <p className="text-xl text-gray-700">{error}</p>
          <button onClick={goBackToTickets} className="mt-4 text-indigo-600 hover:text-indigo-500 underline">
            Back to Tickets
          </button>
        </div>
      </div>
    );
  }

  if (!ticket) return null;

  // SLA data
  const isClosed = ticket.status === 'CLOSED';
  const slaStatus = getSlaStatus(ticket.createdAt, ticket.severity, isClosed, ticket.closedAt);
  const responseSlaLabel = getResponseSlaLabel(ticket.createdAt, ticket.severity, ticket.chatStartedAt);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Chat Container */}
      <div
        ref={chatContainerRef}
        className="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 scroll-smooth p-6 lg:p-10"
      >
        <div className="max-w-4xl mx-auto space-y-8 pb-10">

          {/* Original Ticket + Metadata Card */}
          <div className="relative group bg-white/70 backdrop-blur-xl border border-white p-8 rounded-[2.5rem] shadow-[0_8px_40px_rgba(0,0,0,0.03)] hover:shadow-[0_24px_48px_rgba(0,0,0,0.06)] transition-all duration-500 overflow-hidden">
            <div className="absolute top-0 right-0 w-40 h-40 bg-gradient-to-br from-indigo-500/5 to-purple-500/5 rounded-full blur-3xl -mr-20 -mt-20 group-hover:scale-150 transition-transform duration-700"></div>

            <div className="relative flex gap-6">
              <div className="flex-shrink-0">
                <div className="w-12 h-12 rounded-2xl bg-indigo-600 flex items-center justify-center text-white shadow-lg shadow-indigo-200">
                  <i className="fas fa-ticket-alt text-lg"></i>
                </div>
              </div>
              <div className="flex-1 space-y-4">
                <div className="flex justify-between items-center">
                  <span className="text-[10px] font-black text-slate-400 uppercase tracking-widest leading-none">Original Issue Description</span>
                  <span className="text-[10px] font-bold text-slate-400 uppercase tracking-tighter">Request #{ticket.id}</span>
                </div>
                <div className="text-base text-slate-700 font-medium leading-relaxed whitespace-pre-wrap">{ticket.description}</div>

                <div className="flex flex-wrap gap-4 pt-4 border-t border-slate-100">
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Service:</span>
                    <span className="text-[10px] font-black text-indigo-600">{ticket.service}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Submitted:</span>
                    <span className="text-[10px] font-black text-slate-600">{formatIST(ticket.createdAt)}</span>
                  </div>
                  {ticket.chatStartedAt && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Chat Started:</span>
                      <span className="text-[10px] font-black text-indigo-600">{formatIST(ticket.chatStartedAt)}</span>
                    </div>
                  )}
                  {ticket.closedAt && (
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Closed At:</span>
                      <span className="text-[10px] font-black text-slate-600">{formatIST(ticket.closedAt)}</span>
                    </div>
                  )}
                </div>

                {/* SLA card (admin only) — same style as TicketDetailPage */}
                {isAdmin && (
                  <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl border ${
                    slaStatus.status === 'ok' ? 'bg-emerald-100 text-emerald-700 border-emerald-200' :
                    slaStatus.status === 'warning' ? 'bg-amber-100 text-amber-700 border-amber-200' :
                    'bg-red-100 text-red-700 border-red-200'
                  }`}>
                    <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                      slaStatus.status === 'ok' ? 'bg-emerald-500' :
                      slaStatus.status === 'warning' ? 'bg-amber-500 animate-pulse' :
                      'bg-red-500 animate-pulse'
                    }`}></span>
                    <div className="flex-1 min-w-0">
                      <div className="text-[10px] font-black uppercase tracking-widest leading-none mb-1">
                        Resolution SLA — {slaStatus.status === 'breached' ? 'Breached' : slaStatus.status === 'warning' ? 'At Risk' : 'On Track'}
                      </div>
                      <div className="text-[11px] font-bold">{slaStatus.label}</div>
                    </div>
                    <div className="text-[10px] font-black opacity-70 whitespace-nowrap">{responseSlaLabel}</div>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Discussion Header */}
          <div className="flex items-center gap-4 text-slate-400">
            <span className="text-[10px] font-black uppercase tracking-[0.2em] whitespace-nowrap">Correspondence Log</span>
            <div className="h-px bg-slate-200 flex-1"></div>
            <span className="text-[10px] font-bold text-slate-400 whitespace-nowrap">{ticket.replies?.length || 0} messages</span>
          </div>

          {/* Replies */}
          {ticket.replies && ticket.replies.length > 0 ? (
            <div className="space-y-6">
              {ticket.replies.map(reply => {
                const isMine = isMe(reply);
                const isInternalReply = reply.isInternal;

                // Internal notes: amber styling, left-aligned
                if (isInternalReply) {
                  return (
                    <div key={reply.id} className="flex w-full justify-start">
                      <div className="flex max-w-[85%] md:max-w-[70%] gap-4">
                        <div className="flex-shrink-0 mt-auto">
                          <div className="w-10 h-10 rounded-2xl bg-amber-100 text-amber-700 flex items-center justify-center text-sm font-black shadow-sm border border-amber-200">
                            <i className="fas fa-lock text-xs"></i>
                          </div>
                        </div>
                        <div className="space-y-2 text-left">
                          <div className="flex items-center gap-2 px-1 flex-wrap">
                            <span className="text-[10px] font-black text-amber-700 uppercase tracking-widest">{reply.authorUsername}</span>
                            <span className="px-2 py-0.5 text-[8px] font-black bg-amber-100 text-amber-600 border border-amber-200 rounded-full uppercase tracking-widest">Internal Note</span>
                            <span className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">{formatIST(reply.createdAt, { short: true })}</span>
                          </div>
                          <div className="p-5 rounded-[1.5rem] shadow-sm text-sm font-medium leading-relaxed bg-amber-50 text-amber-900 border border-amber-200 rounded-tl-none">
                            <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat !p-0" />
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                }

                return (
                  <div key={reply.id} className={`flex w-full ${isMine ? 'justify-end' : 'justify-start'}`}>
                    <div className={`flex max-w-[85%] md:max-w-[70%] gap-4 ${isMine ? 'flex-row-reverse' : ''}`}>
                      <div className="flex-shrink-0 mt-auto">
                        <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm ${reply.admin ? 'bg-indigo-600 text-white shadow-indigo-100' : 'bg-slate-200 text-slate-600'}`}>
                          {reply.admin ? <i className="fas fa-shield-alt"></i> : <span>{getInitials(reply.authorUsername)}</span>}
                        </div>
                      </div>

                      <div className={`space-y-2 ${isMine ? 'text-right' : 'text-left'}`}>
                        <div className="flex items-center gap-2 px-1">
                          {!isMine && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">{reply.authorUsername}</span>}
                          <span className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">{formatIST(reply.createdAt, { short: true })}</span>
                          {isMine && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">You</span>}
                        </div>

                        <div className={`p-5 rounded-[1.5rem] shadow-sm text-sm font-medium leading-relaxed ${isMine ? 'bg-indigo-600 text-white rounded-tr-none' : 'bg-white text-slate-700 border border-slate-100 rounded-tl-none'}`}>
                          <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat !p-0" />

                          {/* Attachments */}
                          {(reply.attachmentUrl || reply.attachmentName) && (
                            <div className="mt-4">
                              {reply.attachmentType?.startsWith('image/') && (
                                <div
                                  className="mb-3 rounded-2xl overflow-hidden border border-white/20 shadow-md max-w-xs cursor-pointer hover:opacity-90 transition-opacity"
                                  onClick={() => globalThis.open(reply.attachmentUrl, '_blank')}
                                >
                                  <img src={reply.attachmentUrl} alt={reply.attachmentName} className="w-full h-auto object-cover max-h-60" />
                                </div>
                              )}

                              {reply.attachmentName?.startsWith('rca_ticket_') && (
                                <div
                                  className="group cursor-pointer bg-white/10 backdrop-blur-md rounded-2xl overflow-hidden border border-white/20 hover:border-purple-300 transition-colors shadow-lg"
                                  onClick={() => reply.attachmentUrl ? globalThis.open(reply.attachmentUrl, '_blank') : globalThis.alert('File URL not found')}
                                >
                                  <div className={`flex items-center p-4 ${isMine ? 'bg-white/10' : 'bg-purple-50'}`}>
                                    <div className="p-3 bg-purple-600 text-white rounded-xl mr-4 shadow-lg shadow-purple-200">
                                      <i className="fas fa-file-medical-alt text-xl"></i>
                                    </div>
                                    <div className="flex-1">
                                      <h4 className={`text-sm font-black leading-tight ${isMine ? 'text-white' : 'text-purple-900'}`}>RCA Report Generated</h4>
                                      <p className={`text-[10px] font-bold mt-1 uppercase tracking-widest ${isMine ? 'text-white/70' : 'text-purple-600'}`}>Download PDF Report</p>
                                    </div>
                                    <i className={`fas fa-download ${isMine ? 'text-white/50' : 'text-purple-400'}`}></i>
                                  </div>
                                </div>
                              )}

                              {(!reply.attachmentName || !reply.attachmentName.startsWith('rca_ticket_')) && (
                                <div
                                  className={`flex items-center p-3 rounded-2xl transition-all cursor-pointer ${isMine ? 'bg-white/10 border border-white/20 hover:bg-white/20' : 'bg-slate-50 border border-slate-100 hover:bg-slate-100'}`}
                                  onClick={() => reply.attachmentUrl ? globalThis.open(reply.attachmentUrl, '_blank') : alert('File URL not found')}
                                >
                                  <div className={`w-10 h-10 flex items-center justify-center rounded-xl mr-3 ${isMine ? 'bg-white/20 text-white' : 'bg-white shadow-sm text-indigo-500'}`}>
                                    {reply.attachmentType?.startsWith('image/') ? <i className="fas fa-file-image text-lg"></i> : reply.attachmentType?.includes('pdf') ? <i className="fas fa-file-pdf text-lg"></i> : <i className="fas fa-file-alt text-lg"></i>}
                                  </div>
                                  <div className="flex-1 min-w-0">
                                    <p className={`text-xs font-bold truncate ${isMine ? 'text-white' : 'text-slate-800'}`}>{reply.attachmentName || 'Attached File'}</p>
                                    <p className={`text-[9px] font-bold uppercase tracking-widest mt-0.5 ${isMine ? 'text-white/60' : 'text-slate-400'}`}>Click to view</p>
                                  </div>
                                  <i className={`fas fa-download text-xs ${isMine ? 'text-white/40' : 'text-slate-300'}`}></i>
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="text-center py-10">
              <p className="text-gray-400 text-sm italic">No replies yet. Start the conversation below.</p>
            </div>
          )}
        </div>
      </div>

      {/* Footer - Reply Form */}
      {ticket.status !== 'CLOSED' ? (
        <div className="p-6 lg:p-10 bg-white/70 backdrop-blur-xl border-t border-slate-200/50">
          <div className="max-w-4xl mx-auto">
            <form onSubmit={handleSubmitReply} className="space-y-4">
              <div className={`relative bg-white rounded-[2rem] border overflow-hidden focus-within:ring-4 transition-all shadow-xl ${
                isInternalNote
                  ? 'border-amber-300 focus-within:ring-amber-500/10 focus-within:border-amber-400'
                  : 'border-slate-200 focus-within:ring-indigo-500/10 focus-within:border-indigo-500/30'
              } shadow-slate-200/40`}>
                <div className="px-6 py-3 border-b border-slate-50 flex items-center justify-between bg-slate-50/30">
                  <div className={`flex items-center gap-2 text-[10px] font-black uppercase tracking-widest leading-none ${isInternalNote ? 'text-amber-600' : 'text-slate-500'}`}>
                    <i className={`${isInternalNote ? 'fas fa-lock text-amber-500' : 'fas fa-reply text-indigo-500'}`}></i>
                    {isInternalNote ? 'Internal Note (Admin Only)' : 'Official Ticket Response'}
                  </div>
                  <div className="flex items-center gap-3">
                    {attachment && (
                      <div className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-lg border border-indigo-100 text-[9px] font-black uppercase tracking-widest">
                        <i className="fas fa-paperclip"></i>
                        <span className="max-w-[150px] truncate">{fileName}</span>
                        <button type="button" onClick={clearFile} className="hover:text-rose-500 transition-colors">
                          <i className="fas fa-times"></i>
                        </button>
                      </div>
                    )}
                    {/* Internal Note Toggle - admin only */}
                    {isAdmin && (
                      <button
                        type="button"
                        onClick={() => setIsInternalNote(v => !v)}
                        title={isInternalNote ? 'Switch to public reply' : 'Switch to internal note'}
                        className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[9px] font-black uppercase tracking-widest border transition-all ${
                          isInternalNote
                            ? 'bg-amber-100 text-amber-700 border-amber-300 shadow-sm'
                            : 'bg-slate-50 text-slate-400 border-slate-200 hover:border-amber-300 hover:text-amber-600'
                        }`}
                      >
                        <i className="fas fa-lock"></i>
                        {isInternalNote ? 'Internal' : 'Note'}
                      </button>
                    )}
                  </div>
                </div>

                <div className="quill-minimal-wrapper">
                  <div ref={quillRef} className="bg-white"></div>
                </div>

                <div className="px-6 py-4 border-t border-slate-50 flex items-center justify-between bg-slate-50/10">
                  <label className="flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-600 rounded-xl text-[10px] font-black uppercase tracking-widest cursor-pointer transition-all active:scale-95">
                    <i className="fas fa-paperclip text-sm"></i>
                    <span>Add Attachments</span>
                    <input ref={fileInputRef} type="file" className="hidden" onChange={handleFileSelect} />
                  </label>

                  <button
                    type="submit"
                    disabled={isSubmitting || (!newReplyHasContent && !attachment)}
                    className={`flex items-center gap-2 px-8 py-3 text-white rounded-xl text-[11px] font-black uppercase tracking-widest shadow-lg transition-all disabled:opacity-50 active:scale-95 hover:-translate-y-0.5 ${
                      isInternalNote
                        ? 'bg-amber-500 shadow-amber-500/30 hover:bg-amber-600'
                        : 'bg-indigo-600 shadow-indigo-500/30 hover:bg-indigo-700'
                    }`}
                  >
                    {isSubmitting
                      ? <><i className="fas fa-circle-notch fa-spin"></i> Processing...</>
                      : isInternalNote
                        ? <><i className="fas fa-lock"></i> Save Note</>
                        : <><i className="fas fa-paper-plane"></i> Send Message</>
                    }
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      ) : (
        <div className="p-8 bg-slate-100/50 border-t border-slate-200/50 backdrop-blur-sm">
          <div className="max-w-md mx-auto bg-white p-4 rounded-2xl shadow-sm border border-slate-200 text-center">
            <div className="flex items-center justify-center gap-2 text-slate-400 font-bold text-[10px] uppercase tracking-[0.2em] flex-wrap">
              <i className="fas fa-lock"></i>
              Ticket Interaction Disabled &bull; Case Closed
              {ticket.closedAt && <span className="text-slate-400">· {formatIST(ticket.closedAt)}</span>}
            </div>
          </div>
        </div>
      )}

      {/* RCA Modal */}
      <DialogRoot isOpen={showRcaModal} onClose={() => setShowRcaModal(false)} maxWidth="max-w-4xl">
        <DialogPanel
          title={
            <span className="flex items-center gap-2 text-lg font-medium text-gray-900">
              <i className="fas fa-file-medical-alt text-purple-600"></i> Generate Root Cause Analysis
            </span>
          }
          onClose={() => setShowRcaModal(false)}
          footer={
            <>
              <button type="button" onClick={() => setShowRcaModal(false)} className="inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-sm font-medium text-gray-700 hover:bg-gray-50">
                Cancel
              </button>
              <button type="button" onClick={handleSubmitRca} disabled={rcaSubmitting} className="inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-purple-600 text-sm font-medium text-white hover:bg-purple-700 disabled:opacity-50">
                {rcaSubmitting ? <><i className="fas fa-circle-notch fa-spin mr-2"></i> Generating PDF...</> : 'Submit & Close Ticket'}
              </button>
            </>
          }
        >
          <div className="overflow-y-auto max-h-[60vh] pr-2 custom-scrollbar">
            <form className="space-y-6">
              <div>
                <label className="block text-sm font-bold text-gray-800 mb-2">1. Problem Statement <span className="text-red-500">*</span></label>
                <textarea value={rcaFormData.problemStatement} onChange={e => setRcaFormData({ ...rcaFormData, problemStatement: e.target.value })} className="w-full shadow-sm border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm" rows={3} placeholder="Describe the problem briefly..."></textarea>
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">2. Incident Overview</label>
                {renderRcaSection('incidentOverview', rcaFormData.incidentOverview)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">3. Description of the Issue</label>
                {renderRcaSection('descriptionSections', rcaFormData.descriptionSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">4. Root Cause Identification</label>
                {renderRcaSection('rootCauseSections', rcaFormData.rootCauseSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">5. Resolution</label>
                {renderRcaSection('resolutionSections', rcaFormData.resolutionSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">6. Prevention / Future Mitigation</label>
                {renderRcaSection('preventionSections', rcaFormData.preventionSections)}
              </div>
              <div className="border-t border-gray-200 pt-4">
                <label className="block text-sm font-bold text-gray-800 mb-2">7. Conclusion</label>
                <textarea value={rcaFormData.conclusion} onChange={e => setRcaFormData({ ...rcaFormData, conclusion: e.target.value })} className="w-full shadow-sm border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm" rows={3} placeholder="Final summary..."></textarea>
              </div>
            </form>
          </div>
        </DialogPanel>
      </DialogRoot>
    </div>
  );
};

export default BillOpsTicketDetailPage;
