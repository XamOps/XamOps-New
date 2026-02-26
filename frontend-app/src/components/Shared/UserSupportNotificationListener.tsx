import React, { useEffect, useRef, useCallback } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import apiClient from '../../utils/apiClient';
import { notifyNewSupportReply, initNotificationService, playNotificationSound } from '../../utils/notificationService';

const POLL_INTERVAL = 5000; // 5 seconds for background polling (same as chat widget)
const MAX_CONSECUTIVE_ERRORS = 5; // Stop polling after this many consecutive failures

interface ChatHistoryItem {
    content: string;
    userMessage: boolean;
    timestamp: string;
}

/**
 * Global User Notification Listener
 *
 * Background component that polls the chat history for new admin replies
 * when the user is a XamOps user. This ensures notifications work
 * even when the chat widget is closed or user is on a different page.
 *
 * Similar to AdminSupportNotificationListener but for the customer side.
 */
const UserSupportNotificationListener: React.FC = () => {
    const { hasAnyRole } = useAuth();
    const isXamopsUser = hasAnyRole('ROLE_XAMOPS');

    // Refs to track state between polls
    const intervalRef = useRef<NodeJS.Timeout | null>(null);
    const previousMessageCountRef = useRef<number>(0);
    const previousLastTimestampRef = useRef<string>('');
    const isInitializedRef = useRef(false);
    const isMountedRef = useRef(true);
    const consecutiveErrorsRef = useRef<number>(0);

    /**
     * Get selected account ID from sessionStorage
     */
    const getSelectedAccountId = useCallback((): string | null => {
        try {
            const selectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
            return selectedIds.length > 0 ? selectedIds[0] : null;
        } catch {
            return null;
        }
    }, []);

    const checkNewMessages = useCallback(async () => {
        if (!isMountedRef.current) return;

        try {
            const accountId = getSelectedAccountId();
            const url = accountId
                ? `/api/ai/chat/history?accountId=${encodeURIComponent(accountId)}`
                : '/api/ai/chat/history';

            const history = await apiClient.get<ChatHistoryItem[]>(url);
            if (!history || !Array.isArray(history)) return;

            const currentCount = history.length;
            const previousCount = previousMessageCountRef.current;

            // On first load, just store the count without notifying
            if (!isInitializedRef.current) {
                previousMessageCountRef.current = currentCount;
                if (history.length > 0) {
                    previousLastTimestampRef.current = history[history.length - 1].timestamp;
                }
                isInitializedRef.current = true;
                return;
            }

            // Check if there are new messages
            if (currentCount > previousCount) {
                // Get the new messages
                const newMessages = history.slice(previousCount);
                // Filter for admin messages only (userMessage = false means admin sent it)
                const newAdminMessages = newMessages.filter(msg => !msg.userMessage);

                if (newAdminMessages.length > 0) {
                    const latestMessage = newAdminMessages[newAdminMessages.length - 1];

                    // ALWAYS play notification sound for new admin messages
                    console.log('[UserNotification] New admin message detected, playing sound');
                    playNotificationSound();

                    // ALWAYS show browser notification for new admin messages
                    // This ensures user gets notified even when on a different page
                    console.log('[UserNotification] Showing browser notification');
                    notifyNewSupportReply(latestMessage.content, { playSound: false });

                    // Dispatch custom event so chat widget can refresh if open
                    window.dispatchEvent(new CustomEvent('newAdminMessage', {
                        detail: { message: latestMessage.content, count: newAdminMessages.length }
                    }));
                }
            }

            // Update tracking refs
            previousMessageCountRef.current = currentCount;
            if (history.length > 0) {
                previousLastTimestampRef.current = history[history.length - 1].timestamp;
            }

            // Reset error counter on successful response
            consecutiveErrorsRef.current = 0;

        } catch (error) {
            consecutiveErrorsRef.current++;
            console.error('[UserNotification] Failed to poll chat history:', error);

            // Stop polling after repeated consecutive failures to prevent log spam
            if (consecutiveErrorsRef.current >= MAX_CONSECUTIVE_ERRORS) {
                console.warn(`[UserNotification] Stopping poll after ${MAX_CONSECUTIVE_ERRORS} consecutive failures.`);
                if (intervalRef.current) clearInterval(intervalRef.current);
            }
        }
    }, [getSelectedAccountId]);

    useEffect(() => {
        if (!isXamopsUser) {
            if (intervalRef.current) clearInterval(intervalRef.current);
            return;
        }

        isMountedRef.current = true;

        // Initialize notification service
        initNotificationService();

        // Initial check (with small delay to let main components load first)
        const initialTimeout = setTimeout(() => {
            checkNewMessages();
        }, 1000);

        // Set up periodic polling
        intervalRef.current = setInterval(checkNewMessages, POLL_INTERVAL);

        return () => {
            isMountedRef.current = false;
            clearTimeout(initialTimeout);
            if (intervalRef.current) clearInterval(intervalRef.current);
        };
    }, [isXamopsUser, checkNewMessages]);

    // Listen for account changes
    useEffect(() => {
        const handleStorageChange = () => {
            // Reset state when account changes
            previousMessageCountRef.current = 0;
            previousLastTimestampRef.current = '';
            isInitializedRef.current = false;
        };

        window.addEventListener('storage', handleStorageChange);
        return () => window.removeEventListener('storage', handleStorageChange);
    }, []);

    return null; // Side-effect only component
};

export default UserSupportNotificationListener;
