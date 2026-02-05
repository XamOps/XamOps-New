/**
 * BillOpsNotificationDropdown Component
 *
 * Notification dropdown specifically for BillOps Admin Dashboard
 * Shows ONLY:
 * - New tickets raised
 * - Unread chat messages
 *
 * No other notification types
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { BillOpsNotificationItem, BillOpsNotificationCounts } from '../../hooks/useBillOpsAdminNotifications';
import { markBillOpsChatAsOpened } from '../../hooks/useBillOpsAdminNotifications';
import '../../styles/notifications.css';

interface BillOpsNotificationDropdownProps {
  notifications: BillOpsNotificationItem[];
  counts: BillOpsNotificationCounts;
  isLoading: boolean;
  onClose: () => void;
}

const BillOpsNotificationDropdown: React.FC<BillOpsNotificationDropdownProps> = ({
  notifications,
  counts,
  isLoading,
  onClose,
}) => {
  /**
   * Get icon for notification type
   */
  const getTypeIcon = (type: string): string => {
    switch (type) {
      case 'ticket':
        return 'fas fa-ticket-alt';
      case 'chat':
        return 'fas fa-comment-dots';
      default:
        return 'fas fa-bell';
    }
  };

  /**
   * Get color class for notification type
   */
  const getTypeColorClass = (type: string): string => {
    switch (type) {
      case 'ticket':
        return 'notification-ticket';
      case 'chat':
        return 'notification-chat';
      default:
        return 'notification-info';
    }
  };

  /**
   * Format timestamp to relative time
   */
  const formatTimestamp = (timestamp: Date): string => {
    const now = Date.now();
    const diff = now - timestamp.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return 'Just now';
    if (minutes < 60) return `${minutes}m ago`;
    if (hours < 24) return `${hours}h ago`;
    return `${days}d ago`;
  };

  /**
   * Group notifications by type
   */
  const groupedNotifications = {
    tickets: notifications.filter(n => n.type === 'ticket'),
    chat: notifications.filter(n => n.type === 'chat'),
  };

  /**
   * Handle chat notification click - mark chat as opened
   */
  const handleChatClick = () => {
    markBillOpsChatAsOpened();
    onClose();
  };

  /**
   * Get severity badge for tickets
   */
  const getSeverityBadge = (severity?: string): React.ReactNode => {
    if (!severity) return null;

    const severityColors: Record<string, string> = {
      LOW: 'bg-blue-100 text-blue-700',
      MEDIUM: 'bg-yellow-100 text-yellow-700',
      HIGH: 'bg-orange-100 text-orange-700',
      CRITICAL: 'bg-red-100 text-red-700',
    };

    return (
      <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold ${severityColors[severity] || 'bg-gray-100 text-gray-700'}`}>
        {severity}
      </span>
    );
  };

  return (
    <div className="notification-dropdown">
      {/* Header */}
      <div className="notification-header">
        <div className="notification-header-left">
          <i className="fas fa-bell notification-header-icon"></i>
          <h3 className="notification-header-title">Notifications</h3>
        </div>
        <span className="notification-count-badge">{counts.total}</span>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="notification-loading">
          <div className="notification-spinner"></div>
          <p className="notification-loading-text">Loading notifications...</p>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && notifications.length === 0 && (
        <div className="notification-empty">
          <i className="fas fa-bell-slash notification-empty-icon"></i>
          <h4 className="notification-empty-title">No Notifications</h4>
          <p className="notification-empty-text">No new tickets or messages</p>
        </div>
      )}

      {/* Notifications List */}
      {!isLoading && notifications.length > 0 && (
        <div className="notification-content">
          {/* New Tickets Section */}
          {groupedNotifications.tickets.length > 0 && (
            <div className="notification-section">
              <div className="notification-section-header">
                <i className="fas fa-ticket-alt notification-section-icon text-orange-600"></i>
                <span className="notification-section-title">New Tickets</span>
                <span className="notification-section-count">{counts.tickets}</span>
              </div>
              <div className="notification-list">
                {groupedNotifications.tickets.slice(0, 5).map((notification) => (
                  <Link
                    key={notification.id}
                    to={notification.link || '/billops-tickets'}
                    className={`notification-item ${getTypeColorClass(notification.type)}`}
                    onClick={onClose}
                  >
                    <div className="notification-item-icon bg-orange-100 text-orange-600">
                      <i className={getTypeIcon(notification.type)}></i>
                    </div>
                    <div className="notification-item-content">
                      <div className="flex items-center justify-between gap-2">
                        <h4 className="notification-item-title flex-1">{notification.title}</h4>
                        {getSeverityBadge(notification.metadata?.severity)}
                      </div>
                      <p className="notification-item-description">{notification.description}</p>
                      {notification.metadata?.clientName && (
                        <p className="text-[10px] text-gray-500 font-semibold mt-1">
                          <i className="fas fa-building text-[8px] mr-1"></i>
                          {notification.metadata.clientName}
                        </p>
                      )}
                      <span className="notification-item-time">{formatTimestamp(notification.timestamp)}</span>
                    </div>
                    {notification.isUnread && (
                      <span className="notification-unread-badge"></span>
                    )}
                  </Link>
                ))}
              </div>
              {groupedNotifications.tickets.length > 5 && (
                <Link to="/billops-tickets" className="notification-view-all" onClick={onClose}>
                  View all tickets ({groupedNotifications.tickets.length})
                </Link>
              )}
            </div>
          )}

          {/* Unread Messages Section */}
          {groupedNotifications.chat.length > 0 && (
            <div className="notification-section">
              <div className="notification-section-header">
                <i className="fas fa-comment-dots notification-section-icon text-indigo-600"></i>
                <span className="notification-section-title">Unread Messages</span>
                <span className="notification-section-count">{counts.chat}</span>
              </div>
              <div className="notification-list">
                {groupedNotifications.chat.slice(0, 5).map((notification) => (
                  <div
                    key={notification.id}
                    className={`notification-item ${getTypeColorClass(notification.type)} notification-chat-item`}
                    onClick={handleChatClick}
                    role="button"
                    tabIndex={0}
                    onKeyPress={(e) => e.key === 'Enter' && handleChatClick()}
                  >
                    <div className="notification-item-icon bg-indigo-100 text-indigo-600">
                      <i className={getTypeIcon(notification.type)}></i>
                    </div>
                    <div className="notification-item-content">
                      <h4 className="notification-item-title">{notification.title}</h4>
                      <p className="notification-item-description">{notification.description}</p>
                      {notification.metadata?.accountId && (
                        <p className="text-[10px] text-gray-500 font-semibold mt-1">
                          <i className="fas fa-user text-[8px] mr-1"></i>
                          Account: {notification.metadata.accountId}
                        </p>
                      )}
                      <span className="notification-item-time">{formatTimestamp(notification.timestamp)}</span>
                    </div>
                    {notification.isUnread && (
                      <span className="notification-unread-badge"></span>
                    )}
                  </div>
                ))}
              </div>
              {groupedNotifications.chat.length > 5 && (
                <div
                  className="notification-view-all"
                  onClick={handleChatClick}
                  role="button"
                  tabIndex={0}
                  onKeyPress={(e) => e.key === 'Enter' && handleChatClick()}
                >
                  View all messages ({groupedNotifications.chat.length})
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Footer */}
      {!isLoading && notifications.length > 0 && (
        <div className="notification-footer">
          <div className="flex items-center justify-between w-full px-4">
            <Link to="/billops-tickets" className="notification-footer-link" onClick={onClose}>
              <i className="fas fa-ticket-alt"></i>
              <span>All Tickets</span>
            </Link>
            <div className="h-4 w-[1px] bg-gray-200"></div>
            <button className="notification-footer-link" onClick={handleChatClick}>
              <i className="fas fa-comment-dots"></i>
              <span>Messages</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default BillOpsNotificationDropdown;
