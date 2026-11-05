/**
 * EnableAutomationDrawer (FEAT-002, Phase-2 gated) — NET-NEW, distinct from
 * shared/EnableAutomationPanel.tsx (that one is a read-only capabilities checklist). This drawer
 * drives the Phase-2 flow:
 *   1. getPermissions({ phase: 'AUTOMATION', createReadRole }) → PermissionList
 *   2. runPreflight(clusterName, region, 'AUTOMATION') → PreflightList
 *   3. when the preflight is NOT blocking, enable an action that calls
 *      issueConnectToken({ phase: 'AUTOMATION', createReadRole }) and shows the returned `command`
 *      VERBATIM with its TTL; while blocking, the action stays disabled and the blocking checks
 *      are surfaced through PreflightList.
 * The createReadRole toggle maps to the server's `--create-iam` (it is part of the request body;
 * the command string is built server-side and shown verbatim — never hand-built here).
 *
 * Composes around shared/DetailDrawer. The hook holds no state, so this drawer owns a loading/error
 * slice per call. NEVER emits a long-lived API key flag or env var.
 */
import { useState } from 'react';
import { TITLE, BODY } from '../shared/typeScale';
import { DetailDrawer } from '../shared/DetailDrawer';
import {
  useK8sOnboarding,
  type ConnectTokenResponse,
  type PermissionManifest,
  type PreflightResult,
} from '../../../hooks/useK8sOnboarding';
import { PermissionList } from './PermissionList';
import { PreflightList } from './PreflightList';
import { ConnectCommand } from './ConnectClusterDrawer';

export interface EnableAutomationDrawerProps {
  open: boolean;
  onClose: () => void;
  clusterName: string;
  region: string;
  accountId: string;
}

export function EnableAutomationDrawer({ open, onClose, clusterName, region, accountId }: EnableAutomationDrawerProps) {
  const { getPermissions, runPreflight, issueConnectToken } = useK8sOnboarding({ accountId });

  const [createReadRole, setCreateReadRole] = useState(true);
  const [manifest, setManifest] = useState<PermissionManifest | null>(null);
  const [preflight, setPreflight] = useState<PreflightResult | null>(null);
  const [token, setToken] = useState<ConnectTokenResponse | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  const review = async () => {
    setLoading(true);
    setError(null);
    setToken(null);
    try {
      const [nextManifest, nextPreflight] = await Promise.all([
        getPermissions({ clusterName, region, phase: 'AUTOMATION', createReadRole }),
        runPreflight(clusterName, region, 'AUTOMATION'),
      ]);
      setManifest(nextManifest);
      setPreflight(nextPreflight);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load the automation review.');
    } finally {
      setLoading(false);
    }
  };

  const connect = async () => {
    setConnecting(true);
    setConnectError(null);
    try {
      const result = await issueConnectToken(clusterName, { region, phase: 'AUTOMATION', createReadRole });
      setToken(result);
    } catch (e) {
      setConnectError(e instanceof Error ? e.message : 'Could not generate a connect command.');
    } finally {
      setConnecting(false);
    }
  };

  const blocking = preflight?.blocking ?? false;
  const reviewed = manifest !== null && preflight !== null;

  return (
    <DetailDrawer open={open} onClose={onClose} title="Enable automation" subtitle={clusterName}>
      <div className="flex flex-col gap-5 px-5 py-4" data-enable-automation-drawer="">
        <p className={`${BODY} leading-[1.5]`}>
          Review the permissions and preflight checks for node automation. Once the checks pass, generate the connect
          command to enable it.
        </p>

        <label className="flex items-center gap-2" data-create-read-role-toggle="">
          <input
            type="checkbox"
            checked={createReadRole}
            onChange={(e) => setCreateReadRole(e.target.checked)}
            className="h-4 w-4"
          />
          <span className={BODY}>Create the read-only IAM role (--create-iam)</span>
        </label>

        {!reviewed ? (
          <button
            type="button"
            onClick={review}
            disabled={loading}
            data-enable-review=""
            className="self-start rounded-[6px] bg-[color:var(--chrome-ink)] px-4 py-2 text-[13px] font-semibold text-[color:var(--chrome-card)] outline-none hover:opacity-90 focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)] disabled:opacity-50"
          >
            {loading ? 'Loading…' : 'Review permissions & preflight'}
          </button>
        ) : null}

        {error ? (
          <p className={`${BODY} text-[color:var(--chrome-muted)]`} role="alert" data-enable-error="">
            {error}
          </p>
        ) : null}

        {manifest ? (
          <section className="flex flex-col gap-3" data-enable-permissions="">
            <h3 className={TITLE}>Permissions</h3>
            <PermissionList manifest={manifest} />
          </section>
        ) : null}

        {preflight ? (
          <section className="flex flex-col gap-3" data-enable-preflight="">
            <h3 className={TITLE}>Preflight</h3>
            <PreflightList result={preflight} />
          </section>
        ) : null}

        {reviewed && !token ? (
          <button
            type="button"
            onClick={connect}
            disabled={blocking || connecting}
            data-enable-connect=""
            className="self-start rounded-[6px] bg-[color:var(--chrome-ink)] px-4 py-2 text-[13px] font-semibold text-[color:var(--chrome-card)] outline-none hover:opacity-90 focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)] disabled:opacity-50"
          >
            {connecting ? 'Generating…' : 'Generate connect command'}
          </button>
        ) : null}

        {reviewed && blocking && !token ? (
          <p className={`${BODY} text-[color:var(--chrome-muted)]`} data-enable-blocked="">
            Resolve the blocking checks above before enabling automation.
          </p>
        ) : null}

        {token ? <ConnectCommand token={token} /> : null}

        {connectError ? (
          <p className={`${BODY} text-[color:var(--chrome-muted)]`} role="alert" data-enable-connect-error="">
            {connectError}
          </p>
        ) : null}
      </div>
    </DetailDrawer>
  );
}

export default EnableAutomationDrawer;
