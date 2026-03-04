/**
 * Support Dashboard Hook
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import {
  SupportConversation,
  SupportAccount,
  AttachmentInfo,
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
  accounts: SupportAccount[];

  // State
  loading: boolean;
  chatLoading: boolean;
  sending: boolean;
  loadingAccounts: boolean;
  error: string | null;

  // Filters
  searchQuery: string;
  providerFilter: CloudProvider | '';

  // Actions
  setSearchQuery: (query: string) => void;
  setProviderFilter: (provider: CloudProvider | '') => void;
  selectConversation: (account: SupportConversation) => Promise<void>;
  sendReply: (message: string, attachment?: AttachmentInfo) => Promise<boolean>;
  startConversation: (account: SupportAccount, message: string) => Promise<boolean>;
  uploadAttachment: (file: File) => Promise<AttachmentInfo | null>;
  refreshInbox: () => Promise<void>;
  refreshChat: () => Promise<void>;
  fetchAccounts: () => Promise<void>;
}

const INBOX_REFRESH_INTERVAL = 10000;
const CHAT_REFRESH_INTERVAL = 3000;

export const useSupportDashboard = (): UseSupportDashboardReturn => {
  const [conversations, setConversations] = useState<SupportConversation[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [activeAccount, setActiveAccount] = useState<SupportConversation | null>(null);
  const [accounts, setAccounts] = useState<SupportAccount[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [providerFilter, setProviderFilter] = useState<CloudProvider | ''>('');
  const [loading, setLoading] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [loadingAccounts, setLoadingAccounts] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inboxIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const chatIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const activeAccountRef = useRef<SupportConversation | null>(null);
  const previousMessageCountRef = useRef(0);
  const previousInboxRef = useRef<Map<string, number>>(new Map());
  const notificationInitializedRef = useRef(false);
  const isMountedRef = useRef(true);

  useEffect(() => {
    activeAccountRef.current = activeAccount;
  }, [activeAccount]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => { isMountedRef.current = false; };
  }, []);

  const fetchInbox = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);
    try {
      const data = await apiClient.get<SupportConversation[]>('/api/billops/support/inbox');
      if (!isMountedRef.current) return;
      const inboxData = data || [];
      const newInboxMap = new Map<string, number>();
      for (const conv of inboxData) {
        newInboxMap.set(conv.accountId, conv.unreadCount || 0);
      }
      previousInboxRef.current = newInboxMap;
      setConversations(inboxData);
    } catch (err) {
      console.error('Inbox Error:', err);
      if (isMountedRef.current && !silent) setError('Failed to load inbox. Please try again.');
    } finally {
      if (isMountedRef.current && !silent) setLoading(false);
    }
  }, []);

  const fetchAccounts = useCallback(async () => {
    setLoadingAccounts(true);
    try {
      const data = await apiClient.get<SupportAccount[]>('/api/billops/support/accounts');
      if (isMountedRef.current) setAccounts(data || []);
    } catch (err) {
      console.error('Accounts Error:', err);
    } finally {
      if (isMountedRef.current) setLoadingAccounts(false);
    }
  }, []);

  const fetchChat = useCallback(async () => {
    const account = activeAccountRef.current;
    if (!account) return;
    try {
      const data = await apiClient.get<ChatMessage[]>(`/api/billops/support/chat/${account.accountId}`);
      if (!isMountedRef.current) return;
      const chatData = data || [];
      previousMessageCountRef.current = chatData.length;
      setMessages(prevMessages => {
        if (chatData.length !== prevMessages.length) return chatData;
        return prevMessages;
      });
    } catch (err) {
      console.error('Chat Error:', err);
    }
  }, []);

  const selectConversation = useCallback(async (account: SupportConversation) => {
    setActiveAccount(account);
    setMessages([]);
    setChatLoading(true);
    previousMessageCountRef.current = 0;
    setConversations(prev => prev.map(c =>
      c.accountId === account.accountId ? { ...c, unreadCount: 0 } : c
    ));
    try {
      const data = await apiClient.get<ChatMessage[]>(`/api/billops/support/chat/${account.accountId}`);
      if (isMountedRef.current) {
        const chatData = data || [];
        setMessages(chatData);
        previousMessageCountRef.current = chatData.length;
      }
    } catch (err) {
      console.error('Chat Error:', err);
      if (isMountedRef.current) setError('Failed to load chat messages.');
    } finally {
      if (isMountedRef.current) setChatLoading(false);
    }
  }, []);

  /**
   * Upload a file attachment and return the server-side info
   */
  const uploadAttachment = useCallback(async (file: File): Promise<AttachmentInfo | null> => {
    const formData = new FormData();
    formData.append('file', file);
    try {
      // Use apiClient.post which handles FormData correctly (no JSON.stringify)
      const result = await apiClient.post<AttachmentInfo>('/api/billops/support/upload', formData);
      return result;
    } catch (err) {
      console.error('Upload Error:', err);
      return null;
    }
  }, []);

  /**
   * Send a reply with optional attachment
   */
  const sendReply = useCallback(async (message: string, attachment?: AttachmentInfo): Promise<boolean> => {
    if (!activeAccount || (!message.trim() && !attachment)) return false;
    setSending(true);
    try {
      const payload: ReplyPayload = {
        accountId: activeAccount.accountId,
        message: message.trim(),
        ...(attachment && {
          attachmentUrl: attachment.url,
          attachmentName: attachment.name,
          attachmentType: attachment.type,
          attachmentSize: String(attachment.size),
        }),
      };
      await apiClient.post('/api/billops/support/reply', payload);
      if (!isMountedRef.current) return true;
      await fetchChat();
      return true;
    } catch (err) {
      console.error('Send Reply Error:', err);
      if (isMountedRef.current) setError('Failed to send message. Please try again.');
      return false;
    } finally {
      if (isMountedRef.current) setSending(false);
    }
  }, [activeAccount, fetchChat]);

  /**
   * Admin starts a brand-new conversation with a cloud account
   */
  const startConversation = useCallback(async (account: SupportAccount, message: string): Promise<boolean> => {
    if (!message.trim()) return false;
    setSending(true);
    try {
      const payload: ReplyPayload = {
        accountId: account.accountId,
        message: message.trim(),
        accountName: account.accountName,
        cloudProvider: account.provider,
      };
      await apiClient.post('/api/billops/support/reply', payload);
      if (!isMountedRef.current) return true;

      // Refresh inbox so the new conversation appears
      await fetchInbox(true);

      // Auto-select the new conversation
      const newConv: SupportConversation = {
        accountId: account.accountId,
        accountName: account.accountName,
        provider: account.provider,
        senderUsername: '',
        unreadCount: 0,
      };
      await selectConversation(newConv);
      return true;
    } catch (err: any) {
      console.error('Start Conversation Error:', err);
      if (isMountedRef.current) setError(err?.message || 'Failed to start conversation.');
      return false;
    } finally {
      if (isMountedRef.current) setSending(false);
    }
  }, [fetchInbox, selectConversation]);

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

  useEffect(() => {
    if (!notificationInitializedRef.current) {
      notificationInitializedRef.current = true;
      initNotificationService();
    }
    fetchInbox();
    inboxIntervalRef.current = setInterval(() => fetchInbox(true), INBOX_REFRESH_INTERVAL);
    return () => { if (inboxIntervalRef.current) clearInterval(inboxIntervalRef.current); };
  }, [fetchInbox]);

  useEffect(() => {
    if (chatIntervalRef.current) { clearInterval(chatIntervalRef.current); chatIntervalRef.current = null; }
    if (activeAccount) {
      chatIntervalRef.current = setInterval(() => fetchChat(), CHAT_REFRESH_INTERVAL);
    }
    return () => { if (chatIntervalRef.current) clearInterval(chatIntervalRef.current); };
  }, [activeAccount, fetchChat]);

  useEffect(() => {
    const handleNewCustomerMessage = (event: CustomEvent) => {
      fetchInbox(true);
      const { accountId } = event.detail;
      if (activeAccountRef.current?.accountId === accountId) fetchChat();
    };
    window.addEventListener('newCustomerMessage', handleNewCustomerMessage as EventListener);
    return () => window.removeEventListener('newCustomerMessage', handleNewCustomerMessage as EventListener);
  }, [fetchInbox, fetchChat]);

  return {
    conversations, filteredConversations, messages, activeAccount, accounts,
    loading, chatLoading, sending, loadingAccounts, error,
    searchQuery, providerFilter,
    setSearchQuery, setProviderFilter, selectConversation,
    sendReply, startConversation, uploadAttachment,
    refreshInbox: fetchInbox, refreshChat: fetchChat, fetchAccounts,
  };
};

export default useSupportDashboard;
