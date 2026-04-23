# Palette's Journal - UX & Accessibility Learnings

## 2025-05-14 - Standardizing Pagination Accessibility
**Learning:** Icon-only navigation buttons in pagination components (First, Prev, Next, Last) are often inaccessible to screen readers and lack context on hover.
**Action:** Always wrap icon-only pagination buttons in `TooltipWrapper` and provide descriptive `aria-label` attributes. Ensure `focus-visible` rings are applied for keyboard navigation.
