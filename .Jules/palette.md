## 2025-05-22 - [SuperAdminTopBar Accessibility Reference Implementation]
**Learning:** Accessibility for complex top-bar controls requires consistent use of `useId` for stable label-select association and `TooltipWrapper` for icon-only actions to support both mouse and screen reader users.
**Action:** Always use `useId` for form controls and wrap icon-only buttons in `TooltipWrapper` with a matching `aria-label` in the `frontend-app` top-bar components.
