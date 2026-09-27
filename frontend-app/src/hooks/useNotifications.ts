/**
 * useNotifications Hook
 *
 * Custom hook to fetch and manage notification data for the topbar bell icon
 * Aggregates CloudGuard alerts and budget alerts from the last 7 days
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import type { NotificationItem, NotificationCounts } from '../types/notifications';
import type { Alert } from '../types/alerts';
import type { Budget } from '../types/finops';
import apiClient from '../utils/apiClient';

interface UseNotificationsOptions {
    selectedAccountId: string | null;
    provider?: string; // e.g. 'AWS', 'GCP', 'Azure' — CloudGuard alerts are AWS-only
    autoRefreshInterval?: number; // in milliseconds, default 5 minutes
}

interface UseNotificationsReturn {
    notifications: NotificationItem[];
    counts: NotificationCounts;
    isLoading: boolean;
    refresh: () => Promise<void>;
}

interface ChatHistoryItem {
    content: string;
    userMessage: boolean;
    timestamp: string;
}

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
const DEFAULT_REFRESH_INTERVAL = 5 * 60 * 1000; // 5 minutes
const BUDGET_ALERT_THRESHOLD = 0.8; // 80% threshold for budget alerts
const CHAT_NOTIFICATION_WINDOW = 24 * 60 * 60 * 1000; // Show chat notifications from last 24 hours

export const useNotifications = ({
    selectedAccountId,
    provider,
    autoRefreshInterval = DEFAULT_REFRESH_INTERVAL,
}: UseNotificationsOptions): UseNotificationsReturn => {
    const [notifications, setNotifications] = useState<NotificationItem[]>([]);
    const [isLoading, setIsLoading] = useState<boolean>(false);
    const abortControllerRef = useRef<AbortController | null>(null);

    /**
     * Convert CloudGuard alert to NotificationItem
     */
    const convertCloudGuardAlert = useCallback((alert: Alert): NotificationItem => {
        const severity = alert.type === 'ANOMALY'
            ? 'critical'
            : alert.type === 'QUOTA' && (alert.usage / alert.limit) > 0.9
                ? 'critical'
                : 'warning';

        return {
            id: `cloudguard-${alert.id}`,
            type: 'cloudguard',
            severity,
            title: `${alert.service}: ${alert.name}`,
            description: alert.description,
            timestamp: new Date(), // CloudGuard alerts don't have timestamp, use current time
            link: '/alerts',
        };
    }, []);

    /**
     * Convert Budget to NotificationItem (if exceeding threshold)
     */
    const convertBudgetAlert = useCallback((budget: Budget): NotificationItem | null => {
        const percentage = budget.actualSpend / budget.budgetLimit;

        if (percentage < BUDGET_ALERT_THRESHOLD) {
            return null; // Not exceeding threshold
        }

        const severity = percentage >= 1.0 ? 'critical' : percentage >= 0.9 ? 'warning' : 'info';

        return {
            id: `budget-${budget.budgetName}`,
            type: 'budget',
            severity,
            title: `Budget Alert: ${budget.budgetName}`,
            description: `${(percentage * 100).toFixed(1)}% of budget used ($${budget.actualSpend.toFixed(2)} / $${budget.budgetLimit.toFixed(2)})`,
            timestamp: new Date(),
            link: '/finops',
        };
    }, []);

    /**
     * Get the last time the chat was opened from localStorage
     */
    const getLastChatOpenTime = useCallback((): number => {
        try {
            const stored = localStorage.getItem('lastChatOpenTime');
            return stored ? parseInt(stored, 10) : Date.now() - CHAT_NOTIFICATION_WINDOW;
        } catch {
            return Date.now() - CHAT_NOTIFICATION_WINDOW;
        }
    }, []);

    /**
     * Convert chat message to NotificationItem (if unread)
     */
    const convertChatMessage = useCallback((msg: ChatHistoryItem, index: number): NotificationItem | null => {
        // Only show messages from support (not user messages)
        if (msg.userMessage) {
            return null;
        }

        const messageTime = new Date(msg.timestamp).getTime();
        const lastOpenTime = getLastChatOpenTime();

        // Only show messages that arrived after the last time chat was opened
        if (messageTime <= lastOpenTime) {
            return null;
        }

        // Truncate long messages
        const description = msg.content.length > 80
            ? msg.content.substring(0, 80) + '...'
            : msg.content;

        return {
            id: `chat-${index}-${msg.timestamp}`,
            type: 'chat',
            severity: 'info',
            title: 'New Support Message',
            description,
            timestamp: new Date(msg.timestamp),
            isUnread: true,
        };
    }, [getLastChatOpenTime]);

    /**
     * Fetch all notifications — cloudguard/alerts and budgets in parallel.
     */
    const fetchNotifications = useCallback(async () => {
        if (!selectedAccountId) {
            setNotifications([]);
            return;
        }

        // Cancel previous in-flight fetch
        abortControllerRef.current?.abort();
        abortControllerRef.current = new AbortController();
        const { signal } = abortControllerRef.current;

        setIsLoading(true);
        const allNotifications: NotificationItem[] = [];

        // CloudGuard alerts are AWS-only — skip for GCP/Azure accounts
        const isAwsAccount = !provider || provider === 'AWS';

        try {
            // Fire both network requests in parallel — previously sequential,
            // which meant a slow cloudguard/alerts (55s timeout) blocked budgets.
            const [cloudGuardResult, budgetsResult] = await Promise.allSettled([
                isAwsAccount
                    ? fetch(
                        `/api/xamops/cloudguard/alerts?accountId=${selectedAccountId}&forceRefresh=false`,
                        { credentials: 'include', signal }
                    )
                    : Promise.resolve(new Response('[]', { status: 200 })),
                fetch(
                    `/api/xamops/finops/budgets?accountId=${selectedAccountId}`,
                    { credentials: 'include', signal }
                ),
            ]);

            if (cloudGuardResult.status === 'fulfilled' && cloudGuardResult.value.ok) {
                const cloudGuardData = await cloudGuardResult.value.json();
                if (Array.isArray(cloudGuardData)) {
                    allNotifications.push(...cloudGuardData.map(convertCloudGuardAlert));
                }
            }

            if (budgetsResult.status === 'fulfilled' && budgetsResult.value.ok) {
                const budgets = await budgetsResult.value.json();
                if (Array.isArray(budgets)) {
                    allNotifications.push(
                        ...budgets
                            .map(convertBudgetAlert)
                            .filter((n: NotificationItem | null): n is NotificationItem => n !== null)
                    );
                }
            }

            // Fetch Chat history for unread messages
            try {
                const url = `/api/ai/chat/history?accountId=${encodeURIComponent(selectedAccountId)}`;
                const chatHistory = await apiClient.get<ChatHistoryItem[]>(url);

                if (Array.isArray(chatHistory)) {
                    const chatNotifications = chatHistory
                        .map((msg, index) => convertChatMessage(msg, index))
                        .filter((n): n is NotificationItem => n !== null);
                    allNotifications.push(...chatNotifications);
                }
            } catch (error) {
                // Chat history might not be available for all users, silently ignore
                console.debug('[Notifications] Chat history not available:', error);
            }

            // Filter to last 7 days and sort by timestamp (newest first)
            const sevenDaysAgo = Date.now() - SEVEN_DAYS_MS;
            const recentNotifications = allNotifications
                .filter(n => n.timestamp.getTime() > sevenDaysAgo)
                .sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());

            setNotifications(recentNotifications);
        } catch (error) {
            if (error instanceof DOMException && (error as DOMException).name === 'AbortError') return;
            console.error('[Notifications] Error fetching notifications:', error);
            setNotifications([]);
        } finally {
            setIsLoading(false);
        }
    }, [selectedAccountId, provider, convertCloudGuardAlert, convertBudgetAlert, convertChatMessage]);

    /**
     * Calculate notification counts
     */
    const counts = useMemo((): NotificationCounts => {
        const cloudguard = notifications.filter(n => n.type === 'cloudguard').length;
        const grafana = notifications.filter(n => n.type === 'grafana').length;
        const budget = notifications.filter(n => n.type === 'budget').length;
        const chat = notifications.filter(n => n.type === 'chat').length;

        return {
            total: notifications.length,
            cloudguard,
            grafana,
            budget,
            chat,
        };
    }, [notifications]);

    /**
     * Auto-refresh notifications
     */
    useEffect(() => {
        if (!selectedAccountId) return;

        // Initial fetch
        fetchNotifications();

        // Set up auto-refresh
        const intervalId = setInterval(fetchNotifications, autoRefreshInterval);

        return () => {
            clearInterval(intervalId);
            abortControllerRef.current?.abort();
        };
    }, [selectedAccountId, autoRefreshInterval, fetchNotifications]);

    return {
        notifications,
        counts,
        isLoading,
        refresh: fetchNotifications,
    };
};

export default useNotifications;

