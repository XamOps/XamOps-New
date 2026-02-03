import React from 'react';

const ClientDetailsSkeleton: React.FC = () => {
    return (
        <div className="space-y-6 animate-premium-fade-in">
            {/* Header Skeleton */}
            <div className="flex justify-between items-center mb-8">
                <div>
                    <div className="h-8 w-64 bg-gray-200 rounded-lg animate-premium-pulse mb-2"></div>
                    <div className="h-4 w-48 bg-gray-100 rounded-md animate-premium-pulse"></div>
                </div>
            </div>

            {/* Client Cards Skeleton */}
            {[1, 2].map((i) => (
                <div key={i} className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden mb-6">
                    <div className="p-6">
                        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-6">
                            <div className="flex items-center gap-4">
                                <div className="w-16 h-16 rounded-2xl bg-gray-200 animate-premium-pulse"></div>
                                <div className="space-y-2">
                                    <div className="h-6 w-40 bg-gray-200 rounded animate-premium-pulse"></div>
                                    <div className="h-4 w-56 bg-gray-100 rounded animate-premium-pulse"></div>
                                </div>
                            </div>
                            <div className="flex items-center gap-8">
                                <div className="text-right space-y-2">
                                    <div className="h-3 w-24 bg-gray-100 rounded ml-auto animate-premium-pulse"></div>
                                    <div className="h-6 w-32 bg-indigo-50 rounded animate-premium-pulse"></div>
                                </div>
                                <div className="h-10 w-24 bg-gray-50 rounded-xl animate-premium-pulse"></div>
                            </div>
                        </div>

                        {/* Tabs Skeleton */}
                        <div className="flex gap-4 border-b border-gray-100 mb-6">
                            <div className="h-10 w-32 bg-gray-200 rounded-t-lg animate-premium-pulse"></div>
                            <div className="h-10 w-32 bg-gray-100 rounded-t-lg animate-premium-pulse"></div>
                        </div>

                        {/* Content Grid Skeleton */}
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                            {[1, 2, 3].map((j) => (
                                <div key={j} className="p-4 rounded-xl border border-gray-50 bg-gray-50/30 space-y-3">
                                    <div className="flex items-center gap-3">
                                        <div className="w-10 h-10 rounded-xl bg-gray-200 animate-premium-pulse"></div>
                                        <div className="space-y-2 flex-1">
                                            <div className="h-4 w-3/4 bg-gray-200 rounded animate-premium-pulse"></div>
                                            <div className="h-3 w-1/2 bg-gray-100 rounded animate-premium-pulse"></div>
                                        </div>
                                    </div>
                                    <div className="h-4 w-full bg-gray-100 rounded animate-premium-pulse"></div>
                                </div>
                            ))}
                        </div>
                    </div>
                </div>
            ))}
        </div>
    );
};

export default ClientDetailsSkeleton;
