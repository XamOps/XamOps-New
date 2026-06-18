## 2026-06-16 - Accessible SuperAdmin Controls
**Learning:** React's `useId` is the most reliable way to create unique, stable IDs for explicit label-select associations in complex headers. Icon-only buttons must synchronize `aria-label` with their visual tooltips to ensure semantic parity for screen readers.
**Action:** Use `useId` for all new form controls and wrap icon-only actions in `TooltipWrapper` with matching `aria-label`.
