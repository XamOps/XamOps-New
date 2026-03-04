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
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentType?: string;
  attachmentSize?: number;
}

/**
 * Uploaded attachment info (returned by upload endpoint)
 */
export interface AttachmentInfo {
  url: string;
  name: string;
  type: string;
  size: number;
}

/**
 * Cloud account available for admin to start a conversation with
 */
export interface SupportAccount {
  id: number;
  accountId: string;
  accountName: string;
  provider: CloudProvider;
}

/**
 * Reply payload for sending a message
 */
export interface ReplyPayload {
  accountId: string;
  message: string;
  accountName?: string;    // Required when admin initiates a new conversation
  cloudProvider?: string;  // Required when admin initiates a new conversation
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentType?: string;
  attachmentSize?: string; // String because backend uses Map<String, String>
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
