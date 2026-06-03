## 2025-05-14 - Accessible Form Controls and Icon Buttons
**Learning:** In 'frontend-app', common accessibility gaps include missing association between <label> and <select>/<input> elements and icon-only buttons lacking descriptive hints. React's `useId` is the preferred way to link labels to inputs, and the `TooltipWrapper` component should be used for all icon-only actions.
**Action:** Always check for explicit 'htmlFor' and 'id' matches on form controls and wrap icon-only buttons in `TooltipWrapper` with an appropriate `aria-label`.
