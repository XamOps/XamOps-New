/**
 * Browser Notification Service
 *
 * Handles browser notifications with sound for the support chat feature.
 * Supports both customer-side (receiving admin replies) and admin-side
 * (receiving customer messages) notifications.
 */

// Notification permission state
let notificationPermission: NotificationPermission = 'default';

// Audio element for notification sound
let notificationAudio: HTMLAudioElement | null = null;

// AudioContext for fallback beep sound
let audioContext: AudioContext | null = null;

/**
 * Initialize the notification service
 * Requests permission and preloads the notification sound
 */
export const initNotificationService = async (): Promise<void> => {
  console.log('[NotificationService] Initializing...');

  // Request notification permission
  if ('Notification' in window) {
    notificationPermission = Notification.permission;
    console.log('[NotificationService] Current permission:', notificationPermission);

    if (notificationPermission === 'default') {
      console.log('[NotificationService] Requesting permission...');
      notificationPermission = await Notification.requestPermission();
      console.log('[NotificationService] Permission result:', notificationPermission);
    }
  } else {
    console.warn('[NotificationService] Browser does not support notifications');
  }

  // Preload notification sound
  try {
    notificationAudio = new Audio('/sounds/notification.mp3');
    notificationAudio.volume = 0.5;
    // Preload the audio
    notificationAudio.load();
    console.log('[NotificationService] Audio preloaded');
  } catch (error) {
    console.warn('[NotificationService] Failed to load notification sound:', error);
  }

  console.log('[NotificationService] Initialization complete');
};

/**
 * Request notification permission (can be called on user interaction)
 */
export const requestNotificationPermission = async (): Promise<NotificationPermission> => {
  if (!('Notification' in window)) {
    console.warn('Browser does not support notifications');
    return 'denied';
  }

  notificationPermission = await Notification.requestPermission();
  return notificationPermission;
};

/**
 * Check if notifications are enabled
 */
export const isNotificationEnabled = (): boolean => {
  // Always check the actual browser permission, not our cached value
  const enabled = 'Notification' in window && Notification.permission === 'granted';
  console.log('[NotificationService] isNotificationEnabled:', enabled, 'Browser permission:', Notification.permission);
  return enabled;
};

/**
 * Play a notification sound using Web Audio API (fallback)
 */
const playFallbackBeep = (): void => {
  try {
    if (!audioContext) {
      audioContext = new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)();
    }

    const oscillator = audioContext.createOscillator();
    const gainNode = audioContext.createGain();

    oscillator.connect(gainNode);
    gainNode.connect(audioContext.destination);

    oscillator.frequency.value = 800; // Frequency in Hz
    oscillator.type = 'sine';

    gainNode.gain.setValueAtTime(0.3, audioContext.currentTime);
    gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.3);

    oscillator.start(audioContext.currentTime);
    oscillator.stop(audioContext.currentTime + 0.3);
  } catch (error) {
    console.warn('Failed to play fallback beep:', error);
  }
};

/**
 * Play the notification sound
 */
export const playNotificationSound = async (): Promise<void> => {
  try {
    if (notificationAudio) {
      // Reset to beginning if already playing
      notificationAudio.currentTime = 0;
      await notificationAudio.play();
    } else {
      // Fallback to Web Audio API beep
      playFallbackBeep();
    }
  } catch (error) {
    // Audio play failed (likely due to autoplay policy), try fallback
    console.warn('Failed to play notification audio, trying fallback:', error);
    playFallbackBeep();
  }
};

/**
 * Show a browser notification with sound
 */
export const showNotification = (
  title: string,
  options?: {
    body?: string;
    icon?: string;
    tag?: string;
    onClick?: () => void;
    playSound?: boolean;
  }
): void => {
  // Play sound if not explicitly disabled
  if (options?.playSound !== false) {
    playNotificationSound();
  }

  // Show browser notification if permitted
  if (isNotificationEnabled()) {
    try {
      console.log('[NotificationService] Creating browser notification...');
      const notification = new Notification(title, {
        body: options?.body,
        icon: options?.icon || '/icons/XamOps.png',
        tag: options?.tag || 'support-chat',
        requireInteraction: false,
      });
      console.log('[NotificationService] Browser notification created successfully');

      // Handle click
      if (options?.onClick) {
        notification.onclick = () => {
          window.focus();
          options.onClick?.();
          notification.close();
        };
      }

      // Auto-close after 5 seconds
      setTimeout(() => notification.close(), 5000);
    } catch (error) {
      console.warn('[NotificationService] Failed to show notification:', error);
    }
  } else {
    console.log('[NotificationService] Notifications not enabled, skipping browser notification');
  }
};

/**
 * Show notification for new support message (customer receiving admin reply)
 */
