## 2026-07-24 - Topbar Accessibility & Screen-Reader Support
**Learning:** Icon-only navigation elements in main header panels often lack textual representation and state context. Wrapping such buttons in `TooltipWrapper` provides visual cues on hover/focus, and adding explicit `aria-label` and `aria-expanded` attributes ensures that screen readers can convey the element's purpose and toggle state.
**Action:** When designing or refactoring global header bars, ensure all action icons are wrapped in tooltips and equipped with ARIA attributes reflecting dynamic dropdown states.
