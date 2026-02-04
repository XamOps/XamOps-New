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
  // Request notification permission
  if ('Notification' in window) {
    notificationPermission = Notification.permission;
    if (notificationPermission === 'default') {
      notificationPermission = await Notification.requestPermission();
    }
  }

  // Preload notification sound
  try {
    notificationAudio = new Audio('/sounds/notification.mp3');
    notificationAudio.volume = 0.5;
    // Preload the audio
    notificationAudio.load();
  } catch (error) {
    console.warn('Failed to load notification sound:', error);
  }
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
  return 'Notification' in window && notificationPermission === 'granted';
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
  }
): void => {
  // Play sound regardless of notification permission
  playNotificationSound();

  // Show browser notification if permitted
  if (isNotificationEnabled()) {
    try {
      const notification = new Notification(title, {
        body: options?.body,
        icon: options?.icon || '/icons/XamOps.png',
        tag: options?.tag || 'support-chat',
        requireInteraction: false,
      });

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
      console.warn('Failed to show notification:', error);
    }
  }
};

/**
 * Show notification for new support message (customer receiving admin reply)
 */
export const notifyNewSupportReply = (message: string): void => {
  showNotification('XamOps Support', {
    body: message.length > 100 ? message.substring(0, 100) + '...' : message,
    tag: 'support-reply',
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
  message: string
): void => {
  showNotification(`New message from ${accountName}`, {
    body: message.length > 100 ? message.substring(0, 100) + '...' : message,
    tag: 'customer-message',
    onClick: () => {
      // Focus will be handled by the dashboard page
      window.focus();
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
};
