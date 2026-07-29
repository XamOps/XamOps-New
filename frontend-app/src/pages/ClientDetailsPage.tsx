import React, { useState, useEffect, useCallback, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import ClientDetailsSkeleton from '../components/Shared/ClientDetailsSkeleton';
import ClientSavingsTab from '../components/BillOps/ClientSavingsTab';
import { useCurrency } from '../hooks/useCurrency';
import { useTopbarActions } from '../contexts/TopbarContext';
import '../styles/billopsAdminDashboard.css';
import '../styles/billopsClientDetails.css';

interface BillOpsAccount {
    id: number;
    name: string;
    providerAccountId: string;
    provider: string;
    consumption: number;
    creditsApplied?: number;
}

interface BillOpsUser {
    id: number;
    username: string;
    email: string;
    accountAccessIds: number[];
    parentId?: number | null;  // null/undefined = primary user; set = sub-user
    lastLoginAt?: string | null;  // ISO datetime string from backend
}

interface ClientDetails {
    id: number;
    name: string;
    email: string;
    accounts: BillOpsAccount[];
    users: BillOpsUser[];
    totalConsumption: number;
    starred: boolean;
}

const CACHE_KEY = 'billops_clients_cache';
const PAGE_SIZE = 10;

function readCache(): ClientDetails[] | null {
    try {
        const raw = localStorage.getItem(CACHE_KEY);
        if (!raw) return null;
        const { data } = JSON.parse(raw);
        return data as ClientDetails[];
    } catch {
        return null;
    }
}

function writeCache(data: ClientDetails[]) {
    try {
        localStorage.setItem(CACHE_KEY, JSON.stringify({ data, ts: Date.now() }));
    } catch { }
}

const ClientDetailsPage: React.FC = () => {
    const [clients, setClients] = useState<ClientDetails[]>(() => readCache() ?? []);
    const [isLoading, setIsLoading] = useState(() => readCache() === null);
    const [isRefreshing, setIsRefreshing] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [expandedClient, setExpandedClient] = useState<number | null>(null);
    const [expandedSection, setExpandedSection] = useState<'accounts' | 'users' | 'savings' | null>(null);
    const [expandedPrimaryUsers, setExpandedPrimaryUsers] = useState<Set<string>>(new Set());
    const [search, setSearch] = useState('');
    const [filterProvider, setFilterProvider] = useState('');
    const [sortBy, setSortBy] = useState('');
    const [currentPage, setCurrentPage] = useState(1);

    const { loadRates, convert } = useCurrency();
    useEffect(() => { loadRates('inr'); }, [loadRates]);

    // Convert GCP/Azure consumption from INR → USD; AWS is already USD
    const convertConsumption = useCallback((amount: number, provider: string): number => {
        if (provider !== 'GCP' && provider !== 'Azure') return amount;
        return convert(amount, 'usd') ?? amount;
    }, [convert]);

    // Sum converted consumptions for a client
    const clientTotal = useCallback((c: ClientDetails): number =>
        c.accounts.reduce((s, a) => s + (convertConsumption(a.consumption || 0, a.provider)), 0),
        [convertConsumption]);

    const { setActions, clearActions } = useTopbarActions();

    const evictServerCache = useCallback(async () => {
        try {
            await apiClient.delete('/api/xamops/billops-admin/clients/cache');
        } catch {
            // non-critical
        }
    }, []);

    const fetchClients = useCallback(async (silent = false) => {
        if (!silent) setIsLoading(true);
        else setIsRefreshing(true);
        setError(null);
        try {
            const data = await apiClient.get<ClientDetails[]>('/api/xamops/billops-admin/clients');
            setClients(data);
            writeCache(data);
        } catch (err: any) {
            console.error('Failed to fetch clients:', err);
            if (!silent) setError(err.message || 'Failed to load client details');
        } finally {
            setIsLoading(false);
            setIsRefreshing(false);
        }
    }, []);

    // Inject Refresh button into the app top bar
    useEffect(() => {
        setActions(
            <div className="flex items-center gap-3">
                {isRefreshing && (
                    <span className="text-xs text-gray-400 flex items-center gap-1">
                        <i className="fas fa-circle-notch fa-spin text-indigo-400"></i>
                        Refreshing...
                    </span>
                )}
                <button
                    onClick={async () => { localStorage.removeItem(CACHE_KEY); await evictServerCache(); fetchClients(true); }}
                    disabled={isLoading || isRefreshing}
                    className="flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-md text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors disabled:opacity-50"
                >
                    <i className={`fas fa-sync-alt text-gray-400 ${isRefreshing ? 'animate-spin' : ''}`}></i>
                    Refresh
                </button>
            </div>
        );
        return () => clearActions();
    }, [setActions, clearActions, isLoading, isRefreshing, evictServerCache, fetchClients]);

    useEffect(() => {
        const cached = readCache();
        if (cached) {
            fetchClients(true);
        } else {
            fetchClients(false);
        }
    }, [fetchClients]);

    // Listen for new client/user creation events so the list updates instantly
    useEffect(() => {
        const handler = async () => {
            localStorage.removeItem(CACHE_KEY);
            await evictServerCache();
            fetchClients(true);
        };
        window.addEventListener('billops:client-updated', handler);
        return () => window.removeEventListener('billops:client-updated', handler);
    }, [fetchClients, evictServerCache]);

    const toggleClient = (clientId: number) => {
        if (expandedClient === clientId) {
            setExpandedClient(null);
            setExpandedSection(null);
        } else {
            setExpandedClient(clientId);
            setExpandedSection('accounts');
        }
    };

    const togglePrimaryUser = (clientId: number, userId: number) => {
        const key = `${clientId}:${userId}`;
        setExpandedPrimaryUsers(prev => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
        });
    };

    const handleLoginAs = async (userId: number, client: ClientDetails, user: BillOpsUser) => {
        const allowedProviderIds: string[] =
            user.accountAccessIds.length === 0
                ? client.accounts.map(a => a.providerAccountId)
                : user.accountAccessIds
                    .map(id => client.accounts.find(a => a.id === id)?.providerAccountId)
                    .filter((pid): pid is string => Boolean(pid));

        try {
            const res = await fetch('/api/billops/generate-impersonation-token', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId,
                    tenantId: client.name,
                    allowedAccountIds: allowedProviderIds.join(','),
                }),
            });

            if (!res.ok) {
                console.error('Failed to generate impersonation token', res.status);
                return;
            }

            // The backend picks the landing page from the target user's real role —
            // BillOps users must not land on (and be stuck in) the XamOps dashboard.
            const { token, destination } = await res.json();
            const url = `${window.location.origin}/${destination || 'unified-dashboard'}#imp=${token}`;
            // Use a programmatic <a> click instead of window.open('', '_blank').
            // window.open with a blank URL clones the opener's sessionStorage into the new
            // tab — when bootstrapImpersonationHash writes back, the admin tab's session
            // gets contaminated and reloading shows the impersonated account.
            const a = document.createElement('a');
            a.href = url;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        } catch (err) {
            console.error('Error during Login As', err);
        }
    };

    const toggleStar = useCallback(async (e: React.MouseEvent, clientId: number) => {
        e.stopPropagation();
        // Optimistic update
        setClients(prev => prev.map(c => c.id === clientId ? { ...c, starred: !c.starred } : c));
        try {
            await apiClient.post(`/api/xamops/billops-admin/clients/${clientId}/star`, {});
            // Refresh cache silently so other pages pick up the change
            localStorage.removeItem(CACHE_KEY);
        } catch (err) {
            // Roll back on failure
            setClients(prev => prev.map(c => c.id === clientId ? { ...c, starred: !c.starred } : c));
            console.error('Failed to toggle star:', err);
        }
    }, []);

    const stats = useMemo(() => ({
        clients: clients.length,
        accounts: clients.reduce((sum, c) => sum + c.accounts.length, 0),
        users: clients.reduce((sum, c) => sum + c.users.length, 0),
        spend: clients.reduce((sum, c) => sum + clientTotal(c), 0),
    }), [clients, clientTotal]);

    const availableProviders = useMemo(() => {
        const providers = new Set<string>();
        clients.forEach(c => c.accounts.forEach(a => providers.add(a.provider)));
        return [...providers].sort();
    }, [clients]);

    const filteredClients = useMemo(() => {
        let result = clients;

        if (search.trim()) {
            const term = search.toLowerCase();
            result = result.filter(c =>
                (c.name ?? '').toLowerCase().includes(term) ||
                (c.email ?? '').toLowerCase().includes(term) ||
                c.accounts.some(a => (a.name ?? '').toLowerCase().includes(term) || (a.providerAccountId ?? '').toLowerCase().includes(term)) ||
                c.users.some(u => (u.username ?? '').toLowerCase().includes(term) || (u.email ?? '').toLowerCase().includes(term))
            );
        }

        if (filterProvider) {
            result = result.filter(c => c.accounts.some(a => a.provider === filterProvider));
        }

        if (sortBy === 'name-asc') result = [...result].sort((a, b) => a.name.localeCompare(b.name));
        else if (sortBy === 'name-desc') result = [...result].sort((a, b) => b.name.localeCompare(a.name));
        else if (sortBy === 'spend-desc') result = [...result].sort((a, b) => clientTotal(b) - clientTotal(a));
        else if (sortBy === 'spend-asc') result = [...result].sort((a, b) => clientTotal(a) - clientTotal(b));

        return result;
    }, [clients, search, filterProvider, sortBy, clientTotal]);

    // Reset to page 1 whenever filters/sort change
    useEffect(() => { setCurrentPage(1); }, [search, filterProvider, sortBy]);

    const totalPages = Math.ceil(filteredClients.length / PAGE_SIZE);
    const paginatedClients = useMemo(() => {
        const start = (currentPage - 1) * PAGE_SIZE;
        return filteredClients.slice(start, start + PAGE_SIZE);
    }, [filteredClients, currentPage]);

    if (isLoading) {
        return (
            <div className="p-5" style={{ background: '#ffffff', minHeight: '100%' }}>
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
        <div className="p-5 space-y-3" style={{ background: '#ffffff', minHeight: '100%' }}>
            {/* Stats Summary */}
            {!isLoading && clients.length > 0 && (
                <div className="bg-white rounded-md border border-gray-200 shadow-sm divide-x divide-gray-100 grid grid-cols-2 md:grid-cols-4 animate-premium-fade-in">
                    <div className="p-4 flex items-center gap-3">
                        <div className="w-8 h-8 rounded-md bg-gray-100 flex items-center justify-center flex-shrink-0">
                            <i className="fas fa-building text-gray-500 text-xs"></i>
                        </div>
                        <div>
                            <p className="text-xl font-bold text-gray-900 leading-none">{stats.clients}</p>
                            <p className="text-[11px] text-gray-400 mt-0.5">Clients</p>
                        </div>
                    </div>
                    <div className="p-4 flex items-center gap-3">
                        <div className="w-8 h-8 rounded-md bg-gray-100 flex items-center justify-center flex-shrink-0">
                            <i className="fas fa-cloud text-gray-500 text-xs"></i>
                        </div>
                        <div>
                            <p className="text-xl font-bold text-gray-900 leading-none">{stats.accounts}</p>
                            <p className="text-[11px] text-gray-400 mt-0.5">Cloud Accounts</p>
                        </div>
                    </div>
                    <div className="p-4 flex items-center gap-3">
                        <div className="w-8 h-8 rounded-md bg-gray-100 flex items-center justify-center flex-shrink-0">
                            <i className="fas fa-users text-gray-500 text-xs"></i>
                        </div>
                        <div>
                            <p className="text-xl font-bold text-gray-900 leading-none">{stats.users}</p>
                            <p className="text-[11px] text-gray-400 mt-0.5">Total Users</p>
                        </div>
                    </div>
                    <div className="p-4 flex items-center gap-3">
                        <div className="w-8 h-8 rounded-md bg-gray-100 flex items-center justify-center flex-shrink-0">
                            <i className="fas fa-dollar-sign text-gray-500 text-xs"></i>
                        </div>
                        <div>
                            <p className="text-xl font-bold text-gray-900 leading-none">${stats.spend.toLocaleString(undefined, { maximumFractionDigits: 0 })}</p>
                            <p className="text-[11px] text-gray-400 mt-0.5">Total Spend</p>
                        </div>
                    </div>
                </div>
            )}

            {/* Search & Filters Toolbar */}
            <div className="flex flex-col md:flex-row gap-2 animate-premium-fade-in">
                <div className="relative flex-1">
                    <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
                    <input
                        type="text"
                        placeholder={`Search ${clients.length} clients by name, email, account or user...`}
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        className="w-full pl-8 pr-8 py-2 bg-white border border-gray-200 rounded-md text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors"
                    />
                    {search && (
                        <button
                            onClick={() => setSearch('')}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                        >
                            <i className="fas fa-times text-xs"></i>
                        </button>
                    )}
                </div>
                {availableProviders.length > 0 && (
                    <select
                        value={filterProvider}
                        onChange={e => setFilterProvider(e.target.value)}
                        className="px-3 py-2 bg-white border border-gray-200 rounded-md text-sm text-gray-600 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors min-w-[140px] cursor-pointer"
                    >
                        <option value="">All Providers</option>
                        {availableProviders.map(p => (
                            <option key={p} value={p}>{p}</option>
                        ))}
                    </select>
                )}
                <select
                    value={sortBy}
                    onChange={e => setSortBy(e.target.value)}
                    className="px-3 py-2 bg-white border border-gray-200 rounded-md text-sm text-gray-600 focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-400 transition-colors min-w-[160px] cursor-pointer"
                >
                    <option value="">Sort: Default</option>
                    <option value="name-asc">Name A → Z</option>
                    <option value="name-desc">Name Z → A</option>
                    <option value="spend-desc">Spend: High → Low</option>
                    <option value="spend-asc">Spend: Low → High</option>
                </select>
            </div>

            {/* Results count + clear all */}
            <div className="flex items-center justify-between">
                <p className="text-xs text-gray-500 font-medium">
                    {filteredClients.length === 0
                        ? '0 clients'
                        : (() => {
                            const start = (currentPage - 1) * PAGE_SIZE + 1;
                            const end = Math.min(currentPage * PAGE_SIZE, filteredClients.length);
                            const suffix = filteredClients.length !== clients.length
                                ? ` of ${clients.length} clients`
                                : ` client${filteredClients.length !== 1 ? 's' : ''}`;
                            return `Showing ${start}–${end} of ${filteredClients.length}${suffix}`;
                        })()}
                </p>
                {(search || filterProvider || sortBy) && (
                    <button
                        onClick={() => { setSearch(''); setFilterProvider(''); setSortBy(''); }}
                        className="text-xs text-red-500 font-semibold hover:text-red-700 flex items-center gap-1 transition-colors"
                    >
                        <i className="fas fa-times-circle"></i>
                        Clear All Filters
                    </button>
                )}
            </div>

            <div className="grid gap-2">
                {paginatedClients.map((client, index) => (
                    <div
                        key={client.id}
                        className={`bg-white border rounded-md overflow-hidden animate-premium-fade-in stagger-${(index % 5) + 1} transition-colors ${expandedClient === client.id ? 'border-indigo-300' : 'border-gray-200 hover:border-gray-300'
                            }`}
                    >
                        {/* Client Header Row */}
                        <div
                            className="px-5 py-3.5 flex flex-col md:flex-row md:items-center justify-between gap-4 cursor-pointer"
                            onClick={() => toggleClient(client.id)}
                        >
                            <div className="flex items-center gap-3.5">
                                <div className="w-9 h-9 rounded-md bg-gray-100 flex items-center justify-center text-gray-600 text-sm font-semibold flex-shrink-0">
                                    {client.name.charAt(0).toUpperCase()}
                                </div>
                                <div>
                                    <h2 className="text-sm font-semibold text-gray-900 leading-tight">{client.name}</h2>
                                    <div className="flex items-center gap-2 mt-0.5">
                                        <span className="text-[11px] text-gray-400">{client.email}</span>
                                        <span className="text-gray-200">·</span>
                                        <span className="text-[11px] text-gray-400">#{client.id}</span>
                                    </div>
                                </div>
                            </div>

                            <div className="flex items-center gap-6 ml-auto">
                                <div className="text-right">
                                    <p className="text-[10px] font-semibold text-gray-400 uppercase tracking-widest leading-none">Consumption</p>
                                    <p className="text-base font-semibold text-gray-800 mt-1 leading-none">
                                        ${clientTotal(client).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                                    </p>
                                </div>
                                <div className="w-px h-8 bg-gray-100 hidden md:block"></div>
                                <div className="flex items-center gap-1.5">
                                    <div className="badge-enterprise badge-enterprise-blue">
                                        {client.accounts.length} Accounts
                                    </div>
                                    <div className="badge-enterprise badge-enterprise-purple">
                                        {client.users.length} Users
                                    </div>
                                </div>
                                <button
                                    onClick={(e) => toggleStar(e, client.id)}
                                    title={client.starred ? 'Remove primary flag' : 'Mark as primary client'}
                                    className={`w-6 h-6 flex items-center justify-center transition-colors ${client.starred ? 'text-amber-400 hover:text-amber-500' : 'text-gray-300 hover:text-amber-300'}`}
                                >
                                    <i className={`${client.starred ? 'fas' : 'far'} fa-star text-xs`}></i>
                                </button>
                                <div className={`w-6 h-6 rounded-md flex items-center justify-center transition-all duration-300 ${expandedClient === client.id ? 'bg-indigo-600 text-white rotate-180' : 'bg-gray-100 text-gray-400'}`}>
                                    <i className="fas fa-chevron-down text-[9px]"></i>
                                </div>
                            </div>
                        </div>

                        {/* Collapsible Content */}
                        {expandedClient === client.id && (
                            <div className="border-t border-gray-100 bg-gray-50/30">
                                {/* Sub-navigation */}
                                <div className="flex border-b border-gray-100 px-8 gap-8">
                                    <button
                                        className={`py-3 text-xs font-semibold uppercase tracking-wider transition-colors relative ${expandedSection === 'accounts' ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'}`}
                                        onClick={() => setExpandedSection('accounts')}
                                    >
                                        <i className="fas fa-microchip mr-1.5 text-gray-400"></i>
                                        Accounts
                                        {expandedSection === 'accounts' && <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600"></div>}
                                    </button>
                                    <button
                                        className={`py-3 text-xs font-semibold uppercase tracking-wider transition-colors relative ${expandedSection === 'users' ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'}`}
                                        onClick={() => setExpandedSection('users')}
                                    >
                                        <i className="fas fa-user-shield mr-1.5 text-gray-400"></i>
                                        Users
                                        {expandedSection === 'users' && <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600"></div>}
                                    </button>
                                    <button
                                        className={`py-3 text-xs font-semibold uppercase tracking-wider transition-colors relative ${expandedSection === 'savings' ? 'text-indigo-600' : 'text-gray-400 hover:text-gray-600'}`}
                                        onClick={() => setExpandedSection('savings')}
                                    >
                                        <i className="fas fa-piggy-bank mr-1.5 text-gray-400"></i>
                                        Savings
                                        {expandedSection === 'savings' && <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-indigo-600"></div>}
                                    </button>
                                </div>

                                <div className="p-6">
                                    {expandedSection === 'accounts' && (
                                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                                            {client.accounts.map((acc, accIdx) => (
                                                <div key={acc.id} className={`bg-white p-4 rounded-md border border-gray-200 hover:border-indigo-200 transition-colors animate-premium-fade-in stagger-${(accIdx % 5) + 1}`}>
                                                    <div className="flex items-center justify-between mb-3">
                                                        <span className={`text-[10px] font-semibold px-2 py-0.5 rounded border uppercase tracking-wider ${acc.provider === 'AWS' ? 'bg-orange-50 text-orange-700 border-orange-200' :
                                                            acc.provider === 'GCP' ? 'bg-blue-50 text-blue-700 border-blue-200' :
                                                                'bg-emerald-50 text-emerald-700 border-emerald-200'
                                                            }`}>
                                                            {acc.provider}
                                                        </span>
                                                        <span className="text-base font-semibold text-gray-900">
                                                            ${convertConsumption(acc.consumption || 0, acc.provider).toLocaleString(undefined, { maximumFractionDigits: 2 })}
                                                        </span>
                                                    </div>
                                                    <h3 className="font-semibold text-gray-800 line-clamp-1 text-sm">{acc.name}</h3>
                                                    {acc.provider === 'AWS' && acc.creditsApplied != null && acc.creditsApplied > 0 && (
                                                        <div className="mt-2 flex items-center justify-between">
                                                            <span className="text-[10px] font-semibold text-emerald-600 flex items-center gap-1">
                                                                <i className="fas fa-tag text-emerald-500"></i>
                                                                Credits Applied
                                                            </span>
                                                            <span className="text-[10px] font-semibold text-emerald-600">
                                                                -${convertConsumption(acc.creditsApplied, 'AWS').toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                                                            </span>
                                                        </div>
                                                    )}
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

                                    {expandedSection === 'savings' && (
                                        <ClientSavingsTab clientId={client.id} />
                                    )}

                                    {expandedSection === 'users' && (() => {
                                        // Build sub-user map: parentId → sub-users
                                        const subUserMap = new Map<string, BillOpsUser[]>();
                                        for (const u of client.users) {
                                            if (u.parentId != null) {
                                                const key = String(u.parentId);
                                                if (!subUserMap.has(key)) subUserMap.set(key, []);
                                                subUserMap.get(key)!.push(u);
                                            }
                                        }
                                        const primaryUsers = client.users.filter(u => u.parentId == null);

                                        const renderAccountAccess = (user: BillOpsUser) => (
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
                                                    <span className="text-xs text-gray-400 italic">Full Access</span>
                                                )}
                                            </div>
                                        );

                                        return (
                                            <div className="rounded-md border border-gray-200 bg-white overflow-hidden">
                                                <table className="w-full text-left">
                                                    <thead className="bg-gray-50 border-b border-gray-200">
                                                        <tr>
                                                            <th className="px-4 py-2.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">User</th>
                                                            <th className="px-4 py-2.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Email</th>
                                                            <th className="px-4 py-2.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Account Access</th>
                                                            <th className="px-4 py-2.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Last Active</th>
                                                            <th className="px-4 py-2.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest text-right">Actions</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody className="divide-y divide-gray-100">
                                                        {primaryUsers.length === 0 && (
                                                            <tr>
                                                                <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                                                                    No users found for this client.
                                                                </td>
                                                            </tr>
                                                        )}
                                                        {primaryUsers.map(primary => {
                                                            const subUsers = subUserMap.get(String(primary.id)) ?? [];
                                                            const hasSubUsers = subUsers.length > 0;
                                                            const isPrimaryExpanded = expandedPrimaryUsers.has(`${client.id}:${primary.id}`);

                                                            return (
                                                                <React.Fragment key={primary.id}>
                                                                    {/* Primary user row */}
                                                                    <tr className="hover:bg-indigo-50/30 transition-colors border-l-4 border-l-indigo-300/60">
                                                                        <td className="px-4 py-3.5">
                                                                            <div className="flex items-center gap-3">
                                                                                {hasSubUsers ? (
                                                                                    <button
                                                                                        onClick={() => togglePrimaryUser(client.id, primary.id)}
                                                                                        className="w-5 h-5 flex items-center justify-center text-indigo-500 hover:text-indigo-700 flex-shrink-0 transition-colors"
                                                                                    >
                                                                                        <i className={`fas fa-chevron-right text-[9px] transition-transform duration-200 ${isPrimaryExpanded ? 'rotate-90' : ''}`}></i>
                                                                                    </button>
                                                                                ) : (
                                                                                    <span className="w-5 h-5 flex-shrink-0"></span>
                                                                                )}
                                                                                <div className="w-7 h-7 rounded-md bg-gray-100 text-gray-600 flex items-center justify-center text-xs font-semibold flex-shrink-0">
                                                                                    {primary.username.charAt(0).toUpperCase()}
                                                                                </div>
                                                                                <div>
                                                                                    <span className="font-semibold text-gray-900 text-sm">{primary.username}</span>
                                                                                    {hasSubUsers && (
                                                                                        <span className="ml-2 text-[10px] font-semibold text-indigo-500 bg-indigo-50 border border-indigo-100 px-1.5 py-0.5 rounded">
                                                                                            {subUsers.length} sub-user{subUsers.length !== 1 ? 's' : ''}
                                                                                        </span>
                                                                                    )}
                                                                                </div>
                                                                            </div>
                                                                        </td>
                                                                        <td className="px-4 py-3.5 text-sm text-gray-600">{primary.email}</td>
                                                                        <td className="px-4 py-3.5">{renderAccountAccess(primary)}</td>
                                                                        <td className="px-4 py-3.5">
                                                                            {primary.lastLoginAt ? (
                                                                                <div>
                                                                                    <p className="text-xs font-semibold text-gray-700">{new Date(primary.lastLoginAt).toLocaleDateString()}</p>
                                                                                    <p className="text-[10px] text-gray-400">{new Date(primary.lastLoginAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</p>
                                                                                </div>
                                                                            ) : (
                                                                                <span className="text-xs text-gray-300 italic">Never</span>
                                                                            )}
                                                                        </td>
                                                                        <td className="px-6 py-3.5 text-right">
                                                                            <button
                                                                                onClick={() => handleLoginAs(primary.id, client, primary)}
                                                                                className="px-3 py-1.5 bg-indigo-600 text-white rounded-md text-xs font-medium hover:bg-indigo-700 transition-colors flex items-center gap-1.5 ml-auto"
                                                                            >
                                                                                <i className="fas fa-user-secret text-[9px]"></i>
                                                                                Login as
                                                                            </button>
                                                                        </td>
                                                                    </tr>

                                                                    {/* Sub-user rows */}
                                                                    {hasSubUsers && isPrimaryExpanded && subUsers.map(sub => (
                                                                        <tr key={sub.id} className="hover:bg-emerald-50/30 transition-colors border-l-4 border-l-emerald-200/60 bg-gray-50/50">
                                                                            <td className="px-4 py-3 pl-16">
                                                                                <div className="flex items-center gap-3">
                                                                                    <i className="fas fa-level-up-alt fa-rotate-90 text-gray-300 text-xs flex-shrink-0"></i>
                                                                                    <div className="w-6 h-6 rounded-md bg-emerald-100 text-emerald-700 flex items-center justify-center text-xs font-semibold flex-shrink-0">
                                                                                        {sub.username.charAt(0).toUpperCase()}
                                                                                    </div>
                                                                                    <span className="font-medium text-gray-700 text-sm">{sub.username}</span>
                                                                                    <span className="text-[10px] font-semibold text-emerald-600 bg-emerald-50 border border-emerald-100 px-1.5 py-0.5 rounded">sub-user</span>
                                                                                </div>
                                                                            </td>
                                                                            <td className="px-4 py-3 text-sm text-gray-500">{sub.email}</td>
                                                                            <td className="px-4 py-3">{renderAccountAccess(sub)}</td>
                                                                            <td className="px-4 py-3">
                                                                                {sub.lastLoginAt ? (
                                                                                    <div>
                                                                                        <p className="text-xs font-semibold text-gray-700">{new Date(sub.lastLoginAt).toLocaleDateString()}</p>
                                                                                        <p className="text-[10px] text-gray-400">{new Date(sub.lastLoginAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</p>
                                                                                    </div>
                                                                                ) : (
                                                                                    <span className="text-xs text-gray-300 italic">Never</span>
                                                                                )}
                                                                            </td>
                                                                            <td className="px-6 py-3 text-right">
                                                                                <button
                                                                                    onClick={() => handleLoginAs(sub.id, client, sub)}
                                                                                    className="px-3 py-1.5 bg-emerald-600 text-white rounded-md text-xs font-medium hover:bg-emerald-700 transition-colors flex items-center gap-1.5 ml-auto"
                                                                                >
                                                                                    <i className="fas fa-user-secret text-[9px]"></i>
                                                                                    Login as
                                                                                </button>
                                                                            </td>
                                                                        </tr>
                                                                    ))}
                                                                </React.Fragment>
                                                            );
                                                        })}
                                                    </tbody>
                                                </table>
                                            </div>
                                        );
                                    })()}
                                </div>
                            </div>
                        )}
                    </div>
                ))}

                {filteredClients.length === 0 && !isLoading && (
                    <div className="text-center py-12 bg-white rounded-md border border-dashed border-gray-300">
                        <i className="fas fa-user-friends text-4xl text-gray-300 mb-3"></i>
                        <p className="text-gray-500">
                            {search ? `No clients match "${search}"` : 'No clients found matching your administrative scope.'}
                        </p>
                    </div>
                )}
            </div>

            {/* Pagination Controls */}
            {totalPages > 1 && (
                <div className="flex items-center justify-center gap-2 pt-2 animate-premium-fade-in">
                    <button
                        onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                        disabled={currentPage === 1}
                        className="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-gray-200 rounded-md text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                    >
                        <i className="fas fa-chevron-left text-xs"></i>
                        Prev
                    </button>

                    {Array.from({ length: totalPages }, (_, i) => i + 1).map(page => {
                        const isActive = page === currentPage;
                        const isEdge = page === 1 || page === totalPages;
                        const isNearCurrent = Math.abs(page - currentPage) <= 1;
                        if (!isEdge && !isNearCurrent) {
                            // Show ellipsis only once per gap
                            if (page === 2 || page === totalPages - 1) {
                                return <span key={page} className="px-1 text-gray-400 text-sm select-none">…</span>;
                            }
                            return null;
                        }
                        return (
                            <button
                                key={page}
                                onClick={() => setCurrentPage(page)}
                                className={`w-8 h-8 rounded-md text-sm font-medium transition-colors ${isActive
                                        ? 'bg-indigo-600 text-white'
                                        : 'bg-white border border-gray-200 text-gray-700 hover:bg-gray-50'
                                    }`}
                            >
                                {page}
                            </button>
                        );
                    })}

                    <button
                        onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                        disabled={currentPage === totalPages}
                        className="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-gray-200 rounded-md text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                    >
                        Next
                        <i className="fas fa-chevron-right text-xs"></i>
                    </button>
                </div>
            )}
        </div>
    );
};

export default ClientDetailsPage;
