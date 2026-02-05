/**
 * useNotifications Hook
 *
 * Custom hook to fetch and manage notification data for the topbar bell icon
 * Aggregates CloudGuard alerts, Grafana alerts, and budget alerts from the last 7 days
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { NotificationItem, NotificationCounts } from '../types/notifications';
import type { Alert, GrafanaAlert } from '../types/alerts';
import type { Budget } from '../types/finops';

interface UseNotificationsOptions {
    selectedAccountId: string | null;
    autoRefreshInterval?: number; // in milliseconds, default 5 minutes
}

interface UseNotificationsReturn {
    notifications: NotificationItem[];
    counts: NotificationCounts;
    isLoading: boolean;
    refresh: () => Promise<void>;
}

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;
const DEFAULT_REFRESH_INTERVAL = 5 * 60 * 1000; // 5 minutes
const BUDGET_ALERT_THRESHOLD = 0.8; // 80% threshold for budget alerts

export const useNotifications = ({
    selectedAccountId,
    autoRefreshInterval = DEFAULT_REFRESH_INTERVAL,
}: UseNotificationsOptions): UseNotificationsReturn => {
    const [notifications, setNotifications] = useState<NotificationItem[]>([]);
    const [isLoading, setIsLoading] = useState<boolean>(false);

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
     * Convert Grafana alert to NotificationItem
     */
    const convertGrafanaAlert = useCallback((alert: GrafanaAlert): NotificationItem => {
        return {
            id: `grafana-${alert.id}`,
            type: 'grafana',
            severity: alert.status === 'firing' ? 'critical' : 'info',
            title: alert.name,
            description: alert.description || 'Grafana alert triggered',
            timestamp: new Date(), // Grafana alerts don't have timestamp in current schema
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
     * Fetch all notifications
     */
    const fetchNotifications = useCallback(async () => {
        if (!selectedAccountId) {
            setNotifications([]);
            return;
        }

        setIsLoading(true);
        const allNotifications: NotificationItem[] = [];

        try {
            // Fetch CloudGuard alerts
            const cloudGuardResponse = await fetch(
                `/api/xamops/cloudguard/alerts?accountId=${selectedAccountId}&forceRefresh=false`,
                { credentials: 'include' }
            );

            if (cloudGuardResponse.ok) {
                const cloudGuardData: Alert[] = await cloudGuardResponse.json();
                const cloudGuardNotifications = cloudGuardData.map(convertCloudGuardAlert);
                allNotifications.push(...cloudGuardNotifications);
            }

            // Fetch Grafana alerts
            const grafanaResponse = await fetch('/api/xamops/cloudguard/grafana-alerts', {
                credentials: 'include',
            });

            if (grafanaResponse.ok) {
                const grafanaData: GrafanaAlert[] = await grafanaResponse.json();
                const grafanaNotifications = grafanaData
                    .filter(alert => alert.status === 'firing') // Only show firing alerts
                    .map(convertGrafanaAlert);
                allNotifications.push(...grafanaNotifications);
            }

            // Fetch Budget data from FinOps
            const finopsResponse = await fetch(
                `/api/xamops/finops/report?accountId=${selectedAccountId}`,
                { credentials: 'include' }
            );

            if (finopsResponse.ok) {
                const finopsData = await finopsResponse.json();
                if (finopsData.budgets && Array.isArray(finopsData.budgets)) {
                    const budgetNotifications = finopsData.budgets
                        .map(convertBudgetAlert)
                        .filter((n: NotificationItem | null): n is NotificationItem => n !== null);
                    allNotifications.push(...budgetNotifications);
                }
            }

            // Filter to last 7 days and sort by timestamp (newest first)
            const sevenDaysAgo = Date.now() - SEVEN_DAYS_MS;
            const recentNotifications = allNotifications
                .filter(n => n.timestamp.getTime() > sevenDaysAgo)
                .sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());

            setNotifications(recentNotifications);
        } catch (error) {
            console.error('[Notifications] Error fetching notifications:', error);
            setNotifications([]);
        } finally {
            setIsLoading(false);
        }
    }, [selectedAccountId, convertCloudGuardAlert, convertGrafanaAlert, convertBudgetAlert]);

    /**
     * Calculate notification counts
     */
    const counts = useMemo((): NotificationCounts => {
        const cloudguard = notifications.filter(n => n.type === 'cloudguard').length;
        const grafana = notifications.filter(n => n.type === 'grafana').length;
        const budget = notifications.filter(n => n.type === 'budget').length;

        return {
            total: notifications.length,
            cloudguard,
            grafana,
            budget,
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

        return () => clearInterval(intervalId);
    }, [selectedAccountId, autoRefreshInterval, fetchNotifications]);

    return {
        notifications,
        counts,
        isLoading,
        refresh: fetchNotifications,
    };
};

export default useNotifications;

