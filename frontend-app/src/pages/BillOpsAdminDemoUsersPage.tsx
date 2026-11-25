/**
 * BillOps Admin — Demo Users
 *
 * A read-only roll-up of everyone who has signed in on the demo host
 * (demo.xamops.com). The demo frontend shows the shared demo estate, but the
 * sign-in activity below is REAL, stored data: it is sourced from the demo
 * client's users joined to their LOGIN rows in user_activity_event, served by
 * billops at GET /api/billops/admin/demo-users.
 *
 * Sits under Accounts in the sidebar. No write actions — the admin only reads.
 *
 * For: ROLE_BILLOPS_ADMIN / ROLE_BILLOPS_ADMIN_SUB
 */

import React, { useEffect, useMemo, useState } from 'react';
import '../styles/billopsAdminDashboard.css';

/**
 * One demo user, mirroring billops {@code DemoUserDto}. authProvider / firstSeen /
 * lastLogin are omitted from the JSON when there is no LOGIN row, so they
 * arrive as undefined and must render as an em-dash. loginCount is always present
 * (0 when the user has never logged in).
 */
interface DemoUser {
    email: string;
    displayName: string;
    authProvider?: string | null;
    firstSeen?: string | null;
    lastLogin?: string | null;
    loginCount: number;
}

/** em-dash for any null/absent/blank value. */
const EM_DASH = '—';

const text = (value: string | null | undefined): string =>
    value != null && value !== '' ? value : EM_DASH;

/** ISO datetime → readable local timestamp, or an em-dash when absent. */
const formatDateTime = (value: string | null | undefined): string => {
    if (!value) return EM_DASH;
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return EM_DASH;
    return d.toLocaleString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
    });
};

/** Google / Microsoft / etc. → a small pill; em-dash when there is no LOGIN row yet. */
const AuthProviderPill: React.FC<{ provider?: string | null }> = ({ provider }) => {
    if (!provider) return <span className="text-gray-300">{EM_DASH}</span>;
    const label = provider.charAt(0).toUpperCase() + provider.slice(1).toLowerCase();
    return (
        <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-gray-700 bg-gray-50 border border-gray-200 px-2 py-0.5 rounded">
            <i className="fas fa-id-badge text-[9px] text-gray-400" aria-hidden="true" />
            {label}
        </span>
    );
};

const TH: React.FC<{ children: React.ReactNode; align?: 'left' | 'right' }> = ({ children, align = 'left' }) => (
    <th
        className={`px-4 py-2.5 text-[11px] font-semibold text-gray-500 uppercase tracking-widest ${
            align === 'right' ? 'text-right' : ''
        }`}
    >
        {children}
    </th>
);

const BillOpsAdminDemoUsersPage: React.FC = () => {
    const [users, setUsers] = useState<DemoUser[] | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        setError(null);
        fetch('/api/billops/admin/demo-users', { credentials: 'include' })
            .then(async (r) => {
                if (!r.ok) throw new Error(`Request failed with status ${r.status}`);
                return (await r.json()) as DemoUser[];
            })
            .then((data) => {
                if (cancelled) return;
                setUsers(Array.isArray(data) ? data : []);
            })
            .catch((e: unknown) => {
                if (cancelled) return;
                setError(e instanceof Error ? e.message : 'Failed to load demo users');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, []);

    const totalLogins = useMemo(
        () => (users ? users.reduce((sum, u) => sum + (u.loginCount || 0), 0) : 0),
        [users],
    );

    return (
        <div className="p-6 max-w-7xl mx-auto">
            <header className="mb-5">
                <h1 className="text-xl font-semibold text-gray-900">Demo Users</h1>
                <p className="text-sm text-gray-500 mt-1">
                    Real sign-in activity for everyone who has logged in on the demo host. Read-only.
                </p>
            </header>

            <div className="rounded-md border border-gray-200 bg-white overflow-hidden">
                {loading ? (
                    <div className="px-4 py-12 text-center text-gray-500">
                        <i className="fas fa-spinner fa-spin mr-2" aria-hidden="true" />
                        Loading demo users…
                    </div>
                ) : error ? (
                    <div className="px-4 py-12 text-center text-red-600">
                        <i className="fas fa-triangle-exclamation mr-2" aria-hidden="true" />
                        {error}
                    </div>
                ) : !users || users.length === 0 ? (
                    <div className="px-4 py-12 text-center text-gray-500">
                        No demo users have signed in yet.
                    </div>
                ) : (
                    <table className="w-full text-left">
                        <thead className="bg-gray-50 border-b border-gray-200">
                            <tr>
                                <TH>Email</TH>
                                <TH>Name</TH>
                                <TH>Auth Provider</TH>
                                <TH>First Seen</TH>
                                <TH>Last Login</TH>
                                <TH align="right">Login Count</TH>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                            {users.map((u) => (
                                <tr key={u.email} className="hover:bg-indigo-50/30 transition-colors">
                                    <td className="px-4 py-3.5 text-sm font-medium text-gray-900">{u.email}</td>
                                    <td className="px-4 py-3.5 text-sm text-gray-600">{text(u.displayName)}</td>
                                    <td className="px-4 py-3.5 text-sm">
                                        <AuthProviderPill provider={u.authProvider} />
                                    </td>
                                    <td className="px-4 py-3.5 text-sm text-gray-600">{formatDateTime(u.firstSeen)}</td>
                                    <td className="px-4 py-3.5 text-sm text-gray-600">{formatDateTime(u.lastLogin)}</td>
                                    <td className="px-4 py-3.5 text-sm text-gray-900 text-right tabular-nums">{u.loginCount ?? 0}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>

            {!loading && !error && users && users.length > 0 && (
                <p className="text-[11px] text-gray-400 mt-3">
                    {users.length} user{users.length !== 1 ? 's' : ''} · {totalLogins} total login
                    {totalLogins !== 1 ? 's' : ''}
                </p>
            )}
        </div>
    );
};

export default BillOpsAdminDemoUsersPage;
