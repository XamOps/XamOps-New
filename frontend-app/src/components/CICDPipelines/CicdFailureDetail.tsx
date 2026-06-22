import React from 'react';
import { FailureAnalysis } from '../../types/cicdPipelines';

interface Props {
  failureCategory: string | undefined;
  failedStage: string | undefined;
  analysis: FailureAnalysis | null;
  isLoading: boolean;
  error: string | null;
  onRetry: () => void;
  onViewFullLog: () => void;
}

const CATEGORY_STYLES: Record<string, string> = {
  TEST_FAILURE:   'bg-yellow-100 text-yellow-800 border-yellow-200',
  BUILD_ERROR:    'bg-red-100 text-red-800 border-red-200',
  DEPLOY_FAILURE: 'bg-orange-100 text-orange-800 border-orange-200',
  INFRA_ERROR:    'bg-purple-100 text-purple-800 border-purple-200',
  UNKNOWN:        'bg-gray-100 text-gray-600 border-gray-200',
};

const CicdFailureDetail: React.FC<Props> = ({
  failureCategory, failedStage, analysis, isLoading, error, onRetry, onViewFullLog,
}) => {
  const catStyle = CATEGORY_STYLES[failureCategory || 'UNKNOWN'] || CATEGORY_STYLES.UNKNOWN;

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center gap-3 flex-wrap">
        {failureCategory && (
          <span className={`text-[11px] font-semibold px-2.5 py-1 rounded border ${catStyle}`}>
            {failureCategory.replace('_', ' ')}
          </span>
        )}
        {failedStage && (
          <span className="text-[12px] text-[rgba(0,0,0,0.5)]">
            Failed in: <span className="font-medium text-[rgba(0,0,0,0.75)]">{failedStage}</span>
          </span>
        )}
      </div>

      <hr className="border-[rgba(0,0,0,0.06)]" />

      {isLoading && (
        <div className="space-y-2">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-16 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />
          ))}
        </div>
      )}

      {error && !isLoading && (
        <div className="flex items-center justify-between p-3 bg-red-50 rounded border border-red-100">
          <span className="text-[12px] text-red-700">{error}</span>
          <button onClick={onRetry} className="text-[11px] text-red-700 underline ml-3">Retry</button>
        </div>
      )}

      {analysis && !isLoading && (
        <div className="space-y-3">
          <p className="text-[12px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wide">
            {analysis.fallback ? 'Last log lines' : 'Why it failed'}
          </p>
          {analysis.snippets.map((snippet, si) => (
            <div key={si} className="rounded border border-[rgba(0,0,0,0.08)] overflow-hidden">
              <pre className="text-[11px] font-mono leading-relaxed overflow-x-auto">
                {snippet.lines.map((line, li) => (
                  <div
                    key={li}
                    className={`px-3 py-0.5 ${li === snippet.matchLine ? 'bg-red-50 text-red-800' : 'text-[rgba(0,0,0,0.65)]'}`}
                  >
                    {line || ' '}
                  </div>
                ))}
              </pre>
            </div>
          ))}
        </div>
      )}

      <button
        onClick={onViewFullLog}
        className="text-[12px] text-blue-600 underline mt-2"
      >
        View full logs
      </button>
    </div>
  );
};

export default CicdFailureDetail;
