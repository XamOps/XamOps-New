/**
 * useWebSocket Hook
 *
 * Converted from: dashboard.html (WebSocket/STOMP connection logic)
 *
 * Manages STOMP WebSocket connection for real-time inventory updates.
 * Preserves exact same connection and subscription logic as original.
 */

import { useEffect, useRef, useCallback } from 'react';
import type { ResourceInventory } from '../types/liveDashboard';

// STOMP client type (from stompjs library loaded via CDN)
declare global {
  interface Window {
    Stomp: any;
    SockJS: any;
  }
}

interface UseWebSocketOptions {
  accountId: string | null;
  enabled: boolean;
  onInventoryUpdate?: (inventory: ResourceInventory) => void;
}

export const useWebSocket = ({
  accountId,
  enabled,
  onInventoryUpdate,
}: UseWebSocketOptions) => {
  const stompClientRef = useRef<any>(null);
  const subscriptionRef = useRef<any>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  /**
   * Connect to WebSocket and subscribe to inventory updates
   * Mimics the original connectWebSocket() function
   */
  const connect = useCallback(() => {
    if (!accountId || !enabled) {
      console.log('⏸️ WebSocket connection skipped (no account or disabled)');
      return;
    }

    if (!window.SockJS || !window.Stomp) {
      console.warn('⚠️ SockJS or STOMP not loaded from CDN');
      return;
    }

    const isMounted = { current: true };

    try {
      console.log('🔌 Connecting to WebSocket for account:', accountId);

      // Create SockJS connection - using the backend path
      const socket = new window.SockJS('/xamops-ws');
      const client = window.Stomp.over(socket);

      // Disable debug logging in production
      if (process.env.NODE_ENV === 'production') {
        client.debug = () => { };
      }

      // Connect to STOMP broker
      client.connect(
        {},
        () => {
          if (!isMounted.current) {
            client.disconnect(() => { });
            return;
          }

          console.log('✅ WebSocket connected');
          stompClientRef.current = client;

          // Subscribe to inventory updates for this account
          const topic = `/topic/dashboard/${accountId}/inventory`;
          console.log('📡 Subscribing to:', topic);

          const subscription = client.subscribe(topic, (message: any) => {
            try {
              const inventory: ResourceInventory = JSON.parse(message.body);
              console.log('📦 Received inventory update:', inventory);

              if (onInventoryUpdate) {
                onInventoryUpdate(inventory);
              }
            } catch (err) {
              console.error('❌ Failed to parse inventory update:', err);
            }
          });

          subscriptionRef.current = subscription;
        },
        (error: any) => {
          if (!isMounted.current) return;

          console.error('❌ WebSocket connection error:', error);
          stompClientRef.current = null;

          // Attempt reconnection after 5 seconds
          reconnectTimeoutRef.current = setTimeout(() => {
            if (isMounted.current) {
              console.log('🔄 Attempting to reconnect WebSocket...');
              connect();
            }
          }, 5000);
        }
      );
    } catch (err) {
      console.error('❌ Failed to initialize WebSocket:', err);
    }

    return () => {
      isMounted.current = false;
    };
  }, [accountId, enabled, onInventoryUpdate]);

  /**
   * Disconnect from WebSocket
   */
  const disconnect = useCallback(() => {
    console.log('🔌 Disconnecting WebSocket...');

    if (subscriptionRef.current) {
      try {
        subscriptionRef.current.unsubscribe();
        subscriptionRef.current = null;
      } catch (err) {
        console.error('❌ Error unsubscribing:', err);
      }
    }

    if (stompClientRef.current) {
      try {
        stompClientRef.current.disconnect(() => {
          console.log('✅ WebSocket disconnected');
          stompClientRef.current = null;
        });
      } catch (err) {
        console.error('❌ Error disconnecting:', err);
        stompClientRef.current = null;
      }
    }

    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
  }, []);

  /**
   * Connect/disconnect based on accountId and enabled state
   */
  useEffect(() => {
    if (accountId && enabled) {
      connect();
    } else {
      disconnect();
    }

    return () => {
      disconnect();
    };
  }, [accountId, enabled, connect, disconnect]);

  return {
    isConnected: !!stompClientRef.current,
    disconnect,
  };
};
