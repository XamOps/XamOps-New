/**
 * Support Chat Hook
 *
 * Manages chat state, messages, and API communication for the support chat widget.
 * Converted from: _support-chat-widget.html
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Used React hooks for state management
 * - Added proper type safety
 * - Used apiClient for consistent API calls
 *
 * What stayed the same:
 * - All API endpoints remain identical (/api/ai/chat, /api/ai/chat/history)
 * - Same CSRF token handling
 * - Same role-based visibility (ROLE_XAMOPS only)
 * - Same auto-refresh logic (5 second interval)
 * - Same message format and error handling
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useAuth } from '../contexts/AuthContext';
import apiClient from '../utils/apiClient';
import { initNotificationService } from '../utils/notificationService';

export interface ChatMessage {
  id?: number;      // Real database ID (needed for edit/delete)
  text: string;
  isUser: boolean;
  timestamp?: string;
  isEdited?: boolean;
  isDeleted?: boolean;
  status?: string;  // 'UNREAD' | 'READ' | 'DELIVERED'
}

interface ChatHistoryItem {
  id: number;
  content: string;
  userMessage: boolean;
  timestamp: string;
  isEdited?: boolean;
  isDeleted?: boolean;
  status?: string;
}

interface UseSupportChatReturn {
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
  messages: ChatMessage[];
  newMessage: string;
  setNewMessage: (message: string) => void;
  isLoading: boolean;
  isXamopsUser: boolean;
  unreadFromAdmin: number;  // count of admin messages user hasn't seen yet
  sendMessage: () => Promise<void>;
  fetchHistory: () => Promise<void>;
  editMessage: (id: number, content: string) => Promise<void>;
  deleteMessage: (id: number) => Promise<void>;
  messagesEndRef: React.RefObject<HTMLDivElement | null>;
}

/**
 * Get selected account ID from sessionStorage
 */
const getSelectedAccountId = (): string | null => {
  try {
    const selectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
    return selectedIds.length > 0 ? selectedIds[0] : null;
  } catch {
    return null;
  }
};

