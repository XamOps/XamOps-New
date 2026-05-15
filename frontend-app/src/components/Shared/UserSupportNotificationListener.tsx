import React, { useEffect, useRef } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import apiClient from '../../utils/apiClient';
import { notifyNewSupportReply, initNotificationService, playNotificationSound } from '../../utils/notificationService';

const POLL_INTERVAL = 5000;
const MAX_CONSECUTIVE_ERRORS = 5;

interface ChatHistoryItem {
    content: string;
    userMessage: boolean;
    timestamp: string;
}

const UserSupportNotificationListener: React.FC = () => {
    const { hasAnyRole } = useAuth();
    const isXamopsUser = hasAnyRole('ROLE_XAMOPS');

    const intervalRef = useRef<NodeJS.Timeout | null>(null);
    const previousMessageCountRef = useRef<number>(0);
    const previousLastTimestampRef = useRef<string>('');
    const isInitializedRef = useRef(false);
    const isMountedRef = useRef(true);
    const consecutiveErrorsRef = useRef<number>(0);

    useEffect(() => {
        if (!isXamopsUser) {
            if (intervalRef.current) clearInterval(intervalRef.current);
            return;
        }

        isMountedRef.current = true;
        initNotificationService();

        const checkNewMessages = async () => {
            if (!isMountedRef.current) return;

            try {
                const selectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
                const accountId = selectedIds.length > 0 ? selectedIds[0] : null;
                const url = accountId
                    ? `/api/ai/chat/history?accountId=${encodeURIComponent(accountId)}`
                    : '/api/ai/chat/history';

                const history = await apiClient.get<ChatHistoryItem[]>(url);
                if (!isMountedRef.current || !history || !Array.isArray(history)) return;

                const currentCount = history.length;

                if (!isInitializedRef.current) {
                    previousMessageCountRef.current = currentCount;
                    if (history.length > 0) {
                        previousLastTimestampRef.current = history[history.length - 1].timestamp;
                    }
                    isInitializedRef.current = true;
                    return;
                }

                if (currentCount > previousMessageCountRef.current) {
                    const newMessages = history.slice(previousMessageCountRef.current);
                    const newAdminMessages = newMessages.filter(msg => !msg.userMessage);

                    if (newAdminMessages.length > 0) {
                        const latestMessage = newAdminMessages[newAdminMessages.length - 1];
                        playNotificationSound();
                        notifyNewSupportReply(latestMessage.content, { playSound: false });
                        window.dispatchEvent(new CustomEvent('newAdminMessage', {
                            detail: { message: latestMessage.content, count: newAdminMessages.length }
                        }));
                    }
                }

                previousMessageCountRef.current = currentCount;
                if (history.length > 0) {
                    previousLastTimestampRef.current = history[history.length - 1].timestamp;
                }
                consecutiveErrorsRef.current = 0;

            } catch (error) {
                consecutiveErrorsRef.current++;
                console.error('[UserNotification] Failed to poll chat history:', error);
                if (consecutiveErrorsRef.current >= MAX_CONSECUTIVE_ERRORS) {
                    console.warn(`[UserNotification] Stopping poll after ${MAX_CONSECUTIVE_ERRORS} consecutive failures.`);
                    if (intervalRef.current) clearInterval(intervalRef.current);
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
            previousMessageCountRef.current = 0;
            previousLastTimestampRef.current = '';
            isInitializedRef.current = false;
        };
        window.addEventListener('storage', handleStorageChange);
        return () => window.removeEventListener('storage', handleStorageChange);
    }, []);

    return null;
};

export default UserSupportNotificationListener;
