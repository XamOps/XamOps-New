/**
 * SuperAdminTopBar Component
 *
 * Horizontal selection panel for super admins (Tenant -> User -> Account)
 * Environment-aware (Local/UAT vs Prod)
 */

import React, { useId } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import clsx from 'clsx';
import { TooltipWrapper } from '../ui/Tooltip';

const SuperAdminTopBar: React.FC = () => {
    const tenantSelectId = useId();
    const userSelectId = useId();
    const accountSelectId = useId();
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
        <div className="flex items-center gap-4 py-1.5 px-4 bg-indigo-50/80 backdrop-blur-md border border-indigo-100 rounded-full shadow-sm hover:shadow-md transition-all duration-300">
            <div className="flex items-center text-xs font-bold text-indigo-900 mr-1">
                <i className="fas fa-crown text-amber-500 mr-1.5" />
                <span className="uppercase tracking-wider">Super Admin</span>
            </div>

            <div className="h-4 w-[1px] bg-indigo-200 mx-1"></div>

            {/* Tenant Selector */}
            <div className="flex items-center space-x-2">
                <label htmlFor={tenantSelectId} className="text-[10px] text-indigo-400 font-bold uppercase tracking-tight">Tenant</label>
                <select
                    id={tenantSelectId}
                    value={selectedTenant}
                    onChange={handleTenantChange}
                    className="bg-white border border-indigo-100 text-indigo-900 text-xs rounded-lg px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-indigo-200 transition-all cursor-pointer hover:border-indigo-300 shadow-sm"
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
                <label htmlFor={userSelectId} className="text-[10px] text-indigo-400 font-bold uppercase tracking-tight">Impersonate</label>
                <select
                    id={userSelectId}
                    value={impersonateUser}
                    onChange={handleUserChange}
                    disabled={!selectedTenant}
                    className="bg-white border border-indigo-100 text-indigo-900 text-xs rounded-lg px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-indigo-200 transition-all cursor-pointer hover:border-indigo-300 shadow-sm"
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
                <label htmlFor={accountSelectId} className="text-[10px] text-indigo-400 font-bold uppercase tracking-tight">Account</label>
                <select
                    id={accountSelectId}
                    value={selectedAccountId}
                    onChange={handleAccountChange}
                    disabled={!impersonateUser || tenantAccounts.length === 0}
                    className="bg-white border border-indigo-100 text-indigo-900 text-xs rounded-lg px-2.5 py-1 focus:outline-none focus:ring-2 focus:ring-indigo-200 transition-all cursor-pointer hover:border-indigo-300 shadow-sm"
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
                    className="ml-1 p-1.5 bg-rose-100 hover:bg-rose-500 text-rose-600 hover:text-white rounded-xl transition-all duration-200 flex items-center justify-center group shadow-sm border border-rose-200/50"
                    aria-label="Reset Context"
                >
                    <i className="fas fa-undo-alt text-xs group-hover:rotate-[-45deg] transition-transform" />
                </button>
            </TooltipWrapper>

            {/* Environment Badge */}
            <div className={clsx(
                "px-2 py-0.5 rounded-lg text-[10px] font-bold uppercase tracking-wider",
                environment === 'prod' ? "bg-rose-100 text-rose-700 border border-rose-200" :
                    environment === 'uat' ? "bg-amber-100 text-amber-700 border border-amber-200" :
                        "bg-emerald-100 text-emerald-700 border border-emerald-200"
            )}>
                {environment}
            </div>
        </div>
    );
};

export default SuperAdminTopBar;
