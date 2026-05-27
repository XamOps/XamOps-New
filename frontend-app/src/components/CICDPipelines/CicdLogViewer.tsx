import React, { useEffect, useState, useCallback } from 'react';
import apiClient from '../../utils/apiClient';

interface Props {
  provider: 'github' | 'jenkins';
  owner?: string;
  repo?: string;
  jobId?: number;
  jobName?: string;
  buildNumber?: number;
  autoOpen?: boolean;
}

const ERROR_REGEX = /error|fatal|exception|traceback|Error:/i;

export const CicdLogViewer: React.FC<Props> = ({
  provider,
  owner,
  repo,
  jobId,
  jobName,
  buildNumber,
  autoOpen = false,
}) => {
  const [isOpen, setIsOpen] = useState(autoOpen);
  const [log, setLog] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchLog = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      let url = '';
      if (provider === 'github' && owner && repo && jobId) {
        url = `/api/cicd/github/runs/${owner}/${repo}/${jobId}/logs`;
      } else if (provider === 'jenkins' && jobName && buildNumber) {
        url = `/api/cicd/jenkins/jobs/${jobName}/${buildNumber}/logs`;
      } else {
        setError('Missing parameters for log fetch');
        return;
      }
      const text = await apiClient.get<string>(url);
      setLog(typeof text === 'string' ? text : JSON.stringify(text));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to fetch logs');
    } finally {
      setIsLoading(false);
    }
  }, [provider, owner, repo, jobId, jobName, buildNumber]);

  useEffect(() => {
    if (isOpen && log === null && !isLoading) {
      fetchLog();
    }
  }, [isOpen, log, isLoading, fetchLog]);

  return (
    <div className="mt-4 border-t border-gray-700 pt-4">
      <button
        onClick={() => setIsOpen(prev => !prev)}
        className="flex items-center gap-2 text-sm text-gray-400 hover:text-white transition-colors mb-2"
      >
        <span className={`text-xs transition-transform ${isOpen ? 'rotate-90' : ''}`}>▶</span>
        {isOpen ? 'Hide Logs' : 'View Logs'}
      </button>

      {isOpen && (
        <div className="bg-gray-950 rounded-lg border border-gray-700 overflow-hidden">
          {isLoading && (
            <div className="p-4 text-gray-500 text-sm animate-pulse">Loading logs...</div>
          )}
          {error && !isLoading && (
            <div className="p-4 text-red-400 text-sm">Logs unavailable: {error}</div>
          )}
          {log !== null && !isLoading && (
            <div
              className="overflow-y-auto max-h-72 p-3 font-mono text-xs leading-relaxed"
              style={{ scrollbarWidth: 'thin', scrollbarColor: '#374151 transparent' }}
            >
              {log.split('\n').map((line, i) => (
                <div
                  key={i}
                  className={ERROR_REGEX.test(line) ? 'text-red-400 bg-red-950/30' : 'text-gray-300'}
                >
                  {line || ' '}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
