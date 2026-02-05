/**
 * Notification Types
 *
 * TypeScript types for the notification dropdown system
 */

export type NotificationType = 'cloudguard' | 'grafana' | 'budget' | 'chat';

export type NotificationSeverity = 'critical' | 'warning' | 'info';

export interface NotificationItem {
    id: string;
    type: NotificationType;
    severity: NotificationSeverity;
    title: string;
    description: string;
    timestamp: Date;
    link?: string; // Optional link to view details
    isUnread?: boolean; // For chat messages
}

export interface NotificationCounts {
    total: number;
    cloudguard: number;
    grafana: number;
    budget: number;
    chat: number;
}

export interface NotificationDropdownState {
    isOpen: boolean;
    notifications: NotificationItem[];
    counts: NotificationCounts;
    isLoading: boolean;
}
