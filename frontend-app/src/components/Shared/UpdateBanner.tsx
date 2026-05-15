import React from 'react';
import { useVersionCheck } from '../../hooks/useVersionCheck';

const UpdateBanner: React.FC = () => {
    const { updateAvailable } = useVersionCheck();

    if (!updateAvailable) return null;

    return (
        <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 flex items-center gap-4 bg-indigo-600 text-white px-5 py-3 rounded-xl shadow-lg text-sm font-medium">
            <span>A new version of XamOps is available.</span>
            <button
                onClick={() => window.location.reload()}
                className="bg-white text-indigo-600 px-3 py-1 rounded-lg font-semibold hover:bg-indigo-50 transition-colors"
            >
                Refresh
            </button>
        </div>
    );
};

export default UpdateBanner;
