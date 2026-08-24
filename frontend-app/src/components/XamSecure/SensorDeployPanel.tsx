import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchSensorCommand } from '../../hooks/useUpwindOnboarding';

/**
 * Host sensor deployment.
 *
 * Shown once for the whole section, not per account: the install script is identical for every
 * host in the organization — nothing in it is keyed by cloud account — so rendering it per
 * account row implied a scoping that does not exist.
 *
 * Connecting a cloud account gives configuration, compliance and agentless inventory. The
 * runtime signal — live threat detections, API security, whether a vulnerable package is
 * actually loaded — only arrives once a sensor is running, which is why this is offered
 * alongside account connection rather than buried.
 *
 * The command is fetched on click and never on mount: it embeds a shared credential, so it is
 * not something to put on screen, into component state, or into a response cache before someone
 * has asked for it.
 */
const SensorDeployPanel: React.FC = () => {
  const [command, setCommand] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);

  const reveal = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCommand((await fetchSensorCommand()).command);
    } catch (err) {
      setError(err instanceof Error ? err.message
        : 'Could not load the install command. Contact your XamOps administrator.');
    } finally {
      setLoading(false);
    }
  }, []);

  const copy = useCallback(async () => {
    if (!command) return;
    await navigator.clipboard.writeText(command);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  }, [command]);

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="min-w-0">
          <p className="text-sm font-bold text-gray-900">Runtime sensor</p>
          <p className="mt-1 text-xs text-gray-500 max-w-2xl">
            Connecting an account covers configuration, compliance and inventory. Install the
            sensor on a host to also get live threat detections, API security and runtime
            vulnerability context. The same command works on any host in your environment.
          </p>
        </div>
        {!command && (
          <button type="button" onClick={reveal} disabled={loading}
                  className="px-3 py-1.5 rounded-md text-[11px] font-semibold bg-[rgba(0,0,0,0.88)] text-white hover:bg-black disabled:opacity-50 transition-colors flex-shrink-0">
            {loading ? 'Loading…' : 'Show install command'}
          </button>
        )}
      </div>

      {error && <p className="mt-3 text-xs text-red-600" role="alert">{error}</p>}

      {command && (
        <div className="mt-4">
          <div className="flex items-center justify-between mb-1.5">
            <p className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide">
              Run on the host you want to monitor
            </p>
            {/* The script carries a credential, so make it easy to stop showing it again. */}
            <button type="button" onClick={() => setCommand(null)}
                    className="text-[10px] font-semibold text-gray-400 hover:text-gray-600">
              Hide
            </button>
          </div>
          <div className="flex items-start gap-2">
            <pre className="flex-1 bg-gray-50 border border-gray-200 rounded-lg p-3 text-[11px] text-gray-700 overflow-x-auto whitespace-pre">
              {command}
            </pre>
            <button onClick={copy}
                    className="p-2 border border-gray-200 rounded-lg hover:bg-gray-50 flex-shrink-0"
                    aria-label="Copy install command">
              <Icon icon={copied ? 'mdi:check' : 'mdi:content-copy'} width={14} />
            </button>
          </div>
          <p className="mt-2 text-[11px] text-gray-400">
            Contains a credential for your organization — treat it like a password and do not
            commit it or share it outside your team.
          </p>
        </div>
      )}
    </div>
  );
};

export default SensorDeployPanel;
