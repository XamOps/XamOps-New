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

  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [loading, setLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [newReply, setNewReply] = useState('');
  const [attachment, setAttachment] = useState<File | null>(null);
  const [fileName, setFileName] = useState('');

  const chatContainerRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /**
   * Quill editor modules configuration
   */
  const quillModules = {
    toolbar: [
      ['bold', 'italic', 'underline', 'strike'],
      ['blockquote', 'code-block'],
      [{ 'list': 'ordered' }, { 'list': 'bullet' }],
      ['clean']
    ]
  };

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
      alert('Error loading ticket');
      navigate('/tickets');
    } finally {
      setLoading(false);
    }
  }, [ticketId, navigate]);

  /**
   * Initialize - fetch ticket on mount
   */
  useEffect(() => {
    fetchTicket();
  }, [fetchTicket]);

  /**
   * Check if reply is from current user
   */
  const isMe = (reply: TicketReply): boolean => {
    if (reply.admin === true) return true;
    if (userProfile && userProfile.id) {
      return String(reply.authorId) === String(userProfile.id);
    }
    return false;
  };

  /**
   * Handle file selection
   */
  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      setAttachment(file);
      setFileName(file.name);
    }
  };

  /**
   * Clear selected file
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
  const submitReply = async (e: React.FormEvent) => {
    e.preventDefault();

    // Get plain text from HTML (strip tags)
    const tempDiv = document.createElement('div');
    tempDiv.innerHTML = newReply;
    const textContent = tempDiv.textContent || tempDiv.innerText || '';

    if (!textContent.trim() && !attachment) {
      alert('Reply cannot be empty. Please enter text or attach a file.');
      return;
    }

    if (!userProfile?.id) {
      alert('User not identified. Please refresh and log in.');
      return;
    }

    setIsSubmitting(true);

    const formData = new FormData();
    formData.append('message', textContent.trim() ? newReply : '');
    formData.append('ticketId', ticketId!);
    formData.append('authorId', String(userProfile.id));

    if (attachment) {
      formData.append('file', attachment);
    }

    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}/replies`, {
        credentials: 'include',
        method: 'POST',
        body: formData
      });

      if (!response.ok) {
        throw new Error('Failed to send reply');
      }

      const updatedTicket = await response.json();
      setTicket(updatedTicket);

      // Reset form
      setNewReply('');
      clearFile();

      // Scroll to bottom
      setTimeout(() => {
        if (chatContainerRef.current) {
          chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
        }
      }, 100);
    } catch (err) {
      console.error('Error submitting reply:', err);
      alert('Error sending reply. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  /**
   * Close ticket
   */
  const closeTicket = async () => {
    if (!window.confirm('Are you sure you want to close this ticket?')) {
      return;
    }

    try {
      const response = await fetch(`/api/xamops/tickets/${ticketId}/close`, {
        method: 'POST',
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error('Failed to close ticket');
      }

      const updatedTicket = await response.json();
      setTicket(updatedTicket);
    } catch (err) {
      console.error('Error closing ticket:', err);
      alert('Error closing ticket. Please try again.');
    }
  };

  /**
   * Format date
   */
  const formatDate = (dateString: string): string => {
    if (!dateString) return '';
    return new Date(dateString).toLocaleString();
  };

  /**
   * Format date simple (time only)
   */
  const formatDateSimple = (dateString: string): string => {
    if (!dateString) return '';
    const date = new Date(dateString);
    return date.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  };

  /**
   * Get initials from username
   */
  const getInitials = (name: string): string => {
    if (!name) return 'U';
    return name.charAt(0).toUpperCase();
  };

  /**
   * Go back to tickets list
   */
  const goBackToTickets = () => {
    navigate('/tickets');
  };

  /**
   * Get status badge class
   */
  const getStatusClass = (status: string): string => {
    switch (status) {
      case 'OPEN':
        return 'bg-green-100 text-green-800';
      case 'IN_PROGRESS':
        return 'bg-yellow-100 text-yellow-800';
      case 'CLOSED':
        return 'bg-gray-200 text-gray-800';
      default:
        return 'bg-blue-100 text-blue-800';
    }
  };

  /**
   * Get severity color class
   */
  const getSeverityClass = (severity: string): string => {
    if (['High', 'Critical', 'Urgent'].includes(severity)) {
      return 'text-red-600';
    }
    if (['Normal', 'Medium'].includes(severity)) {
      return 'text-orange-500';
    }
    return 'text-blue-500';
  };

  if (loading) {
    return (
      <div className="ticket-detail-page">
        <div className="loading-container">
          <i className="fas fa-spinner fa-spin text-5xl text-indigo-600"></i>
          <p className="mt-4 text-gray-600">Loading ticket...</p>
        </div>
      </div>
    );
  }

  if (!ticket) {
    return (
      <div className="ticket-detail-page">
        <div className="error-container">
          <i className="fas fa-exclamation-circle text-5xl text-red-500 mb-4"></i>
          <h2 className="text-xl font-bold text-gray-900 mb-2">Ticket Not Found</h2>
          <button onClick={goBackToTickets} className="btn-primary mt-4">
            <i className="fas fa-arrow-left mr-2"></i>
            Back to Tickets
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="ticket-detail-page">
      {/* Header */}
      <header className="ticket-detail-header">
        <div className="header-content">
          <div className="header-left">
            <button onClick={goBackToTickets} className="back-button">
              <i className="fas fa-arrow-left mr-2"></i>
              Back to List
            </button>
            <div>
              <h1 className="ticket-title">{ticket.subject}</h1>
              <div className="ticket-metadata">
                <span className="ticket-id">#{ticket.id}</span>
                <span className="separator">•</span>
                <span className="metadata-badge">{ticket.category}</span>
                <span className="metadata-text">{ticket.service}</span>
                {ticket.accountId && (
                  <>
                    <span className="separator">•</span>
                    <span className="account-text">Account: {ticket.accountId}</span>
                  </>
                )}
                <span className="separator">•</span>
                <span className="date-text">Created: {formatDate(ticket.createdAt)}</span>
              </div>
            </div>
          </div>

          <div className="header-right">
            <span className={`status-badge ${getStatusClass(ticket.status)}`}>
              {ticket.status.replace('_', ' ')}
            </span>
            {ticket.status !== 'CLOSED' && (
              <button onClick={closeTicket} className="close-ticket-btn">
                Close
              </button>
            )}
          </div>
        </div>
      </header>

      {/* Chat Container */}
      <div ref={chatContainerRef} className="chat-container">
        <div className="chat-content">
          {/* Original Request */}
          <div className="original-request">
            <div className="request-avatar">
              <i className="fas fa-user"></i>
            </div>
            <div className="request-content">
              <div className="request-header">
                <h3 className="request-title">Customer Request</h3>
                <span className="request-date">{formatDate(ticket.createdAt)}</span>
              </div>
              <div className="request-description">{ticket.description}</div>
              <div className="request-metadata">
                <div>
                  <strong>Region:</strong> {ticket.region || 'Global'}
                </div>
                <div>
                  <strong>Severity:</strong>{' '}
                  <span className={getSeverityClass(ticket.severity)}>{ticket.severity}</span>
                </div>
              </div>
            </div>
          </div>

          {/* Divider */}
          <div className="replies-divider">
            <span>Responses</span>
          </div>

          {/* Replies */}
          {ticket.replies.length > 0 ? (
            <div className="replies-container">
              {ticket.replies.map((reply) => (
                <div
                  key={reply.id}
                  className={`reply-wrapper ${isMe(reply) ? 'reply-mine' : 'reply-theirs'}`}
                >
                  <div className="reply-content">
                    <div className="reply-avatar">
                      {reply.admin ? (
                        <i className="fas fa-shield-alt"></i>
                      ) : (
                        <span>{getInitials(reply.authorUsername)}</span>
                      )}
                    </div>

                    <div className="reply-body">
                      <div className="reply-header">
                        <span className="reply-author">{reply.authorUsername}</span>
                        <span className="reply-time">{formatDateSimple(reply.createdAt)}</span>
                      </div>

                      <div className={`reply-message ${isMe(reply) ? 'message-mine' : 'message-theirs'}`}>
                        <div
                          className="message-content"
                          dangerouslySetInnerHTML={{ __html: reply.message }}
                        />

                        {(reply.attachmentUrl || reply.fileName) && (
                          <div
                            className={`attachment ${isMe(reply) ? 'attachment-mine' : 'attachment-theirs'}`}
                            onClick={() => window.open(reply.attachmentUrl, '_blank')}
                          >
                            <div className="attachment-icon">
                              <i className="fas fa-file-invoice"></i>
                            </div>
                            <div className="attachment-info">
                              <p className="attachment-name">{reply.fileName || 'Attachment'}</p>
                              <p className="attachment-action">Click to view</p>
                            </div>
                            <div className="attachment-link">
                              <i className="fas fa-external-link-alt"></i>
                            </div>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="no-replies">
              <p>No replies yet. Start the conversation below.</p>
            </div>
          )}
        </div>
      </div>

      {/* Reply Footer */}
      {ticket.status !== 'CLOSED' && (
        <footer className="reply-footer">
          <div className="footer-content">
            <form onSubmit={submitReply}>
              <div className="editor-container">
                <div className="editor-label">
                  <i className="fas fa-reply mr-2"></i>
                  Replying
                </div>
                <ReactQuill
                  value={newReply}
                  onChange={setNewReply}
                  modules={quillModules}
                  placeholder="Type an official response..."
                  theme="snow"
                />
                {attachment && (
                  <div className="attachment-badge">
                    <i className="fas fa-paperclip mr-2"></i>
                    <span className="attachment-filename">{fileName}</span>
                    <button type="button" onClick={clearFile} className="remove-attachment">
                      <i className="fas fa-times"></i>
                    </button>
                  </div>
                )}
              </div>

              <div className="footer-actions">
                <label className="attach-button">
                  <i className="fas fa-paperclip mr-2"></i>
                  <span>Attach File</span>
                  <input
                    ref={fileInputRef}
                    type="file"
                    className="hidden"
                    onChange={handleFile}
                  />
                </label>

                <div className="action-buttons">
                  <button type="button" onClick={closeTicket} className="btn-close-ticket">
                    Close Ticket
                  </button>
                  <button
                    type="submit"
                    disabled={isSubmitting || (!newReply.replace(/<[^>]*>/g, '').trim() && !attachment)}
                    className="btn-send"
                  >
                    {isSubmitting ? (
                      <>
                        <i className="fas fa-circle-notch fa-spin mr-2"></i>
                        Sending...
                      </>
                    ) : (
                      'Send Response'
                    )}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </footer>
      )}
    </div>
  );
};

export default TicketDetailPage;
