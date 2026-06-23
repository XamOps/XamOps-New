## 2026-06-22 - Improved Accessibility in Shared Components
**Learning:** Shared UI components like `SuperAdminTopBar` often lack explicit label-input associations, which hinders screen reader navigation. Additionally, icon-only buttons rely on `title` attributes for tooltips, which aren't always accessible or consistent.
**Action:** Use the `useId` hook to create stable, unique IDs for form controls in shared components. Wrap icon-only buttons in `TooltipWrapper` and provide explicit `aria-label` attributes to ensure they are both visually and audibly descriptive.
