/**
 * useBillOpsAdminNotifications Hook
 *
 * Custom hook for BillOps Admin notifications
 * Only shows:
 * - New tickets raised
 * - Unread chat messages
 *
 * No other notification types are shown
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { BillOpsTicket } from '../types/billopsTickets';

export type BillOpsNotificationType = 'ticket' | 'chat';

export interface BillOpsNotificationItem {
  id: string;
  type: BillOpsNotificationType;
  title: string;
  description: string;
  timestamp: Date;
  link?: string;
  isUnread?: boolean;
  metadata?: {
    ticketId?: number;
    accountId?: string;
    clientName?: string;
    severity?: string;
  };
}

export interface BillOpsNotificationCounts {
  total: number;
  tickets: number;
  chat: number;
}

interface ChatHistoryItem {
  content: string;
  userMessage: boolean;
  timestamp: string;
}

interface UseBillOpsAdminNotificationsReturn {
  notifications: BillOpsNotificationItem[];
  counts: BillOpsNotificationCounts;
  isLoading: boolean;
  refresh: () => Promise<void>;
}

const CHAT_NOTIFICATION_WINDOW = 24 * 60 * 60 * 1000; // Show chat from last 24 hours
const AUTO_REFRESH_INTERVAL = 30000; // 30 seconds
const TICKET_NOTIFICATION_WINDOW = 7 * 24 * 60 * 60 * 1000; // Show tickets from last 7 days

/**
 * Get the last time the chat was opened from localStorage
 */
const getLastChatOpenTime = (): number => {
  try {
    const stored = localStorage.getItem('billops_lastChatOpenTime');
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
    const stored = localStorage.getItem('billops_seenTicketIds');
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
    localStorage.setItem('billops_seenTicketIds', JSON.stringify([...seenIds]));
  } catch (error) {
    console.error('Failed to mark tickets as seen:', error);
  }
};

export const useBillOpsAdminNotifications = (): UseBillOpsAdminNotificationsReturn => {
  const [notifications, setNotifications] = useState<BillOpsNotificationItem[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);

  /**
   * Convert ticket to notification item
   */
  const convertTicketToNotification = useCallback((ticket: BillOpsTicket, isNew: boolean): BillOpsNotificationItem => {
    return {
      id: `ticket-${ticket.id}`,
      type: 'ticket',
      title: isNew ? `New Ticket: ${ticket.subject}` : ticket.subject,
      description: `${ticket.category} - ${ticket.status}`,
      timestamp: new Date(ticket.createdAt),
      link: `/billops-tickets/${ticket.id}`,
      isUnread: isNew,
      metadata: {
        ticketId: ticket.id,
        accountId: ticket.accountId,
        clientName: ticket.clientName,
        severity: ticket.severity,
      },
    };
  }, []);

  /**
   * Convert chat message to notification item
   */
  const convertChatToNotification = useCallback((msg: ChatHistoryItem, index: number, accountId: string): BillOpsNotificationItem | null => {
    // Only show messages from users (not from admin)
    if (!msg.userMessage) {
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
      id: `chat-${accountId}-${index}-${msg.timestamp}`,
      type: 'chat',
      title: 'New Message',
      description,
      timestamp: new Date(msg.timestamp),
      isUnread: true,
      metadata: {
        accountId,
      },
    };
  }, []);

  /**
   * Fetch all BillOps admin notifications
   */
  const fetchNotifications = useCallback(async () => {
    setIsLoading(true);
    const allNotifications: BillOpsNotificationItem[] = [];

    try {
      // 1. Fetch tickets
      const ticketsResponse = await fetch(
        `/api/billops/tickets/category/Account%20and%20Billing?forceRefresh=false`,
        { credentials: 'include' }
      );

      if (ticketsResponse.ok) {
        const tickets: BillOpsTicket[] = await ticketsResponse.json();
        const seenTicketIds = getSeenTicketIds();
        const cutoffTime = Date.now() - TICKET_NOTIFICATION_WINDOW;

        // Filter to recent tickets
        const recentTickets = tickets.filter(ticket => {
          const ticketTime = new Date(ticket.createdAt).getTime();
          return ticketTime > cutoffTime;
        });

        // Convert to notifications and mark which are new
        const ticketNotifications = recentTickets.map(ticket => {
          const isNew = !seenTicketIds.has(ticket.id);
          return convertTicketToNotification(ticket, isNew);
        });

        allNotifications.push(...ticketNotifications);
      }

      // 2. Fetch chat messages from all accounts
      // Get list of all client accounts from admin dashboard or use a dedicated endpoint
      try {
        // First, try to get chat sessions endpoint
        const chatSessionsResponse = await fetch('/api/billops/support/chat-sessions', {
          credentials: 'include',
        });

        if (chatSessionsResponse.ok) {
          const chatSessions = await chatSessionsResponse.json();

          // Fetch chat history for each session
          for (const session of chatSessions) {
            try {
              const chatHistoryResponse = await fetch(
                `/api/ai/chat/history?accountId=${encodeURIComponent(session.accountId)}`,
                { credentials: 'include' }
              );

              if (chatHistoryResponse.ok) {
                const chatHistory: ChatHistoryItem[] = await chatHistoryResponse.json();

                if (Array.isArray(chatHistory)) {
                  const chatNotifications = chatHistory
                    .map((msg, index) => convertChatToNotification(msg, index, session.accountId))
                    .filter((n): n is BillOpsNotificationItem => n !== null);

                  allNotifications.push(...chatNotifications);
                }
              }
            } catch (error) {
              console.debug('[BillOps Notifications] Error fetching chat for account:', session.accountId, error);
            }
          }
        }
      } catch (error) {
        console.debug('[BillOps Notifications] Chat sessions not available:', error);
      }

      // Sort by timestamp (newest first)
      const sortedNotifications = allNotifications.sort(
        (a, b) => b.timestamp.getTime() - a.timestamp.getTime()
      );

      setNotifications(sortedNotifications);
    } catch (error) {
      console.error('[BillOps Notifications] Error fetching notifications:', error);
      setNotifications([]);
    } finally {
      setIsLoading(false);
    }
  }, [convertTicketToNotification, convertChatToNotification]);

  /**
   * Calculate notification counts
   */
  const counts = useMemo((): BillOpsNotificationCounts => {
    const tickets = notifications.filter(n => n.type === 'ticket').length;
    const chat = notifications.filter(n => n.type === 'chat').length;

    return {
      total: notifications.length,
      tickets,
      chat,
    };
  }, [notifications]);

  /**
   * Auto-refresh notifications
   */
  useEffect(() => {
    // Initial fetch
    fetchNotifications();

    // Set up auto-refresh
    const intervalId = setInterval(fetchNotifications, AUTO_REFRESH_INTERVAL);

    return () => clearInterval(intervalId);
  }, [fetchNotifications]);

  /**
   * Mark tickets as seen when notifications are viewed
   */
  useEffect(() => {
    const ticketIds = notifications
      .filter(n => n.type === 'ticket' && n.metadata?.ticketId)
      .map(n => n.metadata!.ticketId!);

    if (ticketIds.length > 0) {
      // Mark tickets as seen after a delay (user has had time to see them)
      const timeoutId = setTimeout(() => {
        markTicketsAsSeen(ticketIds);
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
export const markBillOpsChatAsOpened = (): void => {
  try {
    localStorage.setItem('billops_lastChatOpenTime', Date.now().toString());
  } catch (error) {
    console.error('Failed to mark chat as opened:', error);
  }
};

export default useBillOpsAdminNotifications;
