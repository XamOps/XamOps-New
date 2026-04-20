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
 * Ticket lifecycle status
 */
export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'CLOSED';

/**
 * Conversation in the support inbox — keyed by userId (one thread per user)
 */
export interface SupportConversation {
  userId?: number;                  // PRIMARY conversation key
  username: string;                 // Display name of the user
  email?: string;                   // User's registered email address
  accountId?: string;               // Optional — user-side messages still carry accountId for reference
  accountName?: string;
  senderUsername?: string;
  provider?: CloudProvider;
  lastMessage?: string;
  lastMessageTime?: string;
  unreadCount?: number;
  adminUnreadCount?: number;        // admin messages the user hasn't seen yet
  ticketStatus?: TicketStatus;      // OPEN | IN_PROGRESS | CLOSED
  assignedAdminUsername?: string;   // Which admin is currently handling this ticket
}

/**
 * Chat message in a conversation
 */
export interface ChatMessage {
  id: number;
  accountId: string;
  content: string;
  timestamp: string;
  userMessage: boolean;  // true = from customer, false = from admin
  senderName?: string;
  senderUsername?: string;  // actual username of sender (admin username for admin messages)
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentType?: string;
  attachmentSize?: number;
  isEdited?: boolean;
  isDeleted?: boolean;
  status?: string;  // 'UNREAD' | 'READ' | 'DELIVERED'
  externalPlatform?: string;    // 'SLACK' | 'TEAMS' | 'GOOGLE_CHAT' — set when sent/received via a platform
  externalMessageId?: string;   // Message ID/TS from the external platform
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
 * Client (company) that has cloud accounts and users
 */
export interface SupportClient {
  id: number;
  name: string;
}

/**
 * User profile within a client, with their assigned accounts
 */
export interface SupportProfile {
  id: number;
  username: string;
  email?: string;
  contact?: string;
  accounts: SupportAccount[];
}

/**
 * Reply payload for sending a message
 */
export interface ReplyPayload {
  accountId?: string;
  message: string;
  accountName?: string;      // Required when admin initiates a new conversation
  cloudProvider?: string;    // Required when admin initiates a new conversation
  userId?: string;           // Profile/user ID for admin-initiated conversations
  autoPingEnabled?: string;  // "true" / "false"
  autoPingChannels?: string; // Comma-separated: "WHATSAPP,SMS,EMAIL"
  attachmentUrl?: string;
  attachmentName?: string;
  attachmentType?: string;
  attachmentSize?: string;   // String because backend uses Map<String, String>
  externalPlatform?: string; // 'SLACK' | 'TEAMS' | 'GOOGLE_CHAT' — routes message to that platform
}

/**
 * Per-user platform connection (webhook URL stored for Slack/Teams/Google Chat)
 */
export interface UserPlatformConnection {
  id?: number;
  userId: number;
  platform: string;          // 'SLACK' | 'TEAMS' | 'GOOGLE_CHAT'
  webhookUrl?: string;
  channelId?: string;
  platformUserId?: string;
  workspaceId?: string;
  /** true when the user has completed the Slack OAuth install flow */
  oauthConnected?: boolean;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Admin integration config for a platform (bot token etc. — tokens are masked)
 */
export interface IntegrationConfig {
  id?: number;
  platform: string;
  label?: string;
  enabled: boolean;
  botTokenConfigured: boolean;
  signingSecretConfigured: boolean;
  /** true when Client ID has been saved (OAuth distributed app flow) */
  clientIdConfigured: boolean;
  /** true when Client Secret has been saved */
  clientSecretConfigured: boolean;
  /** Slack App Client ID — safe to expose (not the secret) */
  clientId?: string;
  /** true when Google Chat service account JSON has been saved */
  serviceAccountConfigured: boolean;
  /** admin Workspace email for DWD impersonation */
  adminEmail?: string;
  adminEmailConfigured?: boolean;
  updatedAt?: string;
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
