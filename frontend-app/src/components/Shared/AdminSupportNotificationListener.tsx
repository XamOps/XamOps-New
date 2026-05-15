import React, { useEffect, useRef } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import apiClient from '../../utils/apiClient';
import { SupportConversation } from '../../types/supportDashboard';
import { notifyNewCustomerMessage, initNotificationService, playNotificationSound } from '../../utils/notificationService';

const POLL_INTERVAL = 5000;

const AdminSupportNotificationListener: React.FC = () => {
    const { hasAnyRole } = useAuth();
    const isBillOpsAdmin = hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_BILLOPS_ADMIN_SUB');

    const intervalRef = useRef<NodeJS.Timeout | null>(null);
    const previousUnreadMap = useRef<Map<string, number>>(new Map());
    const isInitializedRef = useRef(false);
    const isMountedRef = useRef(true);

    useEffect(() => {
        if (!isBillOpsAdmin) {
            if (intervalRef.current) clearInterval(intervalRef.current);
            return;
        }

        isMountedRef.current = true;
        initNotificationService();

        const checkNewMessages = async () => {
            if (!isMountedRef.current) return;

            try {
                const data = await apiClient.get<SupportConversation[]>('/api/billops/support/inbox');
                if (!isMountedRef.current || !data) return;

                if (!isInitializedRef.current) {
                    const newMap = new Map<string, number>();
                    data.forEach(c => newMap.set(c.accountId || c.username, c.unreadCount || 0));
                    previousUnreadMap.current = newMap;
                    isInitializedRef.current = true;
                    console.log('[AdminNotification] Initialized with', data.length, 'conversations');
                    return;
                }

                for (const conv of data) {
                    const prevCount = previousUnreadMap.current.get(conv.accountId || conv.username) || 0;
                    const currentCount = conv.unreadCount || 0;

                    if (currentCount > prevCount) {
                        console.log('[AdminNotification] New message from', conv.accountName || conv.accountId,
                            '- unread:', prevCount, '->', currentCount);
                        playNotificationSound();
                        notifyNewCustomerMessage(
                            conv.accountName || conv.senderUsername || conv.accountId || conv.username,
                            conv.lastMessage || 'Sent a new message',
                            { playSound: false }
                        );
                        window.dispatchEvent(new CustomEvent('newCustomerMessage', {
                            detail: {
                                accountId: conv.accountId,
                                accountName: conv.accountName,
                                message: conv.lastMessage
                            }
                        }));
                    }
                }

                const newMap = new Map<string, number>();
                data.forEach(c => newMap.set(c.accountId || c.username, c.unreadCount || 0));
                previousUnreadMap.current = newMap;

            } catch (error) {
                console.error('[AdminSupportNotification] Failed to poll inbox:', error);
            }
        };

        const initialTimeout = setTimeout(checkNewMessages, 1000);
        intervalRef.current = setInterval(checkNewMessages, POLL_INTERVAL);

        return () => {
            isMountedRef.current = false;
            clearTimeout(initialTimeout);
            if (intervalRef.current) clearInterval(intervalRef.current);
        };
    }, [isBillOpsAdmin]);

    return null;
};

export default AdminSupportNotificationListener;
