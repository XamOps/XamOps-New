/**
 * Security Components Barrel Export
 */

export { default as SecurityHeader } from './SecurityHeader';
export { default as SecurityKPICards } from './SecurityKPICards';
export { default as SecurityComplianceFilter } from './SecurityComplianceFilter';
export { default as SecurityFindingsSection } from './SecurityFindingsSection';
export { default as SecurityProwlerBanner } from './SecurityProwlerBanner';
export { default as SecurityEmptyState } from './SecurityEmptyState';

// Identity & Access tab — absorbed the standalone User Management page. Renders as either an
// access graph (default) or the card canvas; see the note on ViewMode in IdentityAccessCanvas.
export {
  IdentityAccessTab,
  IdentityAccessCanvas,
  IdentityAccessGraph,
  IdentityCard,
} from './Identity';