export const notifyNewSupportReply = (message: string, options?: { playSound?: boolean }): void => {
  showNotification('XamOps Support', {
    body: message.length > 100 ? message.substring(0, 100) + '...' : message,
    tag: 'support-reply',
    playSound: options?.playSound,
    onClick: () => {
      // Focus chat widget if available
      const chatButton = document.querySelector('[data-support-chat-toggle]');
      if (chatButton instanceof HTMLElement) {
        chatButton.click();
      }
    },
  });
};

/**
 * Show notification for new customer message (admin receiving customer message)
 */
export const notifyNewCustomerMessage = (
  accountName: string,
  message: string,
  options?: { playSound?: boolean }
): void => {
  showNotification(`New message from ${accountName}`, {
    body: message.length > 100 ? message.substring(0, 100) + '...' : message,
    tag: 'customer-message',
    playSound: options?.playSound,
    onClick: () => {
      // Focus will be handled by the dashboard page
      window.focus();
    },
  });
};

/**
 * Show notification for new ticket reply (ticket chat)
 */
export const notifyNewTicketReply = (
  ticketId: string | number,
  message: string
): void => {
  showNotification(`New reply on Ticket #${ticketId}`, {
    body: message.length > 100 ? message.substring(0, 100) + '...' : message,
    tag: `ticket-reply-${ticketId}`,
    onClick: () => {
      window.focus();
      // Navigate to ticket detail if not already there
      if (!window.location.pathname.includes(`/tickets/${ticketId}`)) {
        window.location.href = `/billops/tickets/${ticketId}`;
      }
    },
  });
};

/**
 * Show notification for new ticket created (admin notification)
 */
export const notifyNewTicketCreated = (
  ticketId: string | number,
  subject: string,
  accountName?: string
): void => {
  const title = accountName
    ? `New Ticket from ${accountName}`
    : `New Ticket #${ticketId}`;
  showNotification(title, {
    body: subject.length > 100 ? subject.substring(0, 100) + '...' : subject,
    tag: `new-ticket-${ticketId}`,
    onClick: () => {
      window.focus();
      window.location.href = `/billops/tickets/${ticketId}`;
    },
  });
};

/**
 * Show notification for new invoice (when invoice is finalized/pushed)
 */
export const notifyNewInvoice = (
  accountName: string,
  billingPeriod: string,
  amount?: number
): void => {
  const body = amount
    ? `Invoice for ${billingPeriod} - $${amount.toFixed(2)}`
    : `Invoice for ${billingPeriod} is now available`;
  showNotification(`New Invoice for ${accountName}`, {
    body,
    tag: `invoice-${accountName}-${billingPeriod}`,
    onClick: () => {
      window.focus();
      window.location.href = '/billops/invoices';
    },
  });
};

/**
 * Show notification for ticket status change
 */
export const notifyTicketStatusChange = (
  ticketId: string | number,
  newStatus: string,
  subject?: string
): void => {
  const statusLabels: Record<string, string> = {
    'OPEN': 'opened',
    'IN_PROGRESS': 'in progress',
    'CLOSED': 'closed',
    'RESOLVED': 'resolved',
    'PENDING': 'pending'
  };
  const statusText = statusLabels[newStatus] || newStatus.toLowerCase();
  let body: string;
  if (subject) {
    const truncatedSubject = subject.length > 50 ? subject.substring(0, 50) + '...' : subject;
    body = `"${truncatedSubject}" is now ${statusText}`;
  } else {
    body = `Status changed to ${statusText}`;
  }
  showNotification(`Ticket #${ticketId} Updated`, {
    body,
    tag: `ticket-status-${ticketId}`,
    onClick: () => {
      window.focus();
      window.location.href = `/billops/tickets/${ticketId}`;
    },
  });
};

/**
 * Show notification for cost anomaly detection
 */
export const notifyCostAnomaly = (
  accountName: string,
  serviceName: string,
  percentageIncrease: number,
  currentCost?: number
): void => {
  const costText = currentCost ? ` ($${currentCost.toFixed(2)})` : '';
  const body = `${serviceName} spending increased by ${percentageIncrease.toFixed(0)}%${costText}`;
  showNotification(`Cost Alert: ${accountName}`, {
    body,
    icon: '/icons/alert-warning.png',
    tag: `cost-anomaly-${accountName}-${serviceName}`,
    onClick: () => {
      window.focus();
      window.location.href = '/billops/cost-explorer';
    },
  });
};

export default {
  initNotificationService,
  requestNotificationPermission,
  isNotificationEnabled,
  playNotificationSound,
  showNotification,
  notifyNewSupportReply,
  notifyNewCustomerMessage,
  notifyNewTicketReply,
  notifyNewTicketCreated,
  notifyNewInvoice,
  notifyTicketStatusChange,
  notifyCostAnomaly,
};
