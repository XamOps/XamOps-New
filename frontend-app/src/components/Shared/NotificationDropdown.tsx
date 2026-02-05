/**
 * NotificationDropdown Component
 *
 * Dropdown component for displaying notifications in the topbar
 * Shows CloudGuard alerts, Grafana alerts, and budget alerts from the last 7 days
 * Uses Soft Glass theme for premium styling
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { NotificationItem, NotificationCounts } from '../../types/notifications';
import '../../styles/notifications.css';

interface NotificationDropdownProps {
    notifications: NotificationItem[];
    counts: NotificationCounts;
    isLoading: boolean;
    onClose: () => void;
}

const NotificationDropdown: React.FC<NotificationDropdownProps> = ({
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
            case 'cloudguard':
                return 'fas fa-shield-alt';
            case 'grafana':
                return 'fas fa-chart-line';
            case 'budget':
                return 'fas fa-dollar-sign';
            case 'chat':
                return 'fas fa-comment-dots';
            default:
                return 'fas fa-bell';
        }
    };

    /**
     * Get severity color class
     */
    const getSeverityClass = (severity: string): string => {
        switch (severity) {
            case 'critical':
                return 'notification-critical';
            case 'warning':
                return 'notification-warning';
            case 'info':
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
        chat: notifications.filter(n => n.type === 'chat'),
        cloudguard: notifications.filter(n => n.type === 'cloudguard'),
        grafana: notifications.filter(n => n.type === 'grafana'),
        budget: notifications.filter(n => n.type === 'budget'),
    };

    /**
     * Handle chat notification click - mark chat as opened
     */
    const handleChatClick = () => {
        // Update last chat open time in localStorage
        try {
            localStorage.setItem('lastChatOpenTime', Date.now().toString());
        } catch (error) {
            console.error('Failed to update lastChatOpenTime:', error);
        }
        onClose();
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
                    {/* Chat Messages Section */}
                    {groupedNotifications.chat.length > 0 && (
                        <div className="notification-section">
                            <div className="notification-section-header">
                                <i className="fas fa-comment-dots notification-section-icon"></i>
                                <span className="notification-section-title">Unread Messages</span>
                                <span className="notification-section-count">{counts.chat}</span>
                            </div>
                            <div className="notification-list">
                                {groupedNotifications.chat.slice(0, 3).map((notification) => (
                                    <div
                                        key={notification.id}
                                        className={`notification-item ${getSeverityClass(notification.severity)} notification-chat-item`}
                                        onClick={handleChatClick}
                                        role="button"
                                        tabIndex={0}
                                        onKeyPress={(e) => e.key === 'Enter' && handleChatClick()}
                                    >
                                        <div className="notification-item-icon">
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
                            {groupedNotifications.chat.length > 3 && (
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

                    {/* CloudGuard Alerts Section */}
                    {groupedNotifications.cloudguard.length > 0 && (
                        <div className="notification-section">
                            <div className="notification-section-header">
                                <i className="fas fa-shield-alt notification-section-icon"></i>
                                <span className="notification-section-title">CloudGuard Alerts</span>
                                <span className="notification-section-count">{counts.cloudguard}</span>
                            </div>
                            <div className="notification-list">
                                {groupedNotifications.cloudguard.slice(0, 3).map((notification) => (
                                    <Link
                                        key={notification.id}
                                        to={notification.link || '/alerts'}
                                        className={`notification-item ${getSeverityClass(notification.severity)}`}
                                        onClick={onClose}
                                    >
                                        <div className="notification-item-icon">
                                            <i className={getTypeIcon(notification.type)}></i>
                                        </div>
                                        <div className="notification-item-content">
                                            <h4 className="notification-item-title">{notification.title}</h4>
                                            <p className="notification-item-description">{notification.description}</p>
                                            <span className="notification-item-time">{formatTimestamp(notification.timestamp)}</span>
                                        </div>
                                    </Link>
                                ))}
                            </div>
                            {groupedNotifications.cloudguard.length > 3 && (
                                <Link to="/alerts" className="notification-view-all" onClick={onClose}>
                                    View all CloudGuard alerts ({groupedNotifications.cloudguard.length})
                                </Link>
                            )}
                        </div>
                    )}

                    {/* Grafana Alerts Section */}
                    {groupedNotifications.grafana.length > 0 && (
                        <div className="notification-section">
                            <div className="notification-section-header">
                                <i className="fas fa-chart-line notification-section-icon"></i>
                                <span className="notification-section-title">Grafana Alerts</span>
                                <span className="notification-section-count">{counts.grafana}</span>
                            </div>
                            <div className="notification-list">
                                {groupedNotifications.grafana.slice(0, 3).map((notification) => (
                                    <Link
                                        key={notification.id}
                                        to={notification.link || '/alerts'}
                                        className={`notification-item ${getSeverityClass(notification.severity)}`}
                                        onClick={onClose}
                                    >
                                        <div className="notification-item-icon">
                                            <i className={getTypeIcon(notification.type)}></i>
                                        </div>
                                        <div className="notification-item-content">
                                            <h4 className="notification-item-title">{notification.title}</h4>
                                            <p className="notification-item-description">{notification.description}</p>
                                            <span className="notification-item-time">{formatTimestamp(notification.timestamp)}</span>
                                        </div>
                                    </Link>
                                ))}
                            </div>
                            {groupedNotifications.grafana.length > 3 && (
                                <Link to="/alerts" className="notification-view-all" onClick={onClose}>
                                    View all Grafana alerts ({groupedNotifications.grafana.length})
                                </Link>
                            )}
                        </div>
                    )}

                    {/* Budget Alerts Section */}
                    {groupedNotifications.budget.length > 0 && (
                        <div className="notification-section">
                            <div className="notification-section-header">
                                <i className="fas fa-dollar-sign notification-section-icon"></i>
                                <span className="notification-section-title">Budget Alerts</span>
                                <span className="notification-section-count">{counts.budget}</span>
                            </div>
                            <div className="notification-list">
                                {groupedNotifications.budget.slice(0, 3).map((notification) => (
                                    <Link
                                        key={notification.id}
                                        to={notification.link || '/finops'}
                                        className={`notification-item ${getSeverityClass(notification.severity)}`}
                                        onClick={onClose}
                                    >
                                        <div className="notification-item-icon">
                                            <i className={getTypeIcon(notification.type)}></i>
                                        </div>
                                        <div className="notification-item-content">
                                            <h4 className="notification-item-title">{notification.title}</h4>
                                            <p className="notification-item-description">{notification.description}</p>
                                            <span className="notification-item-time">{formatTimestamp(notification.timestamp)}</span>
                                        </div>
                                    </Link>
                                ))}
                            </div>
                            {groupedNotifications.budget.length > 3 && (
                                <Link to="/finops" className="notification-view-all" onClick={onClose}>
                                    View all budget alerts ({groupedNotifications.budget.length})
                                </Link>
                            )}
                        </div>
                    )}
                </div>
            )}

            {/* Footer */}
            {!isLoading && notifications.length > 0 && (
                <div className="notification-footer">
                    <Link to="/alerts" className="notification-footer-link" onClick={onClose}>
                        <i className="fas fa-external-link-alt"></i>
                        <span>View All Alerts</span>
                    </Link>
                </div>
            )}
        </div>
    );
};

export default NotificationDropdown;
