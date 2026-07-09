## 2026-07-07 - Pagination Accessibility
**Learning:** Icon-only buttons in navigation components (like Pagination) are often missing explicit labels and button types, making them difficult for screen readers and potentially causing issues in forms. Using `TooltipWrapper` provides a consistent way to add both visual and non-visual labels.
**Action:** Always add `aria-label` and `type="button"` to icon-only buttons, and wrap them in the design system's `TooltipWrapper` for better UX.
