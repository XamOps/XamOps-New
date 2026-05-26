import React from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { FailingStage, FailingWorkflow } from '../../types/cicdPipelines';

interface Props {
  topFailingStages: FailingStage[];
  topFailingWorkflows: FailingWorkflow[];
  isLoading: boolean;
}

export const CicdFailureAnalysis: React.FC<Props> = ({ topFailingStages, topFailingWorkflows, isLoading }) => {
  if (isLoading) {
    return <div className="h-40 bg-gray-800 rounded-lg animate-pulse mb-4" />;
  }

  const hasData = topFailingStages.length > 0 || topFailingWorkflows.length > 0;
  if (!hasData) {
    return (
      <div className="bg-gray-800 rounded-lg p-4 border border-gray-700 mb-4 flex items-center justify-center h-28">
        <p className="text-gray-500 text-sm">No failures in selected period</p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
      <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
        <p className="text-xs text-gray-400 uppercase tracking-wide mb-3">Top Failing Stages</p>
        {topFailingStages.length === 0 ? (
          <p className="text-gray-500 text-sm">No stage failures</p>
        ) : (
          <ResponsiveContainer width="100%" height={120}>
            <BarChart data={topFailingStages} layout="vertical">
              <XAxis type="number" tick={{ fontSize: 10, fill: '#9ca3af' }} />
              <YAxis dataKey="name" type="category" tick={{ fontSize: 10, fill: '#9ca3af' }} width={80} />
              <Tooltip
                contentStyle={{ background: '#1f2937', border: '1px solid #374151', borderRadius: 4 }}
                formatter={(v: any) => [v, 'Failures']}
              />
              <Bar dataKey="failureCount" fill="#f87171" radius={[0, 3, 3, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>

      <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
        <p className="text-xs text-gray-400 uppercase tracking-wide mb-3">Top Failing Workflows</p>
        {topFailingWorkflows.length === 0 ? (
          <p className="text-gray-500 text-sm">No workflow failures</p>
        ) : (
          <ul className="space-y-2">
            {topFailingWorkflows.map((wf, i) => (
              <li key={wf.name} className="flex items-center justify-between">
                <span className="text-sm text-gray-300 truncate mr-2">
                  <span className="text-gray-500 mr-1">{i + 1}.</span>
                  {wf.name}
                </span>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className="text-xs text-red-400">{wf.failureCount} fail</span>
                  <span className={`text-xs px-2 py-0.5 rounded-full ${
                    wf.successRate >= 80 ? 'bg-green-900 text-green-300' :
                    wf.successRate >= 50 ? 'bg-yellow-900 text-yellow-300' :
                    'bg-red-900 text-red-300'
                  }`}>
                    {wf.successRate.toFixed(0)}%
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};
