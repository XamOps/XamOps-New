import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useInvoices } from '../hooks/useInvoices';
import { useTopbar } from '../contexts/TopbarContext';
import { useAccounts } from '../contexts/AccountContext';
import Big from 'big.js';
import type { InvoiceDiscount } from '../types/invoices';
import '../styles/invoices.css';

const InvoicesPage: React.FC = () => {
  const { accounts, isLoading: accountsLoading } = useAccounts();
  const hasAccounts = accounts.length > 0;

  const {
    filteredInvoices,
    selectedInvoice,
    detailedBreakdown,
    expandedServices,
    expandedRegions,

    selectedYear,
    selectedMonth,
    years,
    months,

    viewState,
    listLoading,
    detailLoading,
    isUpdating,

    error,
    updateError,
    lastUpdatedDisplay,

    hasData,

    setSelectedYear,
    setSelectedMonth,
    handleFilterChange,
    fetchData,
    viewInvoice,
    goBackToList,
    downloadInvoice,
    toggleService,
    toggleRegion,
  } = useInvoices();

  const { setActions, clearActions } = useTopbar();

  const formatCurrency = (value: number | undefined | null): string => {
    if (typeof value !== 'number') return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  };

  const formatNumber = (value: number | string | null | undefined): string => {
    if (typeof value !== 'number' && typeof value !== 'string') return '0';
    const num = Number(value);
    if (Number.isNaN(num)) return '0';
    return num.toLocaleString('en-US', { maximumFractionDigits: 3 });
  };

  const getAccountId = (invoice: any): string => {
    if (invoice.awsAccountId && invoice.awsAccountId !== 'N/A') return invoice.awsAccountId;
    if (invoice.gcpProjectId && invoice.gcpProjectId !== 'N/A') return invoice.gcpProjectId;
    return invoice.azureSubscriptionId || 'N/A';
  };

  const getStatusBadgeClass = (status: string): string => {
    if (status === 'FINALIZED') return 'bg-emerald-100 text-emerald-700 border-emerald-200';
    return 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.5)] border-[rgba(0,0,0,0.08)]';
  };

  const calculateDiscountAmount = (discount: InvoiceDiscount): number => {
    if (!selectedInvoice) return 0;
    try {
      let amount = 0;
      const percentage = new Big(discount.percentage).div(100);
      if (discount.serviceName === 'ALL') {
        amount = new Big(selectedInvoice.preDiscountTotal).times(percentage).toNumber();
      } else {
        const service = detailedBreakdown.find((s) => s.serviceName === discount.serviceName);
        if (service) {
          amount = new Big(service.totalCost).times(percentage).toNumber();
        }
      }
      return amount * -1;
    } catch (err) {
      console.error('[Invoices] Discount calculation error:', err);
      return 0;
    }
  };

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {lastUpdatedDisplay && (
          <div className="text-xs text-[rgba(0,0,0,0.5)] font-medium hidden md:block">
            Synced {lastUpdatedDisplay}
          </div>
        )}
        {viewState === 'list' && (
          <button
            onClick={() => fetchData(true)}
            disabled={listLoading || isUpdating}
            className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50"
          >
            <Icon icon={isUpdating ? 'mdi:loading' : 'mdi:refresh'} width={14} className={isUpdating ? 'animate-spin' : ''} />
            <span>{isUpdating ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        )}
        {viewState === 'detail' && (
          <div className="flex items-center gap-2">
            <button
              onClick={goBackToList}
              className="flex items-center gap-2 px-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-md text-[12px] font-medium text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
            >
              <Icon icon="mdi:arrow-left" width={14} />
              <span>Back</span>
            </button>
            <button
              onClick={downloadInvoice}
              disabled={!selectedInvoice || detailLoading}
              className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50"
            >
              <Icon icon="mdi:download-outline" width={14} />
              <span>Download PDF</span>
            </button>
          </div>
        )}
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, lastUpdatedDisplay, viewState, isUpdating, listLoading, fetchData, goBackToList, downloadInvoice, selectedInvoice, detailLoading]);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen space-y-4">

      {/* No Accounts Empty State */}
      {!accountsLoading && !hasAccounts && (
        <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:power-plug-outline" width={28} className="text-amber-500" />
          </div>
          <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No Accounts Connected</h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] max-w-sm mx-auto mb-5">
            Connect a cloud account to view your invoices.
          </p>
          <a
            href="/account-manager"
            className="inline-flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            <Icon icon="mdi:plus" width={14} />
            Add Account
          </a>
        </div>
      )}

      {(accountsLoading || hasAccounts) && <>

      {/* Filters & Controls */}
      <div className="bg-white p-4 rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)] flex flex-col md:flex-row items-center justify-between gap-4">
        <div className="flex items-center gap-4 w-full md:w-auto">

          <div className="relative">
            <label htmlFor="year" className="block text-[10px] font-semibold text-[rgba(0,0,0,0.4)] mb-1 uppercase tracking-wider">
              Year
            </label>
            <div className="relative">
              <select
                id="year"
                value={selectedYear}
                onChange={(e) => { setSelectedYear(e.target.value); handleFilterChange(); }}
                className="block w-40 pl-3 pr-8 py-2 text-xs border border-[rgba(0,0,0,0.08)] focus:outline-none focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 rounded-lg bg-[rgba(0,0,0,0.04)] cursor-pointer appearance-none font-medium text-[rgba(0,0,0,0.88)]"
                disabled={listLoading || isUpdating}
              >
                <option value="all">All Years</option>
                {years.map((year) => (
                  <option key={year} value={year}>{year}</option>
                ))}
              </select>
              <div className="absolute inset-y-0 right-0 flex items-center px-2 pointer-events-none text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:chevron-down" width={14} />
              </div>
            </div>
          </div>

          <div className="relative">
            <label htmlFor="month" className="block text-[10px] font-semibold text-[rgba(0,0,0,0.4)] mb-1 uppercase tracking-wider">
              Month
            </label>
            <div className="relative">
              <select
                id="month"
                value={selectedMonth}
                onChange={(e) => { setSelectedMonth(e.target.value); handleFilterChange(); }}
                className="block w-48 pl-3 pr-8 py-2 text-xs border border-[rgba(0,0,0,0.08)] focus:outline-none focus:ring-1 focus:ring-indigo-400 focus:border-indigo-400 rounded-lg bg-[rgba(0,0,0,0.04)] cursor-pointer appearance-none font-medium text-[rgba(0,0,0,0.88)]"
                disabled={listLoading || isUpdating}
              >
                <option value="all">All Months</option>
                {months.map((month) => (
                  <option key={month.value} value={month.value}>{month.name}</option>
                ))}
              </select>
              <div className="absolute inset-y-0 right-0 flex items-center px-2 pointer-events-none text-[rgba(0,0,0,0.4)]">
                <Icon icon="mdi:chevron-down" width={14} />
              </div>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 pt-5 md:pt-0">
          {viewState === 'detail' && (
            <>
              <button
                onClick={goBackToList}
                className="flex items-center gap-2 px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-md text-xs font-medium text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              >
                <Icon icon="mdi:arrow-left" width={13} />
                Back
              </button>
              <button
                onClick={downloadInvoice}
                disabled={!selectedInvoice || detailLoading}
                className="flex items-center gap-2 px-3 py-2 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-xs font-medium hover:bg-black transition-colors disabled:opacity-50"
              >
                <Icon icon="mdi:download-outline" width={13} />
                Download PDF
              </button>
            </>
          )}
          {viewState === 'list' && (
            <button
              onClick={() => fetchData(true)}
              className="flex items-center gap-2 px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-md text-xs font-medium text-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              disabled={listLoading || isUpdating}
            >
              <Icon icon={isUpdating ? 'mdi:loading' : 'mdi:refresh'} width={13} className={isUpdating ? 'animate-spin' : ''} />
              <span>{isUpdating ? 'Refreshing...' : 'Refresh'}</span>
            </button>
          )}
        </div>
      </div>

      {/* Update Error */}
      {updateError && hasData && (
        <div className="bg-amber-50 border border-amber-100 rounded-xl p-4 flex items-center gap-3">
          <Icon icon="mdi:alert-circle-outline" width={18} className="text-amber-500 shrink-0" />
          <span className="font-medium text-sm text-[rgba(0,0,0,0.88)]">{updateError}</span>
        </div>
      )}

      {/* Error */}
      {error && !hasData && (
        <div className="text-center py-12 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-red-50 border border-red-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:alert-circle-outline" width={28} className="text-red-500" />
          </div>
          <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">Failed to load invoices</h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] mb-5 max-w-sm mx-auto">{error}</p>
          <button
            onClick={() => fetchData(true)}
            className="px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            Try Again
          </button>
        </div>
      )}

      {/* Loading Skeleton */}
      {listLoading && (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="bg-[rgba(0,0,0,0.03)] rounded-xl p-5 border border-[rgba(0,0,0,0.06)] animate-pulse flex items-center justify-between">
              <div className="flex gap-4 w-1/2">
                <div className="h-4 bg-[rgba(0,0,0,0.08)] rounded w-24" />
                <div className="h-4 bg-[rgba(0,0,0,0.08)] rounded w-32" />
              </div>
              <div className="h-7 bg-[rgba(0,0,0,0.08)] rounded w-20" />
            </div>
          ))}
        </div>
      )}

      {/* Invoice List */}
      {!listLoading && viewState === 'list' && filteredInvoices.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)] overflow-hidden">
          <div className="overflow-x-auto">
            <table className="min-w-full">
              <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Invoice #</th>
                  <th className="px-5 py-3 text-left text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Account</th>
                  <th className="px-5 py-3 text-left text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Period</th>
                  <th className="px-5 py-3 text-left text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Total</th>
                  <th className="px-5 py-3 text-center text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Status</th>
                  <th className="px-6 py-3 text-right text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {filteredInvoices.map((invoice) => (
                  <tr key={invoice.id} className="hover:bg-[rgba(0,0,0,0.025)] transition-colors group">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <button
                        onClick={() => viewInvoice(invoice)}
                        className="text-indigo-600 hover:text-indigo-800 font-bold font-mono text-sm transition-colors"
                      >
                        {invoice.invoiceNumber}
                      </button>
                    </td>
                    <td className="px-5 py-4 whitespace-nowrap">
                      <span className="font-mono text-xs text-[rgba(0,0,0,0.5)] bg-[rgba(0,0,0,0.04)] px-2 py-1 rounded border border-[rgba(0,0,0,0.06)]">
                        {getAccountId(invoice)}
                      </span>
                    </td>
                    <td className="px-5 py-4 whitespace-nowrap text-sm text-[rgba(0,0,0,0.6)] font-medium">
                      {invoice.billingPeriod}
                    </td>
                    <td className="px-5 py-4 whitespace-nowrap font-mono text-sm font-bold text-[rgba(0,0,0,0.88)]">
                      {formatCurrency(invoice.amount)}
                    </td>
                    <td className="px-5 py-4 whitespace-nowrap text-center">
                      <span className={`px-2.5 py-0.5 inline-flex text-xs font-bold rounded-full border ${getStatusBadgeClass(invoice.status)}`}>
                        {invoice.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm">
                      <button
                        onClick={() => viewInvoice(invoice)}
                        className="text-[rgba(0,0,0,0.3)] hover:text-indigo-600 font-medium transition-colors flex items-center justify-end gap-2 ml-auto"
                      >
                        <span className="opacity-0 group-hover:opacity-100 transition-opacity text-xs">View Details</span>
                        <Icon icon="mdi:chevron-right" width={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Empty invoice list */}
      {!listLoading && viewState === 'list' && filteredInvoices.length === 0 && hasData && (
        <div className="text-center py-14 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:file-document-outline" width={24} className="text-[rgba(0,0,0,0.3)]" />
          </div>
          <p className="text-sm text-[rgba(0,0,0,0.4)] font-medium">No invoices found for the selected period.</p>
        </div>
      )}

      {/* Invoice Detail View */}
      {!detailLoading && viewState === 'detail' && selectedInvoice && (
        <div className="space-y-4">
          <div className="bg-white rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)] overflow-hidden">
            <div className="p-6 border-b border-[rgba(0,0,0,0.06)]">

              {/* Invoice Header */}
              <div className="flex flex-col md:flex-row justify-between items-start mb-6 gap-4">
                <div>
                  <div className="flex items-center gap-3 mb-1">
                    <h2 className="text-2xl font-bold text-[rgba(0,0,0,0.88)]">
                      Invoice #{selectedInvoice.invoiceNumber}
                    </h2>
                    <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold border ${getStatusBadgeClass(selectedInvoice.status)}`}>
                      {selectedInvoice.status}
                    </span>
                  </div>
                  <p className="text-sm text-[rgba(0,0,0,0.5)]">
                    Account ID:{' '}
                    <span className="font-mono text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded">
                      {selectedInvoice.awsAccountId && selectedInvoice.awsAccountId !== 'N/A'
                        ? selectedInvoice.awsAccountId
                        : selectedInvoice.gcpProjectId && selectedInvoice.gcpProjectId !== 'N/A'
                          ? selectedInvoice.gcpProjectId
                          : selectedInvoice.azureSubscriptionId}
                    </span>
                  </p>
                </div>
                <div className="text-right">
                  <p className="font-bold text-[rgba(0,0,0,0.88)] text-base mb-0.5">
                    {selectedInvoice.billingPeriod}
                  </p>
                  <p className="text-xs text-[rgba(0,0,0,0.4)]">
                    Issued: {selectedInvoice.invoiceDate}
                  </p>
                </div>
              </div>

              {/* Summary Cards */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="bg-[rgba(0,0,0,0.02)] p-5 rounded-xl border border-[rgba(0,0,0,0.06)]">
                  <h3 className="text-[10px] font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider mb-2">Original Total</h3>
                  <p className="text-2xl font-bold text-[rgba(0,0,0,0.7)] font-mono tracking-tight">
                    {formatCurrency(selectedInvoice.preDiscountTotal)}
                  </p>
                </div>
                <div className="bg-red-50 p-5 rounded-xl border border-red-100">
                  <h3 className="text-[10px] font-bold text-red-400 uppercase tracking-wider mb-2">Discounts Applied</h3>
                  <p className="text-2xl font-bold text-red-600 font-mono tracking-tight flex items-center gap-2">
                    {formatCurrency(selectedInvoice.discountAmount * -1)}
                    {selectedInvoice.discountAmount > 0 && (
                      <span className="text-xs font-bold bg-red-100 px-1.5 py-0.5 rounded text-red-700">
                        -{(selectedInvoice.discountAmount / selectedInvoice.preDiscountTotal * 100).toFixed(0)}%
                      </span>
                    )}
                  </p>
                </div>
                <div className="bg-emerald-50 p-5 rounded-xl border border-emerald-100 relative overflow-hidden">
                  <div className="absolute top-2 right-2 opacity-[0.08]">
                    <Icon icon="mdi:check-circle-outline" width={56} className="text-emerald-600" />
                  </div>
                  <h3 className="text-[10px] font-bold text-emerald-600 uppercase tracking-wider mb-2">Final Due Amount</h3>
                  <p className="text-3xl font-bold text-emerald-700 font-mono tracking-tight relative z-10">
                    {formatCurrency(selectedInvoice.amount)}
                  </p>
                </div>
              </div>
            </div>

            <div className="p-6 pt-5">
              {/* Cost Breakdown Table */}
              <div className="flex items-center gap-2 mb-4">
                <Icon icon="mdi:format-list-bulleted" width={16} className="text-indigo-500" />
                <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Detail Breakdown</span>
              </div>

              <div className="border border-[rgba(0,0,0,0.12)] rounded-xl overflow-hidden">
                <table className="cost-breakdown-table w-full">
                  <colgroup>
                    <col style={{ width: '40px' }} />
                    <col style={{ width: '60%' }} />
                    <col style={{ width: '20%' }} />
                    <col style={{ width: '20%' }} />
                  </colgroup>
                  <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                    <tr>
                      <th className="py-3 pl-4" />
                      <th className="py-3 text-left text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Service / Resource</th>
                      <th className="py-3 text-right text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Usage</th>
                      <th className="py-3 pr-6 text-right text-xs font-bold text-[rgba(0,0,0,0.4)] uppercase tracking-wider">Amount (USD)</th>
                    </tr>
                  </thead>

                  {detailedBreakdown.map((service) => (
                    <tbody key={service.serviceName} className="border-b border-[rgba(0,0,0,0.04)] last:border-0 hover:bg-[rgba(0,0,0,0.01)] transition-colors">
                      {/* Service Row */}
                      <tr
                        onClick={() => toggleService(service.serviceName)}
                        className="cursor-pointer group"
                      >
                        <td className="py-4 pl-4 text-center">
                          <div className={`w-6 h-6 rounded-lg flex items-center justify-center transition-all duration-200 ${expandedServices[service.serviceName] ? 'bg-indigo-100 text-indigo-600' : 'text-[rgba(0,0,0,0.3)] group-hover:bg-[rgba(0,0,0,0.04)]'}`}>
                            <Icon
                              icon="mdi:chevron-right"
                              width={14}
                              className={`transition-transform duration-200 ${expandedServices[service.serviceName] ? 'rotate-90' : ''}`}
                            />
                          </div>
                        </td>
                        <td className="py-4">
                          <div className="font-semibold text-[rgba(0,0,0,0.88)] text-sm group-hover:text-indigo-700 transition-colors">
                            {service.serviceName}
                          </div>
                        </td>
                        <td className="py-4 text-right">
                          <span className="text-[rgba(0,0,0,0.2)] text-xs">—</span>
                        </td>
                        <td className="py-4 pr-6 text-right">
                          <div className="font-mono font-bold text-[rgba(0,0,0,0.88)] text-sm">{formatCurrency(service.totalCost)}</div>
                        </td>
                      </tr>

                      {/* Region Rows */}
                      {expandedServices[service.serviceName] &&
                        service.regionCosts.map((region) => (
                          <React.Fragment key={region.regionName}>
                            <tr
                              onClick={() => toggleRegion(service.serviceName, region.regionName)}
                              className="cursor-pointer bg-[rgba(0,0,0,0.01)] hover:bg-[rgba(0,0,0,0.025)] transition-colors"
                            >
                              <td className="py-3 pl-4 text-center">
                                {(() => {
                                  const regionKey = `${service.serviceName}-${region.regionName}`;
                                  return (
                                    <Icon
                                      icon="mdi:chevron-right"
                                      width={13}
                                      className={`ml-4 transition-transform duration-200 ${expandedRegions[regionKey] ? 'rotate-90 text-[rgba(0,0,0,0.5)]' : 'text-[rgba(0,0,0,0.25)]'}`}
                                    />
                                  );
                                })()}
                              </td>
                              <td className="py-3 pl-8">
                                <div className="text-sm font-medium text-[rgba(0,0,0,0.7)] flex items-center gap-2">
                                  <Icon icon="mdi:earth" width={13} className="text-[rgba(0,0,0,0.35)]" />
                                  {region.regionName}
                                </div>
                              </td>
                              <td className="py-3 text-right">
                                <span className="text-[rgba(0,0,0,0.2)] text-xs">—</span>
                              </td>
                              <td className="py-3 pr-6 text-right">
                                <div className="font-mono text-sm text-[rgba(0,0,0,0.7)]">{formatCurrency(region.cost)}</div>
                              </td>
                            </tr>

                            {/* Resource Rows */}
                            {expandedRegions[`${service.serviceName}-${region.regionName}`] &&
                              region.resources.map((resource) => (
                                <tr key={resource.resourceId} className="bg-white hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                                  <td className="py-2 pl-4" />
                                  <td className="py-2 pl-16">
                                    <div className="text-xs text-[rgba(0,0,0,0.6)] font-mono break-all pl-2 border-l-2 border-[rgba(0,0,0,0.08)]">
                                      {resource.resourceName}
                                    </div>
                                  </td>
                                  <td className="py-2 text-right text-xs text-[rgba(0,0,0,0.4)] font-mono">
                                    {resource.quantity === null
                                      ? '—'
                                      : `${formatNumber(resource.quantity)} ${resource.unit || ''}`}
                                  </td>
                                  <td className="py-2 pr-6 text-right">
                                    <div className="font-mono text-xs text-[rgba(0,0,0,0.6)]">
                                      {formatCurrency(resource.cost)}
                                    </div>
                                  </td>
                                </tr>
                              ))}
                          </React.Fragment>
                        ))}
                    </tbody>
                  ))}

                  {/* Empty State */}
                  {detailedBreakdown.length === 0 && (
                    <tbody>
                      <tr>
                        <td colSpan={4} className="text-center py-14 bg-[rgba(0,0,0,0.01)]">
                          <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
                            <Icon icon="mdi:magnify" width={24} className="text-[rgba(0,0,0,0.3)]" />
                          </div>
                          <p className="text-sm text-[rgba(0,0,0,0.4)]">No detailed breakdown available.</p>
                        </td>
                      </tr>
                    </tbody>
                  )}

                  {/* Footer - Totals */}
                  <tfoot className="bg-[rgba(0,0,0,0.02)] border-t-2 border-[rgba(0,0,0,0.06)]">
                    <tr>
                      <td colSpan={3} className="px-6 py-3 text-right text-[rgba(0,0,0,0.4)] font-medium uppercase text-xs tracking-wider">
                        Subtotal
                      </td>
                      <td className="px-6 py-3 text-right text-[rgba(0,0,0,0.88)] font-bold font-mono text-sm">
                        {formatCurrency(selectedInvoice.preDiscountTotal)}
                      </td>
                    </tr>

                    {selectedInvoice.discounts.map((discount) => (
                      <tr key={discount.id} className="bg-red-50/50">
                        <td colSpan={3} className="px-6 py-2 text-right text-red-600 text-xs font-medium">
                          {discount.description}
                        </td>
                        <td className="px-6 py-2 text-right text-red-700 font-mono font-medium text-sm">
                          {formatCurrency(calculateDiscountAmount(discount))}
                        </td>
                      </tr>
                    ))}

                    <tr className="border-t border-[rgba(0,0,0,0.06)]">
                      <td colSpan={3} className="px-6 py-2 text-right text-[rgba(0,0,0,0.4)] text-xs">
                        Taxable Assessed Amount
                      </td>
                      <td className="px-6 py-2 text-right text-[rgba(0,0,0,0.7)] font-mono text-sm">
                        {formatCurrency(selectedInvoice.preDiscountTotal - selectedInvoice.discountAmount)}
                      </td>
                    </tr>

                    <tr>
                      <td colSpan={3} className="px-6 py-2 text-right text-[rgba(0,0,0,0.4)] text-xs">
                        IGST (18%)
                      </td>
                      <td className="px-6 py-2 text-right text-[rgba(0,0,0,0.7)] font-mono text-sm">
                        {formatCurrency(selectedInvoice.taxAmount)}
                      </td>
                    </tr>

                    <tr className="bg-[rgba(0,0,0,0.88)] text-white">
                      <td colSpan={3} className="px-6 py-4 text-right font-bold text-sm uppercase tracking-wider">
                        Total Due Amount
                      </td>
                      <td className="px-6 py-4 text-right font-bold text-xl font-mono">
                        {formatCurrency(selectedInvoice.amount)}
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      </> /* end accountsLoading || hasAccounts */
      }
    </main>
  );
};

export default InvoicesPage;
