/**
 * Security Components Barrel Export
 */

export { default as SecurityHeader } from './SecurityHeader';
export { default as SecurityProwlerBanner } from './SecurityProwlerBanner';

// Findings tab — the XamLens Misconfigurations layout over the account's findings: one row per check
// with its affected resources, local search / filters / Group By, Columns, Export and the row drawer.
export { default as SecurityFindingsTab } from './Findings/SecurityFindingsTab';

// Identity & Access tab — absorbed the standalone User Management page. Renders as either an
// access graph (default) or the card canvas; see the note on ViewMode in IdentityAccessCanvas.
export {
  IdentityAccessTab,
  IdentityAccessCanvas,
  IdentityAccessGraph,
  IdentityCard,
} from './Identity';
