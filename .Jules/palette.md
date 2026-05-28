## 2025-05-15 - Accessible Pagination Controls
**Learning:** The `Pagination` component was previously using icon-only buttons without ARIA labels or tooltips, making it difficult for screen reader users and potentially confusing for others. Wrapping these buttons in a `TooltipWrapper` and adding explicit `aria-label` attributes improves accessibility.
**Action:** Use a local `NavButton` sub-component to encapsulate accessible behaviors (ARIA labels, tooltips, focus rings, theme-awareness) when dealing with repeated icon-only actions in a component.
