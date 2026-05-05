# Palette Journal 🎨

## 2025-05-13 - [Topbar Accessibility and Micro-UX]
**Learning:** Icon-only buttons in the `Topbar` (Notifications, Help) lacked descriptive tooltips and appropriate ARIA attributes for state (`aria-expanded`) and role (`aria-haspopup`). Additionally, keyboard focus visibility was subtle and needed a more distinct ring. Theme toggle buttons also benefit from `role="switch"` for better screen reader announcement.
**Action:** Always wrap icon-only header actions in `TooltipWrapper` and ensure `aria-label` matches the tooltip text. Use `focus-visible:ring-2` with an offset for high-contrast focus states on rounded action buttons.
