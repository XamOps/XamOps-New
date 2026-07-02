## 2026-07-01 - [A11y: Icon-only Navigation Buttons]
**Learning:** Icon-only buttons (like pagination chevrons) are inaccessible to screen readers and lack visual affordance for some users. Adding `aria-label` and `TooltipWrapper` ensures both screen reader support and visual clarity.
**Action:** Always wrap icon-only buttons in `TooltipWrapper` and provide a descriptive `aria-label`. Ensure `type="button"` is also present to prevent form submission side effects.
