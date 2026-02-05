# Palette's Journal

## 2025-05-15 - [Accessibility Gaps in Icon-Only Buttons]
**Learning:** Many interactive elements in the dashboard used only icons (FontAwesome) without ARIA labels, making them inaccessible to screen reader users. Additionally, high-profile floating action buttons (like the AI Chatbot) lacked visible focus indicators for keyboard users.
**Action:** Always verify that buttons containing only `<i>` tags have descriptive `aria-label` attributes and that decorative icons have `aria-hidden="true"`. Ensure all interactive elements have visible focus states, especially when using `focus:outline-none`.
