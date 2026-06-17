/**
 * useMediaOpsNotifications Hook
 *
 * Manages MediaOps job notifications with real-time WebSocket delivery via STOMP/SockJS.
 * Falls back to REST polling (30s) if WebSocket is unavailable.
 *
 * This is a SEPARATE hook from the global useNotifications (which handles CloudGuard/Grafana/Budget alerts).
 */

import { useState, useEffect, useCallback, useRef } from 'react';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface MediaOpsNotification {
  id: number;
  jobId: number | null;
  type: 'JOB_COMPLETED' | 'JOB_FAILED';
  message: string;
  read: boolean;
  createdAt: string; // ISO timestamp
}

export interface UseMediaOpsNotificationsReturn {
  notifications: MediaOpsNotification[];
  unreadCount: number;
  loading: boolean;
  toast: MediaOpsNotification | null;
  markAsRead: (id: number) => Promise<void>;
  dismissToast: () => void;
  refresh: () => Promise<void>;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const POLL_INTERVAL_MS = 30_000; // 30s fallback polling
const TOAST_DURATION_MS = 5_000; // 5s auto-dismiss
const RECONNECT_DELAY_MS = 5_000; // 5s reconnect delay

// ─── Hook ─────────────────────────────────────────────────────────────────────

export function useMediaOpsNotifications(): UseMediaOpsNotificationsReturn {
  const [notifications, setNotifications] = useState<MediaOpsNotification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState<MediaOpsNotification | null>(null);

  const isMountedRef = useRef(true);
  const stompClientRef = useRef<any>(null);
  const subscriptionRef = useRef<any>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const wsConnectedRef = useRef(false);

  // ── Fetch notifications via REST ─────────────────────────────────────────

  const fetchNotifications = useCallback(async () => {
    try {
      const res = await fetch('/api/media-ops/notifications', { credentials: 'include' });
      if (!res.ok) return;
      const data: MediaOpsNotification[] = await res.json();
      if (isMountedRef.current) {
        setNotifications(data);
      }
    } catch (err) {
      console.error('[MediaOps Notifications] Failed to fetch:', err);
    }
  }, []);

  const fetchUnreadCount = useCallback(async () => {
    try {
      const res = await fetch('/api/media-ops/notifications/unread-count', { credentials: 'include' });
      if (!res.ok) return;
      const data = await res.json();
      const count = typeof data === 'number' ? data : (data?.count ?? 0);
      if (isMountedRef.current) setUnreadCount(count);
    } catch (err) {
      console.error('[MediaOps Notifications] Failed to fetch unread count:', err);
    }
  }, []);

  const refresh = useCallback(async () => {
    await Promise.all([fetchNotifications(), fetchUnreadCount()]);
  }, [fetchNotifications, fetchUnreadCount]);

  // ── Toast management ─────────────────────────────────────────────────────

  const showToast = useCallback((notification: MediaOpsNotification) => {
    setToast(notification);
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    toastTimerRef.current = setTimeout(() => {
      if (isMountedRef.current) setToast(null);
    }, TOAST_DURATION_MS);
  }, []);

  const dismissToast = useCallback(() => {
    setToast(null);
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
      toastTimerRef.current = null;
    }
  }, []);

  // ── Handle incoming WebSocket notification ───────────────────────────────

  const handleWsNotification = useCallback((notification: MediaOpsNotification) => {
    if (!isMountedRef.current) return;

    setNotifications(prev => {
      if (prev.some(n => n.id === notification.id)) return prev;
      return [notification, ...prev];
    });

    if (!notification.read) {
      setUnreadCount(prev => prev + 1);
    }

    showToast(notification);
  }, [showToast]);

  // ── WebSocket connection via STOMP/SockJS ────────────────────────────────

  const connectWebSocket = useCallback(() => {
    if (!window.SockJS || !window.Stomp) return;

    try {
      const socket = new window.SockJS('/media-ops-ws');
      const client = window.Stomp.over(socket);

      if (process.env.NODE_ENV === 'production') {
        client.debug = () => {};
      }

      client.connect(
        {},
        () => {
          if (!isMountedRef.current) {
            client.disconnect(() => {});
            return;
          }

          stompClientRef.current = client;
          wsConnectedRef.current = true;

          // Subscribe to a general notification topic (clientId resolved server-side)
          subscriptionRef.current = client.subscribe('/user/topic/media-ops/notifications', (msg: any) => {
            try {
              const notification: MediaOpsNotification = JSON.parse(msg.body);
              handleWsNotification(notification);
            } catch (err) {
              console.error('[MediaOps Notifications] WS parse error:', err);
            }
          });

          if (pollIntervalRef.current) {
            clearInterval(pollIntervalRef.current);
            pollIntervalRef.current = null;
          }
        },
        () => {
          stompClientRef.current = null;
          wsConnectedRef.current = false;

          if (!isMountedRef.current) return;

          if (!pollIntervalRef.current) {
            pollIntervalRef.current = setInterval(() => {
              if (isMountedRef.current) refresh();
            }, POLL_INTERVAL_MS);
          }

          reconnectTimerRef.current = setTimeout(() => {
            if (isMountedRef.current) connectWebSocket();
          }, RECONNECT_DELAY_MS);
        }
      );
    } catch (err) {
      console.error('[MediaOps Notifications] WS connect error:', err);
      if (!pollIntervalRef.current) {
        pollIntervalRef.current = setInterval(() => {
          if (isMountedRef.current) refresh();
        }, POLL_INTERVAL_MS);
      }
    }
  }, [handleWsNotification, refresh]);

  // ── Mark as read ─────────────────────────────────────────────────────────

  const markAsRead = useCallback(async (id: number) => {
    try {
      const res = await fetch(`/api/media-ops/notifications/${id}/read`, {
        method: 'PATCH',
        credentials: 'include',
      });
      if (!res.ok) return;

      setNotifications(prev =>
        prev.map(n => (n.id === id ? { ...n, read: true } : n))
      );
      setUnreadCount(prev => Math.max(0, prev - 1));
    } catch (err) {
      console.error('[MediaOps Notifications] Failed to mark as read:', err);
    }
  }, []);

  // ── Lifecycle ────────────────────────────────────────────────────────────

  useEffect(() => {
    isMountedRef.current = true;

    const init = async () => {
      await Promise.all([fetchNotifications(), fetchUnreadCount()]);
      if (isMountedRef.current) setLoading(false);
    };
    init();

    connectWebSocket();

    pollIntervalRef.current = setInterval(() => {
      if (isMountedRef.current && !wsConnectedRef.current) {
        fetchNotifications();
        fetchUnreadCount();
      }
    }, POLL_INTERVAL_MS);

    return () => {
      isMountedRef.current = false;
      if (pollIntervalRef.current) { clearInterval(pollIntervalRef.current); pollIntervalRef.current = null; }
      if (toastTimerRef.current) { clearTimeout(toastTimerRef.current); toastTimerRef.current = null; }
      if (reconnectTimerRef.current) { clearTimeout(reconnectTimerRef.current); reconnectTimerRef.current = null; }
      if (subscriptionRef.current) { try { subscriptionRef.current.unsubscribe(); } catch {} subscriptionRef.current = null; }
      if (stompClientRef.current) { try { stompClientRef.current.disconnect(() => {}); } catch {} stompClientRef.current = null; }
      wsConnectedRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    notifications,
    unreadCount,
    loading,
    toast,
    markAsRead,
    dismissToast,
    refresh,
  };
}

export default useMediaOpsNotifications;
