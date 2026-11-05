/**
 * ConnectClusterDrawer (FEAT-002, Phase-1 read-only). Composes around shared/DetailDrawer: the
 * drawer shell owns the modal/focus-trap/scrim behaviour, and this component passes the connect
 * flow as its children. A Generate action asks the server for a short-lived connect token via
 * `issueConnectToken(clusterName, { region, phase: 'READ_ONLY', createReadRole: true })` and shows
 * the returned `command` VERBATIM in a copy-to-clipboard code block, with the token's TTL/expiry.
 *
 * Security (CLAUDE.md + design): the command is a token-based `--connect-token` string the server
 * builds; we NEVER hand-build it and NEVER emit a long-lived API key flag or env var.
 * Honesty: a missing TTL/expiry renders the muted em-dash (DASH), never a fabricated value.
 *
 * The hook holds no state of its own, so this drawer owns its loading + error state.
 */
import { useState } from 'react';
import { DASH } from '../../../utils/k8sQuantities';
import { TITLE, BODY, SECONDARY } from '../shared/typeScale';
import { DetailDrawer } from '../shared/DetailDrawer';
import { useK8sOnboarding, type ConnectTokenResponse } from '../../../hooks/useK8sOnboarding';

export interface ConnectClusterDrawerProps {
  open: boolean;
  onClose: () => void;
  clusterName: string;
  region: string;
  accountId: string;
}

/** Human-readable TTL, e.g. "15 min" or "2 h", or DASH when the server gave no usable value. */
function formatTtl(ttlSeconds: number | null | undefined): string {
  if (ttlSeconds == null || !Number.isFinite(ttlSeconds) || ttlSeconds <= 0) {
    return DASH;
  }
  if (ttlSeconds < 60) {
    return `${Math.round(ttlSeconds)} s`;
  }
  if (ttlSeconds < 3600) {
    return `${Math.round(ttlSeconds / 60)} min`;
  }
  return `${Math.round((ttlSeconds / 3600) * 10) / 10} h`;
}

/** The verbatim connect command in a copy-to-clipboard code block. */
export function ConnectCommand({ token }: { token: ConnectTokenResponse }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(token.command);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard denied; the command stays visible to copy by hand */
    }
  };
  return (
    <div className="flex flex-col gap-2" data-connect-command="">
      <div className="flex items-center justify-between gap-3">
        <span className={TITLE}>Run this command</span>
        <button
          type="button"
          onClick={copy}
          className="rounded-[6px] border border-[color:var(--chrome-line)] px-3 py-1.5 text-[12px] font-semibold text-[color:var(--chrome-ink)] outline-none hover:bg-[rgba(0,0,0,0.04)] focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)] dark:hover:bg-[rgba(255,255,255,0.06)]"
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre
        data-connect-command-text=""
        className="overflow-x-auto whitespace-pre-wrap break-all rounded-[6px] border border-[color:var(--chrome-line)] bg-[rgba(0,0,0,0.04)] px-3 py-2 text-[12px] font-mono text-[color:var(--chrome-ink)] dark:bg-[rgba(255,255,255,0.06)]"
      >
        <code>{token.command}</code>
      </pre>
      <div className="flex flex-wrap gap-x-5 gap-y-1">
        <span className={SECONDARY}>Expires in {formatTtl(token.ttlSeconds)}</span>
        <span className={SECONDARY}>Expires at {token.expiresAt || DASH}</span>
      </div>
    </div>
  );
}

export function ConnectClusterDrawer({ open, onClose, clusterName, region, accountId }: ConnectClusterDrawerProps) {
  const { issueConnectToken } = useK8sOnboarding({ accountId });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [token, setToken] = useState<ConnectTokenResponse | null>(null);

  const generate = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await issueConnectToken(clusterName, { region, phase: 'READ_ONLY', createReadRole: true });
      setToken(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not generate a connect command.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <DetailDrawer open={open} onClose={onClose} title="Connect cluster" subtitle={clusterName}>
      <div className="flex flex-col gap-4 px-5 py-4" data-connect-drawer="">
        <p className={`${BODY} leading-[1.5]`}>
          Generate a short-lived command that connects this cluster to XamOps in read-only mode. Run it with cluster
          admin credentials; it uses a one-time connect token, not a long-lived key.
        </p>

        {token ? (
          <ConnectCommand token={token} />
        ) : (
          <button
            type="button"
            onClick={generate}
            disabled={loading}
            data-connect-generate=""
            className="self-start rounded-[6px] bg-[color:var(--chrome-ink)] px-4 py-2 text-[13px] font-semibold text-[color:var(--chrome-card)] outline-none hover:opacity-90 focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)] disabled:opacity-50"
          >
            {loading ? 'Generating…' : 'Generate connect command'}
          </button>
        )}

        {error ? (
          <p className={`${BODY} text-[color:var(--chrome-muted)]`} role="alert" data-connect-error="">
            {error}
          </p>
        ) : null}
      </div>
    </DetailDrawer>
  );
}

export default ConnectClusterDrawer;
