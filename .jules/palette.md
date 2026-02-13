## 2025-05-15 - Password Visibility Toggle Resilience
**Learning:** Adding interactive elements (like icons) inside form groups can break existing DOM traversal logic (e.g., `parentElement`). Using `.closest('.form-group')` is more robust for applying visual focus states when the input structure becomes more complex.
**Action:** Always use `.closest()` when targeting parent containers for animations or state changes to handle potential nesting.

## 2025-05-15 - Dynamic ARIA Labels for Toggles
**Learning:** For interactive toggles (like show/hide password), static ARIA labels are insufficient. Dynamically updating the `aria-label` along with the visual state (icon/input type) provides a much better experience for screen reader users.
**Action:** Update `aria-label` in the same JavaScript function that handles the visual toggle logic.
