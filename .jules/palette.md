# Palette Journal 🎨

## 2025-05-14 - Enhancing Pagination Accessibility
**Learning:** Icon-only buttons in pagination (First, Prev, Next, Last) lack descriptive labels for screen readers and visual hints for mouse users, and their focus state is not prominent enough for keyboard navigation.
**Action:** Use `TooltipWrapper` for visual hints, add `aria-label` for screen readers, and apply `focus-visible` rings for keyboard users.
