
## 2025-05-15 - Topbar Accessibility & Delight
**Learning:** Icon-only buttons in the main navigation (Help, Notifications, Theme) lack affordance and screen-reader context without explicit TooltipWrapper association and aria-labels.
**Action:** Always wrap Topbar action buttons in TooltipWrapper and synchronize the aria-label with the tip text for semantic parity.
