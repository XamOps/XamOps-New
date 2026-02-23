/**
 * EKS Header Component
 *
 * Sticky header with cluster information and actions
 */

import React from 'react';
import { Link } from 'react-router-dom';

interface EksHeaderProps {
  clusterName: string;
  region: string;
  version: string;
  accountId: string;
  eksData: any;
  onOpenIntegrationGuide: () => void;
}

const EksHeader: React.FC<EksHeaderProps> = ({
  clusterName,
  region,
  version,
  accountId,
  eksData,
  onOpenIntegrationGuide
}) => {
  const { dashboard, isRefreshing, refresh, lastUpdateTimestamp, getTimeSince, totalPodsCount } = eksData;

  return (
    <div className="sticky top-0 z-40 bg-white/95 backdrop-blur-sm relative rounded-xl shadow-sm border border-gray-200 p-4 md:p-6 mb-6 md:mb-8">
      {/* Clean single-row header */}
      <div className="relative flex items-center justify-between">
        {/* Left: Back button + Cluster Info */}
        <div className="flex items-center gap-6">
          <Link
            to={`/cloudk8s?accountIds=${accountId}`}
            className="w-10 h-10 bg-white border border-gray-200 rounded-lg flex items-center justify-center text-gray-500 hover:text-gray-700 hover:border-gray-300 transition-all"
          >
            <i className="fas fa-arrow-left"></i>
          </Link>

          <div className="flex items-center gap-4">
            {/* Cluster icon with status */}
            <div className="relative">
              <div className="w-14 h-14 bg-slate-700 rounded-xl flex items-center justify-center text-white text-2xl shadow-sm">
                <i className="fas fa-dharmachakra"></i>
              </div>
              <div
                className="absolute -bottom-1 -right-1 w-4 h-4 bg-emerald-500 border-2 border-white rounded-full"
                title="Cluster Active"
              >
                <div className="w-full h-full flex items-center justify-center">
                  <div className="w-1.5 h-1.5 bg-white rounded-full animate-pulse"></div>
                </div>
              </div>
            </div>

            {/* Cluster name and metadata */}
            <div>
              <div className="flex items-center gap-3 mb-1">
                <h1 className="text-2xl font-bold text-gray-900">{clusterName}</h1>
                <span className="px-2 py-0.5 rounded bg-slate-100 text-slate-700 text-xs font-bold uppercase">
                  EKS
                </span>
              </div>
              <div className="flex items-center gap-3 text-sm text-gray-600">
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-code-branch text-gray-400 text-xs"></i>
                  <span className="font-medium">v{version}</span>
                </span>
                <span className="text-gray-300">|</span>
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-map-marker-alt text-gray-400 text-xs"></i>
                  <span className="font-medium">{region}</span>
                </span>
                <span className="text-gray-300">|</span>
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-server text-gray-400 text-xs"></i>
                  <span className="font-medium">{dashboard?.clusterOverview?.totalNodes || 0} nodes</span>
                </span>
                <span className="text-gray-300">|</span>
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-cubes text-gray-400 text-xs"></i>
                  <span className="font-medium">{totalPodsCount || 0} pods</span>
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Right: Actions */}
        <div className="flex items-center gap-3">
          <div className="text-right mr-2">
            <div className="text-xs text-gray-400 font-medium">Last synced</div>
            <div className="text-xs text-gray-700 font-semibold">
              {getTimeSince(lastUpdateTimestamp)}
            </div>
          </div>
          <Link
            to={`/k8s-rightsizing?name=${clusterName}&accountId=${accountId}`}
            className="flex items-center gap-2 px-4 py-2.5 bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white rounded-lg font-semibold shadow-sm transition-all"
          >
            <i className="fas fa-chart-line text-sm"></i>
            <span>Right-Sizing</span>
          </Link>
          <button
            onClick={onOpenIntegrationGuide}
            className="flex items-center gap-2 px-4 py-2.5 bg-white border border-gray-200 hover:border-gray-300 hover:bg-gray-50 text-gray-700 rounded-lg font-semibold transition-all"
          >
            <i className="fas fa-book text-sm"></i>
            <span>Integration Guide</span>
          </button>
          <button
            onClick={refresh}
            disabled={isRefreshing}
            className={`flex items-center gap-2 px-5 py-2.5 bg-slate-700 hover:bg-slate-800 text-white rounded-lg font-semibold shadow-sm transition-all disabled:opacity-70 disabled:cursor-not-allowed ${
              isRefreshing ? 'animate-pulse' : ''
            }`}
          >
            <i className={`fas fa-sync-alt text-sm ${isRefreshing ? 'fa-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Syncing...' : 'Sync Data'}</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export default EksHeader;
