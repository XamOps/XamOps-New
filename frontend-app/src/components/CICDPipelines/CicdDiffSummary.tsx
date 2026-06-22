import React, { useState } from 'react';
import { DiffSummary } from '../../types/cicdPipelines';

interface Props {
  diff: DiffSummary | null;
  isLoading: boolean;
  error: string | null;
  onRetry: () => void;
}

const CicdDiffSummary: React.FC<Props> = ({ diff, isLoading, error, onRetry }) => {
  const [filesExpanded, setFilesExpanded] = useState(false);

  if (isLoading) {
    return (
      <div className="p-4 space-y-2">
        {[1, 2, 3].map(i => <div key={i} className="h-8 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />)}
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-4 flex items-center justify-between bg-red-50 rounded border border-red-100">
        <span className="text-[12px] text-red-700">{error}</span>
        <button onClick={onRetry} className="text-[11px] text-red-700 underline ml-3">Retry</button>
      </div>
    );
  }

  if (!diff || diff.noPreviousSuccess) {
    return (
      <div className="p-4 text-[12px] text-[rgba(0,0,0,0.4)]">
        No previous successful run to compare against.
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center gap-2">
        <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.6)] uppercase tracking-wide">
          Changes since last success
        </span>
        <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
          {diff.commits.length} commit{diff.commits.length !== 1 ? 's' : ''} · {diff.filesChanged} file{diff.filesChanged !== 1 ? 's' : ''} changed
        </span>
      </div>

      <div className="space-y-2">
        {diff.commits.map((c, i) => (
          <div key={i} className="flex items-start gap-3 text-[12px]">
            <span className="font-mono text-[rgba(0,0,0,0.4)] shrink-0">{c.sha}</span>
            <span className="text-[rgba(0,0,0,0.75)] flex-1 truncate">{c.message}</span>
            <span className="text-[rgba(0,0,0,0.35)] shrink-0">{c.author}</span>
          </div>
        ))}
      </div>

      {diff.changedFiles.length > 0 && (
        <div>
          <button
            onClick={() => setFilesExpanded(e => !e)}
            className="text-[12px] text-blue-600 underline"
          >
            {filesExpanded ? 'Hide' : 'Show'} {diff.changedFiles.length} changed files
          </button>
          {filesExpanded && (
            <ul className="mt-2 space-y-1">
              {diff.changedFiles.map((f, i) => (
                <li key={i} className="text-[11px] font-mono text-[rgba(0,0,0,0.6)]">{f}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
};

export default CicdDiffSummary;
