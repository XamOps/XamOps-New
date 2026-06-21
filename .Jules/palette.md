# Palette's Journal - UX & Accessibility Learnings

## 2026-06-18 - Accessible Form Controls in SuperAdminTopBar
**Learning:** Found that many form controls in the shared components (like SuperAdminTopBar) use labels that are visually adjacent but not programmatically linked to their inputs. This prevents screen readers from correctly announcing the label when the input is focused and reduces the clickable area for the input.
**Action:** Always use `useId` to generate unique IDs and link `<label htmlFor={id}>` with `<select id={id}>` or `<input id={id}>`.
