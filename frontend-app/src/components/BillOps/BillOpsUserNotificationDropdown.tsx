/**
 * BillOpsUserNotificationDropdown Component
 *
 * Notification dropdown for BillOps Users
 * Shows:
 * - User's own tickets
 * - Unread chat messages (admin replies)
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { BillOpsUserNotificationItem, BillOpsUserNotificationCounts } from '../../hooks/useBillOpsUserNotifications';
import { markChatAsOpened } from '../../hooks/useBillOpsUserNotifications';
import '../../styles/notifications.css';

interface BillOpsUserNotificationDropdownProps {
  notifications: BillOpsUserNotificationItem[];
  counts: BillOpsUserNotificationCounts;
  isLoading: boolean;
  onClose: () => void;
}

const BillOpsUserNotificationDropdown: React.FC<BillOpsUserNotificationDropdownProps> = ({
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
      case 'invoice':
        return 'fas fa-file-invoice-dollar';
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
      case 'invoice':
        return 'notification-info';
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
    invoices: notifications.filter(n => n.type === 'invoice'),
    tickets: notifications.filter(n => n.type === 'ticket'),
    chat: notifications.filter(n => n.type === 'chat'),
  };

  /**
   * Handle chat notification click - mark chat as opened
   */
  const handleChatClick = () => {
    markChatAsOpened();
    onClose();
  };

  /**
   * Get status badge for tickets
   */
  const getStatusBadge = (status?: string): React.ReactNode => {
    if (!status) return null;

    const statusColors: Record<string, string> = {
      OPEN: 'bg-blue-100 text-blue-700',
      IN_PROGRESS: 'bg-yellow-100 text-yellow-700',
      CLOSED: 'bg-gray-100 text-gray-700',
      RESOLVED: 'bg-green-100 text-green-700',
    };

    return (
      <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold ${statusColors[status] || 'bg-gray-100 text-gray-700'}`}>
        {status.replace('_', ' ')}
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
          <p className="notification-empty-text">You're all caught up!</p>
        </div>
      )}

      {/* Notifications List */}
      {!isLoading && notifications.length > 0 && (
        <div className="notification-content">
          {/* New Invoices Section */}
          {groupedNotifications.invoices.length > 0 && (
            <div className="notification-section">
              <div className="notification-section-header">
                <i className="fas fa-file-invoice-dollar notification-section-icon text-green-600"></i>
                <span className="notification-section-title">New Invoices</span>
                <span className="notification-section-count">{counts.invoices}</span>
              </div>
              <div className="notification-list">
                {groupedNotifications.invoices.slice(0, 5).map((notification) => (
                  <Link
                    key={notification.id}
                    to={notification.link || '/billops-invoices'}
                    className={`notification-item ${getTypeColorClass(notification.type)}`}
                    onClick={onClose}
                  >
                    <div className="notification-item-icon bg-green-100 text-green-600">
                      <i className={getTypeIcon(notification.type)}></i>
                    </div>
                    <div className="notification-item-content">
                      <h4 className="notification-item-title">{notification.title}</h4>
                      <p className="notification-item-description">{notification.description}</p>
                      <span className="notification-item-time">{formatTimestamp(notification.timestamp)}</span>
                    </div>
                    {notification.isUnread && (
                      <span className="notification-unread-badge"></span>
                    )}
                  </Link>
                ))}
              </div>
              {groupedNotifications.invoices.length > 5 && (
                <Link to="/billops-invoices" className="notification-view-all" onClick={onClose}>
                  View all invoices ({groupedNotifications.invoices.length})
                </Link>
              )}
            </div>
          )}

          {/* Support Messages Section */}
          {groupedNotifications.chat.length > 0 && (
            <div className="notification-section">
              <div className="notification-section-header">
                <i className="fas fa-comment-dots notification-section-icon text-indigo-600"></i>
                <span className="notification-section-title">Support Messages</span>
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

          {/* My Tickets Section */}
          {groupedNotifications.tickets.length > 0 && (
            <div className="notification-section">
              <div className="notification-section-header">
                <i className="fas fa-ticket-alt notification-section-icon text-orange-600"></i>
                <span className="notification-section-title">My Tickets</span>
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
                        {getStatusBadge(notification.metadata?.status)}
                      </div>
                      <p className="notification-item-description">{notification.description}</p>
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
        </div>
      )}

      {/* Footer */}
      {!isLoading && notifications.length > 0 && (
        <div className="notification-footer">
          <div className="flex items-center justify-around w-full gap-2">
            <Link to="/billops-invoices" className="notification-footer-link flex-1 text-center py-2" onClick={onClose}>
              <i className="fas fa-file-invoice-dollar"></i>
              <span className="text-xs">Invoices</span>
            </Link>
            <div className="h-8 w-[1px] bg-gray-200"></div>
            <Link to="/billops-tickets" className="notification-footer-link flex-1 text-center py-2" onClick={onClose}>
              <i className="fas fa-ticket-alt"></i>
              <span className="text-xs">Tickets</span>
            </Link>
            <div className="h-8 w-[1px] bg-gray-200"></div>
            <button className="notification-footer-link flex-1 text-center py-2" onClick={handleChatClick}>
              <i className="fas fa-comment-dots"></i>
              <span className="text-xs">Chat</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default BillOpsUserNotificationDropdown;
