## 2026-06-18 - Accessible Form Controls in SuperAdminTopBar
**Learning:** Icon-only buttons and form controls in this app often lack proper ARIA labels and explicit label-input associations. Native 'title' attributes are insufficient for screen readers. Using the `useId` hook ensures stable, unique IDs for `htmlFor` links even in concurrent rendering.
**Action:** Always verify that every `<label>` has a matching `id` on its input, and wrap icon-only buttons in `TooltipWrapper` with a synchronized `aria-label`.
