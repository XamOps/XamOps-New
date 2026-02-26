import React from 'react';

const FlexComputePage: React.FC = () => {
  return (
    <main className="flex-1 p-6 bg-transparent flex items-center justify-center min-h-[70vh]">
      <div className="glass-card rounded-2xl p-12 max-w-lg w-full text-center shadow-xl">
        <div className="w-16 h-16 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-2xl flex items-center justify-center mx-auto mb-6 shadow-lg">
          <i className="fas fa-microchip text-white text-2xl" />
        </div>
        <h1 className="text-3xl font-black text-gray-900 mb-2">Coming Soon</h1>
        <p className="text-lg font-semibold text-gray-600 mb-3">Flex Compute</p>
        <p className="text-sm text-gray-500 max-w-sm mx-auto">
          CloudiDR-powered flexible compute capacity management is under development and will be available soon.
        </p>
      </div>
    </main>
  );
};

export default FlexComputePage;
