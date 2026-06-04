## 2025-05-15 - Improving Accessibility in SuperAdminTopBar
**Learning:** Standard React `<label>` elements in `SuperAdminTopBar` were not explicitly linked to their corresponding `<select>` elements, creating a barrier for screen reader users. Additionally, icon-only buttons like "Reset Context" lacked accessible descriptions.
**Action:** Use React's `useId` hook to generate unique, stable IDs for linking labels and form controls. Always wrap icon-only buttons in `TooltipWrapper` and provide a descriptive `aria-label` for screen reader support.
