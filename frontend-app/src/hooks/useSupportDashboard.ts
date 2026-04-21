/**
 * Support Dashboard Hook
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import {
  SupportConversation,
  SupportAccount,
  SupportClient,
  SupportProfile,
  AttachmentInfo,
  ChatMessage,
  CloudProvider,
  ReplyPayload,
  TicketStatus,
  UserPlatformConnection,
  IntegrationConfig,
} from '../types/supportDashboard';
import { initNotificationService } from '../utils/notificationService';

interface AutoPingOptions {
  enabled: boolean;
  channels: string[]; // e.g. ['WHATSAPP', 'SMS', 'EMAIL']
}

interface UseSupportDashboardReturn {
  // Data
  conversations: SupportConversation[];
  filteredConversations: SupportConversation[];
  messages: ChatMessage[];
  activeAccount: SupportConversation | null;
  accounts: SupportAccount[];
  clients: SupportClient[];
  clientProfiles: SupportProfile[];

  // State
  loading: boolean;
  chatLoading: boolean;
  sending: boolean;
  loadingAccounts: boolean;
  loadingClients: boolean;
  loadingProfiles: boolean;
  error: string | null;

  // Filters
  searchQuery: string;
  providerFilter: CloudProvider | '';

  // Platform messaging
  selectedPlatform: string;
  setSelectedPlatform: (platform: string) => void;
  userConnections: UserPlatformConnection[];
  integrationConfigs: IntegrationConfig[];
  loadUserConnections: (userId: number) => Promise<void>;
  saveUserConnection: (userId: number, platform: string, webhookUrl?: string, channelId?: string) => Promise<boolean>;
  removeUserConnection: (userId: number, platform: string) => Promise<boolean>;
  loadIntegrationConfigs: () => Promise<void>;
  saveIntegrationConfig: (platform: string, botToken?: string, signingSecret?: string, clientId?: string, clientSecret?: string) => Promise<boolean>;
  sendSlackInstallLink: (userId: number) => Promise<{ sent: boolean; email?: string; error?: string }>;
  setupGoogleChat: (userId: number) => Promise<{ invited?: boolean; connected: boolean; spaceName?: string; error?: string; message?: string; errorCode?: string; suggestion?: string }>;
  sendTeamsInvite: (userId: number) => Promise<{ invited: boolean; email?: string; error?: string; message?: string }>;

  // Actions
  setSearchQuery: (query: string) => void;
  setProviderFilter: (provider: CloudProvider | '') => void;
  selectConversation: (account: SupportConversation) => Promise<void>;
  sendReply: (message: string, attachment?: AttachmentInfo, externalPlatform?: string) => Promise<boolean>;
  startConversation: (
    profile: SupportProfile,
    message: string,
    autoPing?: AutoPingOptions
  ) => Promise<boolean>;
  uploadAttachment: (file: File) => Promise<AttachmentInfo | null>;
  editAdminMessage: (id: number, content: string) => Promise<void>;
  deleteAdminMessage: (id: number) => Promise<void>;
  updateTicketStatus: (userId: number, status: TicketStatus) => Promise<boolean>;
  sendTicketEmail: (userId: number, recipientEmails: string[], sendMode: 'separate' | 'combined', subject?: string) => Promise<{ success: boolean; error?: string }>;
  refreshInbox: () => Promise<void>;
  refreshChat: () => Promise<void>;
  fetchAccounts: () => Promise<void>;
  fetchClients: () => Promise<void>;
  fetchClientProfiles: (clientId: number) => Promise<void>;
}

const INBOX_REFRESH_INTERVAL = 10000;
const CHAT_REFRESH_INTERVAL = 3000;

export const useSupportDashboard = (): UseSupportDashboardReturn => {
  const [conversations, setConversations] = useState<SupportConversation[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [activeAccount, setActiveAccount] = useState<SupportConversation | null>(null);
  const [accounts, setAccounts] = useState<SupportAccount[]>([]);
  const [clients, setClients] = useState<SupportClient[]>([]);
  const [clientProfiles, setClientProfiles] = useState<SupportProfile[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [providerFilter, setProviderFilter] = useState<CloudProvider | ''>('');
  const [loading, setLoading] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [loadingAccounts, setLoadingAccounts] = useState(false);
  const [loadingClients, setLoadingClients] = useState(false);
  const [loadingProfiles, setLoadingProfiles] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Platform messaging state
  const [selectedPlatform, setSelectedPlatform] = useState<string>('');
  const [userConnections, setUserConnections] = useState<UserPlatformConnection[]>([]);
  const [integrationConfigs, setIntegrationConfigs] = useState<IntegrationConfig[]>([]);

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
        if (conv.accountId) newInboxMap.set(conv.accountId, conv.unreadCount || 0);
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

  const fetchClients = useCallback(async () => {
    setLoadingClients(true);
    try {
      const data = await apiClient.get<SupportClient[]>('/api/billops/support/clients');
      if (isMountedRef.current) setClients(data || []);
    } catch (err) {
      console.error('Clients Error:', err);
    } finally {
      if (isMountedRef.current) setLoadingClients(false);
    }
  }, []);

  const fetchClientProfiles = useCallback(async (clientId: number) => {
    setLoadingProfiles(true);
    setClientProfiles([]);
    try {
      const data = await apiClient.get<SupportProfile[]>(`/api/billops/support/clients/${clientId}/profiles`);
      if (isMountedRef.current) setClientProfiles(data || []);
    } catch (err) {
      console.error('Profiles Error:', err);
    } finally {
      if (isMountedRef.current) setLoadingProfiles(false);
    }
  }, []);

  const fetchChat = useCallback(async () => {
    const account = activeAccountRef.current;
    if (!account?.userId) return;
    try {
      const data = await apiClient.get<any[]>(`/api/billops/support/chat/user/${account.userId}`);
      if (!isMountedRef.current) return;
      // Normalize boolean fields — Jackson strips "is" prefix so backend may return
      // "deleted"/"edited" instead of "isDeleted"/"isEdited" depending on server version
      const chatData: ChatMessage[] = (data || []).map((msg: any) => ({
        ...msg,
        isDeleted: msg.isDeleted ?? msg.deleted ?? false,
        isEdited: msg.isEdited ?? msg.edited ?? false,
      }));
      previousMessageCountRef.current = chatData.length;
      setMessages(chatData);
    } catch (err) {
      console.error('Chat Error:', err);
    }
  }, []);

  const selectConversation = useCallback(async (account: SupportConversation) => {
    setActiveAccount(account);
    setMessages([]);
    setUserConnections([]);
    setSelectedPlatform('');
    setChatLoading(true);
    previousMessageCountRef.current = 0;
    setConversations(prev => prev.map(c =>
      c.userId === account.userId ? { ...c, unreadCount: 0 } : c
    ));
    try {
      const [chatData, connData] = await Promise.all([
        apiClient.get<ChatMessage[]>(`/api/billops/support/chat/user/${account.userId}`),
        account.userId ? apiClient.get<UserPlatformConnection[]>(`/api/billops/messaging/users/${account.userId}/connections`).catch(() => []) : Promise.resolve([]),
      ]);
      if (isMountedRef.current) {
        setMessages(chatData || []);
        previousMessageCountRef.current = (chatData || []).length;
        setUserConnections((connData || []).filter((c: UserPlatformConnection) => c.active));
      }
    } catch (err) {
      console.error('Chat Error:', err);
      if (isMountedRef.current) setError('Failed to load chat messages.');
    } finally {
      if (isMountedRef.current) setChatLoading(false);
    }
  }, []);

  const uploadAttachment = useCallback(async (file: File): Promise<AttachmentInfo | null> => {
    const formData = new FormData();
    formData.append('file', file);
    try {
      const result = await apiClient.post<AttachmentInfo>('/api/billops/support/upload', formData);
      return result;
    } catch (err) {
      console.error('Upload Error:', err);
      return null;
    }
  }, []);

  const sendReply = useCallback(async (message: string, attachment?: AttachmentInfo, externalPlatform?: string): Promise<boolean> => {
    if (!activeAccount || (!message.trim() && !attachment)) return false;
    setSending(true);
    try {
      const payload: ReplyPayload = {
        userId: activeAccount.userId != null ? String(activeAccount.userId) : undefined,
        accountId: activeAccount.accountId ?? undefined,
        message: message.trim(),
        ...(externalPlatform && { externalPlatform }),
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
      // Refresh inbox to update assignedAdminUsername and ticketStatus
      await fetchInbox(true);
      return true;
    } catch (err) {
      console.error('Send Reply Error:', err);
      if (isMountedRef.current) setError('Failed to send message. Please try again.');
      return false;
    } finally {
      if (isMountedRef.current) setSending(false);
    }
  }, [activeAccount, fetchChat, fetchInbox]);

  const editAdminMessage = useCallback(async (id: number, content: string) => {
    try {
      await apiClient.fetch(`/api/billops/support/messages/${id}`, {
        method: 'PATCH',
        body: JSON.stringify({ content }),
      });
      await fetchChat();
    } catch (err) {
      console.error('Edit message error:', err);
    }
  }, [fetchChat]);

  const deleteAdminMessage = useCallback(async (id: number) => {
    // Optimistic update — immediately show as deleted in UI
    setMessages(prev => prev.map(m => m.id === id ? { ...m, isDeleted: true } : m));
    try {
      const response = await apiClient.fetch(`/api/billops/support/messages/${id}`, { method: 'DELETE' });
      if (!response.ok) {
        console.error('Delete message failed with status:', response.status);
        // Revert optimistic update on failure
        await fetchChat();
        return;
      }
      await fetchChat();
    } catch (err) {
      console.error('Delete message error:', err);
      await fetchChat();
    }
  }, [fetchChat]);

  /** Update ticket status (OPEN / IN_PROGRESS / CLOSED) */
  const updateTicketStatus = useCallback(async (userId: number, status: TicketStatus): Promise<boolean> => {
    try {
      await apiClient.fetch(`/api/billops/support/ticket/${userId}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      });
      // Update local state immediately
      setConversations(prev => prev.map(c =>
        c.userId === userId ? { ...c, ticketStatus: status } : c
      ));
      setActiveAccount(prev =>
        prev?.userId === userId ? { ...prev, ticketStatus: status } : prev
      );
      return true;
    } catch (err) {
      console.error('Update ticket status error:', err);
      return false;
    }
  }, []);

  /** Send chat thread as email — multi-recipient with separate or combined send mode */
  const sendTicketEmail = useCallback(async (
    userId: number,
    recipientEmails: string[],
    sendMode: 'separate' | 'combined',
    subject?: string
  ): Promise<{ success: boolean; error?: string }> => {
    try {
      const body: Record<string, unknown> = { recipientEmails, sendMode };
      if (subject) body.subject = subject;
      const result = await apiClient.post<{ success: boolean; sentTo?: string; error?: string }>(
        `/api/billops/support/ticket/${userId}/email`,
        body
      );
      if (result?.success) return { success: true };
      return { success: false, error: result?.error || 'Failed to send email.' };
    } catch (err: any) {
      console.error('Send ticket email error:', err);
      return { success: false, error: err?.message || 'Failed to send email.' };
    }
  }, []);

  const startConversation = useCallback(async (
    profile: SupportProfile,
    message: string,
    autoPing?: AutoPingOptions,
  ): Promise<boolean> => {
    if (!message.trim()) return false;
    setSending(true);
    try {
      const payload: ReplyPayload = {
        userId: String(profile.id),
        message: message.trim(),
        ...(autoPing?.enabled && {
          autoPingEnabled: 'true',
          autoPingChannels: autoPing.channels.join(','),
        }),
      };
      await apiClient.post('/api/billops/support/reply', payload);
      if (!isMountedRef.current) return true;

      await fetchInbox(true);

      const newConv: SupportConversation = {
        userId: profile.id,
        username: profile.username,
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
        (conv.username && conv.username.toLowerCase().includes(term)) ||
        (conv.accountName && conv.accountName.toLowerCase().includes(term)) ||
        (conv.accountId && conv.accountId.toLowerCase().includes(term));
      return matchesSearch;
    });
  }, [conversations, searchQuery]);

  // defined early so mount useEffect can reference it
  const loadIntegrationConfigsEarly = useCallback(async () => {
    try {
      const data = await apiClient.get<IntegrationConfig[]>('/api/billops/messaging/config');
      if (isMountedRef.current) setIntegrationConfigs(data || []);
    } catch (err) {
      console.error('Load integration configs error:', err);
    }
  }, []);

  useEffect(() => {
    if (!notificationInitializedRef.current) {
      notificationInitializedRef.current = true;
      initNotificationService();
    }
    fetchInbox();
    loadIntegrationConfigsEarly();
    inboxIntervalRef.current = setInterval(() => fetchInbox(true), INBOX_REFRESH_INTERVAL);
    return () => { if (inboxIntervalRef.current) clearInterval(inboxIntervalRef.current); };
  }, [fetchInbox, loadIntegrationConfigsEarly]);

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
      const { userId } = event.detail;
      if (activeAccountRef.current?.userId != null &&
          activeAccountRef.current.userId === userId) fetchChat();
    };
    window.addEventListener('newCustomerMessage', handleNewCustomerMessage as EventListener);
    return () => window.removeEventListener('newCustomerMessage', handleNewCustomerMessage as EventListener);
  }, [fetchInbox, fetchChat]);

  // ── Platform messaging functions ──────────────────────────────────────────

  const loadUserConnections = useCallback(async (userId: number) => {
    try {
      const data = await apiClient.get<UserPlatformConnection[]>(`/api/billops/messaging/users/${userId}/connections`);
      if (isMountedRef.current) setUserConnections((data || []).filter(c => c.active));
    } catch (err) {
      console.error('Load user connections error:', err);
    }
  }, []);

  const saveUserConnection = useCallback(async (userId: number, platform: string, webhookUrl?: string, channelId?: string): Promise<boolean> => {
    try {
      const body: Record<string, string> = { platform };
      if (webhookUrl) body.webhookUrl = webhookUrl;
      if (channelId) body.channelId = channelId;
      await apiClient.post(`/api/billops/messaging/users/${userId}/connections`, body);
      await loadUserConnections(userId);
      return true;
    } catch (err) {
      console.error('Save user connection error:', err);
      return false;
    }
  }, [loadUserConnections]);

  const removeUserConnection = useCallback(async (userId: number, platform: string): Promise<boolean> => {
    try {
      await apiClient.fetch(`/api/billops/messaging/users/${userId}/connections/${platform}`, { method: 'DELETE' });
      await loadUserConnections(userId);
      return true;
    } catch (err) {
      console.error('Remove user connection error:', err);
      return false;
    }
  }, [loadUserConnections]);

  const loadIntegrationConfigs = loadIntegrationConfigsEarly;

  const saveIntegrationConfig = useCallback(async (
    platform: string,
    botToken?: string,
    signingSecret?: string,
    clientId?: string,
    clientSecret?: string,
  ): Promise<boolean> => {
    try {
      const body: Record<string, string> = { platform, enabled: 'true' };
      if (botToken) body.botToken = botToken;
      if (signingSecret) body.signingSecret = signingSecret;
      if (clientId) body.clientId = clientId;
      if (clientSecret) body.clientSecret = clientSecret;
      await apiClient.post('/api/billops/messaging/config', body);
      await loadIntegrationConfigs();
      return true;
    } catch (err) {
      console.error('Save integration config error:', err);
      return false;
    }
  }, [loadIntegrationConfigs]);

  const sendSlackInstallLink = useCallback(async (userId: number): Promise<{ sent: boolean; email?: string; error?: string }> => {
    try {
      const result = await apiClient.post<{ sent: boolean; email?: string; error?: string }>(
        `/api/billops/slack/oauth/install/${userId}`, {}
      );
      return result || { sent: false, error: 'No response' };
    } catch (err: any) {
      console.error('Send Slack install link error:', err);
      return { sent: false, error: err?.message || 'Failed to send install link' };
    }
  }, []);

  const setupGoogleChat = useCallback(async (userId: number): Promise<{ invited?: boolean; connected: boolean; spaceName?: string; error?: string; message?: string; errorCode?: string; suggestion?: string }> => {
    try {
      const result = await apiClient.post<{ invited?: boolean; connected: boolean; spaceName?: string; error?: string; message?: string; errorCode?: string; suggestion?: string }>(
        `/api/billops/gchat/setup/${userId}`, {}
      );
      if (result?.connected) await loadUserConnections(userId);
      return result || { connected: false, error: 'No response' };
    } catch (err: any) {
      console.error('Setup Google Chat error:', err);
      return { connected: false, error: err?.message || 'Failed to setup Google Chat' };
    }
  }, [loadUserConnections]);

  const sendTeamsInvite = useCallback(async (userId: number): Promise<{ invited: boolean; email?: string; error?: string; message?: string }> => {
    try {
      const result = await apiClient.post<{ invited: boolean; email?: string; error?: string; message?: string }>(
        `/api/billops/teams/invite/${userId}`, {}
      );
      return result || { invited: false, error: 'No response' };
    } catch (err: any) {
      console.error('Send Teams invite error:', err);
      return { invited: false, error: err?.message || 'Failed to send Teams invite' };
    }
  }, []);

  return {
    conversations, filteredConversations, messages, activeAccount, accounts,
    clients, clientProfiles,
    loading, chatLoading, sending, loadingAccounts, loadingClients, loadingProfiles, error,
    searchQuery, providerFilter,
    selectedPlatform, setSelectedPlatform,
    userConnections, integrationConfigs,
    loadUserConnections, saveUserConnection, removeUserConnection,
    loadIntegrationConfigs, saveIntegrationConfig, sendSlackInstallLink, setupGoogleChat, sendTeamsInvite,
    setSearchQuery, setProviderFilter, selectConversation,
    sendReply, startConversation, uploadAttachment,
    editAdminMessage, deleteAdminMessage,
    updateTicketStatus, sendTicketEmail,
    refreshInbox: fetchInbox, refreshChat: fetchChat,
    fetchAccounts, fetchClients, fetchClientProfiles,
  };
};

export default useSupportDashboard;
