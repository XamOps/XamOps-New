/**
 * Custom hook for managing BillOps ticket detail page
 *
 * Handles:
 * - Fetching ticket details with replies
 * - Current user profile
 * - Submitting replies with attachments (including internal admin notes)
 * - Closing tickets
 * - Generating RCA reports
 * - Ticket assignment to admins
 *
 * Converted from: billops/ticket_detail.html
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  BillOpsTicketDetail,
  BillOpsUserProfile,
  RCAFormData,
  AdminUser
} from '../types/billopsTickets';
import {
  initNotificationService,
  notifyNewTicketReply,
  notifyTicketStatusChange
} from '../utils/notificationService';

interface UseBillOpsTicketDetailReturn {
  ticket: BillOpsTicketDetail | null;
  currentUser: BillOpsUserProfile | null;
  loading: boolean;
  isSubmitting: boolean;
  error: string | null;
  isAdmin: boolean;
  adminUsers: AdminUser[];
  fetchTicketDetails: () => Promise<void>;
  submitReply: (message: string, file: File | null, isInternal?: boolean) => Promise<void>;
  closeTicket: () => Promise<void>;
  submitRCA: (rcaData: RCAFormData) => Promise<void>;
  assignTicket: (adminId: number, adminUsername: string) => Promise<void>;
  sendEmailThread: (recipientEmails: string[], sendMode?: 'separate' | 'combined') => Promise<void>;
  editReply: (replyId: number, message: string) => Promise<void>;
  deleteReply: (replyId: number) => Promise<void>;
}

export const useBillOpsTicketDetail = (
  ticketId: string | null
): UseBillOpsTicketDetailReturn => {
  const [ticket, setTicket] = useState<BillOpsTicketDetail | null>(null);
  const [currentUser, setCurrentUser] = useState<BillOpsUserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adminUsers, setAdminUsers] = useState<AdminUser[]>([]);

  // Refs for polling and notification tracking
  const previousReplyCountRef = useRef<number>(0);
  const previousStatusRef = useRef<string | null>(null);
  const pollingIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const isInitialLoadRef = useRef<boolean>(true);

  /**
   * Check if current user is admin
   */
  const isAdmin =
    currentUser?.roles?.includes('ROLE_BILLOPS_ADMIN') || false;

  /**
   * Fetch current user profile
   */
  const fetchCurrentUser = useCallback(async () => {
    try {
      const response = await fetch('/api/billops/profile', {
        credentials: 'include'
      });
      if (response.ok) {
        const userData = await response.json();
        setCurrentUser({
          ...userData,
          roles: userData.roles || []
        });
      }
    } catch (e) {
      console.error('User profile error', e);
    }
  }, []);

  /**
   * Fetch admin users for assignment dropdown (admin only)
   */
  const fetchAdminUsers = useCallback(async () => {
    try {
      const response = await fetch('/api/billops/admins', {
        credentials: 'include'
      });
      if (response.ok) {
        const data = await response.json();
        setAdminUsers(data);
      }
    } catch (e) {
      console.error('Admin users fetch error', e);
    }
  }, []);

  /**
   * Fetch ticket details
   */
  const fetchTicketDetails = useCallback(async () => {
    if (!ticketId) return;

    try {
      setLoading(true);
      setError(null);

      const response = await fetch(`/api/billops/tickets/${ticketId}`, {
        credentials: 'include'
      });

      if (response.status === 401 || response.status === 403) {
        window.location.href = '/login';
        return;
      }

      if (!response.ok) {
        throw new Error('Failed to fetch ticket');
      }

      const ticketData = await response.json();
      setTicket(ticketData);
    } catch (err) {
      console.error('Error:', err);
      setError('Could not load ticket details.');
    } finally {
      setLoading(false);
    }
  }, [ticketId]);

  /**
   * Submit a reply to the ticket (optionally as an internal admin note)
   */
  const submitReply = useCallback(
    async (message: string, file: File | null, isInternal = false) => {
      if (!ticketId || !currentUser) return;

      setIsSubmitting(true);

      try {
        const formData = new FormData();
        formData.append('message', message);
        formData.append('ticketId', ticketId);
        formData.append('authorId', currentUser.id.toString());
        formData.append('isInternal', isInternal.toString());

        if (file) {
          formData.append('file', file);
        }

        const response = await fetch(
          `/api/billops/tickets/${ticketId}/replies`,
          {
            credentials: 'include',
            method: 'POST',
            body: formData
          }
        );

        if (!response.ok) {
          throw new Error('Failed to send reply.');
        }

        const updatedTicket = await response.json();
        setTicket(updatedTicket);
        window.dispatchEvent(new CustomEvent('ticket:updated'));
      } catch (err) {
        console.error('Error submitting reply:', err);
        throw err;
      } finally {
        setIsSubmitting(false);
      }
    },
    [ticketId, currentUser]
  );

  /**
   * Close the ticket
   */
  const closeTicket = useCallback(async () => {
    if (!ticketId) return;

    try {
      const response = await fetch(`/api/billops/tickets/${ticketId}/close`, {
        method: 'POST',
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error('Failed to close ticket');
      }

      const updatedTicket = await response.json();
      setTicket(updatedTicket);
      window.dispatchEvent(new CustomEvent('ticket:updated'));
    } catch (err) {
      console.error('Error closing ticket:', err);
      throw err;
    }
  }, [ticketId]);

  /**
   * Submit RCA report and close ticket
   */
  const submitRCA = useCallback(
    async (rcaData: RCAFormData) => {
      if (!ticketId) return;

      try {
        const response = await fetch(`/api/billops/tickets/${ticketId}/rca`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          credentials: 'include',
          body: JSON.stringify(rcaData)
        });

        if (!response.ok) {
          const err = await response.json().catch(() => ({}));
          throw new Error((err as any).message || 'Failed to submit RCA');
        }

        // Refresh ticket details to show the new system message
        await fetchTicketDetails();
      } catch (err) {
        console.error('Error submitting RCA:', err);
        throw err;
      }
    },
    [ticketId, fetchTicketDetails]
  );

  /**
   * Send the full ticket conversation thread as an email to the given address
   */
  const sendEmailThread = useCallback(
    async (recipientEmails: string[], sendMode: 'separate' | 'combined' = 'separate') => {
      if (!ticketId) return;

      const response = await fetch(`/api/billops/tickets/${ticketId}/email`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ recipientEmails, sendMode })
      });

      if (!response.ok) {
        throw new Error('Failed to send email thread');
      }
    },
    [ticketId]
  );

  /**
   * Assign ticket to a specific admin
   */
  const assignTicket = useCallback(
    async (adminId: number, adminUsername: string) => {
      if (!ticketId) return;

      try {
        const response = await fetch(`/api/billops/tickets/${ticketId}/assign`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({ assignedToId: adminId, assignedToUsername: adminUsername })
        });

        if (!response.ok) {
          throw new Error('Failed to assign ticket');
        }

        // Update local ticket state optimistically
        setTicket(prev => prev ? {
          ...prev,
          assignedToId: adminId,
          assignedToUsername: adminUsername
        } : prev);
        window.dispatchEvent(new CustomEvent('ticket:updated'));
      } catch (err) {
        console.error('Error assigning ticket:', err);
        throw err;
      }
    },
    [ticketId]
  );

  /**
   * Edit a ticket reply (within 15 min of sending)
   */
  const editReply = useCallback(
    async (replyId: number, message: string) => {
      if (!ticketId) return;

      try {
        const response = await fetch(`/api/billops/tickets/replies/${replyId}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({ message })
        });

        if (!response.ok) throw new Error('Failed to edit reply');
        await fetchTicketDetails();
      } catch (err) {
        console.error('Error editing reply:', err);
        throw err;
      }
    },
    [ticketId, fetchTicketDetails]
  );

  /**
   * Delete a ticket reply (soft delete)
   */
  const deleteReply = useCallback(
    async (replyId: number) => {
      if (!ticketId) return;

      // Optimistic update
      setTicket(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          replies: prev.replies.map(r =>
            r.id === replyId ? { ...r, deleted: true } : r
          )
        };
      });

      try {
        const response = await fetch(`/api/billops/tickets/replies/${replyId}`, {
          method: 'DELETE',
          credentials: 'include'
        });

        if (!response.ok) {
          await fetchTicketDetails(); // revert on failure
          throw new Error('Failed to delete reply');
        }
        await fetchTicketDetails();
      } catch (err) {
        console.error('Error deleting reply:', err);
        throw err;
      }
    },
    [ticketId, fetchTicketDetails]
  );

  /**
   * Initialize: Fetch user and ticket on mount
   */
  useEffect(() => {
    if (ticketId) {
      fetchCurrentUser().then(() => {
        fetchTicketDetails();
      });
    }
  }, [ticketId, fetchCurrentUser, fetchTicketDetails]);

  /**
   * Fetch admin users once we know the current user is an admin
   */
  useEffect(() => {
    if (isAdmin) {
      fetchAdminUsers();
    }
  }, [isAdmin, fetchAdminUsers]);

  /**
   * Listen for browser back/forward navigation
   */
  useEffect(() => {
    const handlePopState = () => {
      console.log('[Ticket Detail] Popstate event detected, reloading ticket details');
      fetchTicketDetails();
    };

    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
    };
  }, [fetchTicketDetails]);

  /**
   * Initialize notification service
   */
  useEffect(() => {
    initNotificationService();
  }, []);

  /**
   * Poll for new replies and trigger notifications
   */
  useEffect(() => {
    if (!ticketId) return;

    // Set up polling interval (every 10 seconds)
    pollingIntervalRef.current = setInterval(async () => {
      try {
        const response = await fetch(`/api/billops/tickets/${ticketId}`, {
          credentials: 'include'
        });

        if (!response.ok) return;

        const ticketData: BillOpsTicketDetail = await response.json();
        const currentReplyCount = ticketData.replies?.length || 0;

        // Check for status change (skip initial load)
        if (
          !isInitialLoadRef.current &&
          previousStatusRef.current &&
          ticketData.status !== previousStatusRef.current &&
          document.hidden
        ) {
          notifyTicketStatusChange(ticketId, ticketData.status, ticketData.subject);
          setTicket(ticketData);
        }

        // Check for new replies (skip initial load)
        if (!isInitialLoadRef.current && currentReplyCount > previousReplyCountRef.current) {
          const newReplies = ticketData.replies?.slice(previousReplyCountRef.current) || [];

          // Notify only for replies not from current user and when page is hidden
          for (const reply of newReplies) {
            if (reply.authorId !== currentUser?.id && document.hidden) {
              const plainText = reply.message.replaceAll(/<[^>]*>/g, '');
              notifyNewTicketReply(ticketId, plainText);
              break; // Only one notification for batch of new replies
            }
          }

          // Update ticket state with new data
          setTicket(ticketData);
        }

        // Update tracking refs
        previousReplyCountRef.current = currentReplyCount;
        previousStatusRef.current = ticketData.status;
        isInitialLoadRef.current = false;
      } catch (err) {
        console.error('[Ticket Detail] Polling error:', err);
      }
    }, 10000); // 10 second polling interval

    return () => {
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, [ticketId, currentUser?.id]);

  /**
   * Update tracking refs when ticket changes
   */
  useEffect(() => {
    if (ticket) {
      if (ticket.replies) {
        previousReplyCountRef.current = ticket.replies.length;
      }
      if (ticket.status) {
        previousStatusRef.current = ticket.status;
      }
    }
  }, [ticket]);

  return {
    ticket,
    currentUser,
    loading,
    isSubmitting,
    error,
    isAdmin,
    adminUsers,
    fetchTicketDetails,
    submitReply,
    closeTicket,
    submitRCA,
    assignTicket,
    sendEmailThread,
    editReply,
    deleteReply
  };
};
