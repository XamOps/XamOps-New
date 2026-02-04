import React, { useState, useEffect } from 'react';
import apiClient from '../utils/apiClient';
import ClientDetailsSkeleton from '../components/Shared/ClientDetailsSkeleton';
import '../styles/billopsAdminDashboard.css';
import '../styles/billopsClientDetails.css';

interface BillOpsAccount {
    id: number;
    name: string;
    providerAccountId: string;
    provider: string;
    consumption: number;
}

interface BillOpsUser {
    id: number;
    username: string;
    email: string;
    accountAccessIds: number[];
}

interface ClientDetails {
    id: number;
    name: string;
    email: string;
    accounts: BillOpsAccount[];
    users: BillOpsUser[];
    totalConsumption: number;
}

const ClientDetailsPage: React.FC = () => {
    const [clients, setClients] = useState<ClientDetails[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [expandedClient, setExpandedClient] = useState<number | null>(null);
    const [expandedSection, setExpandedSection] = useState<'accounts' | 'users' | null>(null);

    useEffect(() => {
        fetchClients();
    }, []);

    const fetchClients = async () => {
        setIsLoading(true);
        setError(null);
        try {
            const data = await apiClient.get<ClientDetails[]>('/api/xamops/billops-admin/clients');
            setClients(data);
        } catch (err: any) {
            console.error('Failed to fetch clients:', err);
            setError(err.message || 'Failed to load client details');
        } finally {
            setIsLoading(false);
        }
    };

    const toggleClient = (clientId: number) => {
        if (expandedClient === clientId) {
            setExpandedClient(null);
            setExpandedSection(null);
        } else {
            setExpandedClient(clientId);
            setExpandedSection('accounts'); // Default to accounts view when expanded
        }
    };

    const handleLoginAs = (userId: number, tenantId: string) => {
        const url = `${window.location.origin}/unified-dashboard?impersonateUserId=${userId}&tenantId=${tenantId}`;

        // Open in a new window with specific dimensions for a "popup" feel
        const width = 1200;
        const height = 800;
        const left = (window.screen.width / 2) - (width / 2);
        const top = (window.screen.height / 2) - (height / 2);

        window.open(
            url,
            `LoginAs_${userId}`,
            `width=${width},height=${height},top=${top},left=${left},scrollbars=yes,resizable=yes,menubar=no,toolbar=no`
        );
    };

    if (isLoading) {
        return (
            <div className="p-4 md:p-8 bg-gray-50/50 min-h-screen">
                <ClientDetailsSkeleton />
            </div>
        );
    }

    if (error) {
        return (
            <div className="p-8">
                <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded relative" role="alert">
                    <strong className="font-bold">Error! </strong>
                    <span className="block sm:inline">{error}</span>
                </div>
            </div>
        );
    }

    return (
        <div className="p-4 md:p-8 space-y-6 bg-gray-50/50 min-h-screen">
            <header className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-4 animate-premium-fade-in">
                <div>
                    <div className="flex items-center gap-3 mb-1">
                        <span className="p-2 bg-slate-800 rounded-lg text-white shadow-sm">
                            <i className="fas fa-users-cog text-sm"></i>
                        </span>
                        <h1 className="text-2xl font-extrabold tracking-tight text-slate-900">
                            Client Administration
                        </h1>
                    </div>
                    <p className="text-slate-500 font-medium text-sm ml-11">Manage enterprise clients, cloud accounts, and identity impersonation</p>
                </div>
                <button
                    onClick={fetchClients}
                    className="flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-lg shadow-sm hover:shadow-md hover:bg-slate-50 transition-all font-bold text-slate-700 premium-button-secondary text-sm"
                >
                    <i className="fas fa-sync-alt text-slate-400"></i>
                    Refresh Overview
                </button>
            </header>

            <div className="grid gap-4">
                {clients.map((client, index) => (
                    <div
                        key={client.id}
                        className={`premium-glass rounded-2xl premium-card-shadow premium-card-hover border transition-all duration-500 overflow-hidden animate-premium-fade-in stagger-${(index % 5) + 1} ${expandedClient === client.id ? 'border-indigo-500 ring-4 ring-indigo-500/5' : 'border-white/40'
                            }`}
                    >
                        {/* Client Header Card */}
                        <div
                            className="p-6 flex flex-col md:flex-row md:items-center justify-between gap-6 cursor-pointer"
                            onClick={() => toggleClient(client.id)}
                        >
                            <div className="flex items-center gap-5">
                                <div className="w-14 h-14 rounded-xl bg-slate-800 flex items-center justify-center text-white text-xl font-black shadow-lg shadow-slate-200">
                                    {client.name.charAt(0).toUpperCase()}
                                </div>
                                <div className="space-y-1">
                                    <h2 className="text-xl font-bold text-slate-900 tracking-tight">{client.name}</h2>
                                    <div className="flex items-center gap-3 text-xs font-semibold text-slate-500">
                                        <span className="flex items-center gap-1.5 px-2 py-0.5 bg-slate-50 rounded border border-slate-100 uppercase tracking-tighter">
                                            <i className="fas fa-envelope text-slate-400"></i>
                                            {client.email}
                                        </span>
                                        <span className="text-indigo-600/70">REF: #{client.id}</span>
                                    </div>
                                </div>
                            </div>

                            <div className="flex items-center gap-8">
                                <div className="text-right">
                                    <p className="text-[10px] font-black text-slate-400 uppercase tracking-[0.2em]">Live Consumption</p>
                                    <p className="text-3xl font-black text-slate-800">
                                        ${client.totalConsumption?.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                                    </p>
                                </div>
                                <div className="w-px h-12 bg-gray-100 hidden md:block"></div>
                                <div className="flex flex-col items-center">
                                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-[0.2em] mb-2">Inventory Summary</p>
                                    <div className="flex gap-2">
                                        <div className="badge-enterprise badge-enterprise-blue">
                                            {client.accounts.length} Accounts
                                        </div>
                                        <div className="badge-enterprise badge-enterprise-purple">
                                            {client.users.length} Users
                                        </div>
                                    </div>
                                </div>
                                <div className={`w-8 h-8 rounded-full flex items-center justify-center transition-all duration-500 ${expandedClient === client.id ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-200 rotate-180' : 'bg-gray-50 text-gray-400'}`}>
                                    <i className="fas fa-chevron-down text-xs"></i>
                                </div>
                            </div>
                        </div>

                        {/* Collapsible Content */}
                        {expandedClient === client.id && (
                            <div className="border-t border-gray-100 bg-gray-50/30">
                                {/* Sub-navigation */}
                                <div className="flex border-b border-gray-100 px-8 gap-8">
                                    <button
                                        className={`py-4 text-xs font-black uppercase tracking-widest transition-all relative ${expandedSection === 'accounts' ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'
                                            }`}
                                        onClick={() => setExpandedSection('accounts')}
                                    >
                                        <i className="fas fa-microchip mr-2 text-slate-400"></i>
                                        Connected Accounts
                                        {expandedSection === 'accounts' && <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600"></div>}
                                    </button>
                                    <button
                                        className={`py-4 text-xs font-bold uppercase tracking-widest transition-all relative ${expandedSection === 'users' ? 'text-indigo-600' : 'text-slate-400 hover:text-slate-600'
                                            }`}
                                        onClick={() => setExpandedSection('users')}
                                    >
                                        <i className="fas fa-user-shield mr-2 text-slate-400"></i>
                                        Access Management
                                        {expandedSection === 'users' && <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600"></div>}
                                    </button>
                                </div>

                                <div className="p-6">
                                    {expandedSection === 'accounts' && (
                                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                                            {client.accounts.map((acc, accIdx) => (
                                                <div key={acc.id} className={`bg-white p-5 rounded-2xl border border-gray-100 shadow-sm hover:shadow-xl hover:border-indigo-200 transition-all duration-300 animate-premium-fade-in stagger-${(accIdx % 5) + 1}`}>
                                                    <div className="flex items-center justify-between mb-4">
                                                        <span className={`text-[10px] font-black px-2.5 py-1 rounded-lg uppercase tracking-wider ${acc.provider === 'AWS' ? 'bg-orange-50 text-orange-700 border border-orange-100' :
                                                            acc.provider === 'GCP' ? 'bg-blue-50 text-blue-700 border border-blue-100' :
                                                                'bg-emerald-50 text-emerald-700 border border-emerald-100'
                                                            }`}>
                                                            {acc.provider}
                                                        </span>
                                                        <span className="text-lg font-black text-gray-900">
                                                            ${acc.consumption?.toLocaleString(undefined, { maximumFractionDigits: 2 })}
                                                        </span>
                                                    </div>
                                                    <h3 className="font-extrabold text-gray-800 line-clamp-1 text-sm">{acc.name}</h3>
                                                    <p className="text-[10px] text-gray-400 mt-2 font-mono bg-gray-50 p-1.5 rounded-md border border-gray-100">{acc.providerAccountId}</p>
                                                </div>
                                            ))}
                                            {client.accounts.length === 0 && (
                                                <div className="col-span-full py-8 text-center text-gray-500">
                                                    No cloud accounts connected for this client.
                                                </div>
                                            )}
                                        </div>
                                    )}

                                    {expandedSection === 'users' && (
                                        <div className="overflow-x-auto rounded-xl border border-gray-200 bg-white">
                                            <table className="w-full text-left">
                                                <thead className="bg-gray-50 border-b border-gray-200">
                                                    <tr>
                                                        <th className="px-4 py-3 text-xs font-bold text-gray-500 uppercase">User</th>
                                                        <th className="px-4 py-3 text-xs font-bold text-gray-500 uppercase">Email</th>
                                                        <th className="px-4 py-3 text-xs font-bold text-gray-500 uppercase">Account Access</th>
                                                        <th className="px-4 py-3 text-xs font-bold text-gray-500 uppercase text-right">Actions</th>
                                                    </tr>
                                                </thead>
                                                <tbody className="divide-y divide-gray-100">
                                                    {client.users.map(user => (
                                                        <tr key={user.id} className="hover:bg-gray-50/50 transition-colors">
                                                            <td className="px-4 py-4">
                                                                <div className="flex items-center gap-3">
                                                                    <div className="w-8 h-8 rounded-full bg-indigo-100 text-indigo-700 flex items-center justify-center text-xs font-bold">
                                                                        {user.username.charAt(0).toUpperCase()}
                                                                    </div>
                                                                    <span className="font-semibold text-gray-900">{user.username}</span>
                                                                </div>
                                                            </td>
                                                            <td className="px-4 py-4 text-sm text-gray-600">{user.email}</td>
                                                            <td className="px-4 py-4">
                                                                <div className="flex flex-wrap gap-1">
                                                                    {user.accountAccessIds.length > 0 ? (
                                                                        user.accountAccessIds.map(accId => {
                                                                            const acc = client.accounts.find(a => a.id === accId);
                                                                            return acc ? (
                                                                                <span key={accId} className="px-2 py-0.5 bg-gray-100 text-gray-700 rounded text-[10px] font-medium border border-gray-200" title={acc.providerAccountId}>
                                                                                    {acc.name}
                                                                                </span>
                                                                            ) : null;
                                                                        })
                                                                    ) : (
                                                                        <span className="text-xs text-gray-400 italic">Full Access (Primary)</span>
                                                                    )}
                                                                </div>
                                                            </td>
                                                            <td className="px-6 py-4 text-right">
                                                                <button
                                                                    onClick={() => handleLoginAs(user.id, client.name)}
                                                                    className="px-4 py-1.5 bg-indigo-600 text-white rounded-lg text-[10px] font-black uppercase tracking-widest hover:bg-slate-900 transition-all flex items-center gap-2 ml-auto shadow-sm"
                                                                >
                                                                    <i className="fas fa-user-secret text-[9px]"></i>
                                                                    Login as
                                                                </button>
                                                            </td>
                                                        </tr>
                                                    ))}
                                                    {client.users.length === 0 && (
                                                        <tr>
                                                            <td colSpan={4} className="px-4 py-8 text-center text-gray-500">
                                                                No users found for this client.
                                                            </td>
                                                        </tr>
                                                    )}
                                                </tbody>
                                            </table>
                                        </div>
                                    )}
                                </div>
                            </div>
                        )}
                    </div>
                ))}

                {clients.length === 0 && !isLoading && (
                    <div className="text-center py-12 bg-white rounded-xl border border-dashed border-gray-300">
                        <i className="fas fa-user-friends text-4xl text-gray-300 mb-3"></i>
                        <p className="text-gray-500">No clients found matching your administrative scope.</p>
                    </div>
                )}
            </div>
        </div>
    );
};

export default ClientDetailsPage;
