## 2025-05-11 - Standardizing Accessible Focus States
**Learning:** For an app with both light and dark modes, standardizing a `focus-visible` ring that uses theme-aware offsets (e.g., `dark:focus-visible:ring-offset-slate-900`) ensures high visibility without visual artifacts on either background.
**Action:** Use `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1 dark:focus-visible:ring-offset-slate-900` as the default for interactive elements.

## 2025-05-11 - Tooltip Consistency
**Learning:** For icon-only buttons, aligning the `aria-label` exactly with the `TooltipWrapper` tip content provides the most consistent experience for both visual and screen-reader users.
**Action:** Always match `tip` and `aria-label` for icon-only triggers.
