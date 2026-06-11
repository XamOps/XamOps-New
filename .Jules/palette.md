## 2025-05-14 - Accessible SuperAdminTopBar
**Learning:** Icon-only buttons in the top bar (like Reset Context) lack accessible names if they only use the `title` attribute. Standardizing on `TooltipWrapper` with `aria-label` provides both a visual hint and screen reader support. Additionally, using `useId` to link `<label>` and `<select>` ensures robust accessibility without hardcoding IDs.
**Action:** Always use `TooltipWrapper` for icon-only buttons and `useId` for form control associations in new components.
