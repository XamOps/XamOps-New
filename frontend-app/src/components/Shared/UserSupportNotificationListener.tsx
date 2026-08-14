import React, { useEffect, useRef } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import apiClient from '../../utils/apiClient';
import { notifyNewSupportReply, initNotificationService, playNotificationSound } from '../../utils/notificationService';

const POLL_INTERVAL = 5000;
const MAX_CONSECUTIVE_ERRORS = 5;
/** Backoff applied after MAX_CONSECUTIVE_ERRORS, instead of giving up permanently. */
const ERROR_BACKOFF_MS = 60_000;

interface UnreadSummary {
    unreadCount: number;
    latestMessage?: string;
    latestId?: number;
    latestTimestamp?: string;
}

const UserSupportNotificationListener: React.FC = () => {
    const { hasAnyRole } = useAuth();
    const isXamopsUser = hasAnyRole('ROLE_XAMOPS');

    const intervalRef = useRef<NodeJS.Timeout | null>(null);
    const lastNotifiedIdRef = useRef<number | null>(null);
    const isInitializedRef = useRef(false);
    const isMountedRef = useRef(true);
    const consecutiveErrorsRef = useRef<number>(0);
    const backoffUntilRef = useRef<number>(0);

    useEffect(() => {
        if (!isXamopsUser) {
            if (intervalRef.current) clearInterval(intervalRef.current);
            return;
        }

        isMountedRef.current = true;
        initNotificationService();

        const checkNewMessages = async () => {
            if (!isMountedRef.current) return;
            // Skip while backgrounded — nobody can see a notification on a hidden tab,
            // and this used to poll the backend every 5s regardless.
            if (document.hidden) return;
            if (Date.now() < backoffUntilRef.current) return;

            try {
                // Uses /chat/unread rather than /chat/history: a count plus one row, instead
                // of the user's entire message history downloaded every 5 seconds.
                const summary = await apiClient.get<UnreadSummary>('/api/ai/chat/unread');
                if (!isMountedRef.current || !summary) return;

                const latestId = summary.latestId ?? null;

                // First successful poll only establishes a baseline, so existing unread
                // messages don't fire a notification burst on page load.
                if (!isInitializedRef.current) {
                    lastNotifiedIdRef.current = latestId;
                    isInitializedRef.current = true;
                    consecutiveErrorsRef.current = 0;
                    return;
                }

                const isNew = latestId !== null && latestId !== lastNotifiedIdRef.current;
                if (isNew && summary.unreadCount > 0 && summary.latestMessage) {
                    lastNotifiedIdRef.current = latestId;
                    playNotificationSound();
                    notifyNewSupportReply(summary.latestMessage, { playSound: false });
                    window.dispatchEvent(new CustomEvent('newAdminMessage', {
                        detail: { message: summary.latestMessage, count: summary.unreadCount }
                    }));
                } else if (latestId !== null) {
                    lastNotifiedIdRef.current = latestId;
                }

                consecutiveErrorsRef.current = 0;

            } catch (error) {
                consecutiveErrorsRef.current++;
                console.error('[UserNotification] Failed to poll unread messages:', error);
                if (consecutiveErrorsRef.current >= MAX_CONSECUTIVE_ERRORS) {
                    // Back off rather than stopping forever. The previous version cleared the
                    // interval permanently, so a transient backend outage silently disabled
                    // support notifications until a full page reload.
                    console.warn(`[UserNotification] Backing off ${ERROR_BACKOFF_MS}ms after ${MAX_CONSECUTIVE_ERRORS} failures.`);
                    backoffUntilRef.current = Date.now() + ERROR_BACKOFF_MS;
                    consecutiveErrorsRef.current = 0;
                }
            }
        };

        const initialTimeout = setTimeout(checkNewMessages, 1000);
        intervalRef.current = setInterval(checkNewMessages, POLL_INTERVAL);

        return () => {
            isMountedRef.current = false;
            clearTimeout(initialTimeout);
            if (intervalRef.current) clearInterval(intervalRef.current);
        };
    }, [isXamopsUser]);

    useEffect(() => {
        const handleStorageChange = () => {
            lastNotifiedIdRef.current = null;
            isInitializedRef.current = false;
        };
        window.addEventListener('storage', handleStorageChange);
        return () => window.removeEventListener('storage', handleStorageChange);
    }, []);

    return null;
};

export default UserSupportNotificationListener;
