/**
 * Custom hook for managing BillOps ticket detail page
 *
 * Handles:
 * - Fetching ticket details with replies
 * - Current user profile
 * - Submitting replies with attachments
 * - Closing tickets
 * - Generating RCA reports
 *
 * Converted from: billops/ticket_detail.html
 */

import { useState, useEffect, useCallback } from 'react';
import {
  BillOpsTicketDetail,
  BillOpsUserProfile,
  RCAFormData
} from '../types/billopsTickets';

interface UseBillOpsTicketDetailReturn {
  ticket: BillOpsTicketDetail | null;
  currentUser: BillOpsUserProfile | null;
  loading: boolean;
  isSubmitting: boolean;
  error: string | null;
  isAdmin: boolean;
  fetchTicketDetails: () => Promise<void>;
  submitReply: (message: string, file: File | null) => Promise<void>;
  closeTicket: () => Promise<void>;
  submitRCA: (rcaData: RCAFormData) => Promise<void>;
}

export const useBillOpsTicketDetail = (
  ticketId: string | null
): UseBillOpsTicketDetailReturn => {
  const [ticket, setTicket] = useState<BillOpsTicketDetail | null>(null);
  const [currentUser, setCurrentUser] = useState<BillOpsUserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
   * Submit a reply to the ticket
   */
  const submitReply = useCallback(
    async (message: string, file: File | null) => {
      if (!ticketId || !currentUser) return;

      setIsSubmitting(true);

      try {
        const formData = new FormData();
        formData.append('message', message);
        formData.append('ticketId', ticketId);
        formData.append('authorId', currentUser.id.toString());

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
          throw new Error(err.message || 'Failed to submit RCA');
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

  return {
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
  };
};
