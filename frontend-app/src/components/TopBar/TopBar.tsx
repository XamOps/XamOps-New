/**
 * TopBar Component
 *
 * Global top navigation bar containing Super Admin Panel and Profile
 */

import React, { useState, useRef, useEffect } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { useAccounts } from '../../contexts/AccountContext';
import { useNotifications } from '../../hooks/useNotifications';
import SuperAdminTopBar from '../Shared/SuperAdminTopBar';
import NotificationDropdown from '../Shared/NotificationDropdown';
import { TooltipWrapper } from '../ui';

const TopBar: React.FC = () => {
    const { hasAnyRole, userProfile, logout } = useAuth();
    const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
    const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

    const [isNotificationOpen, setIsNotificationOpen] = useState(false);
    const notificationRef = useRef<HTMLDivElement>(null);

    // Fetch notifications
    const { notifications, counts, isLoading: notificationsLoading } = useNotifications({
        selectedAccountId,
        provider: selectedAccountDisplay?.provider,
    });

    // Close dropdown when clicking outside
    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (notificationRef.current && !notificationRef.current.contains(event.target as Node)) {
                setIsNotificationOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClickOutside);
        return () => document.removeEventListener('mousedown', handleClickOutside);
    }, []);

    const toggleNotifications = () => setIsNotificationOpen(!isNotificationOpen);

    return (
        <header className="h-16 fixed top-0 left-0 right-0 z-40 bg-white/80 backdrop-blur-xl border-b border-gray-200 lg:left-64 flex items-center justify-between px-6 transition-all duration-300">
            {/* Search or Page Title */}
            <div className="flex-1 max-w-xl">
                <div className="relative group">
                    <i className="fas fa-search absolute left-4 top-1/2 -translate-y-1/2 text-gray-400 group-focus-within:text-indigo-500 transition-colors" />
                    <input
                        type="text"
                        aria-label="Search resources, billing, tickets"
                        placeholder="Search resources, billing, tickets..."
                        className="w-full pl-11 pr-4 py-2.5 bg-gray-100/50 border-none rounded-2xl text-sm focus:ring-2 focus:ring-indigo-500/20 focus:bg-white transition-all outline-none"
                    />
                </div>
            </div>

            {/* Center/Right - Super Admin Panel */}
            <div className="flex-1 flex justify-center px-4">
                {hasAnyRole('ROLE_SUPER_ADMIN') && <SuperAdminTopBar />}
            </div>

            {/* Right side - Profile & Actions */}
            <div className="flex items-center space-x-4">
                {/* Notifications */}
                <div className="relative" ref={notificationRef}>
                    <TooltipWrapper tip="Notifications">
                        <button
                            onClick={toggleNotifications}
                            aria-label="Toggle notifications"
                            className="relative p-2.5 text-gray-500 hover:text-indigo-600 hover:bg-indigo-50 rounded-xl transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1"
                        >
                            <i className="far fa-bell text-xl" />
                            {counts.total > 0 && (
                                <span className="absolute top-2 right-2 w-2 h-2 bg-red-500 border-2 border-white rounded-full"></span>
                            )}
                        </button>
                    </TooltipWrapper>

                    {isNotificationOpen && (
                        <NotificationDropdown
                            notifications={notifications}
                            counts={counts}
                            isLoading={notificationsLoading}
                            onClose={() => setIsNotificationOpen(false)}
                        />
                    )}
                </div>

                {/* Support */}
                <TooltipWrapper tip="Help & Support">
                    <button
                        aria-label="Help and support"
                        className="p-2.5 text-gray-500 hover:text-indigo-600 hover:bg-indigo-50 rounded-xl transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1"
                    >
                        <i className="far fa-question-circle text-xl" />
                    </button>
                </TooltipWrapper>

                {/* Profile Dropdown (Simplified) */}
                <div className="flex items-center space-x-3 pl-4 border-l border-gray-200">
                    <div className="text-right hidden sm:block">
                        <p className="text-sm font-bold text-gray-900 leading-none">{userProfile?.username || 'User'}</p>
                        <p className="text-[10px] text-gray-500 font-medium uppercase mt-1">{userProfile?.roles?.[0] || 'Member'}</p>
                    </div>
                    <TooltipWrapper tip="Logout">
                        <button
                            onClick={logout}
                            aria-label="Sign out"
                            className="w-10 h-10 rounded-2xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white font-bold shadow-lg shadow-indigo-200 hover:scale-105 active:scale-95 transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1"
                        >
                            {userProfile?.username?.[0]?.toUpperCase() || 'U'}
                        </button>
                    </TooltipWrapper>
                </div>
            </div>
        </header>
    );
};

export default TopBar;
