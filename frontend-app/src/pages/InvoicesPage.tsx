/**
 * Invoices Page
 *
 * Converted from: billops/invoices.html
 *
 * Invoice management with list and detail views:
 * - Filter by year/month
 * - List view with all invoices
 * - Detail view with drilldown (Service -> Region -> Resource)
 * - PDF download functionality
 * - Expandable/collapsible breakdown
 *
 * What stayed the same:
 * - Same API endpoints
 * - Same data structure and fields
 * - Same caching strategy (localStorage)
 * - Same filtering and sorting logic
 * - All business logic preserved exactly
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Using custom hook for data fetching
 * - Enhanced UI with smoother animations
 * - Better TypeScript type safety
 */

import React from 'react';
import { useInvoices } from '../hooks/useInvoices';
import Big from 'big.js';
import type { Invoice, InvoiceDiscount } from '../types/invoices';
import '../styles/invoices.css';

const InvoicesPage: React.FC = () => {
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

    selectedAccountId,
    selectedAccountName,

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

  /**
   * Format currency
   */
  const formatCurrency = (value: number | undefined | null): string => {
    if (typeof value !== 'number') return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  };

  /**
   * Format number
   */
  const formatNumber = (value: number | string | null | undefined): string => {
    if (typeof value !== 'number' && typeof value !== 'string') return '0';
    const num = Number(value);
    if (isNaN(num)) return '0';
    return num.toLocaleString('en-US', { maximumFractionDigits: 3 });
  };

  /**
   * Calculate discount amount using Big.js
   */
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
    } catch (e) {
      return 0;
    }
  };

  return (
    <div className="flex-1 flex flex-col h-screen overflow-hidden bg-gray-50/50">
      <main
        id="main-content"
        className="flex-1 overflow-x-hidden overflow-y-auto custom-scrollbar p-8"
      >
        <div className="w-full space-y-8">
          {/* Header */}
          <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
            <div>
              <h1 className="text-2xl font-bold text-gray-900 flex items-center">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white mr-3 shadow-sm">
                  <i className="fas fa-file-invoice-dollar text-lg"></i>
                </div>
                View Invoices
                {isUpdating && (
                  <span className="ml-3 inline-block w-4 h-4 border-2 border-indigo-600 border-t-transparent rounded-full animate-spin"></span>
                )}
              </h1>
              <div className="flex items-center gap-3 mt-1 ml-13">
                <p className="text-gray-500">Manage and download your monthly invoices</p>
                {selectedAccountName && (
                  <span className="text-xs font-medium px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-100">
                    Account: {selectedAccountName} ({selectedAccountId})
                  </span>
                )}
              </div>
            </div>

            <div className="flex items-center gap-3">
              {lastUpdatedDisplay && (
                <span className="text-xs text-gray-400 font-medium bg-white border border-gray-200 px-3 py-1.5 rounded-lg shadow-sm flex items-center">
                  <i className="far fa-clock mr-2 text-indigo-400" />
                  Synced {lastUpdatedDisplay}
                </span>
              )}
            </div>
          </div>

          {/* Filters & Controls */}
          <div className="bg-white p-5 rounded-2xl shadow-sm border border-gray-100 flex flex-col md:flex-row items-center justify-between gap-4">
            <div className="flex items-center gap-4 w-full md:w-auto">
              <div className="relative group">
                <label htmlFor="year" className="block text-xs font-semibold text-gray-500 mb-1 uppercase tracking-wider">
                  Year
                </label>
                <div className="relative">
                  <select
                    id="year"
                    value={selectedYear}
                    onChange={(e) => {
                      setSelectedYear(e.target.value);
                      handleFilterChange();
                    }}
                    className="block w-40 pl-4 pr-10 py-2.5 text-sm border-gray-200 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 rounded-xl bg-gray-50 group-hover:bg-white transition-colors cursor-pointer appearance-none font-medium text-gray-700"
                    disabled={listLoading || isUpdating}
                  >
                    <option value="all">All Years</option>
                    {years.map((year) => (
                      <option key={year} value={year}>
                        {year}
                      </option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-3 pointer-events-none text-gray-400">
                    <i className="fas fa-chevron-down text-xs"></i>
                  </div>
                </div>
              </div>

              <div className="relative group">
                <label htmlFor="month" className="block text-xs font-semibold text-gray-500 mb-1 uppercase tracking-wider">
                  Month
                </label>
                <div className="relative">
                  <select
                    id="month"
                    value={selectedMonth}
                    onChange={(e) => {
                      setSelectedMonth(e.target.value);
                      handleFilterChange();
                    }}
                    className="block w-48 pl-4 pr-10 py-2.5 text-sm border-gray-200 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 rounded-xl bg-gray-50 group-hover:bg-white transition-colors cursor-pointer appearance-none font-medium text-gray-700"
                    disabled={listLoading || isUpdating}
                  >
                    <option value="all">All Months</option>
                    {months.map((month) => (
                      <option key={month.value} value={month.value}>
                        {month.name}
                      </option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 flex items-center px-3 pointer-events-none text-gray-400">
                    <i className="fas fa-chevron-down text-xs"></i>
                  </div>
                </div>
              </div>
            </div>

            <div className="flex items-center gap-3 pt-5 md:pt-0">
              {viewState === 'detail' && (
                <>
                  <button
                    onClick={goBackToList}
                    className="px-5 py-2.5 bg-white text-gray-700 border border-gray-200 rounded-xl hover:bg-gray-50 hover:text-gray-900 transition-all font-medium shadow-sm flex items-center"
                  >
                    <i className="fas fa-arrow-left mr-2" />
                    Back
                  </button>
                  <button
                    onClick={downloadInvoice}
                    disabled={!selectedInvoice || detailLoading}
                    className="px-5 py-2.5 bg-indigo-600 text-white rounded-xl hover:bg-indigo-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-all font-medium shadow-sm hover:shadow hover:translate-y-px flex items-center"
                  >
                    <i className="fas fa-download mr-2" />
                    Download PDF
                  </button>
                </>
              )}

              {viewState === 'list' && (
                <button
                  onClick={() => fetchData(true)}
                  className="px-5 py-2.5 bg-white text-gray-600 border border-gray-200 rounded-xl hover:bg-gray-50 hover:text-indigo-600 transition-all font-medium shadow-sm flex items-center gap-2"
                  disabled={listLoading || isUpdating}
                >
                  <i className={`fas ${isUpdating ? 'fa-spinner fa-spin' : 'fa-sync-alt'}`} />
                  <span>{isUpdating ? 'Refreshing...' : 'Refresh'}</span>
                </button>
              )}
            </div>
          </div>

          {/* Update Error */}
          {updateError && hasData && (
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-center gap-3 text-amber-800 shadow-sm">
              <i className="fas fa-exclamation-triangle text-amber-500 text-lg" />
              <span className="font-medium text-sm">{updateError}</span>
            </div>
          )}

          {/* Error */}
          {error && !hasData && (
            <div className="text-center p-12 bg-white rounded-2xl shadow-sm border border-red-100">
              <div className="w-16 h-16 bg-red-50 rounded-full flex items-center justify-center mx-auto mb-4">
                <i className="fas fa-exclamation-circle text-red-500 text-3xl" />
              </div>
              <h3 className="text-lg font-bold text-gray-900 mb-2">Failed to load invoices</h3>
              <p className="text-gray-500 mb-6 max-w-sm mx-auto">{error}</p>
              <button
                onClick={() => fetchData(true)}
                className="px-6 py-2 bg-white border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 font-medium transition-colors"
              >
                Try Again
              </button>
            </div>
          )}

          {/* Loading Skeleton */}
          {listLoading && (
            <div className="space-y-4">
              {[1, 2, 3].map(i => (
                <div key={i} className="bg-white/80 backdrop-blur-sm rounded-xl p-6 border border-gray-100 shadow-sm animate-pulse flex items-center justify-between">
                  <div className="flex gap-4 w-1/2">
                    <div className="h-4 bg-gray-200 rounded w-24"></div>
                    <div className="h-4 bg-gray-200 rounded w-32"></div>
                  </div>
                  <div className="h-8 bg-gray-200 rounded w-20"></div>
                </div>
              ))}
            </div>
          )}

          {/* Invoice List */}
          {!listLoading && viewState === 'list' && filteredInvoices.length > 0 && (
            <div className="bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden">
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-100">
                  <thead className="bg-gray-50/50">
                    <tr>
                      <th className="px-8 py-4 text-left text-xs font-bold text-gray-400 uppercase tracking-wider">
                        Invoice #
                      </th>
                      <th className="px-6 py-4 text-left text-xs font-bold text-gray-400 uppercase tracking-wider">
                        Account
                      </th>
                      <th className="px-6 py-4 text-left text-xs font-bold text-gray-400 uppercase tracking-wider">
                        Period
                      </th>
                      <th className="px-6 py-4 text-left text-xs font-bold text-gray-400 uppercase tracking-wider">
                        Total
                      </th>
                      <th className="px-6 py-4 text-center text-xs font-bold text-gray-400 uppercase tracking-wider">
                        Status
                      </th>
                      <th className="px-8 py-4 text-right text-xs font-bold text-gray-400 uppercase tracking-wider">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody className="bg-white divide-y divide-gray-50">
                    {filteredInvoices.map((invoice) => (
                      <tr key={invoice.id} className="hover:bg-gray-50/80 transition-all duration-200 group">
                        <td className="px-8 py-5 whitespace-nowrap">
                          <button
                            onClick={() => viewInvoice(invoice)}
                            className="text-indigo-600 hover:text-indigo-800 font-bold font-mono text-sm transition-colors"
                          >
                            {invoice.invoiceNumber}
                          </button>
                        </td>
                        <td className="px-6 py-5 whitespace-nowrap">
                          <span className="font-mono text-xs text-gray-500 bg-gray-100 px-2 py-1 rounded border border-gray-200">
                            {invoice.awsAccountId && invoice.awsAccountId !== 'N/A'
                              ? invoice.awsAccountId
                              : invoice.gcpProjectId && invoice.gcpProjectId !== 'N/A'
                                ? invoice.gcpProjectId
                                : invoice.azureSubscriptionId}
                          </span>
                        </td>
                        <td className="px-6 py-5 whitespace-nowrap text-sm text-gray-600 font-medium">
                          {invoice.billingPeriod}
                        </td>
                        <td className="px-6 py-5 whitespace-nowrap font-mono text-sm font-bold text-gray-900">
                          {formatCurrency(invoice.amount)}
                        </td>
                        <td className="px-6 py-5 whitespace-nowrap text-center">
                          <span
                            className={`px-3 py-1 inline-flex text-xs leading-5 font-bold rounded-full border ${invoice.status === 'FINALIZED'
                                ? 'bg-green-50 text-green-700 border-green-200'
                                : 'bg-gray-50 text-gray-600 border-gray-200'
                              }`}
                          >
                            {invoice.status}
                          </span>
                        </td>
                        <td className="px-8 py-5 whitespace-nowrap text-right text-sm">
                          <button
                            onClick={() => viewInvoice(invoice)}
                            className="text-gray-400 hover:text-indigo-600 font-medium transition-colors flex items-center justify-end gap-2 ml-auto"
                          >
                            <span className="opacity-0 group-hover:opacity-100 transition-opacity">View Details</span>
                            <i className="fas fa-chevron-right text-xs"></i>
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Invoice Detail View */}
          {!detailLoading && viewState === 'detail' && selectedInvoice && (
            <div className="space-y-6 animate-fadeIn">
              <div className="bg-white rounded-2xl shadow-sm border border-gray-100 overflow-hidden">
                <div className="p-8 pb-6 border-b border-gray-100">
                  {/* Invoice Header */}
                  <div className="flex flex-col md:flex-row justify-between items-start mb-8 gap-6">
                    <div>
                      <div className="flex items-center gap-3 mb-2">
                        <h2 className="text-3xl font-bold text-gray-900">
                          Invoice #{selectedInvoice.invoiceNumber}
                        </h2>
                        <span className="px-3 py-1 rounded-full bg-green-50 text-green-700 text-xs font-bold border border-green-200">
                          {selectedInvoice.status}
                        </span>
                      </div>
                      <p className="text-gray-500 font-medium">
                        Account ID:{' '}
                        <span className="font-mono text-gray-700 bg-gray-50 px-2 py-0.5 rounded">
                          {selectedInvoice.awsAccountId && selectedInvoice.awsAccountId !== 'N/A'
                            ? selectedInvoice.awsAccountId
                            : selectedInvoice.gcpProjectId && selectedInvoice.gcpProjectId !== 'N/A'
                              ? selectedInvoice.gcpProjectId
                              : selectedInvoice.azureSubscriptionId}
                        </span>
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="font-bold text-gray-900 text-lg mb-1">
                        {selectedInvoice.billingPeriod}
                      </p>
                      <p className="text-gray-500 text-sm">
                        Issued: {selectedInvoice.invoiceDate}
                      </p>
                    </div>
                  </div>

                  {/* Summary Cards */}
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <div className="bg-gray-50 p-6 rounded-2xl border border-gray-100">
                      <h3 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-2">Original Total</h3>
                      <p className="text-3xl font-bold text-gray-700 font-mono tracking-tight">
                        {formatCurrency(selectedInvoice.preDiscountTotal)}
                      </p>
                    </div>
                    <div className="bg-red-50/50 p-6 rounded-2xl border border-red-100">
                      <h3 className="text-xs font-bold text-red-400 uppercase tracking-wider mb-2">Discounts Applied</h3>
                      <p className="text-3xl font-bold text-red-600 font-mono tracking-tight flex items-center">
                        {formatCurrency(selectedInvoice.discountAmount * -1)}
                        {selectedInvoice.discountAmount > 0 && <span className="text-base ml-2 bg-red-100 px-2 py-0.5 rounded text-red-700">-{(selectedInvoice.discountAmount / selectedInvoice.preDiscountTotal * 100).toFixed(0)}%</span>}
                      </p>
                    </div>
                    <div className="bg-gradient-to-br from-green-50 to-emerald-50 p-6 rounded-2xl border border-green-100 relative overflow-hidden">
                      <div className="absolute top-0 right-0 p-4 opacity-10">
                        <i className="fas fa-check-circle text-6xl text-green-600" />
                      </div>
                      <h3 className="text-xs font-bold text-green-600 uppercase tracking-wider mb-2">Final Due Amount</h3>
                      <p className="text-4xl font-bold text-green-700 font-mono tracking-tight relative z-10">
                        {formatCurrency(selectedInvoice.amount)}
                      </p>
                    </div>
                  </div>
                </div>

                <div className="p-8 pt-6">
                  {/* Cost Breakdown Table */}
                  <h2 className="text-lg font-bold text-gray-900 mb-6 flex items-center">
                    <i className="fas fa-list-ul mr-3 text-indigo-500"></i>
                    Detail Breakdown
                  </h2>
                  <div className="border border-gray-200 rounded-xl overflow-hidden shadow-sm">
                    <table className="cost-breakdown-table w-full">
                      <colgroup>
                        <col style={{ width: '40px' }} />
                        <col style={{ width: '60%' }} />
                        <col style={{ width: '20%' }} />
                        <col style={{ width: '20%' }} />
                      </colgroup>
                      <thead className="bg-gray-50 border-b border-gray-200">
                        <tr>
                          <th className="py-3 pl-4"></th>
                          <th className="py-3 text-left text-xs font-bold text-gray-500 uppercase">Service / Resource</th>
                          <th className="py-3 text-right text-xs font-bold text-gray-500 uppercase">Usage</th>
                          <th className="py-3 pr-6 text-right text-xs font-bold text-gray-500 uppercase">Amount (USD)</th>
                        </tr>
                      </thead>

                      {detailedBreakdown.map((service) => (
                        <tbody key={service.serviceName} className="border-b border-gray-100 last:border-0 hover:bg-gray-50/30 transition-colors">
                          {/* Service Row */}
                          <tr
                            onClick={() => toggleService(service.serviceName)}
                            className="cursor-pointer group"
                          >
                            <td className="py-4 pl-4 text-center">
                              <div
                                className={`w-6 h-6 rounded-full flex items-center justify-center transition-all duration-200 ${expandedServices[service.serviceName] ? 'bg-indigo-100 text-indigo-600 rotate-90' : 'text-gray-400 group-hover:bg-gray-100'}`}
                              >
                                <i className="fas fa-chevron-right text-[10px]" />
                              </div>
                            </td>
                            <td className="py-4">
                              <div className="font-bold text-gray-900 text-sm group-hover:text-indigo-700 transition-colors">{service.serviceName}</div>
                            </td>
                            <td className="py-4 text-right">
                              <span className="text-gray-300 text-xs">—</span>
                            </td>
                            <td className="py-4 pr-6 text-right">
                              <div className="font-mono font-bold text-gray-900">{formatCurrency(service.totalCost)}</div>
                            </td>
                          </tr>

                          {/* Region Rows */}
                          {expandedServices[service.serviceName] &&
                            service.regionCosts.map((region) => (
                              <React.Fragment key={region.regionName}>
                                <tr
                                  onClick={() => toggleRegion(service.serviceName, region.regionName)}
                                  className="cursor-pointer bg-gray-50/50 hover:bg-indigo-50/30 transition-colors"
                                >
                                  <td className="py-3 pl-4 text-center">
                                    <div className="w-px h-full bg-gray-200 mx-auto absolute top-0 bottom-0 left-7 -z-10"></div>
                                    <div
                                      className={`ml-6 w-5 h-5 rounded flex items-center justify-center transition-transform duration-200 ${expandedRegions[`${service.serviceName}-${region.regionName}`] ? 'text-indigo-500 rotate-90' : 'text-gray-400'}`}
                                    >
                                      <i className="fas fa-caret-right text-xs" />
                                    </div>
                                  </td>
                                  <td className="py-3 pl-8">
                                    <div className="text-sm font-medium text-gray-700 flex items-center">
                                      <i className="fas fa-globe-americas text-xs text-gray-400 mr-2"></i>
                                      {region.regionName}
                                    </div>
                                  </td>
                                  <td className="py-3 text-right">
                                    <span className="text-gray-300 text-xs">—</span>
                                  </td>
                                  <td className="py-3 pr-6 text-right">
                                    <div className="font-mono text-sm text-gray-800">{formatCurrency(region.cost)}</div>
                                  </td>
                                </tr>

                                {/* Resource Rows */}
                                {expandedRegions[`${service.serviceName}-${region.regionName}`] &&
                                  region.resources.map((resource) => (
                                    <tr key={resource.resourceId} className="bg-white hover:bg-yellow-50/30 transition-colors">
                                      <td className="py-2 pl-4">
                                        <div className="w-px h-full bg-gray-200 mx-auto absolute top-0 bottom-0 left-7 -z-10"></div>
                                      </td>
                                      <td className="py-2 pl-16">
                                        <div className="text-xs text-gray-600 font-mono break-all pl-2 border-l-2 border-gray-100">{resource.resourceName}</div>
                                      </td>
                                      <td className="py-2 text-right text-xs text-gray-500">
                                        {resource.quantity != null
                                          ? `${formatNumber(resource.quantity)} ${resource.unit || ''}`
                                          : '-'}
                                      </td>
                                      <td className="py-2 pr-6 text-right">
                                        <div className="font-mono text-xs text-gray-600">
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
                            <td colSpan={4} className="text-center py-20 bg-gray-50/30">
                              <i className="fas fa-search text-gray-300 text-3xl mb-4" />
                              <p className="text-gray-500 font-medium">
                                No detailed breakdown available.
                              </p>
                            </td>
                          </tr>
                        </tbody>
                      )}

                      {/* Footer - Totals */}
                      <tfoot className="bg-gray-50 border-t-2 border-gray-200">
                        <tr>
                          <td colSpan={3} className="px-6 py-3 text-right text-gray-500 font-medium uppercase text-xs tracking-wider">
                            Subtotal
                          </td>
                          <td className="px-6 py-3 text-right text-gray-800 font-bold font-mono">
                            {formatCurrency(selectedInvoice.preDiscountTotal)}
                          </td>
                        </tr>

                        {/* Discounts */}
                        {selectedInvoice.discounts.map((discount) => (
                          <tr key={discount.id} className="bg-red-50/30">
                            <td colSpan={3} className="px-6 py-2 text-right text-red-600 text-xs font-medium">
                              <span>{discount.description}</span>
                            </td>
                            <td className="px-6 py-2 text-right text-red-700 font-mono font-medium">
                              {formatCurrency(calculateDiscountAmount(discount))}
                            </td>
                          </tr>
                        ))}

                        <tr className="border-t border-gray-200">
                          <td colSpan={3} className="px-6 py-2 text-right text-gray-500 text-xs">
                            Taxable Assessed Amount
                          </td>
                          <td className="px-6 py-2 text-right text-gray-700 font-mono text-sm">
                            {formatCurrency(
                              selectedInvoice.preDiscountTotal - selectedInvoice.discountAmount
                            )}
                          </td>
                        </tr>

                        <tr>
                          <td colSpan={3} className="px-6 py-2 text-right text-gray-500 text-xs">
                            IGST (18%)
                          </td>
                          <td className="px-6 py-2 text-right text-gray-700 font-mono text-sm">
                            {formatCurrency(selectedInvoice.taxAmount)}
                          </td>
                        </tr>

                        <tr className="bg-indigo-600 text-white">
                          <td
                            colSpan={3}
                            className="px-6 py-4 text-right font-bold text-sm uppercase tracking-wider"
                          >
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
        </div>
      </main>
    </div>
  );
};

export default InvoicesPage;
