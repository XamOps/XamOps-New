/**
 * Grafana Dashboard Header Component
 *
 * Displays the page header with title, account info, and external link to Grafana
 */

import React from 'react';

interface GrafanaHeaderProps {
  accountName?: string;
  externalUrl?: string | null;
  error?: string | null;
}

const GrafanaHeader: React.FC<GrafanaHeaderProps> = ({
  accountName,
  externalUrl,
  error,
}) => {
  const subtitleText = error
    ? error
    : accountName
    ? `Live Grafana Dashboard for ${accountName}`
    : 'Live Grafana Dashboard';

  return (
    <header className="grafana-header h-20 flex items-center justify-between px-8 sticky top-0 z-10">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-orange-400 to-orange-600 rounded-xl flex items-center justify-center shadow-lg">
          <i className="fas fa-chart-line text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">
            Infrastructure Monitoring
          </h1>
          <p className={`text-sm transition-colors ${error ? 'text-red-500 font-semibold' : 'text-gray-600'}`}>
            {subtitleText}
          </p>
        </div>
      </div>

      {externalUrl && (
        <a
          href={externalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center space-x-2 px-5 py-2.5 text-sm font-semibold text-gray-700 bg-white rounded-xl border border-gray-200 hover:bg-gray-50 hover:border-gray-300 hover:shadow-md transition-all group"
        >
          <span>View in Grafana</span>
          <i className="fas fa-external-link-alt text-xs text-gray-400 group-hover:text-orange-500 transition-colors"></i>
        </a>
      )}
    </header>
  );
};

export default GrafanaHeader;
