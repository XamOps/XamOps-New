## 2026-07-15 - [Accessibility: Linking Labels to Selectors]
**Learning:** In the Super Admin panel, selectors for Tenant, User, and Account were missing explicit associations with their labels. Using React 19's `useId` hook provides a stable way to link `htmlFor` and `id` without manual ID management.
**Action:** Always use `useId` to generate unique IDs for form controls and link them to their labels to ensure screen readers correctly identify the input's purpose.

## 2026-07-15 - [UX: Enhancing Icon-only Buttons]
**Learning:** Icon-only buttons often rely on the native `title` attribute, which is not accessible for all users and doesn't match the application's design system. The `TooltipWrapper` component provides a consistent, accessible way to show descriptions on hover and focus.
**Action:** Replace native `title` attributes on icon-only buttons with `TooltipWrapper` and provide a descriptive `aria-label`.
