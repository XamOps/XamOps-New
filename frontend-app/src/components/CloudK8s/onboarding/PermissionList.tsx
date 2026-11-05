/**
 * PermissionList (FEAT-002): renders a `PermissionManifest` the user reviews before issuing a
 * connect token. It shows (a) the feature catalogue the manifest carries, via
 * `presentFeatureList` — a dot + word per row, no pills, with a tier badge only when the server
 * sent one (the helper is the single display gate for that); and (b) the concrete IAM actions the
 * user is about to grant, read straight from `manifest.aws[].policy.Statement[]`.
 *
 * Honesty rules (CLAUDE.md): any null/absent field renders the muted em-dash (DASH), never a 0 or
 * a fabricated value. Pure presentation — all data arrives as props; no fetching here.
 */
import { DASH } from '../../../utils/k8sQuantities';
import { SECTION, TITLE, LABEL, BODY, SECONDARY } from '../shared/typeScale';
import { presentFeatureList } from './featureCatalogPresentation';
import type { PermissionManifest } from '../../../hooks/useK8sOnboarding';

export interface PermissionListProps {
  manifest: PermissionManifest;
  className?: string;
}

/** A labelled field that falls back to the em-dash when the value is null/empty. */
function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className={LABEL}>{label}</span>
      <span className={BODY}>{value ? value : <span className="text-[color:var(--chrome-muted)]">{DASH}</span>}</span>
    </div>
  );
}

export function PermissionList({ manifest, className = '' }: PermissionListProps) {
  const features = presentFeatureList(manifest.features);
  const roles = manifest.aws;

  return (
    <div className={`flex flex-col gap-5 ${className}`.trim()} data-permission-list="">
      <section className="flex flex-col gap-2" data-permission-meta="">
        <Field label="Identity mode" value={manifest.identityMode} />
        <Field label="Node scaler" value={manifest.nodeScaler} />
      </section>

      <section className="flex flex-col gap-2" data-permission-features="">
        <h3 className={SECTION}>Features</h3>
        <ul className="flex flex-col gap-1.5">
          {features.map((f) => (
            <li key={f.id} className="flex items-center gap-1.5" data-feature-row={f.id}>
              <svg width="8" height="8" viewBox="0 0 8 8" aria-hidden="true" className="shrink-0 text-[color:var(--chrome-muted)]">
                <circle cx="4" cy="4" r="3.5" fill="currentColor" />
              </svg>
              <span className={BODY}>{f.label}</span>
              {f.tierBadge ? <span className={SECONDARY}>· {f.tierBadge}</span> : null}
              {!f.available && f.reason ? <span className={SECONDARY}>· {f.reason}</span> : null}
            </li>
          ))}
        </ul>
      </section>

      <section className="flex flex-col gap-3" data-permission-aws="">
        <h3 className={SECTION}>AWS permissions granted</h3>
        {roles.map((role) => (
          <div key={role.role} className="flex flex-col gap-2" data-aws-role={role.role}>
            <div className="flex items-baseline gap-2">
              <span className={TITLE}>{role.role === 'read' ? 'Read role' : 'Write role'}</span>
              <span className={SECONDARY}>bound to {role.boundTo || DASH}</span>
            </div>
            {role.policy ? (
              <ul className="flex flex-col gap-2">
                {role.policy.Statement.map((stmt) => (
                  <li key={stmt.Sid} className="flex flex-col gap-1" data-aws-statement={stmt.Sid}>
                    <span className={LABEL}>{stmt.Sid}</span>
                    <ul className="flex flex-col gap-0.5">
                      {stmt.Action.map((action) => (
                        <li key={action} className={`${BODY} font-mono`} data-aws-action={action}>
                          {action}
                        </li>
                      ))}
                    </ul>
                    <span className={SECONDARY}>Resource: {stmt.Resource || DASH}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <span className={SECONDARY}>{DASH}</span>
            )}
          </div>
        ))}
      </section>
    </div>
  );
}

export default PermissionList;
