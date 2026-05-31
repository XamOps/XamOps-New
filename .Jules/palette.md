## 2025-05-31 - Accessible Hover Actions Pattern
**Learning:** Using `display: none` for hover-only actions breaks keyboard accessibility as elements are removed from the tab order. Switching to `opacity: 0` and `pointer-events: none` preserves them in the DOM.
**Action:** Always use the `opacity` + `pointer-events: none` + `:focus-within` pattern for hover actions to ensure they are discoverable and usable by keyboard users, and wrap icon-only buttons in `TooltipWrapper`.
