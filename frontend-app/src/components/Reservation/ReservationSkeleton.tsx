/**
 * ReservationSkeleton Component
 *
 * Loading skeleton for the Reservation page
 * Shows placeholders for metrics, charts, and inventory while data is being fetched
 */

import React from 'react';
import Skeleton from '../Shared/Skeleton';

interface ReservationSkeletonProps {
  type?: 'overview' | 'recommendations';
}

export const ReservationSkeleton: React.FC<ReservationSkeletonProps> = ({ type = 'overview' }) => {
  if (type === 'recommendations') {
    return (
      <div className="space-y-6 fade-in">
        {/* Filters Skeleton */}
        <div className="glass-card rounded-xl p-5 border-t-4 border-t-purple-500">
          <div className="flex items-center justify-between mb-4">
            <div className="space-y-2">
              <Skeleton variant="rounded" width="250px" height="24px" />
              <Skeleton variant="rounded" width="180px" height="16px" />
            </div>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="space-y-2">
                <Skeleton variant="rounded" width="120px" height="16px" />
                <Skeleton variant="rounded" width="100%" height="42px" />
              </div>
            ))}
          </div>
        </div>

        {/* Recommendations Table Skeleton */}
        <div className="glass-card rounded-xl overflow-hidden border-t-4 border-t-green-500">
          <div className="px-6 py-5 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-r from-[rgba(0,0,0,0.025)] to-white">
            <Skeleton variant="rounded" width="280px" height="24px" className="mb-2" />
            <Skeleton variant="rounded" width="200px" height="16px" />
          </div>
          <div className="p-6 space-y-4">
            {/* Table Header */}
            <div className="grid grid-cols-7 gap-4 pb-3 border-b border-[rgba(0,0,0,0.06)]">
              {[1, 2, 3, 4, 5, 6, 7].map((i) => (
                <Skeleton key={i} variant="rounded" width="80px" height="16px" />
              ))}
            </div>
            {/* Table Rows */}
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="grid grid-cols-7 gap-4 py-4 border-b border-[rgba(0,0,0,0.04)]">
                <div className="space-y-2">
                  <Skeleton variant="rounded" width="100px" height="20px" />
                  <Skeleton variant="rounded" width="80px" height="14px" />
                </div>
                <Skeleton variant="rounded" width="90px" height="20px" />
                <Skeleton variant="rounded" width="90px" height="20px" />
                <Skeleton variant="rounded" width="120px" height="28px" />
                <Skeleton variant="rounded" width="80px" height="20px" />
                <Skeleton variant="rounded" width="90px" height="20px" />
                <div className="space-y-2">
                  <Skeleton variant="rounded" width="90px" height="20px" />
                  <Skeleton variant="rounded" width="70px" height="16px" />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6 fade-in">
      {/* Metrics Cards Skeleton */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        {[1, 2, 3, 4].map((i) => (
          <div
            key={i}
            className="glass-card rounded-xl p-5 border-l-4 border-l-indigo-500 space-y-3"
          >
            <div className="flex items-center justify-between">
              <Skeleton variant="rounded" width="140px" height="16px" />
              <Skeleton variant="rounded" width="40px" height="40px" />
            </div>
            <Skeleton variant="rounded" width="100px" height="36px" />
            <Skeleton variant="rounded" width="100%" height="8px" />
          </div>
        ))}
      </div>

      {/* Chart Skeleton */}
      <div className="glass-card rounded-xl overflow-hidden border-t-4 border-t-blue-500">
        <div className="px-6 py-5 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-r from-[rgba(0,0,0,0.025)] to-white">
          <Skeleton variant="rounded" width="280px" height="24px" className="mb-2" />
          <Skeleton variant="rounded" width="200px" height="16px" />
        </div>
        <div className="p-6">
          <div className="space-y-4">
            <div className="flex gap-4 mb-6">
              <Skeleton variant="rounded" width="120px" height="32px" />
              <Skeleton variant="rounded" width="120px" height="32px" />
            </div>
            <Skeleton variant="rounded" width="100%" height="300px" />
          </div>
        </div>
      </div>

      {/* Inventory Tabs Skeleton */}
      <div className="glass-card rounded-xl overflow-hidden border-t-4 border-t-purple-500">
        <div className="px-6 py-5 border-b border-[rgba(0,0,0,0.06)] bg-gradient-to-r from-[rgba(0,0,0,0.025)] to-white">
          <Skeleton variant="rounded" width="220px" height="24px" className="mb-2" />
          <Skeleton variant="rounded" width="180px" height="16px" />
        </div>
        <div className="p-6 space-y-4">
          {/* Tabs */}
          <div className="flex gap-2 mb-4">
            <Skeleton variant="rounded" width="200px" height="40px" />
            <Skeleton variant="rounded" width="180px" height="40px" />
          </div>
          {/* Table Content */}
          <div className="space-y-3">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="p-4 bg-[rgba(0,0,0,0.02)] rounded-lg space-y-2">
                <div className="flex items-center justify-between">
                  <Skeleton variant="rounded" width="140px" height="20px" />
                  <Skeleton variant="rounded" width="100px" height="24px" />
                </div>
                <div className="flex gap-4">
                  <Skeleton variant="rounded" width="120px" height="16px" />
                  <Skeleton variant="rounded" width="100px" height="16px" />
                  <Skeleton variant="rounded" width="90px" height="16px" />
                </div>
                <div className="flex gap-4">
                  <Skeleton variant="rounded" width="150px" height="16px" />
                  <Skeleton variant="rounded" width="140px" height="16px" />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ReservationSkeleton;
