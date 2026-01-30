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

import React, { useState, useEffect, useRef, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useBillOpsTicketDetail } from '../hooks/useBillOpsTicketDetail';
import { RCAFormData, RCAFormSection } from '../types/billopsTickets';
import Quill from 'quill';
import 'quill/dist/quill.snow.css';

const BillOpsTicketDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const ticketId = searchParams.get('id');

  const {
    ticket,
    currentUser,
    loading,
    isSubmitting,
    error,
    isAdmin,
    fetchTicketDetails,
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
   * Initialize Quill editor
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

  /**
   * Format date
   */
  const formatDate = (dateString: string): string => {
    if (!dateString) return '';
    return new Date(dateString).toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  /**
   * Format date (simple - for chat bubbles)
   */
  const formatDateSimple = (dateString: string): string => {
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
  };

  /**
   * Get status badge classes
   */
  const getStatusClasses = (status: string): string => {
    switch (status) {
      case 'OPEN':
        return 'bg-green-100 text-green-800';
      case 'CLOSED':
        return 'bg-gray-100 text-gray-800';
      case 'IN_PROGRESS':
        return 'bg-yellow-100 text-yellow-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  /**
   * Check if reply is from current user
   */
  const isMe = (reply: any): boolean => {
    if (!currentUser?.id) return false;
    return String(reply.authorId) === String(currentUser.id);
  };

  /**
   * Get initials from username
   */
  const getInitials = (name: string): string => {
    if (!name) return 'U';
    return name.charAt(0).toUpperCase();
  };

  /**
   * Handle file selection
   */
  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) {
      if (file.size > 10 * 1024 * 1024) {
        alert('File is too large. Max size is 10MB.');
        return;
      }
      setAttachment(file);
      setFileName(file.name);
    }
  };

  /**
   * Clear file attachment
   */
  const clearFile = () => {
    setAttachment(null);
    setFileName('');
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

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
      alert('Error sending reply. Please try again.');
    }
  };

  /**
   * Handle close ticket
   */
  const handleCloseTicket = async () => {
    if (confirm('Are you sure you want to close this ticket?')) {
      try {
        await closeTicket();
      } catch (err) {
        alert('Error closing ticket. Please try again.');
      }
    }
  };

  /**
   * Go back to tickets list
   * Navigates to admin tickets if user is admin and came from admin tickets page
   */
  const goBackToTickets = () => {
    // Check if we can go back in history and if the referrer contains admin tickets
    if (window.history.length > 1) {
      const referrer = document.referrer;
      if (referrer.includes('/billops-admin-tickets') || referrer.includes('/billops/admin_tickets')) {
        navigate('/billops-admin-tickets');
        return;
      }
      if (referrer.includes('/billops-tickets') || referrer.includes('/billops/tickets')) {
        navigate('/billops-tickets');
        return;
      }
    }

    // Fallback: Navigate based on user role
    if (isAdmin) {
      navigate('/billops-admin-tickets');
    } else {
      navigate('/billops-tickets');
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
      alert('Problem Statement is required.');
      return;
    }

    if (
      !confirm(
        'This will generate an RCA report and CLOSE the ticket. Continue?'
      )
    ) {
      return;
    }

    setRcaSubmitting(true);

    try {
      await submitRCA(rcaFormData);
      alert('RCA Generated successfully. Ticket Closed.');
      setShowRcaModal(false);
    } catch (err: any) {
      alert('Failed to submit RCA: ' + (err.message || 'Unknown error'));
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
    <div className="billops-ticket-detail-page flex flex-col h-full">
      {/* Header */}
      <header className="bg-white shadow-sm z-10 border-b border-gray-200 px-6 py-4 flex-shrink-0">
        <div className="max-w-5xl mx-auto flex justify-between items-center">
          <div className="flex items-center gap-4">
            <button
              onClick={goBackToTickets}
              className="text-gray-500 hover:text-indigo-600 transition cursor-pointer"
            >
              <i className="fas fa-arrow-left"></i> Back to Tickets
            </button>
            <div>
              <h1 className="text-xl font-bold text-gray-900 flex items-center gap-3">
                <span>{ticket.subject}</span>
                <span
                  className={`px-2.5 py-0.5 rounded-full text-xs font-medium uppercase tracking-wide ${getStatusClasses(
                    ticket.status
                  )}`}
                >
                  {ticket.status}
                </span>
              </h1>
              <p className="text-xs text-gray-500 mt-1">
                <span>#{ticket.id}</span> &bull; <span>{ticket.service}</span>{' '}
                &bull; <span>{formatDate(ticket.createdAt)}</span>
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {isAdmin && ticket.status !== 'CLOSED' && (
              <button
                onClick={() => setShowRcaModal(true)}
                className="bg-purple-600 hover:bg-purple-700 text-white text-sm font-medium py-2 px-4 rounded transition flex items-center gap-2"
                title="Generate RCA Report and Close Ticket"
              >
                <i className="fas fa-file-medical-alt"></i> Generate RCA
              </button>
            )}

            {ticket.status !== 'CLOSED' && (
              <button
                onClick={handleCloseTicket}
                className="text-gray-500 hover:text-red-600 text-sm font-medium transition flex items-center gap-2 px-3 py-2 rounded hover:bg-gray-50"
              >
                <i className="far fa-times-circle"></i> Close Ticket
              </button>
            )}
          </div>
        </div>
      </header>

      {/* Chat Container */}
      <div
        ref={chatContainerRef}
        className="flex-1 overflow-y-auto bg-gray-100 p-6 scroll-smooth"
      >
        <div className="max-w-5xl mx-auto space-y-6 pb-6">
          {/* Original Ticket Description */}
          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6 relative overflow-hidden">
            <div className="absolute top-0 left-0 w-1 h-full bg-indigo-500"></div>
            <div className="flex items-start gap-4">
              <div className="flex-shrink-0">
                <div className="w-10 h-10 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-700 font-bold">
                  <i className="fas fa-ticket-alt"></i>
                </div>
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex justify-between items-baseline mb-2">
                  <h3 className="text-sm font-semibold text-gray-900">
                    Ticket Description
                  </h3>
                  <span className="text-xs text-gray-400">Original Request</span>
                </div>
                <div className="prose prose-sm max-w-none text-gray-700">
                  {ticket.description}
                </div>
              </div>
            </div>
          </div>

          {/* Divider */}
          <div className="relative flex items-center justify-center py-2">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-gray-300"></div>
            </div>
            <span className="relative bg-gray-100 px-4 text-xs text-gray-500 font-medium uppercase tracking-widest">
              Discussion History
            </span>
          </div>

          {/* Replies */}
          {ticket.replies && ticket.replies.length > 0 ? (
            ticket.replies.map(reply => {
              const isMine = isMe(reply);
              return (
                <div
                  key={reply.id}
                  className={`flex w-full ${isMine ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`flex max-w-[85%] md:max-w-[75%] gap-3 ${
                      isMine ? 'flex-row-reverse' : 'flex-row'
                    }`}
                  >
                    {/* Avatar */}
                    <div className="flex-shrink-0 mt-auto">
                      <div
                        className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold shadow-sm ${
                          reply.admin
                            ? 'bg-indigo-100 text-indigo-700'
                            : 'bg-gray-200 text-gray-600'
                        }`}
                      >
                        {reply.admin ? (
                          <i className="fas fa-headset"></i>
                        ) : (
                          <span>{getInitials(reply.authorUsername)}</span>
                        )}
                      </div>
                    </div>

                    {/* Message Bubble */}
                    <div>
                      <div
                        className={`flex items-center gap-2 mb-1 px-1 ${
                          isMine ? 'justify-end' : 'justify-start'
                        }`}
                      >
                        <span className="text-xs font-semibold text-gray-600">
                          {reply.admin ? 'Support Agent' : reply.authorUsername}
                        </span>
                        <span className="text-[10px] text-gray-400">
                          {formatDateSimple(reply.createdAt)}
                        </span>
                      </div>

                      <div
                        className={`shadow-sm px-5 py-3 relative ${
                          isMine
                            ? 'bg-indigo-600 text-white rounded-2xl rounded-tr-none'
                            : 'bg-white text-gray-800 border border-gray-200 rounded-2xl rounded-tl-none'
                        }`}
                      >
                        <div
                          className={`prose-chat ql-editor !p-0 !min-h-0 ${
                            isMine ? 'prose-chat-inverse' : ''
                          }`}
                          dangerouslySetInnerHTML={{ __html: reply.message }}
                        />

                        {/* Attachments */}
                        {(reply.attachmentUrl || reply.attachmentName) && (
                          <div className="mt-3">
                            {/* Image Preview */}
                            {reply.attachmentType?.startsWith('image/') && (
                              <div
                                className="mb-2 rounded-lg overflow-hidden border border-gray-200 shadow-sm bg-gray-50 max-w-xs cursor-pointer hover:opacity-90 transition-opacity"
                                onClick={() =>
                                  window.open(reply.attachmentUrl, '_blank')
                                }
                              >
                                <img
                                  src={reply.attachmentUrl}
                                  alt={reply.attachmentName}
                                  className="w-full h-auto object-cover max-h-60"
                                />
                              </div>
                            )}

                            {/* RCA Document Box */}
                            {reply.attachmentName?.startsWith('rca_ticket_') && (
                              <div
                                className="group cursor-pointer bg-white rounded-lg overflow-hidden border border-gray-200 hover:border-purple-300 transition-colors shadow-sm"
                                onClick={() =>
                                  reply.attachmentUrl
                                    ? window.open(reply.attachmentUrl, '_blank')
                                    : alert('File URL not found')
                                }
                              >
                                <div className="flex items-center p-3 bg-purple-50/50">
                                  <div className="p-2 bg-purple-100 text-purple-600 rounded-lg mr-3 shadow-sm">
                                    <i className="fas fa-file-medical-alt text-xl"></i>
                                  </div>
                                  <div className="flex-1">
                                    <h4 className="text-sm font-bold text-purple-900 leading-tight">
                                      RCA Report Generated
                                    </h4>
                                    <p className="text-xs text-purple-700 mt-0.5">
                                      Click to download PDF
                                    </p>
                                  </div>
                                  <div className="text-purple-400 group-hover:text-purple-600 transition-colors bg-white rounded-full p-1.5 shadow-sm">
                                    <i className="fas fa-download"></i>
                                  </div>
                                </div>
                              </div>
                            )}

                            {/* General Attachment Box */}
                            {(!reply.attachmentName ||
                              !reply.attachmentName.startsWith('rca_ticket_')) && (
                              <div
                                className={`flex items-center p-2 rounded-lg transition-colors cursor-pointer ${
                                  isMine
                                    ? 'bg-white/20 border border-white/30 hover:bg-white/40'
                                    : 'bg-gray-50 border border-gray-200 hover:bg-gray-100'
                                }`}
                                onClick={() =>
                                  reply.attachmentUrl
                                    ? window.open(reply.attachmentUrl, '_blank')
                                    : alert('File URL not found')
                                }
                              >
                                <div
                                  className={`p-2 rounded mr-3 ${
                                    isMine
                                      ? 'bg-white/20 text-white'
                                      : 'bg-white text-indigo-600 shadow-sm'
                                  }`}
                                >
                                  {reply.attachmentType?.startsWith('image/') ? (
                                    <i className="fas fa-file-image text-lg"></i>
                                  ) : reply.attachmentType?.includes('pdf') ? (
                                    <i className="fas fa-file-pdf text-lg"></i>
                                  ) : (
                                    <i className="fas fa-file-alt text-lg"></i>
                                  )}
                                </div>
                                <div className="flex-1 min-w-0 overflow-hidden">
                                  <p className="text-sm font-medium truncate">
                                    {reply.attachmentName || 'Attached File'}
                                  </p>
                                  <p className="text-[10px] opacity-80">
                                    {reply.attachmentUrl
                                      ? 'Click to view / download'
                                      : 'Attachment processing...'}
                                  </p>
                                </div>
                                <div className="ml-2">
                                  <i className="fas fa-download text-xs opacity-70"></i>
                                </div>
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              );
            })
          ) : (
            <div className="text-center py-10">
              <p className="text-gray-400 text-sm italic">
                No replies yet. Start the conversation below.
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Footer - Reply Form or Closed Message */}
      {ticket.status !== 'CLOSED' ? (
        <footer className="bg-white border-t border-gray-200 p-6 flex-shrink-0">
          <div className="max-w-5xl mx-auto">
            <form onSubmit={handleSubmitReply}>
              <div className="mb-4 relative">
                <div ref={quillRef} className="bg-white"></div>

                {attachment && (
                  <div className="absolute bottom-2 right-2 bg-indigo-50 border border-indigo-100 text-indigo-700 px-3 py-1 rounded-full text-xs flex items-center shadow-sm z-10">
                    <i className="fas fa-paperclip mr-2"></i>
                    <span className="max-w-[150px] truncate">{fileName}</span>
                    <button
                      type="button"
                      onClick={clearFile}
                      className="ml-2 text-indigo-400 hover:text-indigo-900"
                    >
                      <i className="fas fa-times"></i>
                    </button>
                  </div>
                )}
              </div>

              <div className="flex justify-between items-center">
                <div className="flex items-center">
                  <label className="cursor-pointer text-gray-500 hover:text-indigo-600 transition flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-gray-50">
                    <i className="fas fa-paperclip text-lg"></i>
                    <span className="text-sm font-medium">Attach File</span>
                    <input
                      ref={fileInputRef}
                      type="file"
                      className="hidden"
                      onChange={handleFileSelect}
                    />
                  </label>
                </div>

                <button
                  type="submit"
                  disabled={isSubmitting || (!newReplyHasContent && !attachment)}
                  className="bg-indigo-600 text-white px-6 py-2.5 rounded-lg font-semibold shadow-md hover:bg-indigo-700 hover:shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  {isSubmitting ? (
                    <>
                      <i className="fas fa-circle-notch fa-spin"></i> Sending...
                    </>
                  ) : (
                    <>
                      Send Reply <i className="fas fa-paper-plane ml-1"></i>
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </footer>
      ) : (
        <div className="bg-gray-50 border-t border-gray-200 p-6 text-center flex-shrink-0">
          <div className="inline-flex items-center gap-2 text-gray-500 font-medium bg-white px-4 py-2 rounded-full shadow-sm border border-gray-200">
            <i className="fas fa-lock"></i> This ticket is closed.
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
