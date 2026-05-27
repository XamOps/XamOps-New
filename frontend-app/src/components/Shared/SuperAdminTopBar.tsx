/**
 * SuperAdminTopBar Component
 *
 * Horizontal selection panel for super admins (Tenant -> User -> Account)
 * Environment-aware (Local/UAT vs Prod)
 */

import React from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { useTheme } from '../../contexts/ThemeContext';
import { TooltipWrapper } from '../ui/Tooltip';
import clsx from 'clsx';

const SuperAdminTopBar: React.FC = () => {
    const { theme } = useTheme();
    const {
        tenantList,
        tenantUsers,
        tenantAccounts,
        selectedTenant,
        impersonateUser,
        selectedAccountId,
        environment,
        switchTenant,
        switchUser,
        switchAccount,
        resetContext,
        fetchTenantUsers,
        fetchTenantAccounts,
    } = useAuth();

    const handleTenantChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const tenantId = e.target.value;
        switchTenant(tenantId);
        if (tenantId) {
            fetchTenantUsers(tenantId);
        }
    };

    const handleUserChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const userId = e.target.value;
        switchUser(userId);
        if (userId) {
            fetchTenantAccounts(userId, selectedTenant);
        }
    };

    const handleAccountChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const accountId = e.target.value;
        switchAccount(accountId);
    };

    return (
        <div className="flex items-center gap-4 py-1.5 px-4 backdrop-blur-md border rounded-full shadow-sm hover:shadow-md transition-all duration-300 bg-indigo-50/80 border-indigo-100 shadow-indigo-500/10 dark:bg-slate-900/80 dark:border-slate-700 dark:shadow-slate-950/50">
            <div className="flex items-center text-xs font-bold mr-1 text-indigo-900 dark:text-indigo-400">
                <i className="fas fa-crown text-amber-500 mr-1.5" />
                <span className="uppercase tracking-wider">Super Admin</span>
            </div>

            <div className="h-4 w-[1px] mx-1 bg-indigo-200 dark:bg-slate-700"></div>

            {/* Tenant Selector */}
            <div className="flex items-center space-x-2">
                <label htmlFor="tenant-select" className="text-[10px] text-indigo-400 font-bold uppercase tracking-tight">Tenant</label>
                <select
                    id="tenant-select"
                    value={selectedTenant}
                    onChange={handleTenantChange}
                    className="border text-xs rounded-lg px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all cursor-pointer shadow-sm bg-white border-indigo-100 text-indigo-900 hover:border-indigo-300 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-100 dark:hover:border-slate-600"
                >
                    <option value="" className="text-gray-900 font-bold">-- Select Tenant --</option>
                    {tenantList.map(tenant => (
                        <option key={tenant.id} value={tenant.id} className="text-gray-900">
                            {tenant.name}
                        </option>
                    ))}
                </select>
            </div>

            {/* User Selector */}
            <div className={clsx("flex items-center space-x-2", !selectedTenant && "opacity-40 grayscale pointer-events-none")}>
                <label htmlFor="user-select" className="text-[10px] text-indigo-400 font-bold uppercase tracking-tight">Impersonate</label>
                <select
                    id="user-select"
                    value={impersonateUser}
                    onChange={handleUserChange}
                    disabled={!selectedTenant}
                    className="border text-xs rounded-lg px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all cursor-pointer shadow-sm bg-white border-indigo-100 text-indigo-900 hover:border-indigo-300 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-100 dark:hover:border-slate-600"
                >
                    <option value="" className="text-gray-900 font-bold">-- Select User --</option>
                    {tenantUsers.map(user => (
                        <option key={user.id} value={user.id} className="text-gray-900">
                            {user.username} ({user.email})
                        </option>
                    ))}
                </select>
            </div>

            {/* Account Selector */}
            <div className={clsx("flex items-center space-x-2", (!impersonateUser || tenantAccounts.length === 0) && "opacity-40 grayscale pointer-events-none")}>
                <label htmlFor="account-select" className="text-[10px] text-indigo-400 font-bold uppercase tracking-tight">Account</label>
                <select
                    id="account-select"
                    value={selectedAccountId}
                    onChange={handleAccountChange}
                    disabled={!impersonateUser || tenantAccounts.length === 0}
                    className="border text-xs rounded-lg px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all cursor-pointer shadow-sm bg-white border-indigo-100 text-indigo-900 hover:border-indigo-300 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-100 dark:hover:border-slate-600"
                >
                    <option value="" className="text-gray-900 font-bold">-- Select Account --</option>
                    {tenantAccounts.map(account => (
                        <option key={account.id} value={account.id} className="text-gray-900">
                            {account.name} ({account.provider})
                        </option>
                    ))}
                </select>
            </div>

            {/* Reset Button */}
            <TooltipWrapper tip="Reset Context">
                <button
                    onClick={resetContext}
                    aria-label="Reset context"
                    className="ml-1 p-1.5 bg-rose-100 hover:bg-rose-500 text-rose-600 hover:text-white rounded-xl transition-all duration-200 flex items-center justify-center group shadow-sm border border-rose-200/50 focus-visible:ring-2 focus-visible:ring-rose-500 outline-none"
                >
                    <i className="fas fa-undo-alt text-xs group-hover:rotate-[-45deg] transition-transform" />
                </button>
            </TooltipWrapper>

            {/* Environment Badge */}
            <div className={clsx(
                "px-2 py-0.5 rounded-lg text-[10px] font-bold uppercase tracking-wider border transition-colors",
                environment === 'prod'
                    ? "bg-rose-100 text-rose-700 border-rose-200 dark:bg-rose-900/40 dark:text-rose-400 dark:border-rose-800"
                    : environment === 'uat'
                        ? "bg-amber-100 text-amber-700 border-amber-200 dark:bg-amber-900/40 dark:text-amber-400 dark:border-amber-800"
                        : "bg-emerald-100 text-emerald-700 border-emerald-200 dark:bg-emerald-900/40 dark:text-emerald-400 dark:border-emerald-800"
            )}>
                {environment}
            </div>
        </div>
    );
};

export default SuperAdminTopBar;
