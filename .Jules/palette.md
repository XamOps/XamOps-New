## 2025-05-14 - Standardizing TopBar Accessibility
**Learning:** Icon-only buttons and theme toggles in the primary navigation were missing critical accessibility attributes (ARIA labels, roles, and focus states), making them difficult to use for screen reader and keyboard users.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` with matching `aria-label`, and use `role="switch"` with `aria-checked` for theme toggles to ensure semantic clarity.
