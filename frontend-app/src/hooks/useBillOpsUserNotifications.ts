/**
 * useBillOpsUserNotifications Hook
 *
 * Custom hook for BillOps User notifications
 * Shows notifications relevant to the user:
 * - Their own tickets (tickets they created)
 * - Unread chat messages (admin replies to them)
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { BillOpsTicket } from '../types/billopsTickets';

export type BillOpsUserNotificationType = 'ticket' | 'chat' | 'invoice';

export interface BillOpsUserNotificationItem {
  id: string;
  type: BillOpsUserNotificationType;
  title: string;
  description: string;
  timestamp: Date;
  link?: string;
  isUnread?: boolean;
  metadata?: {
    ticketId?: number;
    invoiceId?: number;
    invoiceNumber?: string;
    amount?: number;
    status?: string;
    severity?: string;
  };
}

export interface BillOpsUserNotificationCounts {
  total: number;
  tickets: number;
  chat: number;
  invoices: number;
}

interface ChatHistoryItem {
  content: string;
  userMessage: boolean;
  timestamp: string;
}

interface Invoice {
  id: number;
  invoiceNumber: string;
  accountName: string;
  billingPeriod: string;
  invoiceDate: string;
  amount: number;
  status: 'DRAFT' | 'FINALIZED' | 'PAID' | 'OVERDUE';
}

interface UseBillOpsUserNotificationsReturn {
  notifications: BillOpsUserNotificationItem[];
  counts: BillOpsUserNotificationCounts;
  isLoading: boolean;
  refresh: () => Promise<void>;
}

const CHAT_NOTIFICATION_WINDOW = 24 * 60 * 60 * 1000; // Show chat from last 24 hours
const AUTO_REFRESH_INTERVAL = 30000; // 30 seconds
const TICKET_NOTIFICATION_WINDOW = 7 * 24 * 60 * 60 * 1000; // Show tickets from last 7 days
const INVOICE_NOTIFICATION_WINDOW = 30 * 24 * 60 * 60 * 1000; // Show invoices from last 30 days

/**
 * Get the last time the chat was opened from localStorage
 */
const getLastChatOpenTime = (): number => {
  try {
    const stored = localStorage.getItem('lastChatOpenTime');
    return stored ? parseInt(stored, 10) : Date.now() - CHAT_NOTIFICATION_WINDOW;
  } catch {
    return Date.now() - CHAT_NOTIFICATION_WINDOW;
  }
};

/**
 * Get the IDs of tickets that have already been seen
 */
const getSeenTicketIds = (): Set<number> => {
  try {
    const stored = localStorage.getItem('billops_user_seenTicketIds');
    return stored ? new Set(JSON.parse(stored)) : new Set();
  } catch {
    return new Set();
  }
};

/**
 * Mark tickets as seen
 */
const markTicketsAsSeen = (ticketIds: number[]): void => {
  try {
    const seenIds = getSeenTicketIds();
    ticketIds.forEach(id => seenIds.add(id));
    localStorage.setItem('billops_user_seenTicketIds', JSON.stringify([...seenIds]));
  } catch (error) {
    console.error('Failed to mark tickets as seen:', error);
  }
};

/**
 * Get the IDs of invoices that have already been seen
 */
const getSeenInvoiceIds = (): Set<number> => {
  try {
    const stored = localStorage.getItem('billops_user_seenInvoiceIds');
    return stored ? new Set(JSON.parse(stored)) : new Set();
  } catch {
    return new Set();
  }
};

/**
 * Mark invoices as seen
 */
const markInvoicesAsSeen = (invoiceIds: number[]): void => {
  try {
    const seenIds = getSeenInvoiceIds();
    invoiceIds.forEach(id => seenIds.add(id));
    localStorage.setItem('billops_user_seenInvoiceIds', JSON.stringify([...seenIds]));
  } catch (error) {
    console.error('Failed to mark invoices as seen:', error);
  }
};

