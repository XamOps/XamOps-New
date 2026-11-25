import React from 'react';
import type { StackCapabilities } from '../../../api/autospottingApi';
import { BTN_PRIMARY, LINK, Notice } from './theme';
interface Props {
  capabilities: StackCapabilities | undefined;
  /** Opens the XamOps stack update (a change set through the stack updater role). */
  onUpdate: () => void;
  /** Where the console steps live (new template URL, existing parameter values kept). */
  consoleHref: string;
}
/**
 * "Update stack" prompt (design-aws.md §10.3, AC-ONB-01): shown on an AWS account whose stack lacks a
 * capability a conversion needs. Names what is missing and offers the two paths. Listing, status,
 * revert and fallback keep working, so the page below stays usable.
 */
const UpdateStackBanner: React.FC<Props> = ({ capabilities, onUpdate, consoleHref }) => {
  if (!capabilities || !capabilities.applicable || !capabilities.updateRequired) return null;
  const version = capabilities.templateVersion ?? 'not recorded';
  return (
    <Notice tone="warning" icon="mdi:layers-outline" role="status" aria-label="Update stack">
      <p className="font-semibold">Update stack</p>
      <p className="mt-1">
        {`The XamOps stack in this account (version ${version}) is missing: ${capabilities.missing.map(m => m.label).join(', ')}. `}
        {`Update it to ${capabilities.latestVersion} to convert groups. Listing, status, revert and fallback keep working.`}
      </p>
      <div className="mt-3 flex items-center gap-4">
        <button type="button" onClick={onUpdate} className={BTN_PRIMARY}>
          Update stack
        </button>
        <a href={consoleHref} className={LINK}>
          Update in the AWS console
        </a>
      </div>
    </Notice>
  );
};
export default UpdateStackBanner;
