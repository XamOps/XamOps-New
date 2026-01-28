/**
 * LoadingSkeleton Component
 * Shows loading placeholders while data is being fetched
 */

import React from 'react';

const LoadingSkeleton: React.FC = () => {
  return (
    <div className="space-y-6 fade-in">
      {/* KPI Cards Skeleton */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="glass-card p-6 rounded-xl h-32 skeleton"></div>
        ))}
      </div>

      {/* Map Skeleton */}
      <div className="glass-card p-6 rounded-xl h-96 skeleton"></div>

      {/* Grid Skeleton */}
      <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
        <div className="lg:col-span-2 glass-card p-6 rounded-xl h-48 skeleton"></div>
        <div className="glass-card p-6 rounded-xl h-48 skeleton"></div>
      </div>

      {/* Chart Skeleton */}
      <div className="glass-card p-6 rounded-xl h-64 skeleton"></div>
    </div>
  );
};

export default LoadingSkeleton;
