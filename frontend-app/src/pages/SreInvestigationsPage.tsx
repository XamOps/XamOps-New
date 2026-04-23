import React from 'react';

const SreInvestigationsPage: React.FC = () => {
  return (
    <div className="flex flex-col h-screen bg-gray-50 items-center justify-center">
      <div className="text-center max-w-md px-6">
        <div className="w-20 h-20 bg-indigo-100 rounded-2xl flex items-center justify-center mx-auto mb-6">
          <svg className="w-10 h-10 text-indigo-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M9.75 3.104v5.714a2.25 2.25 0 0 1-.659 1.591L5 14.5M9.75 3.104c-.251.023-.501.05-.75.082m.75-.082a24.301 24.301 0 0 1 4.5 0m0 0v5.714c0 .597.237 1.17.659 1.591L19.8 15.3M14.25 3.104c.251.023.501.05.75.082M19.8 15.3l-1.57.393A9.065 9.065 0 0 1 12 15a9.065 9.065 0 0 0-6.23-.693L5 14.5m14.8.8 1.402 1.402c1.232 1.232.65 3.318-1.067 3.611A48.309 48.309 0 0 1 12 21c-2.773 0-5.491-.235-8.135-.687-1.718-.293-2.3-2.379-1.067-3.61L5 14.5" />
          </svg>
        </div>
        <h1 className="text-2xl font-bold text-gray-900 mb-3">SRE Investigations</h1>
        <p className="text-gray-500 text-sm leading-relaxed mb-6">
          AI-powered root cause analysis for your infrastructure incidents. Automatically investigate alerts, pods, and cloud resources — coming soon.
        </p>
        <span className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-indigo-50 text-indigo-600 text-sm font-medium">
          <span className="w-2 h-2 rounded-full bg-indigo-400 animate-pulse" />
          Coming Soon
        </span>
      </div>
    </div>
  );
};

export default SreInvestigationsPage;