export const useBillOpsUserNotifications = (accountId: string | null): UseBillOpsUserNotificationsReturn => {
  const [notifications, setNotifications] = useState<BillOpsUserNotificationItem[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);

  /**
   * Convert ticket to notification item
   */
  const convertTicketToNotification = useCallback((ticket: BillOpsTicket, isNew: boolean): BillOpsUserNotificationItem => {
    return {
      id: `ticket-${ticket.id}`,
      type: 'ticket',
      title: isNew ? `New Update: ${ticket.subject}` : ticket.subject,
      description: `Status: ${ticket.status}`,
      timestamp: new Date(ticket.updatedAt || ticket.createdAt),
      link: `/billops-ticket-detail?id=${ticket.id}`,
      isUnread: isNew,
      metadata: {
        ticketId: ticket.id,
        status: ticket.status,
        severity: ticket.severity,
      },
    };
  }, []);

  /**
   * Convert chat message to notification item (admin replies)
   */
  const convertChatToNotification = useCallback((msg: ChatHistoryItem, index: number): BillOpsUserNotificationItem | null => {
    // Only show messages from admin (not user's own messages)
    if (msg.userMessage) {
      return null;
    }

    const messageTime = new Date(msg.timestamp).getTime();
    const lastOpenTime = getLastChatOpenTime();

    // Only show messages that arrived after the last time chat was opened
    if (messageTime <= lastOpenTime) {
      return null;
    }

    // Truncate long messages
    const description = msg.content.length > 80
      ? msg.content.substring(0, 80) + '...'
      : msg.content;

    return {
      id: `chat-${index}-${msg.timestamp}`,
      type: 'chat',
      title: 'New Reply from Support',
      description,
      timestamp: new Date(msg.timestamp),
      isUnread: true,
    };
  }, []);

  /**
   * Convert invoice to notification item
   */
  const convertInvoiceToNotification = useCallback((invoice: Invoice, isNew: boolean): BillOpsUserNotificationItem => {
    const statusText = invoice.status === 'FINALIZED' ? 'Ready to View' : invoice.status;

    return {
      id: `invoice-${invoice.id}`,
      type: 'invoice',
      title: isNew ? `New Invoice: ${invoice.invoiceNumber}` : invoice.invoiceNumber,
      description: `${invoice.billingPeriod} - $${invoice.amount.toFixed(2)} (${statusText})`,
      timestamp: new Date(invoice.invoiceDate),
      link: '/billops-invoices',
      isUnread: isNew,
      metadata: {
        invoiceId: invoice.id,
        invoiceNumber: invoice.invoiceNumber,
        amount: invoice.amount,
        status: invoice.status,
      },
    };
  }, []);

  /**
   * Fetch all BillOps user notifications
   */
  const fetchNotifications = useCallback(async () => {
    if (!accountId) {
      setNotifications([]);
      return;
    }

    setIsLoading(true);
    const allNotifications: BillOpsUserNotificationItem[] = [];

    try {
      // 1. Fetch user's tickets
      const ticketsResponse = await fetch(
        `/api/billops/tickets/category/Account%20and%20Billing?forceRefresh=false`,
        { credentials: 'include' }
      );

      if (ticketsResponse.ok) {
        const tickets: BillOpsTicket[] = await ticketsResponse.json();
        const seenTicketIds = getSeenTicketIds();
        const cutoffTime = Date.now() - TICKET_NOTIFICATION_WINDOW;

        // Filter to user's recent tickets
        const userTickets = tickets.filter(ticket => {
          const ticketTime = new Date(ticket.createdAt).getTime();
          return ticketTime > cutoffTime && ticket.accountId === accountId;
        });

        // Convert to notifications and mark which are new
        const ticketNotifications = userTickets.map(ticket => {
          const isNew = !seenTicketIds.has(ticket.id);
          return convertTicketToNotification(ticket, isNew);
        });

        allNotifications.push(...ticketNotifications);
      }

      // 2. Fetch user's chat messages
      try {
        const chatHistoryResponse = await fetch(
          `/api/ai/chat/history?accountId=${encodeURIComponent(accountId)}`,
          { credentials: 'include' }
        );

        if (chatHistoryResponse.ok) {
          const chatHistory: ChatHistoryItem[] = await chatHistoryResponse.json();

          if (Array.isArray(chatHistory)) {
            const chatNotifications = chatHistory
              .map((msg, index) => convertChatToNotification(msg, index))
              .filter((n): n is BillOpsUserNotificationItem => n !== null);

            allNotifications.push(...chatNotifications);
          }
        }
      } catch (error) {
        console.debug('[BillOps User Notifications] Error fetching chat:', error);
      }

      // 3. Fetch user's invoices
      try {
        const invoicesResponse = await fetch(
          `/api/admin/invoices?accountId=${encodeURIComponent(accountId)}`,
          { credentials: 'include' }
        );

        if (invoicesResponse.ok) {
          const invoices: Invoice[] = await invoicesResponse.json();
          const seenInvoiceIds = getSeenInvoiceIds();
          const cutoffTime = Date.now() - INVOICE_NOTIFICATION_WINDOW;

          // Filter to recent invoices
          const recentInvoices = invoices.filter(invoice => {
            const invoiceTime = new Date(invoice.invoiceDate).getTime();
            return invoiceTime > cutoffTime;
          });

          // Convert to notifications and mark which are new
          const invoiceNotifications = recentInvoices.map(invoice => {
            const isNew = !seenInvoiceIds.has(invoice.id);
            return convertInvoiceToNotification(invoice, isNew);
          });

          allNotifications.push(...invoiceNotifications);
        }
      } catch (error) {
        console.debug('[BillOps User Notifications] Error fetching invoices:', error);
      }

      // Sort by timestamp (newest first)
      const sortedNotifications = allNotifications.sort(
        (a, b) => b.timestamp.getTime() - a.timestamp.getTime()
      );

      setNotifications(sortedNotifications);
    } catch (error) {
      console.error('[BillOps User Notifications] Error fetching notifications:', error);
      setNotifications([]);
    } finally {
      setIsLoading(false);
    }
  }, [accountId, convertTicketToNotification, convertChatToNotification, convertInvoiceToNotification]);

  /**
   * Calculate notification counts
   */
  const counts = useMemo((): BillOpsUserNotificationCounts => {
    const tickets = notifications.filter(n => n.type === 'ticket').length;
    const chat = notifications.filter(n => n.type === 'chat').length;
    const invoices = notifications.filter(n => n.type === 'invoice').length;

    return {
      total: notifications.length,
      tickets,
      chat,
      invoices,
    };
  }, [notifications]);

  /**
   * Auto-refresh notifications
   */
  useEffect(() => {
    if (!accountId) return;

    // Initial fetch
    fetchNotifications();

    // Set up auto-refresh
    const intervalId = setInterval(fetchNotifications, AUTO_REFRESH_INTERVAL);

    return () => clearInterval(intervalId);
  }, [accountId, fetchNotifications]);

  /**
   * Mark tickets and invoices as seen when notifications are viewed
   */
  useEffect(() => {
    const ticketIds = notifications
      .filter(n => n.type === 'ticket' && n.metadata?.ticketId)
      .map(n => n.metadata!.ticketId!);

    const invoiceIds = notifications
      .filter(n => n.type === 'invoice' && n.metadata?.invoiceId)
      .map(n => n.metadata!.invoiceId!);

    if (ticketIds.length > 0 || invoiceIds.length > 0) {
      // Mark as seen after a delay (user has had time to see them)
      const timeoutId = setTimeout(() => {
        if (ticketIds.length > 0) {
          markTicketsAsSeen(ticketIds);
        }
        if (invoiceIds.length > 0) {
          markInvoicesAsSeen(invoiceIds);
        }
      }, 5000); // 5 seconds

      return () => clearTimeout(timeoutId);
    }
  }, [notifications]);

  return {
    notifications,
    counts,
    isLoading,
    refresh: fetchNotifications,
  };
};

/**
 * Mark chat as opened (for clearing unread chat notifications)
 */
export const markChatAsOpened = (): void => {
  try {
    localStorage.setItem('lastChatOpenTime', Date.now().toString());
  } catch (error) {
    console.error('Failed to mark chat as opened:', error);
  }
};

export default useBillOpsUserNotifications;
