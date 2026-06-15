# Palette Journal - UX & Accessibility Learnings

## 2025-06-15 - Accessible form controls in SuperAdminTopBar
**Learning:** For accessibility in specialized navigation components like the SuperAdminTopBar, it is critical to explicitly link <label> and <select> elements using unique IDs to ensure screen readers correctly associate the label with its control. Using the `useId` hook ensures stable, unique IDs across renders.
**Action:** Always use `useId` for associating labels and form controls in shared components to maintain accessibility standards.
