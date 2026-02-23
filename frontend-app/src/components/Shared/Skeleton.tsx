/**
 * Shared Skeleton Components
 * 
 * Reusable skeleton loaders for a smoother user experience during data fetching.
 */

import React from 'react';
import clsx from 'clsx';

interface SkeletonProps {
    className?: string;
    variant?: 'text' | 'circular' | 'rounded' | 'rectangular';
    width?: string | number;
    height?: string | number;
}

export const Skeleton: React.FC<SkeletonProps> = ({
    className,
    variant = 'rectangular',
    width,
    height
}) => {
    const style: React.CSSProperties = {
        width: typeof width === 'number' ? `${width}px` : width,
        height: typeof height === 'number' ? `${height}px` : height,
    };

    return (
        <div
            className={clsx(
                "animate-pulse bg-slate-200",
                variant === 'circular' ? 'rounded-full' : variant === 'rounded' ? 'rounded-lg' : 'rounded',
                className
            )}
            style={style}
        ></div>
    );
};

export default Skeleton;

export const SpotAutomationSkeleton: React.FC = () => {
    return (
        <div className="space-y-8">
            {/* KPI Cards Skeleton */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-6">
                {[1, 2, 3, 4].map((i) => (
                    <div key={i} className="bg-white rounded-xl shadow-lg p-6 border border-gray-100">
                        <div className="flex justify-between items-start">
                            <div className="space-y-3 flex-1">
                                <div className="h-4 w-28 bg-gray-200 rounded animate-pulse" />
                                <div className="h-9 w-36 bg-gray-200 rounded animate-pulse" />
                            </div>
                            <div className="h-12 w-12 bg-gray-100 rounded-lg animate-pulse" />
                        </div>
                        <div className="mt-4">
                            <div className="h-3 w-24 bg-gray-200 rounded animate-pulse" />
                        </div>
                    </div>
                ))}
            </div>

            {/* Chart Skeleton */}
            <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100">
                <div className="flex justify-between items-center mb-4">
                    <div className="h-6 w-48 bg-gray-200 rounded animate-pulse" />
                    <div className="flex gap-2">
                        {[1, 2, 3].map((i) => (
                            <div key={i} className="h-8 w-16 bg-gray-200 rounded-lg animate-pulse" />
                        ))}
                    </div>
                </div>
                <div className="h-64 bg-gray-100 rounded-lg animate-pulse" />
            </div>

            {/* ASG Table Skeleton */}
            <div className="bg-white rounded-xl shadow-lg p-6 border border-gray-100">
                <div className="flex justify-between items-center mb-4">
                    <div className="h-6 w-56 bg-gray-200 rounded animate-pulse" />
                    <div className="h-8 w-24 bg-gray-200 rounded-lg animate-pulse" />
                </div>
                <div className="space-y-3">
                    {/* Table header */}
                    <div className="grid grid-cols-6 gap-4 pb-3 border-b border-gray-100">
                        {[1, 2, 3, 4, 5, 6].map((i) => (
                            <div key={i} className="h-4 bg-gray-200 rounded animate-pulse" />
                        ))}
                    </div>
                    {/* Table rows */}
                    {[1, 2, 3, 4, 5].map((i) => (
                        <div key={i} className="grid grid-cols-6 gap-4 py-3 border-b border-gray-50">
                            {[1, 2, 3, 4, 5, 6].map((j) => (
                                <div key={j} className="h-4 bg-gray-100 rounded animate-pulse" />
                            ))}
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
};

export const DashboardSkeleton: React.FC = () => {
    return (
        <div className="min-h-screen bg-slate-50 p-6 space-y-6">
            {/* Header Skeleton */}
            <div className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm">
                <Skeleton className="h-8 w-64 mb-2" />
                <Skeleton className="h-4 w-96" />
            </div>

            {/* KPI Cards Skeleton */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {[1, 2, 3, 4].map((i) => (
                    <div key={i} className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm">
                        <div className="flex items-start justify-between">
                            <div className="space-y-3 flex-1">
                                <Skeleton className="h-4 w-24" />
                                <Skeleton className="h-8 w-32" />
                                <Skeleton className="h-3 w-40" />
                            </div>
                            <Skeleton className="h-12 w-12 rounded-xl" />
                        </div>
                    </div>
                ))}
            </div>

            {/* Main Content Skeleton */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                <div className="lg:col-span-2 bg-white border border-slate-200 rounded-2xl p-6 shadow-sm h-80">
                    <Skeleton className="h-6 w-48 mb-6" />
                    <Skeleton className="h-64 w-full" />
                </div>
                <div className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm h-80 overflow-hidden">
                    <Skeleton className="h-6 w-48 mb-4" />
                    <div className="space-y-4">
                        {[1, 2, 3, 4, 5].map((i) => (
                            <Skeleton key={i} className="h-10 w-full" />
                        ))}
                    </div>
                </div>
            </div>

            {/* Bottom Section Skeleton */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <div className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm h-64">
                    <Skeleton className="h-6 w-48 mb-4" />
                    <div className="space-y-3">
                        {[1, 2, 3].map((i) => (
                            <Skeleton key={i} className="h-16 w-full" />
                        ))}
                    </div>
                </div>
                <div className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm h-64">
                    <Skeleton className="h-6 w-48 mb-4" />
                    <div className="space-y-3">
                        {[1, 2, 3].map((i) => (
                            <Skeleton key={i} className="h-16 w-full" />
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};
