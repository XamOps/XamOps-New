## 2025-05-15 - Accessible Form Controls and Tooltips in Topbar

**Learning:** Standard accessibility oversight in this application's top-bar components involves missing explicit associations between `<label>` and `<select>` elements, and icon-only buttons lacking accessible descriptions. Using React's `useId` hook ensures stable, unique IDs for `htmlFor` and `id` linking, while `TooltipWrapper` provides necessary context for screen readers and keyboard users.

**Action:** Always use `useId` for linking labels and inputs/selects in shared components. Wrap icon-only buttons in `TooltipWrapper` and synchronize the `tip` prop with the button's `aria-label`.
