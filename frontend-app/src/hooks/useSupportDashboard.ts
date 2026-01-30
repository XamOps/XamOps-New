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

  // Keep ref in sync with state
  useEffect(() => {
    activeAccountRef.current = activeAccount;
  }, [activeAccount]);

  /**
   * Fetch inbox (list of conversations)
   */
  const fetchInbox = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);

    try {
      const data = await apiClient.get<SupportConversation[]>('/api/billops/support/inbox');
      setConversations(data || []);
    } catch (err) {
      console.error('Inbox Error:', err);
      if (!silent) {
        setError('Failed to load inbox. Please try again.');
      }
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  /**
   * Fetch chat messages for active account
   */
  const fetchChat = useCallback(async () => {
    const account = activeAccountRef.current;
    if (!account) return;

    try {
      const data = await apiClient.get<ChatMessage[]>(
        `/api/billops/support/chat/${account.accountId}`
      );

      // Only update if we have new messages
      setMessages(prevMessages => {
        if (data.length > prevMessages.length || data.length !== prevMessages.length) {
          return data || [];
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

    try {
      const data = await apiClient.get<ChatMessage[]>(
        `/api/billops/support/chat/${account.accountId}`
      );
      setMessages(data || []);
    } catch (err) {
      console.error('Chat Error:', err);
      setError('Failed to load chat messages.');
    } finally {
      setChatLoading(false);
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

      // Refresh chat after sending
      await fetchChat();
      return true;
    } catch (err) {
      console.error('Send Reply Error:', err);
      setError('Failed to send message. Please try again.');
      return false;
    } finally {
      setSending(false);
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
    refreshInbox: () => fetchInbox(),
    refreshChat: fetchChat,
  };
};

export default useSupportDashboard;
