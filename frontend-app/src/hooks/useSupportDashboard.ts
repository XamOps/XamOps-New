/**
 * Support Dashboard Hook
 *
 * Custom hook for managing the BillOps Admin Support Console state and API calls.
 * Provides real-time updates for inbox and chat messages.
 *
 * Converted from: billops/support-dashboard.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Added proper type safety
 * - Improved error handling
 * - Added auto-refresh with configurable intervals
 *
 * What stayed the same:
 * - All API endpoints remain identical:
 *   - GET /api/billops/support/inbox
 *   - GET /api/billops/support/chat/{accountId}
 *   - POST /api/billops/support/reply
 * - Same refresh intervals (10s inbox, 3s chat)
 * - Same filtering logic
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import {
  SupportConversation,
  ChatMessage,
  CloudProvider,
  ReplyPayload,
} from '../types/supportDashboard';
import { initNotificationService } from '../utils/notificationService';

interface UseSupportDashboardReturn {
  // Data
  conversations: SupportConversation[];
  filteredConversations: SupportConversation[];
  messages: ChatMessage[];
  activeAccount: SupportConversation | null;

  // State
  loading: boolean;
  chatLoading: boolean;
  sending: boolean;
  error: string | null;

  // Filters
  searchQuery: string;
  providerFilter: CloudProvider | '';

  // Actions
  setSearchQuery: (query: string) => void;
  setProviderFilter: (provider: CloudProvider | '') => void;
  selectConversation: (account: SupportConversation) => Promise<void>;
  sendReply: (message: string) => Promise<boolean>;
  refreshInbox: () => Promise<void>;
  refreshChat: () => Promise<void>;
}

const INBOX_REFRESH_INTERVAL = 10000; // 10 seconds
const CHAT_REFRESH_INTERVAL = 3000; // 3 seconds

export const useSupportDashboard = (): UseSupportDashboardReturn => {
  // State
  const [conversations, setConversations] = useState<SupportConversation[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [activeAccount, setActiveAccount] = useState<SupportConversation | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [providerFilter, setProviderFilter] = useState<CloudProvider | ''>('');
  const [loading, setLoading] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Refs for intervals
  const inboxIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const chatIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const activeAccountRef = useRef<SupportConversation | null>(null);
  const previousMessageCountRef = useRef(0);
  const previousInboxRef = useRef<Map<string, number>>(new Map());
  const notificationInitializedRef = useRef(false);
  const isMountedRef = useRef(true);

  // Keep ref in sync with state
  useEffect(() => {
    activeAccountRef.current = activeAccount;
  }, [activeAccount]);

  // Cleanup on unmount
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  /**
   * Fetch inbox (list of conversations)
   * Note: Notifications are handled by the global AdminSupportNotificationListener
   * to avoid duplicate sounds. This function only updates UI state.
   */
  const fetchInbox = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);

    try {
      const data = await apiClient.get<SupportConversation[]>('/api/billops/support/inbox');

      if (!isMountedRef.current) return;

      const inboxData = data || [];

      // Update previous inbox state
      const newInboxMap = new Map<string, number>();
      for (const conv of inboxData) {
        newInboxMap.set(conv.accountId, conv.unreadCount || 0);
      }
      previousInboxRef.current = newInboxMap;

      setConversations(inboxData);
    } catch (err) {
      console.error('Inbox Error:', err);
      if (isMountedRef.current && !silent) {
        setError('Failed to load inbox. Please try again.');
      }
    } finally {
      if (isMountedRef.current && !silent) setLoading(false);
    }
  }, []);

  /**
   * Fetch chat messages for active account
   * Note: Notifications are handled by the global AdminSupportNotificationListener
   * to avoid duplicate sounds. This function only updates UI state.
   */
  const fetchChat = useCallback(async () => {
    const account = activeAccountRef.current;
    if (!account) return;

    try {
      const data = await apiClient.get<ChatMessage[]>(
        `/api/billops/support/chat/${account.accountId}`
      );

      if (!isMountedRef.current) return;

      const chatData = data || [];
      previousMessageCountRef.current = chatData.length;

      // Only update if we have new messages
      setMessages(prevMessages => {
        if (chatData.length > prevMessages.length || chatData.length !== prevMessages.length) {
          return chatData;
        }
        return prevMessages;
      });
    } catch (err) {
      console.error('Chat Error:', err);
    }
  }, []);

  /**
   * Select a conversation and load its messages
   */
  const selectConversation = useCallback(async (account: SupportConversation) => {
    setActiveAccount(account);
    setMessages([]);
    setChatLoading(true);
    previousMessageCountRef.current = 0; // Reset message count for new conversation

    // Locally clear unread count for immediate UI feedback
    setConversations(prev => prev.map(c =>
      c.accountId === account.accountId ? { ...c, unreadCount: 0 } : c
    ));

    try {
      const data = await apiClient.get<ChatMessage[]>(
        `/api/billops/support/chat/${account.accountId}`
      );
      if (isMountedRef.current) {
        const chatData = data || [];
        setMessages(chatData);
        previousMessageCountRef.current = chatData.length; // Set initial message count
      }
    } catch (err) {
      console.error('Chat Error:', err);
      if (isMountedRef.current) {
        setError('Failed to load chat messages.');
      }
    } finally {
      if (isMountedRef.current) {
        setChatLoading(false);
      }
    }
  }, []);

  /**
   * Send a reply message
   */
  const sendReply = useCallback(async (message: string): Promise<boolean> => {
    if (!activeAccount || !message.trim()) return false;

    setSending(true);

    try {
      const payload: ReplyPayload = {
        accountId: activeAccount.accountId,
        message: message.trim(),
      };

      await apiClient.post('/api/billops/support/reply', payload);

      if (!isMountedRef.current) return true;

      // Refresh chat after sending
      await fetchChat();
      return true;
    } catch (err) {
      console.error('Send Reply Error:', err);
      if (isMountedRef.current) {
        setError('Failed to send message. Please try again.');
      }
      return false;
    } finally {
      if (isMountedRef.current) {
        setSending(false);
      }
    }
  }, [activeAccount, fetchChat]);

  /**
   * Filter conversations by search query and provider
   */
  const filteredConversations = useMemo(() => {
    return conversations.filter(conv => {
      const term = searchQuery.toLowerCase();
      const matchesSearch =
        (conv.accountName && conv.accountName.toLowerCase().includes(term)) ||
        (conv.accountId && conv.accountId.toLowerCase().includes(term)) ||
        (conv.senderUsername && conv.senderUsername.toLowerCase().includes(term));
      const matchesProvider = providerFilter === '' || conv.provider === providerFilter;
      return matchesSearch && matchesProvider;
    });
  }, [conversations, searchQuery, providerFilter]);

  /**
   * Initialize: fetch inbox and set up auto-refresh
   */
  useEffect(() => {
    // Initialize notification service (requests permission)
    if (!notificationInitializedRef.current) {
      notificationInitializedRef.current = true;
      initNotificationService();
    }

    // Initial fetch
    fetchInbox();

    // Set up inbox auto-refresh
    inboxIntervalRef.current = setInterval(() => {
      fetchInbox(true); // Silent refresh
    }, INBOX_REFRESH_INTERVAL);

    return () => {
      if (inboxIntervalRef.current) {
        clearInterval(inboxIntervalRef.current);
      }
    };
  }, [fetchInbox]);

  /**
   * Set up chat auto-refresh when active account changes
   */
  useEffect(() => {
    // Clear existing interval
    if (chatIntervalRef.current) {
      clearInterval(chatIntervalRef.current);
      chatIntervalRef.current = null;
    }

    // Set up new interval if there's an active account
    if (activeAccount) {
      chatIntervalRef.current = setInterval(() => {
        fetchChat();
      }, CHAT_REFRESH_INTERVAL);
    }

    return () => {
      if (chatIntervalRef.current) {
        clearInterval(chatIntervalRef.current);
      }
    };
  }, [activeAccount, fetchChat]);

  /**
   * Listen for new customer message events from the global AdminSupportNotificationListener
   */
  useEffect(() => {
    const handleNewCustomerMessage = (event: CustomEvent) => {
      // Refresh inbox to update unread counts
      fetchInbox(true);

      // If we have an active conversation with this customer, refresh chat
      const { accountId } = event.detail;
      if (activeAccountRef.current?.accountId === accountId) {
        fetchChat();
      }
    };

    window.addEventListener('newCustomerMessage', handleNewCustomerMessage as EventListener);
    return () => {
      window.removeEventListener('newCustomerMessage', handleNewCustomerMessage as EventListener);
    };
  }, [fetchInbox, fetchChat]);

  return {
    // Data
    conversations,
    filteredConversations,
    messages,
    activeAccount,

    // State
    loading,
    chatLoading,
    sending,
    error,

    // Filters
    searchQuery,
    providerFilter,

    // Actions
    setSearchQuery,
    setProviderFilter,
    selectConversation,
    sendReply,
    refreshInbox: fetchInbox,
    refreshChat: fetchChat,
  };
};

export default useSupportDashboard;
