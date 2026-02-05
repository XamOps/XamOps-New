/**
 * Notification Types
 *
 * TypeScript types for the notification dropdown system
 */

export type NotificationType = 'cloudguard' | 'grafana' | 'budget';

export type NotificationSeverity = 'critical' | 'warning' | 'info';

export interface NotificationItem {
    id: string;
    type: NotificationType;
    severity: NotificationSeverity;
    title: string;
    description: string;
    timestamp: Date;
    link?: string; // Optional link to view details
}

export interface NotificationCounts {
    total: number;
    cloudguard: number;
    grafana: number;
    budget: number;
}

export interface NotificationDropdownState {
    isOpen: boolean;
    notifications: NotificationItem[];
    counts: NotificationCounts;
    isLoading: boolean;
}
