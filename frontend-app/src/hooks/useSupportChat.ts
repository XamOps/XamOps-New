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

export interface ChatMessage {
  id?: string;
  text: string;
  isUser: boolean;
  timestamp?: string;
}

interface ChatHistoryItem {
  content: string;
  userMessage: boolean;
  timestamp: string;
}

interface UseSupportChatReturn {
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
  messages: ChatMessage[];
  newMessage: string;
  setNewMessage: (message: string) => void;
  isLoading: boolean;
  isXamopsUser: boolean;
  sendMessage: () => Promise<void>;
  fetchHistory: () => Promise<void>;
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

  // Check if user has ROLE_XAMOPS
  const isXamopsUser = hasAnyRole('ROLE_XAMOPS');

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
   */
  const fetchHistory = useCallback(async () => {
    if (!isXamopsUser) return;

    try {
      const accountId = getSelectedAccountId();
      const url = accountId
        ? `/api/ai/chat/history?accountId=${encodeURIComponent(accountId)}`
        : '/api/ai/chat/history';

      const history = await apiClient.get<ChatHistoryItem[]>(url);

      if (Array.isArray(history)) {
        setMessages(history.map((msg, index) => ({
          id: `msg-${index}-${msg.timestamp}`,
          text: msg.content,
          isUser: msg.userMessage,
          timestamp: msg.timestamp
        })));
        scrollToBottom();
      }
    } catch (error) {
      console.error('Failed to fetch chat history:', error);
    }
  }, [isXamopsUser, scrollToBottom]);

  /**
   * Send a new message
   */
  const sendMessage = useCallback(async () => {
    if (newMessage.trim() === '' || isLoading) return;

    // Add user message immediately for better UX
    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
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
          id: `error-${Date.now()}`,
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
        id: `error-${Date.now()}`,
        text: 'Connection failed. Please try again later.',
        isUser: false
      }]);
    } finally {
      setIsLoading(false);
      scrollToBottom();
    }
  }, [newMessage, isLoading, fetchHistory, scrollToBottom]);

  // Initialize chat and set up polling
  useEffect(() => {
    if (!isXamopsUser || isInitializedRef.current) return;

    isInitializedRef.current = true;

    // Initial fetch
    fetchHistory();

    // Set up polling every 5 seconds
    intervalRef.current = setInterval(() => {
      fetchHistory();
    }, 5000);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [isXamopsUser, fetchHistory]);

  // Fetch history when chat opens
  useEffect(() => {
    if (isOpen && isXamopsUser) {
      fetchHistory();
      scrollToBottom();
    }
  }, [isOpen, isXamopsUser, fetchHistory, scrollToBottom]);

  return {
    isOpen,
    setIsOpen,
    messages,
    newMessage,
    setNewMessage,
    isLoading,
    isXamopsUser,
    sendMessage,
    fetchHistory,
    messagesEndRef
  };
};

export default useSupportChat;