export const useSupportChat = (): UseSupportChatReturn => {
  const { hasAnyRole } = useAuth();

  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [newMessage, setNewMessage] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const isInitializedRef = useRef(false);
  const previousMessageCountRef = useRef(0);
  const notificationInitializedRef = useRef(false);
  const isOpenRef = useRef(isOpen); // Ref to track current isOpen state for use in callbacks
  const fetchHistoryRef = useRef<(() => Promise<void>) | undefined>(undefined); // Ref to store latest fetchHistory function

  // Check if user has ROLE_XAMOPS
  const isXamopsUser = hasAnyRole('ROLE_XAMOPS');

  // Keep isOpenRef in sync with isOpen state
  useEffect(() => {
    isOpenRef.current = isOpen;
  }, [isOpen]);

  /**
   * Scroll to bottom of chat
   */
  const scrollToBottom = useCallback(() => {
    setTimeout(() => {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, 100);
  }, []);

  /**
   * Fetch chat history from API
   * Note: Notifications are handled by the global UserSupportNotificationListener
   * to avoid duplicate sounds. This function only updates UI state.
   */
  const fetchHistory = useCallback(async () => {
    if (!isXamopsUser) return;

    try {
      // Return all messages for the current user — user-based conversations, no accountId filter
      const url = '/api/ai/chat/history';

      const history = await apiClient.get<any[]>(url);

      if (Array.isArray(history)) {
        const mappedMessages = history.map((msg) => ({
          id: msg.id,
          text: msg.content,
          isUser: msg.userMessage,
          timestamp: msg.timestamp,
          // Normalize: Jackson strips "is" prefix, so backend may return "deleted"/"edited"
          isEdited: msg.isEdited ?? msg.edited ?? false,
          isDeleted: msg.isDeleted ?? msg.deleted ?? false,
          status: msg.status,
        }));

        previousMessageCountRef.current = mappedMessages.length;
        setMessages(mappedMessages);
        scrollToBottom();

        // Auto-mark admin messages as read when widget is open
        if (isOpenRef.current) {
          apiClient.post('/api/ai/chat/mark-read', {}).catch(() => {});
        }
      }
    } catch (error) {
      console.error('Failed to fetch chat history:', error);
    }
  }, [isXamopsUser, scrollToBottom]);

  // Keep fetchHistoryRef updated with the latest fetchHistory function
  useEffect(() => {
    fetchHistoryRef.current = fetchHistory;
  }, [fetchHistory]);

  /**
   * Send a new message
   */
  const sendMessage = useCallback(async () => {
    if (newMessage.trim() === '' || isLoading) return;

    // Add user message immediately for better UX (no id — will be replaced by fetchHistory)
    const userMsg: ChatMessage = {
      text: newMessage,
      isUser: true,
      timestamp: new Date().toISOString()
    };

    setMessages(prev => [...prev, userMsg]);
    const messageToSend = newMessage;
    setNewMessage('');
    setIsLoading(true);
    scrollToBottom();

    try {
      const response = await apiClient.fetch('/api/ai/chat', {
        method: 'POST',
        body: JSON.stringify({
          message: messageToSend,
          accountId: getSelectedAccountId()
        })
      });

      if (response.status === 401 || response.status === 403) {
        setMessages(prev => [...prev, {
          text: 'Session expired. Please refresh the page to log in again.',
          isUser: false
        }]);
        return;
      }

      if (!response.ok) {
        const errorText = await response.text();
        console.error('Server Error:', response.status, errorText);
        throw new Error(`Server error: ${response.status}`);
      }

      // Fetch updated history after sending
      await fetchHistory();

    } catch (error) {
      console.error('Chat Error:', error);
      setMessages(prev => [...prev, {
        text: 'Connection failed. Please try again later.',
        isUser: false
      }]);
    } finally {
      setIsLoading(false);
      scrollToBottom();
    }
  }, [newMessage, isLoading, fetchHistory, scrollToBottom]);

  /** Edit own message within 15-minute window */
  const editMessage = useCallback(async (id: number, content: string) => {
    try {
      await apiClient.fetch(`/api/ai/chat/messages/${id}`, {
        method: 'PATCH',
        body: JSON.stringify({ content }),
      });
      await fetchHistory();
    } catch (error) {
      console.error('Edit message failed:', error);
    }
  }, [fetchHistory]);

  /** Soft-delete own message within 60-minute window */
  const deleteMessage = useCallback(async (id: number) => {
    try {
      await apiClient.fetch(`/api/ai/chat/messages/${id}`, { method: 'DELETE' });
      await fetchHistory();
    } catch (error) {
      console.error('Delete message failed:', error);
    }
  }, [fetchHistory]);

  // Initialize chat and set up polling
  useEffect(() => {
    if (!isXamopsUser || isInitializedRef.current) return;

    isInitializedRef.current = true;

    // Initialize notification service (requests permission)
    if (!notificationInitializedRef.current) {
      notificationInitializedRef.current = true;
      initNotificationService();
    }

    // Initial fetch
    fetchHistoryRef.current?.();

    // Set up polling every 5 seconds
    // Use fetchHistoryRef.current to always call the latest version of fetchHistory
    intervalRef.current = setInterval(() => {
      fetchHistoryRef.current?.();
    }, 5000);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [isXamopsUser]);

  // Listen for new admin message events from the global UserSupportNotificationListener
  useEffect(() => {
    const handleNewAdminMessage = () => {
      // Refresh chat history when global listener detects new messages
      fetchHistoryRef.current?.();
      scrollToBottom();
    };

    window.addEventListener('newAdminMessage', handleNewAdminMessage);
    return () => {
      window.removeEventListener('newAdminMessage', handleNewAdminMessage);
    };
  }, [scrollToBottom]);

  // Fetch history and mark messages as read when chat opens
  useEffect(() => {
    if (isOpen && isXamopsUser) {
      fetchHistory();
      scrollToBottom();

      // Mark admin messages as read when chat is opened (user-based — no accountId needed)
      apiClient.post('/api/ai/chat/mark-read', {}).catch(err => {
        console.warn('Failed to mark messages as read:', err);
      });
    }
  }, [isOpen, isXamopsUser, fetchHistory, scrollToBottom]);

  const unreadFromAdmin = messages.filter(m => !m.isUser && m.status === 'DELIVERED').length;

  return {
    isOpen,
    setIsOpen,
    messages,
    newMessage,
    setNewMessage,
    isLoading,
    isXamopsUser,
    unreadFromAdmin,
    sendMessage,
    fetchHistory,
    editMessage,
    deleteMessage,
    messagesEndRef
  };
};

export default useSupportChat;
