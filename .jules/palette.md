# Palette Journal
## 2026-05-01 - Standardizing TopBar Accessibility
**Learning:** Icon-only buttons (Notifications, Support, Logout) and the global search input in TopBar lacked descriptive accessible names, hindering screen reader users. Using `TooltipWrapper` provides the dual benefit of visual 'delight' for mouse users and programmatic labels for accessibility.
**Action:** Always wrap icon-only header actions in `TooltipWrapper` and ensure inputs without visible labels have an explicit `aria-label`.
