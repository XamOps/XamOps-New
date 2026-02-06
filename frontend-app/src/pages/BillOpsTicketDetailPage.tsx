/**
 * BillOps Ticket Detail Page Component
 *
 * Displays detailed view of a support ticket with:
 * - Full ticket information
 * - Reply/communication thread (chat-style)
 * - Rich text editor (Quill) for composing replies
 * - File attachments support
 * - RCA (Root Cause Analysis) report generation
 * - Close ticket functionality
 * - Admin-only features (RCA generation)
 *
 * Converted from: billops/ticket_detail.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Improved chat-style UI with better bubbles and spacing
 * - Enhanced RCA modal with better form layout
 * - Better mobile responsiveness
 * - Improved attachment handling and previews
 * - Modern status badges and animations
 *
 * What stayed the same:
 * - All API endpoints remain identical
 * - Same Quill editor integration
 * - Same file upload functionality
 * - All business logic preserved exactly
 * - Same RCA form structure
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useBillOpsTicketDetail } from '../hooks/useBillOpsTicketDetail';
import { RCAFormData, RCAFormSection } from '../types/billopsTickets';
import { useTopbarActions } from '../contexts/TopbarContext';
import Quill from 'quill';
import 'quill/dist/quill.snow.css';

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
    submitReply,
    closeTicket,
    submitRCA
  } = useBillOpsTicketDetail(ticketId);

  // Quill editor
  const quillRef = useRef<HTMLDivElement>(null);
  const quillInstance = useRef<Quill | null>(null);
  const [newReplyHasContent, setNewReplyHasContent] = useState(false);

  // File attachment
  const [attachment, setAttachment] = useState<File | null>(null);
  const [fileName, setFileName] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

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

  /**
   * Helper Functions
   */
  const formatDate = useCallback((dateString: string): string => {
    if (!dateString) return '';
    return new Date(dateString).toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }, []);

  const formatDateSimple = useCallback((dateString: string): string => {
    if (!dateString) return '';
    const date = new Date(dateString);
    const today = new Date();
    const isToday = date.toDateString() === today.toDateString();

    if (isToday) {
      return date.toLocaleTimeString('en-US', {
        hour: 'numeric',
        minute: '2-digit'
      });
    }
    return (
      date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) +
      ', ' +
      date.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
    );
  }, []);

  const getInitials = useCallback((name: string): string => {
    if (!name) return 'U';
    return name.charAt(0).toUpperCase();
  }, []);

  const isMe = useCallback((reply: any): boolean => {
    if (!currentUser?.id) return false;
    return String(reply.authorId) === String(currentUser.id);
  }, [currentUser]);

  /**
   * Action Handlers
   */
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

  /**
   * File Handlers
   */
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
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }, []);

  /**
   * Quill initialization
   */
  useEffect(() => {
    if (!quillRef.current || quillInstance.current || !ticket || ticket.status === 'CLOSED') {
      return;
    }

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
      if (quillInstance.current) {
        quillInstance.current = null;
      }
    };
  }, [ticket]);

  /**
   * Scroll to bottom when replies change
   */
  useEffect(() => {
    if (chatContainerRef.current && ticket) {
      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop =
            chatContainerRef.current.scrollHeight;
        }
      }, 100);
    }
  }, [ticket?.replies]);

  /**
   * Redirect if no ticket ID
   */
  useEffect(() => {
    if (!ticketId) {
      navigate('/billops-tickets');
    }
  }, [ticketId, navigate]);

  const getStatusBadgeClasses = (status: string) => {
    if (status === 'OPEN') {
      return 'bg-emerald-500 text-white shadow-emerald-200 ring-4 ring-emerald-500/10';
    }
    if (status === 'IN_PROGRESS') {
      return 'bg-amber-500 text-white shadow-amber-200 ring-4 ring-amber-500/10';
    }
    return 'bg-slate-400 text-white shadow-slate-200 ring-4 ring-slate-400/10';
  };

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

          {isAdmin && ticket.status !== 'CLOSED' && (
            <button
              onClick={() => setShowRcaModal(true)}
              className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-purple-500/20 hover:bg-purple-700 transition-all hover:-translate-y-0.5"
              title="Generate RCA Report"
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
  }, [setActions, clearActions, ticket, isAdmin, handleCloseTicket, goBackToTickets]);

  /**
   * Submit reply
   */
  const handleSubmitReply = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!quillInstance.current) return;

    const textContent = quillInstance.current.getText().trim();

    if (!textContent && !attachment) {
      return;
    }

    try {
      const htmlMessage = textContent ? quillInstance.current.root.innerHTML : '';
      await submitReply(htmlMessage, attachment);

      // Clear editor and attachment
      quillInstance.current.root.innerHTML = '';
      clearFile();

      // Scroll to bottom
      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop =
            chatContainerRef.current.scrollHeight;
        }
      }, 100);
    } catch (err) {
      globalThis.alert('Error sending reply. Please try again.');
    }
  };

  /**
   * Add section to RCA form
   */
  const addRcaSection = (arrayName: keyof RCAFormData) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      setRcaFormData({
        ...rcaFormData,
        [arrayName]: [...(rcaFormData[arrayName] as RCAFormSection[]), { heading: '', content: '' }]
      });
    }
  };

  /**
   * Remove section from RCA form
   */
  const removeRcaSection = (arrayName: keyof RCAFormData, index: number) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      const sections = [...(rcaFormData[arrayName] as RCAFormSection[])];
      sections.splice(index, 1);
      setRcaFormData({
        ...rcaFormData,
        [arrayName]: sections
      });
    }
  };

  /**
   * Update RCA section
   */
  const updateRcaSection = (
    arrayName: keyof RCAFormData,
    index: number,
    field: 'heading' | 'content',
    value: string
  ) => {
    if (Array.isArray(rcaFormData[arrayName])) {
      const sections = [...(rcaFormData[arrayName] as RCAFormSection[])];
      sections[index][field] = value;
      setRcaFormData({
        ...rcaFormData,
        [arrayName]: sections
      });
    }
  };

  /**
   * Handle RCA submission
   */
  const handleSubmitRca = async () => {
    if (!rcaFormData.problemStatement) {
      globalThis.alert('Problem Statement is required.');
      return;
    }

    if (
      !globalThis.confirm(
        'This will generate an RCA report and CLOSE the ticket. Continue?'
      )
    ) {
      return;
    }

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

  /**
   * Render RCA section fields
   */
  const renderRcaSection = (
    arrayName: keyof RCAFormData,
    sections: RCAFormSection[]
  ) => {
    return (
      <>
        {sections.map((section, index) => (
          <div
            key={index}
            className="mb-3 bg-gray-50 p-4 rounded-lg border border-gray-200 relative group"
          >
            <button
              type="button"
              onClick={() => removeRcaSection(arrayName, index)}
              className="absolute top-2 right-2 text-gray-400 hover:text-red-500 transition-colors"
              title="Remove Section"
            >
              <i className="fas fa-times"></i>
            </button>
            <div className="mb-2">
              <input
                value={section.heading}
                onChange={e =>
                  updateRcaSection(arrayName, index, 'heading', e.target.value)
                }
                type="text"
                className="block w-full bg-transparent border-0 border-b border-gray-300 focus:border-purple-500 focus:ring-0 text-sm font-semibold placeholder-gray-400 px-0"
                placeholder="Sub-heading"
              />
            </div>
            <textarea
              value={section.content}
              onChange={e =>
                updateRcaSection(arrayName, index, 'content', e.target.value)
              }
              className="w-full bg-white border-gray-300 rounded-md shadow-sm focus:ring-purple-500 focus:border-purple-500 text-sm"
              rows={2}
              placeholder="Details..."
            ></textarea>
          </div>
        ))}
        <button
          type="button"
          onClick={() => addRcaSection(arrayName)}
          className="text-sm text-purple-600 hover:text-purple-800 flex items-center font-medium"
        >
          <i className="fas fa-plus-circle mr-1"></i> Add Sub-section
        </button>
      </>
    );
  };

  /**
   * Loading state
   */
  if (loading) {
    return (
      <div className="billops-ticket-detail-page">
        <div className="flex justify-center items-center h-screen">
          <i className="fas fa-spinner fa-spin text-3xl text-indigo-600"></i>
        </div>
      </div>
    );
  }

  /**
   * Error state
   */
  if (error && !ticket) {
    return (
      <div className="billops-ticket-detail-page">
        <div className="flex justify-center items-center h-screen">
          <div className="text-center">
            <i className="fas fa-exclamation-circle text-5xl text-red-500 mb-4"></i>
            <p className="text-xl text-gray-700">{error}</p>
            <button
              onClick={goBackToTickets}
              className="mt-4 text-indigo-600 hover:text-indigo-500 underline"
            >
              Back to Tickets
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!ticket) return null;

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      {/* Chat Container */}
      <div
        ref={chatContainerRef}
        className="flex-1 overflow-y-auto custom-scrollbar bg-slate-50/50 scroll-smooth p-6 lg:p-10"
      >
        <div className="max-w-4xl mx-auto space-y-8 pb-10">
          {/* Original Ticket Description - Premium Glass Card */}
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
                    <span className="text-[10px] font-black text-slate-600">{formatDate(ticket.createdAt)}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Discussion Header */}
          <div className="flex items-center gap-4 text-slate-400">
            <span className="text-[10px] font-black uppercase tracking-[0.2em] whitespace-nowrap">Correspondence Log</span>
            <div className="h-px bg-slate-200 flex-1"></div>
          </div>

          {/* Replies */}
          {ticket.replies && ticket.replies.length > 0 ? (
            <div className="space-y-6">
              {ticket.replies.map(reply => {
                const isMine = isMe(reply);
                return (
                  <div
                    key={reply.id}
                    className={`flex w-full ${isMine ? 'justify-end' : 'justify-start'}`}
                  >
                    <div className={`flex max-w-[85%] md:max-w-[70%] gap-4 ${isMine ? 'flex-row-reverse' : ''}`}>
                      <div className="flex-shrink-0 mt-auto">
                        <div className={`w-10 h-10 rounded-2xl flex items-center justify-center text-sm font-black shadow-sm ${reply.admin ? 'bg-indigo-600 text-white shadow-indigo-100' : 'bg-slate-200 text-slate-600'}`}>
                          {reply.admin ? <i className="fas fa-shield-alt"></i> : <span>{getInitials(reply.authorUsername)}</span>}
                        </div>
                      </div>

                      <div className={`space-y-2 ${isMine ? 'text-right' : 'text-left'}`}>
                        <div className="flex items-center gap-2 px-1">
                          {!isMine && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">{reply.authorUsername}</span>}
                          <span className="text-[9px] font-bold text-slate-400 uppercase tracking-tighter">{formatDateSimple(reply.createdAt)}</span>
                          {isMine && <span className="text-[10px] font-black text-slate-800 uppercase tracking-widest">You</span>}
                        </div>

                        <div className={`p-5 rounded-[1.5rem] shadow-sm text-sm font-medium leading-relaxed ${isMine ? 'bg-indigo-600 text-white rounded-tr-none' : 'bg-white text-slate-700 border border-slate-100 rounded-tl-none'}`}>
                          <div dangerouslySetInnerHTML={{ __html: reply.message }} className="prose-chat !p-0" />

                          {/* Attachments */}
                          {(reply.attachmentUrl || reply.attachmentName) && (
                            <div className="mt-4">
                              {/* Image Preview */}
                              {reply.attachmentType?.startsWith('image/') && (
                                <div
                                  className="mb-3 rounded-2xl overflow-hidden border border-white/20 shadow-md max-w-xs cursor-pointer hover:opacity-90 transition-opacity"
                                  onClick={() => globalThis.open(reply.attachmentUrl, '_blank')}
                                >
                                  <img src={reply.attachmentUrl} alt={reply.attachmentName} className="w-full h-auto object-cover max-h-60" />
                                </div>
                              )}

                              {/* RCA Document Box */}
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

                              {/* General Attachment Box */}
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
              <p className="text-gray-400 text-sm italic">
                No replies yet. Start the conversation below.
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Footer - Floating Glass Form */}
      {ticket.status !== 'CLOSED' ? (
        <div className="p-6 lg:p-10 bg-white/70 backdrop-blur-xl border-t border-slate-200/50">
          <div className="max-w-4xl mx-auto">
            <form onSubmit={handleSubmitReply} className="space-y-4">
              <div className="relative bg-white rounded-[2rem] border border-slate-200 overflow-hidden focus-within:ring-4 focus-within:ring-indigo-500/10 focus-within:border-indigo-500/30 transition-all shadow-xl shadow-slate-200/40">
                <div className="px-6 py-3 border-b border-slate-50 flex items-center justify-between bg-slate-50/30">
                  <div className="flex items-center gap-2 text-[10px] font-black text-slate-500 uppercase tracking-widest leading-none">
                    <i className="fas fa-reply text-indigo-500"></i>
                    Official Ticket Response
                  </div>
                  {attachment && (
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-indigo-50 text-indigo-700 rounded-lg border border-indigo-100 text-[9px] font-black uppercase tracking-widest">
                      <i className="fas fa-paperclip"></i>
                      <span className="max-w-[150px] truncate">{fileName}</span>
                      <button type="button" onClick={clearFile} className="hover:text-rose-500 transition-colors">
                        <i className="fas fa-times"></i>
                      </button>
                    </div>
                  )}
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
                    className="flex items-center gap-2 px-8 py-3 bg-indigo-600 text-white rounded-xl text-[11px] font-black uppercase tracking-widest shadow-lg shadow-indigo-500/30 hover:bg-indigo-700 hover:-translate-y-0.5 transition-all disabled:opacity-50 active:scale-95"
                  >
                    {isSubmitting ? <><i className="fas fa-circle-notch fa-spin"></i> Processing...</> : <><i className="fas fa-paper-plane"></i> Send Message</>}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      ) : (
        <div className="p-8 bg-slate-100/50 border-t border-slate-200/50 backdrop-blur-sm">
          <div className="max-w-md mx-auto bg-white p-4 rounded-2xl shadow-sm border border-slate-200 text-center">
            <div className="flex items-center justify-center gap-2 text-slate-400 font-bold text-[10px] uppercase tracking-[0.2em]">
              <i className="fas fa-lock"></i>
              Ticket Interaction Disabled &bull; Case Closed
            </div>
          </div>
        </div>
      )}

      {/* RCA Modal */}
      {showRcaModal && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          <div
            className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"
            onClick={() => setShowRcaModal(false)}
          ></div>

          <div className="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
            <div
              className="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-4xl sm:w-full"
              onClick={e => e.stopPropagation()}
            >
              <div className="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
                <div className="flex justify-between items-center mb-4 border-b pb-3">
                  <h3 className="text-lg leading-6 font-medium text-gray-900 flex items-center gap-2">
                    <i className="fas fa-file-medical-alt text-purple-600"></i>{' '}
                    Generate Root Cause Analysis
                  </h3>
                  <button
                    onClick={() => setShowRcaModal(false)}
                    className="text-gray-400 hover:text-gray-500"
                  >
                    <i className="fas fa-times"></i>
                  </button>
                </div>

                <div className="max-h-[70vh] overflow-y-auto pr-2 custom-scrollbar">
                  <form className="space-y-6">
                    {/* Problem Statement */}
                    <div>
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        1. Problem Statement <span className="text-red-500">*</span>
                      </label>
                      <textarea
                        value={rcaFormData.problemStatement}
                        onChange={e =>
                          setRcaFormData({
                            ...rcaFormData,
                            problemStatement: e.target.value
                          })
                        }
                        className="w-full shadow-sm border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm"
                        rows={3}
                        placeholder="Describe the problem briefly..."
                      ></textarea>
                    </div>

                    {/* Incident Overview */}
                    <div className="border-t border-gray-200 pt-4">
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        2. Incident Overview
                      </label>
                      {renderRcaSection(
                        'incidentOverview',
                        rcaFormData.incidentOverview
                      )}
                    </div>

                    {/* Description Sections */}
                    <div className="border-t border-gray-200 pt-4">
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        3. Description of the Issue
                      </label>
                      {renderRcaSection(
                        'descriptionSections',
                        rcaFormData.descriptionSections
                      )}
                    </div>

                    {/* Root Cause Sections */}
                    <div className="border-t border-gray-200 pt-4">
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        4. Root Cause Identification
                      </label>
                      {renderRcaSection(
                        'rootCauseSections',
                        rcaFormData.rootCauseSections
                      )}
                    </div>

                    {/* Resolution Sections */}
                    <div className="border-t border-gray-200 pt-4">
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        5. Resolution
                      </label>
                      {renderRcaSection(
                        'resolutionSections',
                        rcaFormData.resolutionSections
                      )}
                    </div>

                    {/* Prevention Sections */}
                    <div className="border-t border-gray-200 pt-4">
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        6. Prevention / Future Mitigation
                      </label>
                      {renderRcaSection(
                        'preventionSections',
                        rcaFormData.preventionSections
                      )}
                    </div>

                    {/* Conclusion */}
                    <div className="border-t border-gray-200 pt-4">
                      <label className="block text-sm font-bold text-gray-800 mb-2">
                        7. Conclusion
                      </label>
                      <textarea
                        value={rcaFormData.conclusion}
                        onChange={e =>
                          setRcaFormData({
                            ...rcaFormData,
                            conclusion: e.target.value
                          })
                        }
                        className="w-full shadow-sm border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm"
                        rows={3}
                        placeholder="Final summary..."
                      ></textarea>
                    </div>
                  </form>
                </div>
              </div>

              <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse border-t border-gray-200">
                <button
                  type="button"
                  onClick={handleSubmitRca}
                  disabled={rcaSubmitting}
                  className="w-full inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-purple-600 text-base font-medium text-white hover:bg-purple-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-purple-500 sm:ml-3 sm:w-auto sm:text-sm disabled:opacity-50"
                >
                  {rcaSubmitting ? (
                    <>
                      <i className="fas fa-circle-notch fa-spin mr-2"></i>{' '}
                      Generating PDF...
                    </>
                  ) : (
                    'Submit & Close Ticket'
                  )}
                </button>
                <button
                  type="button"
                  onClick={() => setShowRcaModal(false)}
                  className="mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default BillOpsTicketDetailPage;
