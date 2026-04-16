import React, { useEffect, useRef, useCallback } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import apiClient from '../../utils/apiClient';
import { SupportConversation } from '../../types/supportDashboard';
import { notifyNewCustomerMessage, initNotificationService, playNotificationSound } from '../../utils/notificationService';

const POLL_INTERVAL = 5000; // 5 seconds for background polling (faster response like WhatsApp)

/**
 * Global Admin Notification Listener
 *
 * Background component that polls the support inbox for new unread messages
 * when the user is a BillOps Admin. Provides real-time notifications
 * even when not on the support dashboard.
 */
const AdminSupportNotificationListener: React.FC = () => {
    const { hasAnyRole } = useAuth();
    const isBillOpsAdmin = hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_BILLOPS_ADMIN_SUB');

    // Refs to track state between polls
    const intervalRef = useRef<NodeJS.Timeout | null>(null);
    const previousUnreadMap = useRef<Map<string, number>>(new Map());
    const isInitializedRef = useRef(false);
    const isMountedRef = useRef(true);

    const checkNewMessages = useCallback(async () => {
        if (!isMountedRef.current) return;

        try {
            const data = await apiClient.get<SupportConversation[]>('/api/billops/support/inbox');
            if (!data) return;

            // On first load, just store the counts without notifying
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

                // If unread count increased, notify
                if (currentCount > prevCount) {
                    console.log('[AdminNotification] New message from', conv.accountName || conv.accountId,
                        '- unread:', prevCount, '->', currentCount);

                    // ALWAYS play sound for new messages
                    playNotificationSound();

                    // ALWAYS show browser notification for new messages
                    // This ensures admin gets notified even when on a different page
                    notifyNewCustomerMessage(
                        conv.accountName || conv.senderUsername || conv.accountId || conv.username,
                        conv.lastMessage || 'Sent a new message',
                        { playSound: false } // Sound already played above
                    );

                    // Dispatch custom event so dashboard can refresh if open
                    window.dispatchEvent(new CustomEvent('newCustomerMessage', {
                        detail: {
                            accountId: conv.accountId,
                            accountName: conv.accountName,
                            message: conv.lastMessage
                        }
                    }));
                }
            }

            // Update map for next check
            const newMap = new Map<string, number>();
            data.forEach(c => newMap.set(c.accountId || c.username, c.unreadCount || 0));
            previousUnreadMap.current = newMap;

        } catch (error) {
            console.error('[AdminSupportNotification] Failed to poll inbox:', error);
        }
    }, []);

    useEffect(() => {
        if (!isBillOpsAdmin) {
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
    }, [isBillOpsAdmin, checkNewMessages]);

    return null; // Side-effect only component
};

export default AdminSupportNotificationListener;
