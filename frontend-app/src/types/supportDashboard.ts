/**
 * Support Dashboard Types
 *
 * Types for the BillOps Admin Support Console (support-dashboard.html)
 * Used by ROLE_BILLOPS_ADMIN to view and respond to customer conversations
 */

/**
 * Cloud provider type
 */
export type CloudProvider = 'AWS' | 'GCP' | 'Azure';

/**
 * Conversation/Account in the support inbox
 */
export interface SupportConversation {
  accountId: string;
  accountName: string;
  senderUsername: string;
  provider: CloudProvider;
  lastMessage?: string;
  lastMessageTime?: string;
  unreadCount?: number;
}

/**
 * Chat message in a conversation
 */
export interface ChatMessage {
  id: number;
  accountId: string;
  content: string;
  timestamp: string;
  userMessage: boolean; // true = from customer, false = from admin
  senderName?: string;
}

/**
 * Reply payload for sending a message
 */
export interface ReplyPayload {
  accountId: string;
  message: string;
}

/**
 * Support Dashboard state
 */
export interface SupportDashboardState {
  conversations: SupportConversation[];
  messages: ChatMessage[];
  activeAccount: SupportConversation | null;
  searchQuery: string;
  providerFilter: CloudProvider | '';
  loading: boolean;
  chatLoading: boolean;
  sending: boolean;
  error: string | null;
}
