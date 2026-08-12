/**
 * Shared variable substitution for the in-app Kubernetes setup guides (AKS / EKS / GKE).
 *
 * Every guide is a long series of copy-paste command blocks that repeat the same few values
 * — cluster name, region/resource group, account/subscription id. Before this existed the
 * user hand-edited each occurrence, and the failure mode was not a broken command but a
 * silently wrong one: a cluster name typed one way in the Kubescape `--set clusterName` and
 * another way in the agent ConfigMap makes two clusters collide on a single
 * cluster_config_id, and the data lands under the wrong cluster.
 *
 * The provider-specific part is only the list of tokens, so that is the sole thing a guide
 * supplies. The substitution itself lives here so AKS and EKS cannot drift apart.
 */

import React from 'react';

/** Maps a placeholder token (e.g. `<CLUSTER_NAME>`) to the value the user typed. */
export type GuideTokenValues = Record<string, string>;

/** One input in a guide's variable bar. */
export interface GuideVarSpec {
  /** The literal token as it appears in the command blocks, e.g. `<CLUSTER_NAME>`. */
  token: string;
  /** Field label shown above the input. */
  label: string;
  /** Greyed example value. */
  placeholder?: string;
  /** Optional one-line hint rendered under the input. */
  hint?: string;
}

/**
 * Replaces every filled-in token.
 *
 * Blank values are deliberately left as the literal token rather than substituted with an
 * empty string: an unmistakable `<CLUSTER_NAME>` in a copied command is far better than a
 * command that looks complete but has a hole where the name should be. Values are trimmed
 * because these fields are usually pasted from a console.
 *
 * Uses split/join rather than a regex so values containing regex metacharacters (dots and
 * dashes are common in cluster names) substitute literally.
 */
export function substituteTokens(code: string, values: GuideTokenValues): string {
  let out = code;
  for (const [token, raw] of Object.entries(values)) {
    const value = (raw ?? '').trim();
    if (!value) continue;
    out = out.split(token).join(value);
  }
  return out;
}

/** Token values for the surrounding guide. Consumed by each guide's own CodeBlock. */
export const GuideTokensContext = React.createContext<GuideTokenValues>({});

export function useGuideTokens(): GuideTokenValues {
  return React.useContext(GuideTokensContext);
}

/** True when every token in `specs` has a non-blank value. */
export function allTokensFilled(specs: GuideVarSpec[], values: GuideTokenValues): boolean {
  return specs.every(s => (values[s.token] ?? '').trim().length > 0);
}

/**
 * Lists only the tokens a step needs that are still empty. Once they are filled from the bar
 * the note flips to a confirmation, instead of continuing to tell the user to replace values
 * that the block below already shows substituted.
 */
export const GuideVarNote: React.FC<{ tokens: string[] }> = ({ tokens }) => {
  const values = useGuideTokens();
  const missing = tokens.filter(t => !(values[t] ?? '').trim());

  if (missing.length === 0) {
    return (
      <div className="bg-green-50 border border-green-200 rounded-lg p-3 mb-4">
        <p className="text-sm text-green-800">
          <i className="fas fa-check-circle mr-2"></i>
          Values filled in — the commands below are ready to copy as-is.
        </p>
      </div>
    );
  }

  return (
    <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 mb-4">
      <p className="text-sm text-amber-800">
        <i className="fas fa-exclamation-triangle mr-2"></i>
        <strong>Still needed:</strong> {missing.join(', ')} — fill these in at the top of this guide.
      </p>
    </div>
  );
};

/**
 * The inputs at the top of a guide that drive every command block below it.
 *
 * `tunnel` is only supplied when the backend the guide points at is not reachable from
 * inside a cluster, which is the case for local development.
 */
export const GuideVariableBar: React.FC<{
  specs: GuideVarSpec[];
  values: GuideTokenValues;
  onChange: (next: GuideTokenValues) => void;
  tunnel?: { url: string; onChange: (next: string) => void; backendUrl: string };
}> = ({ specs, values, onChange, tunnel }) => {
  const allSet = allTokensFilled(specs, values);

  return (
    <div className={`rounded-xl border p-4 mb-4 ${allSet ? 'border-green-200 bg-green-50/50' : 'border-amber-200 bg-amber-50/50'}`}>
      <div className="flex items-center gap-2 mb-3">
        <i className={`fas ${allSet ? 'fa-check-circle text-green-600' : 'fa-pen text-amber-600'} text-xs`}></i>
        <span className="text-sm font-bold text-gray-800">
          {allSet ? 'Your cluster details' : 'Enter your cluster details'}
        </span>
        <span className="text-xs text-gray-500">— applied to every command in this guide</span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {specs.map(spec => {
          const value = values[spec.token] ?? '';
          const id = `guide-var-${spec.token.replace(/[<>]/g, '').toLowerCase()}`;
          return (
            <div key={spec.token}>
              <label htmlFor={id} className="block text-xs font-semibold text-gray-600 mb-1">
                {spec.label}
              </label>
              <input
                id={id}
                type="text"
                className={`w-full px-2.5 py-1.5 text-xs font-mono border rounded-lg outline-none transition-colors focus:border-indigo-500 ${
                  value.trim() ? 'border-gray-300 bg-white' : 'border-amber-300 bg-amber-50'
                }`}
                placeholder={spec.placeholder}
                value={value}
                onChange={e => onChange({ ...values, [spec.token]: e.target.value })}
              />
              {spec.hint && <p className="text-[11px] text-gray-500 mt-1">{spec.hint}</p>}
            </div>
          );
        })}
      </div>

      {tunnel && (
        <div className="mt-3 pt-3 border-t border-amber-200">
          <label htmlFor="guide-tunnel-url" className="block text-xs font-semibold text-gray-600 mb-1">
            Publicly reachable URL for this XamOps backend
          </label>
          <input
            id="guide-tunnel-url"
            type="text"
            className="w-full px-2.5 py-1.5 text-xs font-mono border border-amber-300 bg-amber-50 rounded-lg outline-none focus:border-indigo-500"
            placeholder="https://your-tunnel.ngrok-free.app"
            value={tunnel.url}
            onChange={e => tunnel.onChange(e.target.value)}
          />
          <p className="text-[11px] text-amber-800 mt-1.5">
            This XamOps instance is at <code className="bg-amber-100 px-1 rounded">{tunnel.backendUrl}</code>, which a
            pod in your cluster cannot reach. Expose it with a tunnel (<code className="bg-amber-100 px-1 rounded">ngrok http 8080</code>)
            and paste the public URL here.
            {' '}
            <strong>Do not leave the default</strong> — the agent's own health server also listens on 8080, so a
            <code className="bg-amber-100 px-1 rounded">localhost:8080</code> server URL makes the agent POST to itself
            and report a misleading HTTP 404 instead of a connection error.
          </p>
        </div>
      )}
    </div>
  );
};
