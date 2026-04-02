/**
 * MarketOps — Subscribed Marketplace Products & Meter Billing
 *
 * Shows all marketplace product subscriptions for the currently selected
 * cloud account, with detailed month-by-month meter billing per product.
 */

import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useSubscriptions, useMeterBilling, triggerSync } from '../hooks/useMarketOps';
import { MarketplaceSubscription } from '../types/marketops';

// ─── Helper ──────────────────────────────────────────────────────────────────

function formatUsd(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

// ─── Meter Billing Detail Panel ───────────────────────────────────────────────

interface BillingDetailProps {
  cloudAccountId: string;
  subscription: MarketplaceSubscription;
  onClose: () => void;
}

const BillingDetailPanel: React.FC<BillingDetailProps> = ({
  cloudAccountId,
  subscription,
  onClose,
}) => {
  const { data, loading, error } = useMeterBilling(cloudAccountId, subscription.productCode);

  return (
    <div className="mt-6 rounded-2xl border border-blue-100 bg-white/80 backdrop-blur shadow-lg overflow-hidden">
      {/* Panel header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 bg-gradient-to-r from-blue-50 to-indigo-50">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center">
            <i className="fas fa-chart-bar text-white text-sm" />
          </div>
          <div>
            <p className="font-semibold text-gray-800 text-sm">
              {subscription.productTitle}
            </p>
            <p className="text-xs text-gray-500">Monthly Meter Billing</p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="text-gray-400 hover:text-gray-600 transition-colors p-1"
          aria-label="Close billing detail"
        >
          <i className="fas fa-times" />
        </button>
      </div>

      {/* Content */}
      <div className="p-6">
        {loading && (
          <div className="flex items-center justify-center py-8 gap-2 text-gray-400">
            <i className="fas fa-spinner fa-spin" />
            <span className="text-sm">Loading billing data…</span>
          </div>
        )}

        {error && (
          <div className="flex items-center gap-2 text-red-500 py-4 text-sm">
            <i className="fas fa-exclamation-circle" />
            <span>Failed to load billing: {error}</span>
          </div>
        )}

        {!loading && !error && data.length === 0 && (
          <div className="text-center py-8 text-gray-400">
            <i className="fas fa-inbox text-3xl mb-2 block" />
            <p className="text-sm">No billing data found for this product.</p>
          </div>
        )}

        {!loading && !error && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100">
                  <th className="text-left py-2 px-3 font-semibold text-gray-600 text-xs uppercase tracking-wide">
                    Month
                  </th>
                  <th className="text-left py-2 px-3 font-semibold text-gray-600 text-xs uppercase tracking-wide">
                    Usage Type
                  </th>
                  <th className="text-right py-2 px-3 font-semibold text-gray-600 text-xs uppercase tracking-wide">
                    Quantity
                  </th>
                  <th className="text-right py-2 px-3 font-semibold text-gray-600 text-xs uppercase tracking-wide">
                    Cost (USD)
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.map((row, idx) => (
                  <tr
                    key={idx}
                    className="border-b border-gray-50 hover:bg-blue-50/40 transition-colors"
                  >
                    <td className="py-2.5 px-3 font-medium text-gray-700">{row.yearMonth}</td>
                    <td className="py-2.5 px-3 text-gray-500 font-mono text-xs">{row.usageType}</td>
                    <td className="py-2.5 px-3 text-right text-gray-700">
                      {row.usageQuantity != null ? row.usageQuantity.toFixed(4) : '—'}
                    </td>
                    <td className="py-2.5 px-3 text-right font-semibold text-blue-700">
                      {formatUsd(Number(row.costUsd))}
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-gray-200 bg-gray-50">
                  <td colSpan={3} className="py-2.5 px-3 font-semibold text-gray-600 text-xs uppercase">
                    Total
                  </td>
                  <td className="py-2.5 px-3 text-right font-bold text-blue-800">
                    {formatUsd(data.reduce((sum, r) => sum + Number(r.costUsd), 0))}
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};

// ─── Product Card ──────────────────────────────────────────────────────────────

interface ProductCardProps {
  subscription: MarketplaceSubscription;
  selected: boolean;
  onClick: () => void;
}

const statusColors: Record<string, string> = {
  ACTIVE: 'bg-green-100 text-green-700',
  INACTIVE: 'bg-amber-100 text-amber-700',
  EXPIRED: 'bg-red-100 text-red-600',
};

const ProductCard: React.FC<ProductCardProps> = ({ subscription, selected, onClick }) => (
  <div
    onClick={onClick}
    className={`
      group cursor-pointer rounded-2xl border p-5 transition-all duration-200
      ${selected
        ? 'border-blue-400 bg-blue-50/60 shadow-lg ring-2 ring-blue-200'
        : 'border-gray-100 bg-white/70 hover:border-blue-200 hover:shadow-md hover:-translate-y-0.5'}
    `}
  >
    {/* Icon + status */}
    <div className="flex items-start justify-between mb-4">
      <div className="w-11 h-11 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-sm">
        <i className="fas fa-cube text-white text-lg" />
      </div>
      <span
        className={`text-xs font-semibold px-2.5 py-1 rounded-full ${
          statusColors[subscription.subscriptionStatus] ?? 'bg-gray-100 text-gray-600'
        }`}
      >
        {subscription.subscriptionStatus}
      </span>
    </div>

    {/* Product name */}
    <h3 className="font-bold text-gray-800 text-base mb-0.5 truncate">
      {subscription.productTitle}
    </h3>
    {subscription.vendorName && (
      <p className="text-xs text-gray-400 mb-3 truncate">{subscription.vendorName}</p>
    )}

    {/* Cost */}
    <div className="flex items-end justify-between mt-3 pt-3 border-t border-gray-100">
      <div>
        <p className="text-xs text-gray-400 mb-0.5">Total billed</p>
        <p className="font-bold text-gray-800 text-lg">{formatUsd(subscription.totalCostUsd)}</p>
      </div>
      <span className={`text-xs font-medium text-blue-600 transition-all ${selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
        <i className="fas fa-chart-bar mr-1" />
        View Billing
      </span>
    </div>
  </div>
);

// ─── Main Page ─────────────────────────────────────────────────────────────────

const MarketplacePurchasesPage: React.FC = () => {
  const { selectedAccountIds, selectedAccountDisplay, accounts } = useAccounts();
  const cloudAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const selectedAccount = accounts.find((a) => a.id === cloudAccountId);
  const isAwsAccount = selectedAccount?.provider === 'AWS';

  const { data: subscriptions, loading, error, refetch } = useSubscriptions(
    isAwsAccount ? cloudAccountId : null
  );

  const [selectedProduct, setSelectedProduct] = useState<MarketplaceSubscription | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const handleSync = async () => {
    if (!cloudAccountId) return;
    setSyncing(true);
    setSyncError(null);
    try {
      await triggerSync(cloudAccountId);
      setSelectedProduct(null);
      await refetch();
    } catch (e: any) {
      setSyncError(e.message ?? 'Sync failed');
    } finally {
      setSyncing(false);
    }
  };

  const handleProductClick = (sub: MarketplaceSubscription) => {
    setSelectedProduct(prev => (prev?.productCode === sub.productCode ? null : sub));
  };

  // ── No account selected ──
  if (!cloudAccountId) {
    return (
      <main className="flex-1 p-6 bg-transparent">
        <div className="flex flex-col items-center justify-center min-h-64 gap-4 text-gray-400">
          <i className="fas fa-store text-5xl" />
          <p className="text-lg font-medium">Select an account from the sidebar to view MarketOps</p>
        </div>
      </main>
    );
  }

  // ── Non-AWS account ──
  if (!isAwsAccount) {
    return (
      <main className="flex-1 p-6 bg-transparent">
        <div className="flex flex-col items-center justify-center min-h-64 gap-4 text-gray-400">
          <i className="fab fa-aws text-5xl" />
          <p className="text-lg font-medium">MarketOps is currently available for AWS accounts only</p>
          <p className="text-sm">Select an AWS account from the sidebar to view marketplace subscriptions.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 p-6 bg-transparent space-y-6">

      {/* ── Page Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">MarketOps</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Subscribed marketplace products for{' '}
            <span className="font-semibold text-gray-700">
              {selectedAccountDisplay?.name ?? cloudAccountId}
            </span>
          </p>
        </div>

        <button
          onClick={handleSync}
          disabled={syncing}
          className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-sm hover:shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <i className={`fas fa-sync-alt ${syncing ? 'animate-spin' : ''}`} />
          {syncing ? 'Syncing…' : 'Sync from AWS'}
        </button>
      </div>

      {syncError && (
        <div className="flex items-center gap-2 text-red-600 text-sm bg-red-50 border border-red-100 rounded-xl px-4 py-3">
          <i className="fas fa-exclamation-circle" />
          <span>{syncError}</span>
        </div>
      )}

      {/* ── Loading state ── */}
      {loading && (
        <div className="flex items-center justify-center py-20 gap-3 text-gray-400">
          <i className="fas fa-spinner fa-spin text-2xl" />
          <span>Loading subscriptions…</span>
        </div>
      )}

      {/* ── Error state ── */}
      {!loading && error && (
        <div className="rounded-2xl border border-red-100 bg-red-50 p-6 text-center text-red-500">
          <i className="fas fa-exclamation-triangle text-3xl mb-3 block" />
          <p className="font-semibold">Failed to load subscriptions</p>
          <p className="text-sm mt-1">{error}</p>
          <button
            onClick={refetch}
            className="mt-4 px-4 py-2 rounded-xl bg-red-500 text-white text-sm font-semibold hover:bg-red-600 transition-colors"
          >
            Retry
          </button>
        </div>
      )}

      {/* ── Empty state (after successful load) ── */}
      {!loading && !error && subscriptions.length === 0 && (
        <div className="flex flex-col items-center justify-center py-20 gap-4 text-gray-400">
          <div className="w-16 h-16 rounded-2xl bg-gray-100 flex items-center justify-center">
            <i className="fas fa-store text-3xl text-gray-300" />
          </div>
          <p className="text-base font-medium text-gray-500">No marketplace subscriptions found</p>
          <p className="text-sm text-gray-400 max-w-sm text-center">
            Subscribe to products on AWS Marketplace and click{' '}
            <strong>Sync from AWS</strong> to see them here.
          </p>
        </div>
      )}

      {/* ── Product grid ── */}
      {!loading && !error && subscriptions.length > 0 && (
        <>
          {/* Summary strip */}
          <div className="flex items-center gap-6 px-5 py-3 rounded-2xl bg-white/70 border border-gray-100 shadow-sm w-fit">
            <div className="flex items-center gap-2 text-sm text-gray-600">
              <i className="fas fa-cube text-blue-500" />
              <span><strong className="text-gray-800">{subscriptions.length}</strong> products</span>
            </div>
            <div className="w-px h-4 bg-gray-200" />
            <div className="flex items-center gap-2 text-sm text-gray-600">
              <i className="fas fa-check-circle text-green-500" />
              <span>
                <strong className="text-gray-800">
                  {subscriptions.filter(s => s.subscriptionStatus === 'ACTIVE').length}
                </strong>{' '}
                active
              </span>
            </div>
            <div className="w-px h-4 bg-gray-200" />
            <div className="flex items-center gap-2 text-sm text-gray-600">
              <i className="fas fa-dollar-sign text-indigo-500" />
              <span>
                <strong className="text-gray-800">
                  {formatUsd(subscriptions.reduce((sum, s) => sum + s.totalCostUsd, 0))}
                </strong>{' '}
                total billed
              </span>
            </div>
          </div>

          {/* Cards grid */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
            {subscriptions.map((sub) => (
              <ProductCard
                key={sub.productCode}
                subscription={sub}
                selected={selectedProduct?.productCode === sub.productCode}
                onClick={() => handleProductClick(sub)}
              />
            ))}
          </div>

          {/* Billing detail panel */}
          {selectedProduct && cloudAccountId && (
            <BillingDetailPanel
              cloudAccountId={cloudAccountId}
              subscription={selectedProduct}
              onClose={() => setSelectedProduct(null)}
            />
          )}
        </>
      )}
    </main>
  );
};

export default MarketplacePurchasesPage;
