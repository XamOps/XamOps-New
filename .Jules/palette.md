## 2025-05-15 - Accessibility Improvements for TopBar Controls

**Learning:** Accessible controls in the `TopBar` (like theme toggles and super admin selectors) require systematic use of `useId` for stable label association and `TooltipWrapper` for providing persistent, screen-reader-friendly hints for icon-only buttons. Synchronizing `aria-label` with `TooltipWrapper` tip content ensures a consistent experience across different assistive technologies.

**Action:** When adding or modifying top-bar controls:
1. Use `useId` to link `<label htmlFor={id}>` with `<select id={id}>` or `<input id={id}>`.
2. Wrap icon-only buttons in `TooltipWrapper` from `src/components/ui/Tooltip.tsx`.
3. Set the button's `aria-label` to exactly match the tooltip's `tip` text.
